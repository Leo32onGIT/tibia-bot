package com.tibiabot.persistence

import com.tibiabot.domain.PlayerCache

import java.time.ZonedDateTime

/** Persistence port for the per-guild `tracked_activity` table. Keyed by
 *  guildId; callers pass `guild.getId` so JDA stays in BotApp. */
trait ActivityRepository {
  /** All tracked-activity rows for a guild (creating the table on first use). */
  def getActivity(guildId: String): List[PlayerCache]
  /** Upsert a tracked player (ON CONFLICT(name) DO UPDATE). */
  def add(guildId: String, name: String, formerNames: List[String], guildName: String, updatedTime: ZonedDateTime): Unit
  /** Rename / update a tracked player, retrying past a duplicate-key collision. */
  def update(guildId: String, name: String, formerNames: List[String], guildName: String, updatedTime: ZonedDateTime, newName: String): Unit
  def removeByName(guildId: String, name: String): Unit
  def removeByGuild(guildId: String, guildName: String): Unit
}
