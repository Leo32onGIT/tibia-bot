# The Statistics channel

A per-world channel that posts one embed a day, just after server save, summarising
the save day that has just closed: who gained the most experience, who lost the
most, the day's best skill advance, the guild's frag tally, and what the world was
killing (and being killed by).

Scoping notes and the build order. Branch: `statistics`, off `dev`.

---

## 1. Decisions already taken

| Question | Decision |
| --- | --- |
| Whose numbers in the leaderboards | **The whole world**, from the top-1000 experience highscores. Identical for every guild tracking that world. Frags are the exception and stay guild-scoped, because "hunted" and "allied" are per-guild facts. |
| Boss predictions | **Deferred.** Start recording the daily killstatistics snapshots now so the history accumulates; ship no prediction embed until the data is worth reading. |
| "Highest skill gain" | **Highest skill level reached** among the day's advances — "Bubble reached magic level 131". Not the biggest jump. |
| Channel placement | **Per world**, in that world's existing category alongside `💀・ᴅᴇᴀᴛʜs` and `💖・ʟᴇᴠᴇʟs`. Everything in the post except frags is a fact about the world, and a guild tracking two worlds wants two of these. |

---

## 2. What already exists

More than half of this feature is already in the tree, because the experience
history was built for it a week ago.

**`experience_daily`** (`bot_cache`) — one row per character per world per
**server-save day**, holding cumulative experience, level, vocation and display
name. Written on every hourly highscore snapshot, last write winning. Its own
docstring says it "exists only so the Statistics channel has something to read
when it is built". Landed 2 Sep 2026 (`a5657335`), on `main`, so production has
roughly a week of history already.

**`highscore_events`** (`bot_cache`) — every detected skill advance, with
`previous_score`, `score`, `char_level`, `vocation` and `observed`. Covers magic
level, shielding, sword, axe, club, distance and fist. This is the whole skill
half of the feature, already being filled.

**The primary-sweeps / every-bot-posts split** (`HighscoreService` +
`HighscoreFeed`) — the pattern this feature must copy. Scraping tibia.com is a
fleet-wide job that belongs to one bot; posting to Discord can only be done by the
bot that is actually in the guild. Anything new that fetches goes on the primary;
anything that posts runs on every bot.

**`ServerSaveSchedule`** — `lastServerSave`, `isServerSaveWindow` (10:00–10:45
Berlin). Already the boundary `experience_daily` is keyed on, and already drives
the boosted server-save post in `BotApp`.

**Channel + config plumbing** — `worlds` table, `Worlds` case class,
`ChannelService.createChannels` / `repairChannel` / `removeChannels`, the
`/settings` button panel, and `RespawnThreads.createForum` as the worked example
of retrofitting a *new* channel into guilds that ran `/setup` long ago.

## 3. What is missing

- No killstatistics endpoint, response model or client method.
- No storage for killstatistics snapshots.
- **Nothing persists frags.** Deaths are classified per-guild, posted, and
  forgotten. The frag tally cannot be backfilled; it starts the day this deploys.
- No `statistics_channel` column, no channel, no post.
- `HighscoreChange.Declined` is classified but never written to
  `highscore_events`. Skill *losses* are therefore not available. Not needed for
  what was asked (only experience loss was), but it's a one-line-ish change if
  skill loss is ever wanted.

---

## 4. Phase 1 — the channel, and the experience/skill embed — **BUILT**

**Ships without fetching anything new.** Everything it reads is already in the
database. This is the phase that can post tomorrow, and it should go first for
exactly that reason.

### 4.1 Config and channel

1. `worlds.statistics_channel VARCHAR(255) DEFAULT '0'` — added through the
   existing `columnExists` / `ALTER TABLE` migration in
   `JdbcWorldConfigRepository.migrate`, and a `statisticsChannel` field on
   `domain.Worlds`.
2. `📊・sᴛᴀᴛɪsᴛɪᴄs` created in the world's category. Created by `/setup` for new
   worlds; for the several hundred guilds that already ran it, created **on
   demand** from a new `/settings` button rather than force-created on everyone —
   the same choice the respawn forum made, and it keeps the bot from making a
   channel in servers that never asked for one.
3. `/repair` support, mirroring `recreateDeathsChannel`.
4. Permissions: `VIEW_CHANNEL`, `MESSAGE_SEND`, `MESSAGE_EMBED_LINKS` — the same
   set `commandLogPermissions` uses, since this only ever posts embeds.

