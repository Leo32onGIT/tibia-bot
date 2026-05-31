package com.tibiabot.setup

import com.tibiabot.Config
import com.tibiabot.app.StreamSupervisor
import com.tibiabot.persistence.SchemaInitializer
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.guild.{GuildJoinEvent, GuildLeaveEvent}

/** Per-guild channel/role setup lifecycle, being extracted from BotApp
 *  incrementally. Currently holds the guild-join/leave handlers; the
 *  channel-create/repair/remove operations will move here as their BotApp
 *  dependencies are untangled.
 *
 *  @param forgetGuild         drops a guild's in-memory state (worldsData/discordsData)
 *  @param sharedConfigGuilds  guilds whose database is shared with another bot, so it must NOT be dropped on leave
 */
final class ChannelService(
  streamSupervisor: StreamSupervisor,
  schemaInitializer: SchemaInitializer,
  forgetGuild: String => Unit,
  sharedConfigGuilds: Set[String]
) extends StrictLogging {

  /** Posts the welcome/help message when the bot joins a new guild. */
  def discordJoin(event: GuildJoinEvent): Unit = {
    val guild = event.getGuild
    val publicChannel = guild.getTextChannelById(guild.getDefaultChannel.getId)
    if (publicChannel != null) {
      if (publicChannel.canTalk() || !Config.prod) {
        val embedBuilder = new EmbedBuilder()
        embedBuilder.setAuthor("Violent Beams", "https://www.tibia.com/community/?subtopic=characters&name=Violent+Beams", "https://github.com/Leo32onGIT.png")
        embedBuilder.setDescription(Config.helpText)
        embedBuilder.setThumbnail(Config.webHookAvatar)
        embedBuilder.setColor(14397256) // orange for bot auto command
        try {
          publicChannel.sendMessageEmbeds(embedBuilder.build()).queue()
        } catch {
          case ex: Throwable => logger.error(s"Failed to send 'New Discord Join' message for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
        }
      }
    }
  }

  /** Cleans up after the bot is removed from a guild: forgets the guild's
   *  in-memory state, cancels its world streams, and drops its database —
   *  unless the guild's config is shared with another bot. */
  def discordLeave(event: GuildLeaveEvent): Unit = {
    val guildId = event.getGuild.getId
    forgetGuild(guildId)
    streamSupervisor.removeGuild(guildId)
    logger.info(guildId)
    if (sharedConfigGuilds.contains(guildId)) {
      logger.info("Config is shared between Pulsera Bot, will use as alpha environment will delete when guild wants it deleted")
    } else {
      schemaInitializer.dropGuild(guildId)
    }
  }
}
