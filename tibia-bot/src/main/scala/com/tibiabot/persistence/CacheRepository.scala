package com.tibiabot.persistence

import com.tibiabot.domain.{BoostedCache, DeathsCache, LevelsCache, ListCache}

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