### 4.2 The queries

**Experience gained/lost.** A day's gain is a difference of two `experience_daily`
rows: `exp(D) - exp(D-1)` for the same `(world, name)`. New method on
`ExperienceRepository`:

```scala
/** The day's biggest movers on one world, gainers first.
 *  A character with no row on the previous day is skipped — entering the
 *  top thousand is not a day's experience. */
def dailyMovers(world: String, saveDay: LocalDate, limit: Int): List[ExperienceDelta]
```

One self-join on `(world, name)` between `save_day = D` and `save_day = D - 1`,
ordered by the delta. The top 10 comes off the head, the single biggest loss off
the tail (and is only rendered when it is actually negative).

**Best skill advance.** New method on `HighscoreRepository`:

```scala
/** The highest skill level reached on one world between two instants,
 *  experience excluded. */
def topAdvance(world: String, from: Instant, to: Instant): Option[HighscoreEvent]
```

`SELECT ... WHERE world = ? AND category <> 'experience' AND observed >= ? AND
observed < ? ORDER BY score DESC LIMIT 1`. The existing
`highscore_events_world_observed` index already covers the predicate.

### 4.3 Timing — the part most likely to be got wrong

`recordDaily` keys a reading by `lastServerSave(snapshotAt)`. So the row for save
day **D** is written from every snapshot between 10:00 on D and 09:59 on D+1, and
the last write into it is the ~09:40 snapshot on D+1. Highscores rebuild hourly,
landing on :40 past.

Therefore:

- Post inside the existing `isServerSaveWindow` (10:00–10:45 Berlin) job in
  `BotApp`, which already runs on the 30-second tick.
- The day being reported is `lastServerSave(now).toLocalDate.minusDays(1)`. At
  10:15 on the 11th that is the 10th — the day that just closed.
- Its baseline is the 9th, so **two consecutive days of `experience_daily` must
  exist** before the first post is possible.
- Guard with a per-world "already posted for this save day" marker so the
  45-minute window's ~90 ticks produce one post, and a restart mid-window does not
  produce a second. Same shape as `dreamScarSave`.

A player who levels between 09:40 and 10:00 lands in the next day's figures. That
is a snapshot-granularity artefact, not a bug, and is not worth chasing.

### 4.4 New files

```
statistics/DailyStatistics.scala    — pure assembly of a day's figures, no DB, no JDA
statistics/StatisticsService.scala  — reads the repositories, decides when to post
presentation/StatisticsEmbeds.scala — the embed
```

`DailyStatistics` holds the arithmetic and the "is this day even reportable"
rules, so it is unit-testable the way `HighscoreDiff` and `Killers` are. Tests in
`test/scala/com/tibiabot/statistics/`, covering: the delta join, a missing
baseline row, a day with no losses, a day with no advances, and a cold start with
only one day of history.

### 4.5 Known limitation worth stating in the embed's footer

The experience list is the world's top 1000. A character outside it who has a huge
day is invisible. On established worlds the cutoff is high enough that this
effectively never changes the top 10; on a young world it can. Footer should say
what the numbers are drawn from rather than implying the whole world was measured.

---

## 5. Phase 2 — frags — **BUILT**

Guild-scoped, and **has no history** — it starts recording the day it deploys.

### 5.1 Storage

A `frag_event` table in the **per-guild database**, not `bot_cache`. Hunted and
allied lists already live there, a guild is served by exactly one bot so writes
never contend, and the daily post reads one guild's day at a time.

```
frag_event(
  world, save_day, killer, victim, victim_side, occurred_at
)
```

`victim_side` is `'enemy'` when the victim was on that guild's hunted list or in a
hunted guild, `'ally'` when allied. A neutral victim is not a frag and is not
recorded. The killer's side is the opposite of the victim's — so
"hunted players killed" counts rows with `victim_side = 'enemy'`, and the top
allied fraggers are the killers on those rows.

### 5.2 Where to record

In `TibiaBot.scala`'s death block, inside the per-guild loop, in the
`killerList.foreach { k => if (k.player) { if (k.name != charName) { ... } } }`
branch that already exists at ~line 1080.

**Do not reuse `exivaBuffer`.** It is only populated when `embedColor == 13773097`
(an ally death) *and* the world's `exiva_list` setting is on — so it misses every
hunted-player death and every world with exiva lists disabled. A separate
`fragBuffer` in the same branch, resolving summons to their summoner through
`Killers.summonBehind`, captures all of them.

