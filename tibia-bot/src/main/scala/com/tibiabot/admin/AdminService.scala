package com.tibiabot.admin

import com.tibiabot.Config
import com.tibiabot.discord.DiscordGateway
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.{Guild, MessageEmbed}
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel


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

  /** Post a "bot creator ran a command" notice to a guild's admin/command-log
   *  channel. No-op if the channel is missing or the bot can't talk there. */
  private def postCreatorLog(adminChannel: TextChannel, description: String, thumbnail: String): Unit =
    if (adminChannel != null && (adminChannel.canTalk() || !Config.prod)) {
      try {
        val adminEmbed = new EmbedBuilder()
          .setTitle(s"${Config.noEmoji} The creator of the bot has run a command:")
          .setDescription(description)
          .setThumbnail(thumbnail)
          .setColor(com.tibiabot.presentation.Embeds.BrandColor)
        adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
      } catch {
        case ex: Throwable =>
          logger.info(s"Failed to send admin message for Guild ID: '${adminChannel.getGuild.getId}' Guild Name: '${adminChannel.getGuild.getName}'", ex)
      }
    }

  /** Leave a guild, posting the reason to its admin channel first. */
  def leave(guildId: String, reason: String): MessageEmbed = {
    val guild = discordGateway.guildById(guildId)
    val discordInfo = retrieveConfig(guild)
    var embedMessage = ""

    if (discordInfo.isEmpty) {
      embedMessage = s":gear: The bot has left the Guild: **${guild.getName()}** without leaving a message for the owner."
    } else {
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      postCreatorLog(adminChannel,
        s"<@$botUserId> has left your discord because of the following reason:\n> $reason",
        "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Abacus.gif")
      embedMessage = s":gear: The bot has left the Guild: **${guild.getName()}** and left a message for the owner."
    }

    guild.leave().queue()
    com.tibiabot.presentation.Embeds.response(embedMessage)
  }

  /** Re-fetch the Dream Courts boss-of-the-day per world. */
  def resyncDreamCourtBosses(): MessageEmbed = {
    resyncDreamScar()
    com.tibiabot.presentation.Embeds.response(s":gear: The dreamcourts bosses for each world have been resynced.")
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
        postCreatorLog(adminChannel,
          s"<@$botUserId> has forwarded a message from the bot's creator:\n> $message",
          "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Letter.gif")
        embedMessage = s":gear: The bot has left a message for the Guild: **${guild.getName()}**."
      } else {
        // Previously a trailing assignment overwrote this, so the "channel deleted"
        // feedback was unreachable and /admin message always reported success.
        embedMessage = s"${Config.noEmoji} The Guild: **${guild.getName()}** has deleted the `command-log` channel, so a message cannot be sent."
      }
    }
    com.tibiabot.presentation.Embeds.response(embedMessage)
  }

  /** Paginated list of every guild the bot is in, delivered via callback. */
  def info(callback: List[MessageEmbed] => Unit): Unit = {
    val allGuilds = discordGateway.guilds
    val allGuildsCleaned: List[String] = allGuilds.map(guild => s"**${guild.getName}** - `${guild.getId}`")
    logger.info(allGuildsCleaned.toString)
    val embeds = com.tibiabot.presentation.ListEmbeds.pack(allGuildsCleaned, 3000).map { description =>
      new EmbedBuilder().setDescription(description).build()
    }
    callback(embeds)
  }
}
