package com.tibiabot

import com.tibiabot.commands.CommandSchemas
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import com.typesafe.scalalogging.StrictLogging
import scala.jdk.CollectionConverters._
import com.tibiabot.domain.PendingScreenshot
import com.tibiabot.commands.{CommandRouter, SlashRouting}

import java.time.ZonedDateTime
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
          // Feeds BotApp's daily inactive-guild prune sweep — any command
          // counts, not just world-related ones (someone using /galthen or
          // /boosted is genuinely using the bot). Must never block or break
          // the actual command, so its own failure is swallowed here rather
          // than left to the outer catch below.
          try {
            Option(event.getGuild).foreach(g => BotApp.guildActivityRepository.recordCommandRun(g.getId, ZonedDateTime.now()))
          } catch {
            case ex: Throwable => logger.warn(s"Failed to record guild activity for command '${event.getName}'", ex)
          }
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
    val excludeAll = CommandSchemas.excludedFromCommands(guild.getIdLong, guild.getJDA.getSelfUser.getId)
    guild.updateCommands().addCommands(CommandSchemas.commandsFor(guild.getIdLong, hasWorldConfigured = false, excludeAll, Config.Respawn.enabled).asJava).queue()
    BotApp.channelService.discordJoin(event)
  }

  override def onGuildLeave(event: GuildLeaveEvent): Unit = {
    BotApp.channelService.discordLeave(event)
  }

  override def onModalInteraction(event: ModalInteractionEvent): Unit =
    // Respawn modals route separately because ModalHandler opens with
    // deferEdit(), which rewrites the message the modal came from — here that is
    // the pinned board post. They also hit the database and JDA, so like the
    // respawn buttons they run on the command pool rather than the event thread.
    if (interactions.RespawnModals.handles(event.getModalId)) {
      commandExecutor.execute(() => {
        try interactions.RespawnModals.handle(event)
        catch {
          case ex: Throwable => logger.error(s"Unhandled exception on respawn modal '${event.getModalId}'", ex)
        }
      })
    } else {
      interactions.ModalHandler.handle(event)
    }

  override def onButtonInteraction(event: ButtonInteractionEvent): Unit =
    // Respawn buttons create/edit forum threads through blocking JDA calls, so
    // they go to the same pool as slash commands. Running them inline would
    // stall JDA's event thread — the exact starvation the pool above exists to
    // prevent — while a thread is created or un-archived.
    if (interactions.RespawnButtons.handles(event.getComponentId)) {
      commandExecutor.execute(() => {
        try interactions.RespawnButtons.handle(event)
        catch {
          case ex: Throwable => logger.error(s"Unhandled exception on respawn button '${event.getComponentId}'", ex)
        }
      })
    } else {
      interactions.ButtonHandler.handle(event, pendingScreenshots, BotApp.streamState)
    }

  override def onMessageReceived(event: MessageReceivedEvent): Unit = interactions.ScreenshotMessageHandler.onMessage(event, pendingScreenshots)
}