Two things to be deliberate about:

- **Record even when the embed is suppressed.** `showEnemiesDeaths` and
  `deathsMin` decide whether a guild wants to *see* the death; the frag happened
  either way, and a tally that silently omits low-level kills would be wrong.
- **Dedup is already handled upstream.** `recentDeaths` (TibiaBot.scala:86, backed
  by the `deaths` cache) filters a death once per world before the per-guild
  fan-out, so one row per guild per death falls out naturally.

### 5.3 Output

Totals for the save day plus the top 10 fraggers on each side, each side omitted
entirely when it is empty rather than rendered as a blank field.

---

## 6. Phase 3 — killstatistics — **BUILT**

### 6.1 The endpoint

`GET /v4/killstatistics/{world}` on the **public** API — verified working and
unauthenticated. Shape:

```json
{ "killstatistics": {
    "world": "Antica",
    "entries": [ { "race": "...", "last_day_players_killed": 0,
                   "last_day_killed": 148, "last_week_players_killed": 5,
                   "last_week_killed": 895 } ],
    "total": { "last_day_players_killed": 818, "last_day_killed": 2514276,
               "last_week_players_killed": 5520, "last_week_killed": 21487813 } },
  "information": { ... } }
```

Adds `KillStatisticsResponse` + `JsonSupport` formats + `getKillStatistics(world)`
on `TibiaApi`/`TibiaDataClient`, following `getWorld`'s shape exactly.

**One request per world per day** — 68 requests, against the highscore sweep's
~90,000. Negligible. Primary only, and it must go through the existing retry
policy: TibiaData is currently failing about 45% of all requests with 503s, and a
miss here costs a permanent hole in one world's history rather than a retryable
blip.

### 6.2 Storage — and why not all of it

Antica reports **1481 races, of which 1277 were non-zero yesterday**. Storing every
row is 87k rows a day across 68 worlds, ~32M a year, on a box already at 80% disk.
That is not worth it. Store two things instead:

1. `kill_statistics_boss(world, save_day, race, killed, players_killed)` —
   restricted to the ~74-boss catalogue. 5k rows a day, ~1.8M a year. This is the
   only part Phase 4 actually needs.
2. `kill_statistics_summary(world, save_day, ...)` — one row per world per day
   holding the day's headlines: most-killed creature and its count, the creature
   that killed the most players and its count, and the `total` block.

### 6.3 Two entries that need special handling

- **`players`** is a race in the list, and it is where PvP deaths are counted
  (378 on Antica yesterday). It must be excluded from "creature that killed the
  most players" or it wins every day on every world. It is worth reporting
  separately as the world's PvP death count.
- **`(elemental forces)`** is environmental damage, not a creature. Same
  exclusion.

Once those two are out, the answer is a real one — quara looters at 13 on Antica
yesterday.

---

## 7. Phase 4 — boss predictions — **BUILT**

Recorded here so Phase 3 stores the right thing, not to be built yet.

`kik-tibia/boss-tracker` is MIT-licensed and its two useful pieces port cleanly:

- **`BossPredictor.getChance`** — pure window arithmetic over "days since last
  seen" against a boss's `windowMin`/`windowMax`, yielding None/Low/High. About 30
  lines, no dependencies, trivially unit-testable. Its `spawnPoints > 1` branch
  handles bosses with several spawn locations.
- **`data-example/boss-list.json`** — 74 bosses with `windowMin`, `windowMax`,
  `spawnPoints` and a category. This is the data that makes prediction possible at
  all, and it is the piece worth taking rather than re-deriving.

The blocker is history depth. Prediction needs the last day a boss was *seen*, and
a killstatistics snapshot only proves a boss was killed on days we actually looked.
From a cold start:

| Boss class | Window | Trustworthy after |
| --- | --- | --- |
| Profitable / Stealth Ring / most others | 12–28 days | ~1 month |
| World bosses (Ferumbras, Ghazbaran, …) | 154–175 days | ~6 months |

`last_week_killed` gives a free 7-day head start on the first snapshot, and
nothing more. The reference project sidesteps this with a companion repository of
historical daily snapshots (`tibia-kill-stats`) which is **not public** — so
backfilling from it is not an option. The realistic alternatives when this is
picked up are a one-time seed of last-seen dates from guildstats.eu, or simply
waiting.

