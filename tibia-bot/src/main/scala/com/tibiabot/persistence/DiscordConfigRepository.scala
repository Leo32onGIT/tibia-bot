package com.tibiabot.persistence

import java.time.ZonedDateTime

/** Persistence port for the per-guild `discord_info` table (admin channel/category,
 *  boosted channel/message, last world). Keyed by guildId. */
trait DiscordConfigRepository {
  /** Read the guild's discord config as a column->value map (migrating columns). */
  def getConfig(guildId: String): Map[String, String]
  /** Upsert the guild's discord config. */
  def create(guildId: String, guildName: String, guildOwner: String, adminCategory: String,
             adminChannel: String, boostedChannel: String, boostedMessageId: String, created: ZonedDateTime): Unit
  /** Conditionally update individual fields (empty-string args are left unchanged). */
  def update(guildId: String, adminCategory: String, adminChannel: String, boostedChannel: String,
             boostedMessage: String, lastWorld: String): Unit
}
