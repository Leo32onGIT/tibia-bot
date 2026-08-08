package com.tibiabot.discord

import net.dv8tion.jda.api.{JDA, Permission}
import net.dv8tion.jda.api.entities.{Activity, Guild, User}

import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.control.NonFatal

/** JDA-backed [[DiscordGateway]]. */
final class JdaDiscordGateway(jda: JDA) extends DiscordGateway with com.typesafe.scalalogging.StrictLogging {
  def guildById(id: String): Guild = jda.getGuildById(id)
  def guilds: List[Guild] = jda.getGuilds.asScala.toList
  def retrieveUser(id: String): User = jda.retrieveUserById(id).complete()

  def memberAccess(guildId: String, userId: String, channelIds: List[String]): Option[MemberAccess] = {
    val guild = jda.getGuildById(guildId)
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