---

## 8. Risks and open items

- **Cold start (Phase 1).** Two consecutive `experience_daily` days are needed.
  Production has had the table since 2 Sep, so this is likely already satisfied —
  worth confirming against the live database before building the post rather than
  discovering it on the first morning.
- **Frags have no history.** The first post's frag section will be empty, and
  there is no way to make it otherwise.
- **Disk.** Blue is at 80%. Phase 3's storage decision above is what keeps this
  feature from being the thing that fills it; the retention prune should be added
  in the same commit as the table, not afterwards.
- **TibiaData 503s.** 45% failure rate makes the once-a-day killstatistics fetch
  genuinely likely to miss. It needs retries and a "we have no data for world X on
  day D" state that renders as absence rather than as a zero.
- **Post volume.** One embed per world per guild per day. A guild tracking six
  worlds gets six posts in the same 45-minute window; they go through
  `RateLimitedSender` like everything else, but it is worth checking the pacing
  under the biggest real guild rather than assuming.

## 9. Suggested order

1. ~~**Phase 1** end-to-end — channel, queries, embed, schedule, tests.~~ **Done** —
   see section 10.
2. ~~**Phase 3's fetch-and-store half**, without any embed.~~ **Done** — see
   section 11.
3. ~~**Phase 2** frags.~~ **Done** — see section 12.
4. ~~**Phase 3's embed** — creature stats.~~ **Done** — see section 12.
5. ~~**Phase 4** predictions.~~ **Done** — see section 13. Built ahead of the
   history rather than after it, so each boss starts predicting the moment it is
   first seen.

---

## 10. What Phase 1 actually shipped

Branch `statistics`, off `dev`. Compiles warning-free; 1,935 tests pass, 34 of them
new. (`DualCharacterApiSpec` aborts for want of a `POSTGRES_HOST` locally — it does
the same at the branch point, checked against `cb9555ac` in a scratch worktree.)

**Storage.** Two columns on the per-guild `worlds` table, both added through the
existing `columnExists` / `ALTER TABLE` migration so old guilds pick them up on
first read: `statistics_channel` (`"0"` = off) and `statistics_posted` (the last
save day covered). A new `experience_daily_world_day` index on
`(world, save_day)` — the primary key leads with `world` but puts `name` before
`save_day`, so without it the movers query walks every retained day of that
world, ~90k rows, once per world, every morning inside the window.

**Queries.** `ExperienceRepository.dailyMovers` / `dailyLoss` (one self-join
against the previous day; the *inner* join is what drops characters with no
baseline, so the exclusion cannot be forgotten) and
`HighscoreRepository.topAdvance` (highest `score` in the day's window, experience
excluded, ties to the earlier advance).

**Logic.** `statistics/DailyStatistics.scala` — which day has closed, what
instants it spans, what counts as a gain. Pure, no database or JDA, the same split
`HighscoreDiff` keeps from `HighscoreSweep`. `statistics/StatisticsService.scala`
drives it: gated on `isServerSaveWindow`, one report per world shared across every
guild watching it, and a mark written whether the send worked or not.

**Presentation.** `presentation/StatisticsEmbeds.scala`. The leaderboard sits in
the description, not a field — a real ten-line board measures 1,216 characters
against a field's 1,024 cap. Config-free, with the skill emoji injected the way
`HighscoreAnnouncement` already takes it; reading Config here made every test of
the file need a database host set.

**Opt-in.** A `Statistics` button on `/settings` (📰) with an On/Off form.
Turning it on creates `📊・sᴛᴀᴛɪsᴛɪᴄs` in that world's category; turning it off
clears the id and leaves the channel, as the command log does. `/repair` rebuilds
it only for a world that had one. `/setup` does not make it — several hundred
guilds should not find a new channel after a deploy.

**Config.** `discord.statistics { enabled, tick-interval }`, `STATISTICS_ENABLED`
to turn it off without stopping the history.

### Carried into Phase 2/3 from building this

- `DailyReport` deliberately holds nothing guild-scoped, so frags will need a
  second, per-target piece rather than a field on it.
- `StatisticsService.report` is the seam the killstatistics figures plug into —
  it already builds per world and caches per tick.

---

## 11. What Phase 3's fetch-and-store half shipped

Compiles warning-free; 1,970 tests pass, 25 of them new here.

