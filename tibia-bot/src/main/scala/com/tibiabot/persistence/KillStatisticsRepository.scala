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

  /** Every day each boss was seen on one world, newest first, keyed by the
   *  lowercased race name.
   *
   *  One query for the whole world rather than seventy-four, since the
   *  prediction wants all of them at once. The whole retained history, because a
   *  world boss counts in months and narrowing this to recent days would hide
   *  exactly the bosses worth predicting — the retention cutoff is the only
   *  bound there is.
   *
   *  Only days a boss was actually seen are returned. The zero rows exist so
   *  that a day we looked is distinguishable from a day we did not, and they
   *  would otherwise be most of the result.
   *
   *  The Int is that day's kill count. It matters for a boss with several spawn
   *  points: three killed on one day is three sightings, not one. */
  def sightings(world: String): Map[String, List[(LocalDate, Int)]]

  /** Every race kept for one world's day that something actually killed, largest
   *  first.
   *
   *  One query answering both halves of the creature embed: the day's top
   *  creatures are the head of it, and the special bosses are picked out of it
   *  by name. A catalogued boss is in here too — it cannot outrank a creature,
   *  since a world kills three of one and a hundred thousand of the other — so
   *  taking the first ten is the ten the post means. */
  def killsOn(world: String, saveDay: LocalDate): List[BossKills]

  /** Every world's daily counts for a named set of races since `from`, keyed by
   *  world, zeros included.
   *
   *  For [[com.tibiabot.statistics.DreamCourtEvidence]], which weighs the five
   *  Dream Courts bosses against each other day by day. The zeros are the point:
   *  a day one of them was not killed is evidence about which boss was available,
   *  so "no row" and "zero" have to stay distinguishable.
   *
   *  Every world at once rather than one at a time, because the caller wants
   *  the lot: it walks the whole wiki map each server save, which was a hundred
   *  and eleven round trips for five races. Narrowing by world is also what
   *  stopped the query using an index — `LOWER(race)` cannot be matched against
   *  the key's own race column, so each of those scanned its world's whole
   *  window anyway. Dropping the world leaves one scan of the day range, which
   *  the `save_day` index does serve, and it reads fewer rows in total than the
   *  hundred and eleven did between them. */
  def dailyCounts(from: LocalDate, races: Set[String]): Map[String, List[BossKills]]

  def summary(world: String, saveDay: LocalDate): Option[DayKillSummary]

  def removeExpired(before: LocalDate): Unit
}
