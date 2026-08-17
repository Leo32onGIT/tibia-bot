package com.tibiabot.discord

import net.dv8tion.jda.api.{JDA, Permission}
import net.dv8tion.jda.api.entities.{Activity, Guild, User}

import scala.jdk.CollectionConverters._
import scala.util.Try
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

  def memberAccess(guildId: String, userId: String, channelIds: List[String]): Option[MemberAccess] = {
    val guild = guildById(guildId)
    if (guild == null) None
    else
      // A member who isn't in the guild makes this throw rather than return
      // null, and that is an ordinary answer here ("no access"), not a fault —
      // so it is caught rather than propagated to the request.
      Try(guild.retrieveMemberById(userId).complete()).toOption.flatMap(Option(_)).map { member =>
        MemberAccess(
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
        )
      }
  }
  def selfUserId: String = jda.getSelfUser.getId
  def selfUserName: String = jda.getSelfUser.getName
  def selfUserAvatarUrl: String = jda.getSelfUser.getEffectiveAvatarUrl
  def applicationOwnerId: String = "313911524475535364"
  def setWatchingActivity(text: String): Unit =
    jda.getPresence().setActivity(Activity.of(Activity.ActivityType.WATCHING, text))
}
