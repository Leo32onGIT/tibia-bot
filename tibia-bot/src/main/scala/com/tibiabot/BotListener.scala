package com.tibiabot

import com.tibiabot.commands.CommandSchemas.commands
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
import com.tibiabot.domain.PendingScreenshot
import com.tibiabot.commands.{CommandRouter, SlashRouting}

import java.util.concurrent.{Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger

class BotListener extends ListenerAdapter with StrictLogging {

  // Mutated from both onButtonInteraction and onMessageReceived, which JDA
  // dispatches on a thread pool — use a thread-safe map (a plain mutable.Map
  // can corrupt structurally under concurrent put/remove). TrieMap is a
  // mutable.Map, so the handler signatures are unchanged.
  private val pendingScreenshots = scala.collection.concurrent.TrieMap[String, PendingScreenshot]()

  // Slash-command dispatch table lives in commands.SlashRouting (one entry per command).
  private val slashRouter = new CommandRouter[SlashCommandInteractionEvent](SlashRouting.handlers)

  // Slash-command handlers run here, off JDA's shared event thread. Some
  // handlers (channel/role creation in particular) make many sequential
  // blocking JDA REST calls; without this, one slow command would starve
  // dispatch of every other event — including a different user's slash
  // command, whose deferReply() would then never fire within Discord's
  // 3-second ack window and show as "interaction failed" even though its
  // own handler code is fine.
  private val commandExecutor = {
    val threadCount = new AtomicInteger(0)
    val factory: ThreadFactory = (r: Runnable) => {
      val thread = new Thread(r, s"slash-command-${threadCount.incrementAndGet()}")
      thread.setDaemon(true)
      thread
    }
    Executors.newFixedThreadPool(8, factory)
  }

  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    if (BotApp.startUpComplete) {
      commandExecutor.execute(() => {
        try {
          slashRouter.route(event.getName, event)
        } catch {
          case ex: Throwable =>
            logger.error(s"Unhandled exception running slash command '${event.getName}'", ex)
            val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} Something went wrong running that command.").setColor(presentation.Embeds.BrandColor).build()
            event.getHook.sendMessageEmbeds(embed).queue(_ => (), _ => ())
        }
      })
    } else {
      val responseText = s"${Config.noEmoji} The bot is still starting up, try running your command later."
      val embed = new EmbedBuilder().setDescription(responseText).setColor(presentation.Embeds.BrandColor).build()
      event.getHook.sendMessageEmbeds(embed).queue()
    }
  }

  override def onGuildJoin(event: GuildJoinEvent): Unit = {
    val guild = event.getGuild
    guild.updateCommands().addCommands(commands.asJava).queue()
    BotApp.channelService.discordJoin(event)
  }

  override def onGuildLeave(event: GuildLeaveEvent): Unit = {
    BotApp.channelService.discordLeave(event)
  }

  override def onModalInteraction(event: ModalInteractionEvent): Unit = interactions.ModalHandler.handle(event)

  override def onButtonInteraction(event: ButtonInteractionEvent): Unit = interactions.ButtonHandler.handle(event, pendingScreenshots, BotApp.streamState)

  override def onMessageReceived(event: MessageReceivedEvent): Unit = interactions.ScreenshotMessageHandler.onMessage(event, pendingScreenshots)
}