**The endpoint.** `KillStatisticsResponse` + spray-json formats +
`getKillStatistics` on a new `KillStatisticsApi` trait — separate from `TibiaApi`
for the reason `HighscoresApi` already is, so the six character-sheet wrappers do
not each grow a delegating method. It shares the highscore sweep's client rather
than opening a third connection pool for 68 requests a day.

**The catalogue.** `resources/bosses.json`, 74 bosses with their spawn windows,
from kik-tibia/boss-tracker (MIT). `statistics/BossCatalogue.scala` loads it the
way `RespawnCatalogue` loads its seed — lazily, degrading to empty with a warning.

**Storage.** `kill_statistics_boss` (74 rows per world per day) and
`kill_statistics_summary` (one), both in `bot_cache`, with a `save_day` index for
the prune and a 400-day retention — longer than the 175-day worst-case window,
because a history shorter than the window it measures can only ever say "not seen
recently".

**The sweep.** `statistics/KillStatisticsService.scala`, primary-only. Files boss
rows before the summary, because `hasDay` reads the summary — so a half-finished
day looks unfiled and is simply read again.

### Two things this phase turned up

**`last_day` is the *previous closed* day, not the running one.** tibia.com states
nothing on the page and the endpoint is Kong-cached, so sampling could not settle
it. The reference bot does: `BossDataFetcher` attributes a snapshot to
`timestamp.minusDays(1)`. That makes `DailyStatistics.reportedDay` the right key
for both halves of this feature — which is what will let the killstats figures and
the experience figures share one embed later.

There is one hazard in that: tibia.com's roll at server save is not instant, and a
read at 10:01 filed under today's date is an off-by-one that nothing downstream
could ever detect. Hence a one-hour `settle` before any read, and no deadline
after it — a bot down all morning still catches the day.

**The upstream catalogue had a wrong race name.** It maps Rotworm Queen to
`"Rotworm Queens"`; tibia.com uses the singular. Checked against the live endpoint
across eight worlds: the plural appears on none, the singular on five. Left alone
that boss would have recorded as never spawning, on every world, forever — a
silent hole in exactly the history this phase exists to build. `yetis`,
`midnight panthers` and `albino dragons` were checked the same way and are right.
The correction and its evidence are recorded in the file's `_source` field.

### Still to do in Phase 3

The embed. The figures are being banked but nothing reads them yet;
`StatisticsService.report` is the seam, and `KillStatisticsRepository.summary`
already returns exactly what a "what the world was killing" field needs.

---

## 12. What Phases 2 and 3's embed shipped

1,984 tests pass, warning-free. The post now carries six fields at full stretch
and measures ~2,060 characters against the 6,000 cap.

**Kill statistics in the post.** `DailyReport` gained a `kills` field and
`StatisticsService` reads `KillStatisticsRepository.summary`. Rendered as one
"Around the world" field: most killed, deadliest creature, PvP deaths, total
creatures killed. Lines drop individually rather than the field being
all-or-nothing, because a world can genuinely have a day where no creature killed
a player. A day whose snapshot was never taken posts everything else regardless —
the two halves come from different sources and neither waits on the other.

**Frags.** `frag_event` in the **per-guild** database, created on first use the
way `JdbcActivityRepository` does it, so the several hundred existing guilds need
no migration pass. Keyed `(world, killer, victim, occurred_at)` — a natural key,
since one player cannot kill the same player twice in one instant — which makes a
reprocessed death a no-op rather than a doubled tally.

Recorded from the death path via a new `fragBuffer`, **not** `exivaBuffer`: that
one only fills for an ally death *and* only when the world has exiva lists on, so
reusing it would have missed every hunted-player kill and every world with the
setting off. Recording is deliberately not gated on the embed being shown —
`showEnemiesDeaths` and `deathsMin` decide what a server wants to *see*, and a
tally that quietly omitted low-level kills would be wrong rather than filtered.
It is gated on the guild having a statistics channel, since the rows exist only to
be posted there.

The counts and the leaderboards measure different things on purpose: "enemies
killed" counts distinct deaths (a victim killed by eight people is one loss), the
leaderboards count rows per killer.

### Gaps worth knowing

- **The three new frag SQL statements are unexercised.** Docker was not running,
  so nothing here has run them against a real Postgres. They are the newest and
  most intricate SQL in the feature. Worth a single local smoke test before this
  is deployed.
- Frags have no history and cannot get one — the tally starts the day it deploys,
  and a guild that turns the channel on later starts counting from that moment.

