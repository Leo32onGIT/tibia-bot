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

## 5. Phase 2 — frags

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

## 6. Phase 3 — killstatistics

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

## 7. Phase 4 — boss predictions (deferred)

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
2. **Phase 3's fetch-and-store half**, without any embed. Cheap, and every day it
   is deployed is a day of boss history banked for Phase 4.
3. **Phase 2** frags — the largest change to existing code, and the one whose
   value only begins accruing after it ships.
4. **Phase 3's embed** — creature stats, once a day's snapshot is reliably there.
5. **Phase 4** predictions, once the history is deep enough to be honest.

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
