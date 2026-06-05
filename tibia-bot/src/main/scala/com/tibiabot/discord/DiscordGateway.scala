package com.tibiabot.discord

import net.dv8tion.jda.api.entities.{Guild, User}

/**
 * Read-side seam over the JDA instance: the single place the rest of the bot
 * goes through for guild/user lookups, identity and presence. Mirrors JDA's
 * semantics (`guildById` may return null; `retrieveUser` blocks) so call sites
 * are unchanged, while making the JDA dependency injectable and fakeable.
 */
trait DiscordGateway {
  /** The guild with this id, or null if the bot can't see it (mirrors JDA). */
  def guildById(id: String): Guild
  /** All guilds the bot is currently in. */
  def guilds: List[Guild]
  /** Blocking user retrieval by id (mirrors `retrieveUserById(id).complete()`). */
  def retrieveUser(id: String): User
  /** The bot account's own user id. */
  def selfUserId: String
  /** The bot account's own username. */
  def selfUserName: String
  /** The Discord application owner's user id (the bot creator), or "" if unknown. */
  def applicationOwnerId: String
  /** Set the bot's "Watching <text>" presence. */
  def setWatchingActivity(text: String): Unit
}
