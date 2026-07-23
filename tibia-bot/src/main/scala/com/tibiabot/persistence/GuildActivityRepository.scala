package com.tibiabot.persistence

import java.time.ZonedDateTime

/** Persistence port for the shared `guild_activity` table (the `bot_cache`
 *  database, not any guild's own database — a guild eligible for pruning may
 *  never have run `/setup`, so its own per-guild database might not exist at
 *  all). Tracks the two signals `BotApp.pruneInactiveGuilds` needs: whether
 *  a command's been run there recently, and how long it's been worldless. */
trait GuildActivityRepository {
  /** Records that some slash command was just run in this guild, regardless
   *  of which one. */
  def recordCommandRun(guildId: String, at: ZonedDateTime): Unit
  /** The last time any command was run in this guild, if ever recorded. */
  def lastCommandAt(guildId: String): Option[ZonedDateTime]
  /** If `worldless_since` isn't already set for this guild, sets it to `now`
   *  and returns `now`; otherwise returns the existing value unchanged. */
  def markWorldlessIfUnset(guildId: String, now: ZonedDateTime): ZonedDateTime
  /** Clears `worldless_since` — called once a guild has a world tracked again. */
  def clearWorldless(guildId: String): Unit
}
