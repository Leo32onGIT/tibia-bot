package com.tibiabot.admin

import com.tibiabot.Config
import com.tibiabot.discord.DiscordGateway
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.{Guild, MessageEmbed}
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal
import com.tibiabot.presentation.Names


/**
 * Bot-creator-only `/admin` operations. The actual dreamScar resync, boosted
 * repost and guild config lookup live in BotApp and are injected here as
 * thunks/functions.
 */
final class AdminService(
  discordGateway: DiscordGateway,
  botUserId: String,
  retrieveConfig: Guild => Map[String, String],
  resyncDreamScar: () => Unit,
  refreshBoosted: () => Future[Int]
)(implicit ec: ExecutionContext) extends StrictLogging {

  /** Post a "bot creator ran a command" notice to a guild's admin/command-log
   *  channel. No-op if the channel is missing, or if the bot lacks permission
   *  to post there (that check is skipped outside prod). */
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
          logger.warn(s"Failed to send admin message for Guild ID: '${adminChannel.getGuild.getId}' Guild Name: '${adminChannel.getGuild.getName}'", ex)
      }
    }

  /** Leave a guild, posting the reason to its admin channel first. */
  def leave(guildId: String, reason: String): MessageEmbed = {
    val guild = discordGateway.guildById(guildId)

    // Reading the guild's config can fail outright, and the notice is not worth
    // staying for. A guild that never ran /setup has no database of its own, so
    // this read throws rather than coming back empty — and it used to throw
    // before the leave below, which meant the bot never left, tried again on
    // the next prune, and warned about it for ever. The message to the owner is
    // a courtesy; leaving is the thing that was asked for.
    val notice = scala.util.Try {
      val discordInfo = retrieveConfig(guild)
      val adminChannel = discordInfo.get("admin_channel").map(guild.getTextChannelById).orNull
      if (adminChannel == null) false
      else {
        postCreatorLog(adminChannel,
          s"${Names.user(discordGateway.selfUserName)} has left your discord because of the following reason:\n> $reason",
          "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Abacus.gif")
        true
      }
    }.recover {
      case NonFatal(e) =>
        logger.info(s"No parting message left in guild '$guildId' — its config could not be read: ${e.getMessage}")
        false
    }.getOrElse(false)

    guild.leave().queue()
    com.tibiabot.presentation.Embeds.response(
      if (notice) s":gear: The bot has left the Guild: **${guild.getName()}** and left a message for the owner."
      else s":gear: The bot has left the Guild: **${guild.getName()}** without leaving a message for the owner.")
  }

  /** Re-fetch the Dream Courts boss-of-the-day per world. */
  def resyncDreamCourtBosses(): MessageEmbed = {
    resyncDreamScar()
    com.tibiabot.presentation.Embeds.response(s":gear: The dreamcourts bosses for each world have been resynced.")
  }

  /** Repost the boosted boss/creature message in every guild that has a
   *  boosted channel, immediately, instead of waiting for the next server
   *  save. Unlike the other subcommands this has to refetch from TibiaData
   *  first, so the reply comes back through a callback the way `info` does. */
  def refreshBoostedMessages(callback: MessageEmbed => Unit): Unit =
    refreshBoosted().onComplete {
      case Success(guilds) =>
        callback(com.tibiabot.presentation.Embeds.response(
          s":gear: The boosted message has been refreshed in **$guilds** discord${if (guilds == 1) "" else "s"}."))
      case Failure(ex) =>
        logger.warn("Failed to refresh the boosted messages", ex)
        callback(com.tibiabot.presentation.Embeds.response(
          s"${Config.noEmoji} The boosted messages failed to refresh."))
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
          s"${Names.user(discordGateway.selfUserName)} has forwarded a message from the bot's creator:\n> $message",
          "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Letter.gif")
        embedMessage = s":gear: The bot has left a message for the Guild: **${guild.getName()}**."
      } else {
        // Previously a later assignment clobbered this, making the "channel deleted"
        // message unreachable and /admin message always reported success.
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
