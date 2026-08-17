package com.tibiabot.discord

import net.dv8tion.jda.api.entities.{Guild, User}

/** What one member may do in one guild, resolved in a single lookup.
 *
 *  Deliberately plain data rather than a JDA `Member`. Everything the dashboard
 *  needs to decide access is here, so the decision itself can be written and
 *  tested without a Discord connection — and the JDA types stay behind the
 *  gateway instead of leaking into the authorization logic, where a fake would
 *  otherwise have to implement a very large interface to say "this person holds
 *  one role".
 */
final case class MemberAccess(
  hasManageServer: Boolean,
  roleIds: Set[String],
  /** Of the channels asked about, the ones this member can actually see. */
  visibleChannelIds: Set[String]
)

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
  /** What `userId` may do in `guildId`, including which of `channelIds` they can
   *  see. None when the member can't be resolved — not in the guild, the bot
   *  can't see it, or Discord refused.
   *
   *  Blocking, and a REST call: this bot runs without the privileged
   *  GUILD_MEMBERS intent, so there is no member cache to read instead. Callers
   *  are expected to ask about a handful of guilds, not all of them. */
  def memberAccess(guildId: String, userId: String, channelIds: List[String]): Option[MemberAccess]
  /** The bot account's own user id. */
  def selfUserId: String
  /** The bot account's own username. */
  def selfUserName: String
  /** The bot account's own avatar URL (falls back to Discord's default avatar
   *  if none is set — never null/empty). Used to badge which bot serves a
   *  world/guild on a merged shared-world-cycle dashboard. */
  def selfUserAvatarUrl: String
  /** The Discord application owner's user id (the bot creator), or "" if unknown. */
  def applicationOwnerId: String
  /** Set the bot's "Watching <text>" presence. */
  def setWatchingActivity(text: String): Unit
}
