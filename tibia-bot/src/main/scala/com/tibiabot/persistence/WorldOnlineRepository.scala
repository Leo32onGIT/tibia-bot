package com.tibiabot.persistence

import java.time.LocalDate

/** How busy a world was and how high its people are, over a server-save day.
 *
 *  Exists for one reader: the PVP bar. Population sets what a full bar is worth,
 *  because ten frags on a world with sixty players on it is the whole day and on
 *  Antica it is a quiet afternoon. Average level sets what one death is worth,
 *  because a level 8 is nothing on a mature server and a real kill on a week-old
 *  one — and taking that reference from the world rather than from the day's
 *  victims is what stops a day of farming nobodies drawing a full bar.
 *
 *  A sum and a count rather than a stored average, so recording a sample is one
 *  upsert that reads nothing back. That also makes it safe for every bot
 *  tracking the world to write: two bots recording the same minute add to both
 *  halves and leave the quotient where it was, so there is no primary to elect
 *  and no coordination to get wrong.
 */
final case class WorldOnlineAverage(online: Double, level: Double)

trait WorldOnlineRepository {

  /** File one reading: how many were online, and their levels added up. Called
   *  on the world stream's ordinary poll, which already holds both. */
  def recordSample(world: String, saveDay: LocalDate, online: Int, levelTotal: Long): Unit

  /** The day's average population and average level, or None for a day nothing
   *  was ever recorded — a world on its first day, or one the bot was down for.
   *  The caller falls back to a default scale rather than dropping the bar. */
  def averages(world: String, saveDay: LocalDate): Option[WorldOnlineAverage]

  def removeExpired(before: LocalDate): Unit
}
