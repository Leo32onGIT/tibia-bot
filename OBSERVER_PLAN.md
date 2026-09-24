# Observer — feature scope & plan

Status: **planning** (branch `observer`, off `dev`). No code written yet.

## 1. Goal

Let a guild's members register their personal **Tibia Observer** access token with
the bot, so the bot can pull **mini world changes (MWC)** from CipSoft's Observer
API and surface them alongside the existing boosted-boss/creature notification.

- `/observer` — replies with an embed showing this guild's Observer token status,
  with buttons to **add** or **remove** a token.
- A token is associated with **`guildId` + `userId`** (the member who entered it).
- When a member has a token configured, the boosted boss/creature notification they
  receive gains an extra **Mini World Changes** embed section.

## 2. Context / permission

CipSoft (Tibia Community Management) confirmed in writing that a promoted fansite
**may use the Observer API** if it can access it, on a strict **as-is** basis:

> "we do not guarantee that this access point will remain available, neither do we
> share any information about it … You can indeed try."

Design consequence: this integration must be **fully optional, fault-tolerant, and
degrade to silent no-op** if the endpoint changes, goes away, or blocks us. It can
never affect the rest of the bot. Mirror the `fansite-api` `mode` toggle (`off`
disables the whole path) so it can ship dark and be pulled instantly.

## 3. Upstream: the Observer API (as-is)

- Base: CipSoft's Observer backend (`observer.tibia.com`), Cloudflare-fronted.
- Versioned REST, `Authorization: Bearer` with a refresh token.
- Flow the client must reproduce:
  1. **Device/session login** → obtain a bearer `accessToken` + `refresh` + `expires`.
  2. **`Account/addAccountLink`** with the member's 5-char token from
     `tibia.com/account/?page=tibiaobserver` → links their account to our session.
  3. **`MiniWorldChanges/GetMiniWorldChanges`** (bearer-authenticated) → MWC data.
- Also available: `Worlds`, `Status`, `Raids`, `Dashboard/boostedCreature`,
  `Area/ExploredAreas`. (Only MWC is in scope for v1; raids are a later phase.)

**Known constraints (must shape the design):**
- **Unsupported / undocumented / may vanish** — see §2.
- **Cloudflare** — *resolved in Phase 0.* A managed challenge keyed on **TLS
  fingerprint**, not IP: a browser-grade TLS client (`curl_cffi` impersonate) passes
  clean from a datacenter IP, while a plain JVM/curl client is challenged. → the
  Observer client needs TLS impersonation; see §6 and `spike/observer/`.
- **Per-account** — a linked account only sees data its own play unlocks
  (Cyclopedia exploration gating). One member's token ≠ global coverage.
- **One-device model** — CipSoft binds an account to a single device/session. If a
  member also uses the official Observer app, our linking may contend with it.
  Surface this to the user at add-time; do not paper over it.
- **Token lifecycle** — access tokens expire and refresh; the member's link can be
  revoked from tibia.com at any time → treat "unauthorized" as a normal state and
  prompt re-link, never crash.

## 4. User-facing behaviour

### `/observer` (self-service, per-member — sits with `/boosted`, `/cooldowns`)
Replies (ephemeral) with the **Observer panel** embed:
- Whether *this member* has a token configured in *this guild*, and its health
  (linked / needs re-link / world it resolves to).
- Buttons: **Add token** (`observer add`) and **Remove token** (`observer remove`).
  When a token exists, Add is disabled/relabelled, mirroring `/boosted`.

### Add token
`observer add` opens a **modal** with a short text input for the 5-char token
(same pattern as `boosted add` → `TextInput`). On submit:
- Validate format, exchange/link via the Observer API, store on success.
- Reply ephemerally with the outcome; never echo the token back.

### Remove token
`observer remove` deletes this member's token for this guild (and best-effort
`DeleteAccountLink` upstream), replies with confirmation.

### MWC section on the boosted notification
The server-save boosted DM (`BotApp` ~L1897, two `BoostedEmbeds`) gains a third
embed — **Mini World Changes** — for recipients who have a healthy token. Built by
a new `ObserverEmbeds`, modelled on `BoostedEmbeds`. If the fetch fails or returns
nothing, the section is simply omitted (the existing DM is unchanged).

## 5. Data model

New table in `bot_cache` (created by `SchemaInitializer`), following
`NotifyRepository`/`BoostedRepository` conventions:

