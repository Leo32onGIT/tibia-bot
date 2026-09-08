package com.tibiabot.persistence

import com.tibiabot.domain.{Guilds, Players}

/** Persistence port for the per-guild hunted/allied lists
 *  (hunted_players, allied_players, hunted_guilds, allied_guilds). Keyed by
 *  guildId; the option/table strings are chosen by the caller as today. */
trait HuntedAlliedRepository {
  def getPlayers(guildId: String, table: String): List[Players]
  def getGuilds(guildId: String, table: String): List[Guilds]
  /** `tradedWhenAdded` is stored for players only — guilds have no such column,
   *  and cannot be traded. Snapshotted at add time because the flag it comes from
   *  decays; see domain.Players. */
  def addHunted(guildId: String, option: String, name: String, reason: String, reasonText: String,
                addedBy: String, tradedWhenAdded: Boolean = false): Unit
  def addAllied(guildId: String, option: String, name: String, reason: String, reasonText: String,
                addedBy: String, tradedWhenAdded: Boolean = false): Unit

  /** Mark a player entry as flagged for removal, naming why. Leaves an entry that
   *  already carries a reason alone, which is what keeps the notice one-shot. */
  def flagPlayer(guildId: String, table: String, name: String, reason: String): Unit

  /** Clear a flag, leaving the entry on the list — see the implementation. */
  def unflagPlayer(guildId: String, table: String, name: String): Unit

  /** Empty one list table, returning how many rows went. */
  def clearAll(guildId: String, table: String): Int
  def removeHunted(guildId: String, option: String, name: String): Unit
  def removeAllied(guildId: String, option: String, name: String): Unit
  /** Rename a hunted/allied player, retrying past a duplicate-key collision. */
  def rename(guildId: String, option: String, oldName: String, newName: String): Unit
}
