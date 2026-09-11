package com.tibiabot.persistence

import com.tibiabot.statistics.{BossKills, DayKillSummary}

import java.time.LocalDate

/** Persistence port for the daily kill statistics in the shared `bot_cache`
 *  database.
 *
 *  World-scoped, like the highscore and experience tables beside it and for the
 *  same reason: what a world killed yesterday is a fact about the world, and
 *  every discord tracking it is looking at the same answer.
 *
 *  Only the catalogued bosses are kept, not the fifteen hundred races the
 *  endpoint returns — see SchemaInitializer for the arithmetic. The rest of the
 *  day survives as one summary row. */
trait KillStatisticsRepository {

  /** File one world's boss rows for one day. Idempotent: a second write of the
   *  same day replaces it, so a re-run after a partial failure is a correction
   *  rather than a duplicate. */
  def recordBossKills(rows: List[BossKills]): Unit

  def recordSummary(summary: DayKillSummary): Unit

  /** Whether this world's day has already been filed.
   *
   *  Read from the summary table, which is written last — so a day that failed
   *  halfway through the boss rows reads as absent and is fetched again. */
  def hasDay(world: String, saveDay: LocalDate): Boolean

  /** One boss's history on one world from `from` onward, oldest first — the
   *  shape a spawn prediction wants. Nothing reads this yet; it is the reason
   *  the table exists. */
  def bossHistory(world: String, race: String, from: LocalDate): List[BossKills]

  /** Every day each boss was seen on one world since `from`, newest first,
   *  keyed by the lowercased race name.
   *
   *  One query for the whole world rather than seventy-four, since the
   *  prediction wants all of them at once. Only days a boss was actually seen
   *  are returned — the zero rows exist so that a day we looked is
   *  distinguishable from a day we did not, which is what [[earliestDay]]
   *  answers, and they would otherwise be most of the result.
   *
   *  The Int is that day's kill count. It matters for a boss with several spawn
   *  points: three killed on one day is three sightings, not one. */
  def sightings(world: String, from: LocalDate): Map[String, List[(LocalDate, Int)]]

  /** The first day this world has any snapshot for, or None if it has none.
   *
   *  How far back the history goes, which is what decides whether "not seen
   *  since" means anything yet. A boss never seen inside it cannot be predicted
   *  at all — the last sighting could be a day before our first snapshot or a
   *  year before it, and nothing here can tell those apart. */
  def earliestDay(world: String): Option[LocalDate]

  /** Every race kept for one world's day that something actually killed, largest
   *  first.
   *
   *  One query answering both halves of the creature embed: the day's top
   *  creatures are the head of it, and the special bosses are picked out of it
   *  by name. A catalogued boss is in here too — it cannot outrank a creature,
   *  since a world kills three of one and a hundred thousand of the other — so
   *  taking the first ten is the ten the post means. */
  def killsOn(world: String, saveDay: LocalDate): List[BossKills]

  def summary(world: String, saveDay: LocalDate): Option[DayKillSummary]

  def removeExpired(before: LocalDate): Unit
}
