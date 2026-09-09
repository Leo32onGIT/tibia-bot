package com.tibiabot.discord

import net.dv8tion.jda.api.{JDA, Permission}
import net.dv8tion.jda.api.entities.{Activity, Guild, User}

import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.requests.ErrorResponse

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/** JDA-backed [[DiscordGateway]]. */
final class JdaDiscordGateway(jda: JDA) extends DiscordGateway with com.typesafe.scalalogging.StrictLogging {
  /** The guild, or null when there isn't one — including when the id could not
   *  name a guild in the first place.
   *
   *  JDA throws on an id that is not a snowflake rather than answering "no such
   *  guild", which turns one bad row into a 500 for every caller that was
   *  reasonably expecting null. A lookup by id should say "not found" for an id
   *  that cannot exist, so that case is answered here instead of propagated. */
  def guildById(id: String): Guild =
    if (id == null || !id.forall(_.isDigit) || id.isEmpty) null else jda.getGuildById(id)
  def guilds: List[Guild] = jda.getGuilds.asScala.toList
  def retrieveUser(id: String): User = jda.retrieveUserById(id).complete()

  def memberAccess(guildId: String, userId: String, channelIds: List[String]): Option[MemberAccess] =
    memberLookup(guildId, userId, channelIds).toOption

  /** The lookup, with a refusal told apart from a failure to ask.
   *
   *  Both used to come back as `None`. JDA throws `ErrorResponseException` for a
   *  member who is not in the guild — a settled answer — and equally for a rate
   *  limit, gateway error or timeout, which are not answers at all. Reading the
   *  second as the first dropped guilds out of a picker at random; see
   *  [[MemberLookup]].
   *
   *  The one error code meaning "no such member" is the whole distinction.
   *  Anything else is taken as unreachable, deliberately: an unrecognised failure
   *  is far likelier transient than a quiet permanent no, and being wrong that way
   *  costs a retry rather than a guild. */
  override def memberLookup(guildId: String, userId: String,
                            channelIds: List[String]): MemberLookup = {
    val guild = guildById(guildId)
    // Not a statement about the visitor: this process simply is not in that
    // guild, and somebody else's bot may well be able to answer. Saying "no"
    // here would settle a question this bot cannot even ask.
    if (guild == null) MemberLookup.Unreachable(s"this bot is not in guild '$guildId'")
    else Try(guild.retrieveMemberById(userId).complete()) match {
      case Success(null)   => MemberLookup.Denied
      case Success(member) => MemberLookup.Allowed(MemberAccess(
        hasManageServer = member.hasPermission(Permission.MANAGE_SERVER),
        roleIds = member.getRoles.asScala.map(_.getId).toSet,
        visibleChannelIds = channelIds.filter { channelId =>
          try Option(guild.getGuildChannelById(channelId))
            .exists(channel => member.hasPermission(channel, Permission.VIEW_CHANNEL))
          // A channel that has since been deleted, or one JDA cannot resolve,
          // simply is not visible — it must not fail the whole lookup and
          // lock somebody out of a guild they can otherwise use.
          catch { case NonFatal(_) => false }
        }.toSet
      ))
      case Failure(e: ErrorResponseException) if e.getErrorResponse == ErrorResponse.UNKNOWN_MEMBER =>
        MemberLookup.Denied
      case Failure(e) =>
        // Said out loud, unlike before. This is the path that makes a server
        // disappear from somebody's dashboard, and it used to leave no trace at
        // all to explain why.
        logger.warn(s"Could not resolve member '$userId' in guild '$guildId': ${e.getMessage}")
        MemberLookup.Unreachable(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
    }
  }
  def selfUserId: String = jda.getSelfUser.getId
  def selfUserName: String = jda.getSelfUser.getName
  def selfUserAvatarUrl: String = jda.getSelfUser.getEffectiveAvatarUrl
  def applicationOwnerId: String = "313911524475535364"
  def setWatchingActivity(text: String): Unit =
    jda.getPresence().setActivity(Activity.of(Activity.ActivityType.WATCHING, text))
}