---

## 13. What Phase 4 shipped

2,021 tests pass, warning-free. All four phases are now built.

**The arithmetic** is a port of `BossPredictor.getChance` from
kik-tibia/boss-tracker (MIT), plus the spawn-point handling beside it. The
non-obvious part is that a boss missed for a cycle counts towards a *later*
window rather than still towards its first — which is exactly the part that would
have been got wrong by re-deriving it. Divisors are guarded, which the original
did not need: its data file was its own, and this one is a resource anybody can
edit where a `windowMin` of 1 would divide by zero.

**The rule that keeps it honest.** A boss with no sighting in our history is not
predicted at all. There is no anchor for it — the last spawn could be the day
before our first snapshot or a year before it, and nothing can tell those apart.
Guessing from "at least N days" would make bosses look overdue purely because the
bot is new, which is the one way this feature could actively mislead. So each
boss becomes predictable the first time it is killed after the snapshots start,
and the embed's footer says how many are still waiting.

**The post** now carries two embeds in one message: what happened, then what might
happen today. Only bosses that might actually be up are listed — the catalogue
has 57 predictable ones and on an ordinary day most are a few days into a long
window, which would be three thousand characters of "not due" burying the four
lines somebody came for. Bands are capped at 12 high and 8 low with a "+N more"
tail, since the two embeds share a 6,000-character message.

### A property worth knowing before reading the output

Wide windows tile. A 12–28 day boss has window one at 12–28 and window two at
24–56, which overlap — so from day 12 onward *every* day is inside some window and
the boss reads as "due" indefinitely, shown as a `12+` window with no upper bound.
That is a real consequence of the arithmetic rather than a rounding artefact, and
it is pinned by a test. Narrow windows (Ferumbras at 161–175) do not tile, and
those genuinely go quiet between cycles.

### What is left

Nothing in the original scope. The remaining items are the two gaps already
recorded: the frag SQL has never run against a real Postgres, and the whole
feature has never been deployed, so no world has any history yet.

---

## 14. The post as designed (locked 10 Sep 2026)

Worked out against a Discord mockup rather than in code. Everything below is
settled; what is written today does not look like this and has to be rebuilt.

**Three embeds in one message. No embed fields anywhere** — every embed is a
description plus, in one case, a thumbnail and a footer. That removes the
1,024-character field cap from the design entirely, and means nothing reflows
differently between desktop and mobile.

### Embed 1 — the world (blue `#2196F3`, thumbnail)

```
## 📊 [Thursday 10 September 2026](world url)
### Top Experience Gained
{voc} **[Name](url)** {sideIcon} · *{level}* · {xpUp} **182,450,912}**     × 10
### Top Experience Lost
{voc} **[Name](url)** {sideIcon} · *{level}* · {xpDown} **18,402,993**
### Top Skill Advancement
{voc} **[Name](url)** {sideIcon} · *{level}* · {skillIcon} magic level **131**
### Creature Stats
**23,965** flimsy lost souls killed
**13** players killed by quara looters
```

No footer. The date is an `##` heading so it outranks its own `###` sections —
which is why it is a masked link in the description rather than an embed title.

### Embed 2 — PVP (red `#C0392B`)

```
## 🗡️ PVP
{splitBar}
**9** enemies killed vs **4** allies killed

### Most Kills
{voc} **[Name](url)** {sideIcon} · **4 kills**      top 5 a side, merged, ranked
### Most Deaths
{voc} **[Name](url)** {sideIcon} · *{level}* · **4 deaths**        × 5
### Most Exp Lost
{voc} **[Name](url)** {sideIcon} · *{level}* · {xpDown} **24,180,400**   × 5, enemies only
### Top Enemy Killed
{voc} **[Name](url)** {sideIcon} · *{level}*
-# [Jump to the death](discord message url)
### Top Ally Killed
{voc} **[Name](url)** {sideIcon} · *{level}*
-# [Jump to the death](discord message url)
```

The bar is one run split where the day was won: fixed segment count, the split
proportional to the two figures. The two fragger lists are merged into one —
each name carries its own side icon, so the columns were doing nothing.

### Embed 3 — Bosses Due (green `#249F2D`)

```
## {bossEmoji} Bosses Due
🟢 {bossEmoji} **Dharalion** · overdue since 22 days ago
🟢 {bossEmoji} **Furyosa** · window closes in 3 days
🟡 {bossEmoji} **White Pale** · opens in 1 day
```