```
observer_tokens
  id            bigserial primary key
  guild_id      text     not null
  user_id       text     not null
  world         text                     -- resolved from the linked account
  token_enc     text     not null        -- encrypted at rest, never logged
  session_state text                     -- cached bearer/refresh/expiry (encrypted)
  bot_id        text                     -- multi-bot ownership, as boosted/notify
  status        text     not null        -- linked | needs_relink | error
  created_at    timestamptz not null
  updated_at    timestamptz not null
  unique (guild_id, user_id)
```

- **Encryption at rest** is required — the token grants access to the member's
  account link. Reuse/extend whatever `DiscordAuth`/`PaywallService` already use;
  never store or log the plaintext token or bearer.
- Multi-bot ownership (`bot_id` + a `claim` on use) mirrors `BoostedRepository`,
  since shared-world-cycle secondaries exist.

## 6. Components

**New**
- `commands/handlers/ObserverCommands.scala` — handles `/observer` (template:
  `BoostedCommands`).
- `observer/ObserverService.scala` — cached token store + orchestration
  (template: `notifications/NotifyService`, write-through cache, `TrieMap`).
- **Observer client with browser-TLS impersonation.** Phase 0 proved the JVM's own
  TLS is Cloudflare-challenged, so the client cannot be plain pekko-http.
  **Recommended: a small local sidecar** (the `spike/observer/` script grown into a
  localhost service, `curl_cffi`/utls) that owns the Cloudflare pass, the bearer
  lifecycle (`LoginWithAccessToken` → refresh), and the undocumented endpoints. The
  Scala side is then a thin `observer/ObserverApiClient.scala` calling localhost
  (circuit breaker / pacing like `FansiteCircuitBreaker`). Serves MWC now, raids
  later. (Alternative: JVM-native JA3 impersonation — less mature, higher risk.)
- `persistence/ObserverRepository.scala` (port) + JDBC impl (template:
  `NotifyRepository`).
- `presentation/ObserverEmbeds.scala` — the panel embed and the MWC section
  (template: `BoostedEmbeds`).
- `interactions/ObserverModals.scala` — the add-token modal submit (template:
  `NotifyModals`).
- `domain/ObserverToken.scala` — the stored row / status ADT.

**Touched**
- `commands/CommandSchemas.scala` — declare `observerCommand`, add to
  `initialCommands`.
- command router wiring — register `"observer"` → `ObserverCommands.handle`.
- `interactions/ButtonHandler.scala` — `observer add` / `observer remove` (mirror
  the `boosted add` block that opens a `TextInput`).
- `interactions/ModalHandler.scala` (or route to `ObserverModals`).
- `persistence/SchemaInitializer.scala` — create `observer_tokens`.
- `BotApp.scala` — construct `ObserverService`; inject the MWC section into the
  server-save boosted block (~L1897); load tokens at startup like `NotifyService.load()`.
- `Config.scala` — an `observer-api` block (`mode`, `base-url`, `user-agent`,
  session/credential config) mirroring `FansiteApi`.
- lifecycle cleanup — drop tokens on guild-leave / user-leave (mirror
  `NotifyService.forgetGuild/forgetUser`).

## 7. Delivery flow (MWC)

1. Server save fires the existing boosted block.
2. For each recipient, `ObserverService` checks for a healthy token.
3. If present, fetch MWC for that member's world (cached briefly; one fetch per
   world per save, not per recipient — pool like `NotifyService`).
4. Append the `ObserverEmbeds` MWC section to that recipient's DM.
5. Any failure → omit the section, mark `needs_relink` if unauthorized, move on.

## 8. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Endpoint changes/vanishes (unsupported) | `mode=off` kills the path; circuit breaker; all failures are silent no-ops |
| Cloudflare challenge | *Resolved:* browser-TLS impersonation passes from a datacenter IP. Risk shifts to Cloudflare tightening later → isolate in the sidecar, alert + `mode=off` on repeated challenge |
| Per-account gating → thin coverage | Set expectations in the panel; MWC is best-effort per member's world |
| One-device contention with official app | Warn at add-time; treat re-link as normal |
| Token/bearer leakage | Encrypt at rest; never log; ephemeral replies only |
| Rate limits / server load | Pace + cache; one fetch per world per save |

## 9. Phased rollout

- **Phase 0 — spike (no bot code): DONE, gate cleared** — see `spike/observer/`.
  Validated end to end from a server context: Cloudflare passes via browser-TLS
  impersonation → `LoginWithAccessToken` (case-sensitive, single-use 5-char token) →
  JWT bearer → `GetMiniWorldChanges` `200`. Captured the login and MWC response
  shapes. Two findings folded into the design: tokens are **case-sensitive** (fixed
  in `ObserverModals`) and **single-use**, so the durable credential is the
  **session** (JWT + refresh), which Phase 2 persists — not the spent code.
