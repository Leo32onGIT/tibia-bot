package com.tibiabot.persistence

import com.tibiabot.domain.{Guilds, Players}

/** Persistence port for the per-guild hunted/allied lists
 *  (hunted_players, allied_players, hunted_guilds, allied_guilds). Keyed by
 *  guildId; the option/table strings are chosen by the caller as today. */
trait HuntedAlliedRepository {
  def getPlayers(guildId: String, table: String): List[Players]
  def getGuilds(guildId: String, table: String): List[Guilds]
  def addHunted(guildId: String, option: String, name: String, reason: String, reasonText: String, addedBy: String): Unit
  def addAllied(guildId: String, option: String, name: String, reason: String, reasonText: String, addedBy: String): Unit
  def removeHunted(guildId: String, option: String, name: String): Unit
  def removeAllied(guildId: String, option: String, name: String): Unit
  /** Rename a hunted/allied player, retrying past a duplicate-key collision. */
  def rename(guildId: String, option: String, oldName: String, newName: String): Unit
}
