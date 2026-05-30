package com.tibiabot.admin

import com.tibiabot.Config
import com.tibiabot.discord.DiscordGateway
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.{Guild, MessageEmbed}

import scala.collection.mutable.ListBuffer

/**
 * Bot-creator-only `/admin` operations, moved from BotApp. The shared `dreamScar`
 * write stays in BotApp via the injected `resyncDreamScar` thunk; guild config
 * lookup is injected too, so this is JDA-gateway + function deps only.
 */
final class AdminService(
  discordGateway: DiscordGateway,
  botUserId: String,
  retrieveConfig: Guild => Map[String, String],
  resyncDreamScar: () => Unit
) extends StrictLogging {

  /** Leave a guild, posting the reason to its admin channel first. */
  def leave(guildId: String, reason: String): MessageEmbed = {
    val guild = discordGateway.guildById(guildId)
    val discordInfo = retrieveConfig(guild)
    var embedMessage = ""

    if (discordInfo.isEmpty) {
      embedMessage = s":gear: The bot has left the Guild: **${guild.getName()}** without leaving a message for the owner."
    } else {
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (adminChannel != null) {
        if (adminChannel.canTalk() || !(Config.prod)) {
          try {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s"${Config.noEmoji} The creator of the bot has run a command:")
            adminEmbed.setDescription(s"<@$botUserId> has left your discord because of the following reason:\n> ${reason}")
            adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Abacus.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          } catch {
            case ex: Throwable => logger.info(s"Failed to send admin message for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
          }
        }
      }
      embedMessage = s":gear: The bot has left the Guild: **${guild.getName()}** and left a message for the owner."
    }

    guild.leave().queue()
    new EmbedBuilder()
      .setColor(3092790)
      .setDescription(embedMessage)
      .build()
  }

  /** Re-fetch the Dream Courts boss-of-the-day per world. */
  def resyncDreamCourtBosses(): MessageEmbed = {
    resyncDreamScar()
    new EmbedBuilder()
      .setColor(3092790)
      .setDescription(s":gear: The dreamcourts bosses for each world have been resynced.")
      .build()
  }

  /** Forward a message from the bot creator to a guild's admin channel. */
  def message(guildId: String, message: String): MessageEmbed = {
    val guild = discordGateway.guildById(guildId)
    val discordInfo = retrieveConfig(guild)
    var embedMessage = ""

    if (discordInfo.isEmpty) {
      embedMessage = s"${Config.noEmoji} The Guild: **${guild.getName()}** doesn't have any worlds setup yet, so a message cannot be sent."
    } else {
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (adminChannel != null) {
        if (adminChannel.canTalk() || !(Config.prod)) {
          try {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s"${Config.noEmoji} The creator of the bot has run a command:")
            adminEmbed.setDescription(s"<@$botUserId> has forwarded a message from the bot's creator:\n> ${message}")
            adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Letter.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          } catch {
            case ex: Throwable => logger.info(s"Failed to send admin message for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'")
          }
        }
      } else {
        embedMessage = s"${Config.noEmoji} The Guild: **${guild.getName()}** has deleted the `command-log` channel, so a message cannot be sent."
      }
      embedMessage = s":gear: The bot has left a message for the Guild: **${guild.getName()}**."
    }
    new EmbedBuilder()
      .setColor(3092790)
      .setDescription(embedMessage)
      .build()
  }

  /** Paginated list of every guild the bot is in, delivered via callback. */
  def info(callback: List[MessageEmbed] => Unit): Unit = {
    val allGuilds = discordGateway.guilds
    val allGuildsCleaned: List[String] = allGuilds.map(guild => s"**${guild.getName}** - `${guild.getId}`")
    logger.info(allGuildsCleaned.toString)
    val embedBuffer = ListBuffer[MessageEmbed]()
    var field = ""
    allGuildsCleaned.foreach { v =>
      val currentField = field + "\n" + v
      if (currentField.length <= 3000) {
        field = currentField
      } else {
        val interimEmbed = new EmbedBuilder()
        interimEmbed.setDescription(field)
        embedBuffer += interimEmbed.build()
        field = v
      }
    }
    val finalEmbed = new EmbedBuilder()
    finalEmbed.setDescription(field)
    embedBuffer += finalEmbed.build()
    callback(embedBuffer.toList)
  }
}