- **Phase 1 — UX + storage, `mode=off`:** `/observer`, panel, add/remove buttons +
  modal, encrypted storage, lifecycle cleanup. No live calls yet (link is stubbed).
- **Phase 2 — live linking: DONE, validated end to end.** `observer-sidecar/` (Flask
  + `curl_cffi`) owns the Cloudflare pass and Observer calls; `ObserverApiClient` (JVM
  `HttpClient`) calls it; `ObserverService.link` exchanges the single-use code for the
  durable credential when `mode=on`, stores it encrypted, sets `Linked`, shows the
  account label. **Credential model (confirmed live):** login returns a **~90-day JWT**
  — that *is* the durable credential (no separate refresh token); `/renew` mints a
  fresh 90-day JWT from the current one via `Account/login`, so the link lasts until
  the user disconnects. `/link`, `/renew`, `/mwc` all validated with a live token.
  *Deferred to a small follow-up:* a periodic renewal job (store `expires`, renew
  before lapse) — not urgent at a 90-day lifetime.
- **Phase 3 — MWC delivery: BUILT.** The Observer API is rule-driven, so on link the
  bot sets enabled MWC rules (via the sidecar) for the account's character-worlds,
  then reads the active feed (`{world,title,body}`). `ObserverService.activeMwcForUser`
  fetches it; `ObserverEmbeds.mwcEmbed` renders the section; `BotApp` appends it,
  per-recipient, to the boosted server-save DM. Unlink clears the bot's rules. Whole
  data path (`/ensure-rules` → `/mwc` → render) validated live; bot compiles clean.
  *Deferred:* a periodic credential-renewal job (unhurried at 90 days), and choosing
  worlds by guild-tracked set rather than the account's own worlds if wanted.
- **Phase 4 — raids: data layer + renewal DONE.** Confirmed live: raid rules are
  exploration-gated (`RegionIds` from `/Area/ExploredAreas`, three modes
  area/subarea-revealed + raid-started); enabling them makes `GET /Raids` return
  `{raidId, worldName, areaName, subareaName, category, startDate, raidTypeId}`.
  Sidecar `/ensure-raid-rules` (auto-derives regions) + `/raids` validated; bot has
  `RaidAnnouncement`, client `raids`/`ensureRaidRules`/`renew`, service
  `activeRaids`/`renewAll`, and `ObserverEmbeds.raidEmbed`. **Renewal job** wired: a
  daily `renewAll` sweep keeps credentials fresh.
  - **Raids-channel delivery: BUILT.** Pooled **per world**: `pooledRaidsByWorld`
    unions raids across every linked account (deduped by raidId+category), and
    `ObserverRaidPoller` (5-min sweep) fans each new one — `RaidRanking`-ordered
    (type priority hook → stage → soonest start) — to the raids channel of every
    guild that has a raids channel for that world. So Discords tracking the same world
    share coverage. The **`📢・ʀᴀɪᴅs` channel is per-world**, living in that world's
    category beside its deaths/levels channels (member-visible, bot-only to post). It
    appears when a linked member runs **`/observer <world>`** (world is a required
    option now, giving the channel context), and is removed **with the world** on
    `/remove <world>` and on guild-leave — the normal world-channel convention. It
    persists when a token is removed (chat history stays). `observer_raid_channels`
    (keyed guild+world) + `observer_posted_raids` back it; channel creation seeds
    dedup so it starts clean. Compiles + command specs green.
  - **Raid names + live unfurl** — the feed reports a raid only as a numeric type id,
    an area and a stage (no name, no text, no timing). A bundled catalogue
    (`resources/raidtypes.json`), `RaidTypeCatalog` keyed by that id, supplies the
    raid's name, location, creatures, wiki link and its full **timed broadcast
    script**. Because that script is deterministic, delivery works in two stages
    (`ObserverRaidPoller`):
      - *Detection* (15-min sweep, the only API call): on a raid's **first sighting** —
        at whichever stage a member's exploration reveals it, area *or* subarea; deduped
        on `raidId` — posts one **imminent-raid** embed and registers the raid. (Raids
        are announced well ahead of starting, so 15 min catches them in good time.) The
        embed: title = `:raid:` emoji + raid name, linked to its wiki page; yellow
        (`14397256`); thumbnail of its boss (via `BossCatalogue`) or lead creature
        (TibiaWiki image); description = subarea, `Starts <t:…:R>`, then the creatures
        as wiki-linked bullets. No footer.
      - *Broadcast lines* (no polling): once the start is known, each broadcast is
        **scheduled as a one-shot timer** at its exact moment (`startDate + millis`),
        so it lands to the second with the scheduler idle in between — no fast drip
        loop. Each is a short embed, in-world text in **bold**, in the
        guilded-neutral-death grey (`4540237`), no footer, one message per line. A line
        whose moment has already passed (a raid caught late) fires immediately. Dedup
        is durable in `observer_posted_raids` (`key` = `imminent` / `line:i`), so a
        restart re-hydrates from the next poll (which re-schedules the remaining lines)
        without re-posting.
    Unknown ids fall back to area + stage; a missing/bad catalogue degrades to that
    too (graceful, as `BossCatalogue`). Layout was designed against an interactive
    Discord mockup and signed off; compiles green; both embeds verified live against
    real ids; image rebuilt and the dev bot recreated on it.

