package com.tibiabot

import com.tibiabot.BotApp.commands
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import com.typesafe.scalalogging.StrictLogging
import scala.jdk.CollectionConverters._
import scala.collection.mutable
import com.tibiabot.domain.PendingScreenshot
import com.tibiabot.commands.CommandRouter
import com.tibiabot.commands.handlers.{AdminCommands, AlliesCommands, BoostedCommands, ChannelCommands, ExivaCommands, FilterCommands, FullblessCommands, GalthenCommands, HelpCommands, HuntedCommands, LeaderboardCommands, NeutralCommands, OnlineListCommands}

class BotListener extends ListenerAdapter with StrictLogging {

  private val pendingScreenshots = mutable.Map[String, PendingScreenshot]()

  // Slash-command dispatch table. Adding a command means adding one entry here.
  private val slashRouter = new CommandRouter[SlashCommandInteractionEvent](Map(
    "setup"        -> (ChannelCommands.setup _),
    "remove"       -> (ChannelCommands.remove _),
    "hunted"       -> (HuntedCommands.handle _),
    "allies"       -> (AlliesCommands.handle _),
    "neutral"      -> (NeutralCommands.handle _),
    "fullbless"    -> (FullblessCommands.handle _),
    "filter"       -> (FilterCommands.handle _),
    "admin"        -> (AdminCommands.handle _),
    "exiva"        -> (ExivaCommands.handle _),
    "help"         -> (HelpCommands.handle _),
    "repair"       -> (ChannelCommands.repair _),
    "galthen"      -> (GalthenCommands.handle _),
    "online"       -> (OnlineListCommands.handle _),
    "boosted"      -> (BoostedCommands.handle _),
    "leaderboards" -> (LeaderboardCommands.handle _)
  ))

  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    if (BotApp.startUpComplete) {
      slashRouter.route(event.getName, event)
    } else {
      val responseText = s"${Config.noEmoji} The bot is still starting up, try running your command later."
      val embed = new EmbedBuilder().setDescription(responseText).setColor(3092790).build()
      event.getHook.sendMessageEmbeds(embed).queue()
    }
  }

  override def onGuildJoin(event: GuildJoinEvent): Unit = {
    val guild = event.getGuild
    //if (Config.verifiedDiscords.contains(guild.getId)) {
      guild.updateCommands().addCommands(commands.asJava).complete()
      BotApp.discordJoin(event)
    //} else {
    //  guild.updateCommands().queue()
    //}
  }

  override def onGuildLeave(event: GuildLeaveEvent): Unit = {
    BotApp.discordLeave(event)
  }

  override def onModalInteraction(event: ModalInteractionEvent): Unit = interactions.ModalHandler.handle(event)

  override def onButtonInteraction(event: ButtonInteractionEvent): Unit = interactions.ButtonHandler.handle(event, pendingScreenshots)

  override def onMessageReceived(event: MessageReceivedEvent): Unit = interactions.ScreenshotMessageHandler.onMessage(event, pendingScreenshots)
}
