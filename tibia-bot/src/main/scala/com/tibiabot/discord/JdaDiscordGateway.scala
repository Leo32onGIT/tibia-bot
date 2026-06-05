package com.tibiabot.discord

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.{Activity, Guild, User}

import scala.jdk.CollectionConverters._

/** JDA-backed [[DiscordGateway]]. */
final class JdaDiscordGateway(jda: JDA) extends DiscordGateway {
  def guildById(id: String): Guild = jda.getGuildById(id)
  def guilds: List[Guild] = jda.getGuilds.asScala.toList
  def retrieveUser(id: String): User = jda.retrieveUserById(id).complete()
  def selfUserId: String = jda.getSelfUser.getId
  def selfUserName: String = jda.getSelfUser.getName
  def applicationOwnerId: String =
    Option(jda.retrieveApplicationInfo().complete().getOwner).map(_.getId).getOrElse("")
  def setWatchingActivity(text: String): Unit =
    jda.getPresence().setActivity(Activity.of(Activity.ActivityType.WATCHING, text))
}
