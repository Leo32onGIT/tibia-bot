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

  def summary(world: String, saveDay: LocalDate): Option[DayKillSummary]

  def removeExpired(before: LocalDate): Unit
}
