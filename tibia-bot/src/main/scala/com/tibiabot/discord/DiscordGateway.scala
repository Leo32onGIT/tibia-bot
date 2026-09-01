package com.tibiabot.discord

import net.dv8tion.jda.api.entities.{Guild, User}

/** What one member may do in one guild, resolved in a single lookup. Plain data
 *  rather than a JDA `Member`, so the access decision is testable without a
 *  Discord connection and JDA types stay behind the gateway — otherwise a fake
 *  would implement a very large interface to say "this person holds one role". */
final case class MemberAccess(
  hasManageServer: Boolean,
  roleIds: Set[String],
  /** Of the channels asked about, the ones this member can actually see. */
  visibleChannelIds: Set[String]
)

/** What a membership lookup produced, with a refusal told apart from a failure to
 *  ask. An `Option` could not say which: "not a member here" should quietly remove
 *  a guild from the picker, where "Discord would not tell us" is a fault to
 *  report. Reading the second as the first let a rate-limited lookup silently
 *  shrink a visitor's server list — and a list shrunk to one sent them into a
 *  board they never picked. */
sealed trait MemberLookup {
  /** The access, where there is any. `None` for both a refusal and a failure —
   *  for the callers that genuinely cannot act on the difference. */
  def toOption: Option[MemberAccess] = this match {
    case MemberLookup.Allowed(access) => Some(access)
    case _                            => None
  }
}

object MemberLookup {
  /** Discord answered, and this is who they are. */
  final case class Allowed(access: MemberAccess) extends MemberLookup

  /** Discord answered, and they are not a member of that guild. A settled
   *  answer: nothing is going to change by asking again. */
  case object Denied extends MemberLookup

  /** Nobody answered. Rate limited, timed out, refused, or the bot cannot see
   *  the guild at all — all of which are worth retrying and none of which say
   *  anything about the visitor. */
  final case class Unreachable(reason: String) extends MemberLookup
}

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

  /** The same lookup, saying whether a `None` was a refusal or a failure.
   *
   *  Defaulted rather than abstract so that the many gateways which cannot tell
   *  the two apart — the fakes, and anything standing in for JDA — need say
   *  nothing. The default reads every absence as a settled refusal, which is
   *  exactly what the whole of this interface meant before the distinction
   *  existed; only [[JdaDiscordGateway]] is in a position to do better, and it
   *  overrides this. */
  def memberLookup(guildId: String, userId: String, channelIds: List[String]): MemberLookup =
    memberAccess(guildId, userId, channelIds)
      .fold[MemberLookup](MemberLookup.Denied)(MemberLookup.Allowed)

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
