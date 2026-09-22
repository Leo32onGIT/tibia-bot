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
- **Cloudflare** — server-side requests may hit a challenge; needs the right
  headers/UA and graceful failure. Validate early (see Phase 0).
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
- `observer/ObserverApiClient.scala` (+ `observer/response/*`) — the Observer HTTP
  client: login, link, refresh, GetMiniWorldChanges (templates: `fansiteapi/`,
  `tibiadata/`). Own circuit breaker / pacing like `FansiteCircuitBreaker`.
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
| Cloudflare blocks server IP | Validate in Phase 0 before building; correct UA/headers; back off on challenge |
| Per-account gating → thin coverage | Set expectations in the panel; MWC is best-effort per member's world |
| One-device contention with official app | Warn at add-time; treat re-link as normal |
| Token/bearer leakage | Encrypt at rest; never log; ephemeral replies only |
| Rate limits / server load | Pace + cache; one fetch per world per save |

## 9. Phased rollout

- **Phase 0 — spike (no bot code):** confirm from a server-like context that we can
  login → link a test token → GetMiniWorldChanges through Cloudflare. If this fails,
  stop — the feature isn't viable as-is. *(gate)*
- **Phase 1 — UX + storage, `mode=off`:** `/observer`, panel, add/remove buttons +
  modal, encrypted storage, lifecycle cleanup. No live calls yet (link is stubbed).
- **Phase 2 — live linking:** wire `ObserverApiClient` login/link/refresh behind the
  mode flag; `/observer` shows real link health.
- **Phase 3 — MWC delivery:** attach the MWC section to the boosted DM.
- **Phase 4 (later):** raids, if wanted.

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
