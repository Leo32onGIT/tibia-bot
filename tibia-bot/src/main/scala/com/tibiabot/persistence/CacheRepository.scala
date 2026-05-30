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
                guild: String, level: String, vocation: String, lastLogin: String, updatedTime: ZonedDateTime): Unit
  /** Delete list rows older than 7 days relative to `now`. */
  def removeExpiredList(now: ZonedDateTime): Unit

  /** Read the boosted boss/creature row (creating the table + default row if needed). */
  def getBoosted(): List[BoostedCache]
  /** Update boosted fields; empty-string arguments are left unchanged. */
  def updateBoosted(boss: String, creature: String, bossChanged: String, creatureChanged: String): Unit
}
