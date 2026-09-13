package com.tibiabot.persistence

import com.tibiabot.domain.{BoostedCache, DeathsCache, LevelsCache, ListCache, SheetCache}

import java.time.ZonedDateTime

/** Persistence port for the shared `bot_cache` database, covering the `deaths`,
 *  `levels`, `list` and `boosted_info` caches. */
trait CacheRepository {
  def getDeaths(world: String): List[DeathsCache]
  def addDeath(world: String, name: String, time: String): Unit
  /** Delete death rows older than 30 minutes relative to `now`. */
  def removeExpiredDeaths(now: ZonedDateTime): Unit

  def getLevels(world: String): List[LevelsCache]
  def addLevel(world: String, name: String, level: String, vocation: String, lastLogin: String, time: String): Unit
  /** Delete level rows older than 25 hours relative to `now`. */
  def removeExpiredLevels(now: ZonedDateTime): Unit

  def getList(world: String): List[ListCache]
  def addToList(name: String, formerNames: List[String], world: String, formerWorlds: List[String],
                guild: String, level: String, vocation: String, lastLogin: String,
                updatedTime: ZonedDateTime): Unit

  /** Drop cached sheets for players no list references any more.
   *
   *  Not an age cut, which is what this used to be. Nothing else reads this
   *  table — it exists to draw the hunted and allied lists — and a sheet does
   *  not go stale sitting still: level and vocation cannot change while a
   *  character is offline, so a row written a year ago for somebody who never
   *  logs in is exactly as right as the day it was written. Deleting it only
   *  meant the list had nothing to show for them.
   *
   *  What does need collecting is a player nobody lists any longer, which is
   *  what this removes. Bounded the same way the old sweep was, without ever
   *  losing a row something still wants.
   */
  def pruneList(keep: Set[String]): Int

  /** Every character this world's poll has seen online inside the retention
   *  window, as lowercased name -> their last sheet.
   *
   *  One query per world for a caller that wants a handful of names and cannot
   *  say which until it has them — see [[com.tibiabot.domain.SheetCache]] for
   *  what this exists to answer. */
  def getSheets(world: String): Map[String, SheetCache]

  /** File what the poll just read, in one batch, last reading of a name winning.
   *
   *  A batch rather than a row at a time because the caller has a whole world's
   *  online population in hand at once, and because the writes it skips — a
   *  character whose sheet has not moved — are decided before it gets here. */
  def recordSheets(rows: List[SheetCache]): Unit

  /** Drop sheets for characters not seen for 25 hours relative to `now`.
   *
   *  The same window the levels cache keeps, and for the same reason: the daily
   *  statistics post reports the save day that just closed, so a character who
   *  fought at the start of it must still be here when the post goes out. */
  def removeExpiredSheets(now: ZonedDateTime): Unit

  /** Read `botId`'s own boosted boss/creature row (creating the table, the
   *  bot_id column and that bot's row if needed).
   *
   *  Keyed by bot identity because several bots can share one bot_cache
   *  database — see JdbcCacheRepository's note on why a single shared row
   *  silently cost one of them its server-save post. */
  def getBoosted(botId: String): List[BoostedCache]
  /** Update `botId`'s own boosted fields; empty-string arguments are left unchanged. */
  def updateBoosted(botId: String, boss: String, creature: String, bossChanged: String, creatureChanged: String): Unit
}