- **Stage posts: BUILT (replaces the single imminent heads-up above).** A live test
  with an account of limited discoveries showed the feed does not say *which* raid
  it is until the raid starts. Before then it gives only the area (revealed an hour
  ahead), the subarea (half an hour ahead) and the start time, which the feed
  carries at every stage. So each stage gets its own post, and nothing is edited.
  The area stage posts the imminent raid and when its subarea reveals; the subarea
  stage posts the subarea and when the raid starts; the start brings the raid type,
  and so the broadcast lines. The raid's name, creatures and picture are added
  whenever any linked account's feed already names it. Because every later stage
  is at a known moment, the poller schedules a one-off poll just after each rather
  than waiting for its 15-minute sweep. It also combines every entry the feed has
  for a raid, one per stage and per account. Previously it kept one arbitrary
  entry per stage. It also marked a raid's lines as scheduled before the raid type
  was known, which left a raid revealed at its start with no lines until a restart.
- **Named at the start: BUILT.** A raid only identified at its start was never
  named in the channel: its area and subarea posts went out before the feed knew
  it, and the lines carry only the in-game text. The start now posts the subarea
  embed again, with the raid's name, creatures and picture, saying it has started.
  It goes out ahead of the first line, because the poll that notices the start also
  schedules the lines. Whichever post first names a raid sets a `named` dedup key,
  so a raid named earlier gets nothing new at the start. A raids channel created
  before a raid is identified is left that post, so the raid's lines never arrive
  alone.
- **Stage post wording.** An unnamed raid's posts are titled "Imminent Raid" and
  "Subarea Revealed", and a named one's carry its name. Every stage post puts its
  location under the title as a grey `-#` line: the area at the area stage, and the
  subarea alone from the subarea stage on (the area when there is no subarea).
- **Fleet split: BUILT.** Every Observer request now leaves from one bot, the same
  shape as the fansite pipeline. The primary (or a lone bot) runs the sidecar
  (`observer` compose profile), fetches the pooled MWC and raid feeds for every
  linked account (read fresh from the shared table, so links made through any bot
  are covered), runs the renewal sweep, and publishes the feeds to Redis
  (`ObserverFeed`). The raids copy expires after 35 minutes; the MWC copy is
  stamped with when it was fetched and used until the next server save, since the
  changes are fixed for the day. A secondary never calls the API: it reads the
  primary's copy, treats a missing or pre-server-save copy as a failed fetch (and
  logs when that starts and stops), and hands `/observer` link and unlink to
  the primary over Redis (`ObserverRelay`) — so it never handles a credential and
  runs on `OBSERVER_API_MODE=on` alone, the encryption secret staying on the
  primary. The boosted DM's section is now the
  pooled changes on the member's account worlds rather than a per-member fetch. The
  raids poller only touches channels in guilds its own bot serves, since those rows
  are shared.

## 10. Open decisions (for review)

1. **World scope of MWC** — a member's token resolves to one account/world. Show MWC
   for *that* world only, or for the guild's tracked world(s)? (Leaning: the token's
   own world.)
2. **Who may add** — any member, or gated (e.g. Manage Server / a role)? `/boosted`
   is open to all; tokens are more sensitive.
3. **One token per member per guild**, or per world? (Leaning: one per member/guild.)
4. **Encryption** — confirm the existing at-rest mechanism to reuse.
5. **MWC placement** — extra embed on the existing boosted DM (as written), or its
   own message/channel?
6. **Public repo** — this repo is public; keep detailed upstream API specifics out of
   committed docs (this file stays deliberately high-level). Confirm that's the line.

## 11. Out of scope (v1)

Raids, housing, char-bazaar, offline-training; any guild-wide (non-member-token)
Observer data; pooling across members à la Tibia Live.
