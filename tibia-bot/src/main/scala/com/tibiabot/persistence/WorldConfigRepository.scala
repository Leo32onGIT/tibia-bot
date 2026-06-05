package com.tibiabot.persistence

import com.tibiabot.domain.Worlds

/** Persistence port for the per-guild `worlds` table (per-world tracking config). */
trait WorldConfigRepository {
  /** All configured worlds (migrating missing columns, filtering merged worlds). */
  def listWorlds(guildId: String): List[Worlds]
  /** Upsert a world's initial config (defaults applied as today). */
  def createWorld(guildId: String, world: String, alliesChannel: String, enemiesChannel: String,
                  neutralsChannels: String, levelsChannel: String, deathsChannel: String, category: String,
                  fullblessRole: String, nemesisRole: String, allyPkRole: String, masslogRole: String,
                  fullblessChannel: String, nemesisChannel: String, activityChannel: String): Unit
  /** Retrieve a single world's config as a column->value map. */
  def retrieveWorld(guildId: String, world: String): Map[String, String]
  def removeWorld(guildId: String, world: String): Unit

  /** Update a string column for a world. `column` is chosen by calling code
   *  (not user input); the caller supplies the already-formatted world name. */
  def updateWorldString(guildId: String, world: String, column: String, value: String): Unit
  /** Update an integer column for a world. */
  def updateWorldInt(guildId: String, world: String, column: String, value: Int): Unit
}
