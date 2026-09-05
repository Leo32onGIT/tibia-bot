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

  private def namedPool(size: Int, prefix: String) = {
    val threadCount = new AtomicInteger(0)
    val factory: ThreadFactory = (r: Runnable) => {
      val thread = new Thread(r, s"$prefix-${threadCount.incrementAndGet()}")
      thread.setDaemon(true)
      thread
    }
    Executors.newFixedThreadPool(size, factory)
  }

  // Slash-command handlers run here, off JDA's shared event thread. Channel and
  // role creation make many sequential blocking REST calls, and without this one
  // slow command starves dispatch of every other event — including another user's
  // command, whose deferReply() then misses Discord's 3-second ack window and
  // shows as "interaction failed" though its own code is fine.
  private val commandExecutor = namedPool(8, "slash-command")

  // Buttons and modals get their own pool. A `/setup` holds a thread for as long
  // as it takes to build a category, four channels and five roles one blocking
  // call at a time, so on a shared pool a press arriving mid-setup waited behind
  // all of it. Everything here either acknowledges on the event thread before
  // queueing or — for a press opening a modal, which cannot be acknowledged early
  // — must reach Discord within three seconds from cold.
  private val interactionExecutor = namedPool(8, "interaction")

  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    // A command that answers with a form is the one shape this cannot acknowledge
    // up front: replyModal has to be the interaction's first response, so there is
    // nothing to defer into. It cannot queue for a worker either — the three-second
    // window would then be spent waiting on a pool shared with /setup, with Discord
    // still unanswered. Both are fine because such a handler only builds a form: no
    // database, no REST, nothing that could block JDA's event thread.
    if (SlashRouting.opensModal(event.getName)) {
      if (!BotApp.startUpComplete) event.reply(startingUpText).setEphemeral(true).queue()
      else {
        try slashRouter.route(event.getName, event)
        catch {
          case ex: Throwable =>
            logger.error(s"Unhandled exception opening the form for slash command '${event.getName}'", ex)
            if (!event.isAcknowledged)
              event.reply(s"${Config.noEmoji} Something went wrong running that command.").setEphemeral(true).queue(_ => (), _ => ())
        }
        // Off the event thread, and after the form has gone out: this is a database
        // write, and the command must not wait on it.
        commandExecutor.execute(() => recordCommandActivity(event))
      }
      return
    }
    event.deferReply(true).queue()
    if (BotApp.startUpComplete) {
      commandExecutor.execute(() => {
        try {
          recordCommandActivity(event)
          slashRouter.route(event.getName, event)
        } catch {
          case ex: Throwable =>
            logger.error(s"Unhandled exception running slash command '${event.getName}'", ex)
            val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} Something went wrong running that command.").setColor(presentation.Embeds.BrandColor).build()
            event.getHook.sendMessageEmbeds(embed).queue(_ => (), _ => ())
        }
      })
    } else {
      val embed = new EmbedBuilder().setDescription(startingUpText).setColor(presentation.Embeds.BrandColor).build()
      event.getHook.sendMessageEmbeds(embed).queue()
    }
  }

  private val startingUpText: String =
    s"${Config.noEmoji} The bot is still starting up, try running your command later."

  /** Feeds BotApp's daily inactive-guild prune sweep — any command counts, not
   *  just world-related ones (someone using /galthen or /boosted is genuinely
   *  using the bot). Must never block or break the actual command, so its own
   *  failure is swallowed here rather than left to either caller's catch. */
  private def recordCommandActivity(event: SlashCommandInteractionEvent): Unit =
    try {
      Option(event.getGuild).foreach(g => BotApp.guildActivityRepository.recordCommandRun(g.getId, ZonedDateTime.now()))
    } catch {
      case ex: Throwable => logger.warn(s"Failed to record guild activity for command '${event.getName}'", ex)
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
    // The loot split neither defers nor queues: it parses the text that arrived
    // with the submission and answers from that, with no database or REST work in
    // between, and it has to pick ephemeral-or-not from what the parse said — which
    // is a choice deferring would already have made. See interactions.LootSplit.
    if (interactions.LootSplit.handlesModal(event.getModalId)) {
      try interactions.LootSplit.handleModal(event)
      catch {
        case ex: Throwable => logger.error(s"Unhandled exception on the loot split form", ex)
      }
    }
    // Respawn modals route separately because ModalHandler opens with
    // deferEdit(), which rewrites the message the modal came from — here that is
    // the pinned board post. They also hit the database and JDA, so like the
    // respawn buttons they run off the event thread.
    else if (interactions.RespawnModals.handles(event.getModalId)) {
      // Acknowledged here rather than inside the handler, as with the buttons
      // below: deferring as the handler's first statement still left the
      // acknowledgement waiting for a free worker. Every branch can be deferred,
      // since none opens a further modal — but *how* differs: the log's search
      // rewrites the panel it came from and needs deferEdit, everything else
      // answers with an ephemeral of its own.
      if (interactions.RespawnModals.editsOriginal(event.getModalId)) event.deferEdit().queue()
      else event.deferReply(true).queue()
      // A form submitted from a spawn's post counts as touching it, exactly as
      // the press that opened the form did — see the button branch below.
      if (Config.Respawn.enabled) respawn.RespawnSleep.touched(event.getGuild, event.getChannel)
      interactionExecutor.execute(() => {
        try interactions.RespawnModals.handle(event)
        catch {
          case ex: Throwable => logger.error(s"Unhandled exception on respawn modal '${event.getModalId}'", ex)
        }
      })
    } else if (interactions.NotifyModals.handles(event.getModalId)) {
      // Same treatment as the respawn forms, and for the same two reasons: these
      // write to the database and call JDA before they have anything to say, and
      // ModalHandler's deferEdit() would rewrite the message the form came from
      // — here either a notifications embed the whole server reads or somebody's
      // alert DM. Every branch answers ephemerally, so this can defer a reply
      // unconditionally.
      event.deferReply(true).queue()
      interactionExecutor.execute(() => {
        try interactions.NotifyModals.handle(event)
        catch {
          case ex: Throwable => logger.error(s"Unhandled exception on notification modal '${event.getModalId}'", ex)
        }
      })
    } else {
      interactions.ModalHandler.handle(event)
    }

  override def onButtonInteraction(event: ButtonInteractionEvent): Unit =
    // Loot Split opens a form and nothing else, so it cannot be deferred and has
    // no reason to queue — the press is answered on the event thread.
    if (interactions.LootSplit.handlesButton(event.getComponentId)) {
      try interactions.LootSplit.handleButton(event)
      catch {
        case ex: Throwable => logger.error(s"Unhandled exception opening the loot split form", ex)
      }
    }
    // Respawn buttons create/edit forum threads through blocking JDA calls, so

    // they go to the interaction pool. Running them inline would stall JDA's
    // event thread — the exact starvation the pools above exist to prevent —
    // while a thread is created or un-archived.
    else if (interactions.RespawnButtons.handles(event.getComponentId)) {
      // Acknowledged here, on the event thread, before the press is queued —
      // the same order onSlashCommandInteraction above uses, and for the same
      // reason. Deferring inside the handler instead put the acknowledgement
      // behind however long the press waited for a free worker, so a press
      // could exceed Discord's three-second window purely by arriving while a
      // /setup was running and show as "Violent Bot did not respond".
      // A press that opens a modal cannot be deferred at all (replyModal has
      // to be the first response), so those are left for the handler to answer
      // directly. A log page rewrites the message it was pressed on, so it
      // defers an edit rather than a reply — see RespawnButtonId.ackFor.
      respawn.RespawnButtonId.ackFor(event.getComponentId) match {
        case respawn.RespawnButtonId.Ack.OpensModal   => ()
        case respawn.RespawnButtonId.Ack.EditsMessage => event.deferEdit().queue()
        case respawn.RespawnButtonId.Ack.Replies      => event.deferReply(true).queue()
      }
      // Pressing anything inside an archived post re-opens it, and most of what
      // a free spawn's card offers changes no claim state — so nothing further
      // down would ever ask for the post to be closed again. Noted here, above
      // the handlers, precisely because that is where the presses that do
      // nothing else still pass through. Map write only; see RespawnSleep.
      if (Config.Respawn.enabled) respawn.RespawnSleep.touched(event.getGuild, event.getChannel)
      interactionExecutor.execute(() => {
        try interactions.RespawnButtons.handle(event)
        catch {
          case ex: Throwable => logger.error(s"Unhandled exception on respawn button '${event.getComponentId}'", ex)
        }
      })
    } else if (interactions.NotifyButtons.handles(event.getComponentId)) {
      // The notification autoroles and the controls under the DMs they send.
      // Acknowledged here for the same reason as the respawn buttons above,
      // with the same exception: a press that opens a form cannot be deferred,
      // since replyModal has to be the interaction's first response. Everything
      // else rewrites the row it was pressed on, so it defers an edit.
      if (!interactions.NotifyButtons.opensModal(event.getComponentId)) event.deferEdit().queue()
      interactionExecutor.execute(() => {
        try interactions.NotifyButtons.handle(event)
        catch {
          case ex: Throwable => logger.error(s"Unhandled exception on notification button '${event.getComponentId}'", ex)
        }
      })
    } else {
      interactions.ButtonHandler.handle(event, pendingScreenshots, BotApp.streamState)
    }

  override def onMessageReceived(event: MessageReceivedEvent): Unit = interactions.ScreenshotMessageHandler.onMessage(event, pendingScreenshots)
}