One flat list, most overdue first, the chance as a dot on the row rather than as
a band heading. Timings are Discord relative timestamps — `<t:epoch:R>` — so they
stay true as the day moves rather than freezing at the moment of posting. Every
window edge is a server save, so the epoch is 10:00 Berlin on that date.

Footer: `N boss(es) not yet predicted.`

### Conventions that hold everywhere

- **Row shape** is `{vocation} **name** {side icon} · *level* · {figure}`, the
  same order the online list uses — vocation first, side icon after the name.
- **Section labels carry no emoji.** Embed titles do.
- Experience figures use the uploaded xp icons **in place of** `+` and `−`.
- Ally and enemy icons come from `GuildIcons`, unchanged, including the
  neutral-guild pairs.

---

## 15. What building it needs

### Assets to upload (yours)

| | |
| --- | --- |
| 9 bar segments | `bar_{green,red,empty}_{start,mid,end}` — generated and sent 10 Sep |
| 2 experience icons | xp up / xp down — supplied |
| boss icon | already exists: `Config.bossEmoji` = `<:boss:1195770698401075281>` |

Nothing renders until the ids are in `discord.conf`.

### Schema

- `frag_event.victim_level` — for the two Top Killed sections.
- `frag_event.death_message_id` — for the jump links.
- Most Deaths and Most Exp Lost need **no** new columns.

### Code

- **Capture the death message id.** Deaths go out through
  `sendMessageWithRateLimit` with `queue(null, ignoreDeletedTarget)`, so nothing
  keeps what Discord returns. Needs a success callback. The screenshot feature
  gets its id from a button press instead, so there is no existing path to reuse.
- **`StatisticsEmbeds`** — rebuilt: three embeds, description-only, new row shape.
- **`BossPredictionEmbeds`** — one list, dots, relative timestamps, "Bosses Due".
- **`BossPredictor`** — expose the window's open and close instants, not just the
  day counts.
- **`StatisticsService`** — renders per target rather than per world (see below),
  and emits a list of embeds.
- **`ExperienceRepository`** — a query for the largest experience losses among a
  given set of names, for Most Exp Lost.
- **`FragRepository`** — `mostWanted`, and the top killed victim per side.
- **Guild resolution** for the board's side icons — reuse
  `BotApp.resolveAdvanceGuilds`, about 12 lookups per world per day.

### Two consequences worth stating before starting

**The world embed is no longer world-shared.** Side icons are a per-guild fact,
so the same board renders differently in every discord. The *data* stays shared
— one pair of queries per world — but the rendering splits per target. That is a
real change to `StatisticsService`, which today builds one report per world and
hands the same thing to everybody.

**Most Exp Lost will usually be nearly empty.** It crosses the hunted list
against `experience_daily`, which holds only the world's top thousand, and most
tracked enemies are not in it. Accepted knowingly; the alternative is fetching
experience for hunted characters individually, which is a much larger change to
the sweep.

### Still true from before

Frags, victim levels and message ids all start from the day this deploys —
none of it is backfillable. Boss predictions warm up over months. And the frag
SQL has still never run against a real Postgres.

---

## 16. What actually shipped (10 Sep 2026)

Section 14 held, with three notes where the build learned something.

**Side icons come from the player lists only.** The checklist above planned to
reuse `resolveAdvanceGuilds` so a character hunted through their guild would
carry an icon too. It does not: the experience tables store vocation and level,
never a guild, so resolving guilds would mean fetching sixty character sheets
every morning. Somebody hunted only through their guild reads as neutral, which
is the quieter wrong answer. `BotApp.statisticsSideIcon` documents it.

**PVP vocations come from the cached character sheets.** A frag row stores names
and nothing else — a killer is a name on a death message — so the `{voc}` the
row shape calls for has to be looked up. `BotApp.statisticsVocation` reads the
`list` cache once per post and keys it lowercased. That cache exists to draw the
hunted and allied lists, so it covers exactly the population a PVP post is about.
Anybody with no sheet renders without an icon and the row closes up around the
gap, which is also what happens to an untracked character's side icon.

**Section labels lost their emoji, titles kept theirs.** Both convention and
code: `StatisticsEmbeds.section` and `PvpEmbeds.section` take a title and no
icon, and a test in `StatisticsEmbedsSpec` fails if one comes back.
