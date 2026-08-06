package com.tibiabot.commands.handlers

import com.tibiabot.presentation.{Embeds, RespawnEmbeds}
import com.tibiabot.commands.Permissions
import com.tibiabot.respawn.RespawnThreads
import com.tibiabot.{BotApp, Config}
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/stamina` and `/bookings` — the respawn system's whole slash surface.
 *
 *  Everything else it does is a button or a modal on the spawns forum: claiming,
 *  queueing, releasing, booking, answering a request and the whole moderator
 *  panel all live on the card for the spawn they act on, where the state they
 *  change is already in front of the person changing it. A command that names a
 *  spawn by code is a worse way to reach the same thing.
 *
 *  These two are the exceptions, and for the same reason: they belong to the
 *  member rather than to any one spawn. There is no card that could hold "how
 *  much have I got left today" or "what have I booked, everywhere" — the Book
 *  button on a spawn only ever knows about that spawn.
 */
object RespawnCommands {

  /** `/stamina` — your own tank, and for a moderator a button to hand somebody
   *  else some.
   *
   *  Same shape as `/bookings`: one command, no options, and the difference for a
   *  moderator is a button rather than arguments to remember. Everybody sees
   *  their own tank either way, which is what the command is for. */
  def handle(event: SlashCommandInteractionEvent): Unit =
    withSetUpGuild(event, "/stamina") { guild =>
      val service = BotApp.respawnService
      service.settings(guild.getId).foreach { config =>
        val tank = service.stamina(guild.getId, event.getUser.getId, config)
        val open = service.openClaimsForUser(guild.getId, event.getUser.getId)
        val embed = RespawnEmbeds.staminaEmbed(tank, open, service.nextStaminaReset())
        // Nothing to hand out when stamina is switched off — every claim is
        // allowed anyway and the numbers are ignored, so the button would only
        // lead to a refusal.
        val moderator = config.staminaMinutes > 0 &&
          Permissions.callerIsModerator(event, BotApp.moderatorRoleId(guild.getId))
        if (moderator) event.getHook.sendMessageEmbeds(embed)
          .setComponents(RespawnThreads.staminaButtons).queue()
        else replyEmbed(event, embed)
      }
    }

  /** Booked slots: a member's own, or — for a moderator — everyone's.
   *
   *  A moderator running this is almost always asking "what is booked on this
   *  server", which nothing else answers: the Book panel shows one spawn, and the
   *  board image shows the catalogue rather than who is on it. Their own list is
   *  one button away rather than a second command to remember.
   *
   *  A member's list carries Cancel all rather than a button per booking: it can
   *  run to five across as many spawns, and clearing them one at a time would be
   *  five presses and five card rewrites. Dropping a single booking still lives
   *  on that spawn's Book panel, where the spawn is named. */
  def bookings(event: SlashCommandInteractionEvent): Unit =
    withSetUpGuild(event, "/bookings") { guild =>
      val moderator = Permissions.callerIsModerator(event, BotApp.moderatorRoleId(guild.getId))
      if (moderator) replyBookings(event, guild, owner = None)
      else replyBookings(event, guild, owner = Some(event.getUser.getId))
    }

  /** One rendering for both lists, so the moderator's My bookings button and the
   *  member's command cannot drift apart. `owner` empty means the whole server. */
  private[commands] def replyBookings(event: SlashCommandInteractionEvent,
                                      guild: net.dv8tion.jda.api.entities.Guild,
                                      owner: Option[String]): Unit = {
    val entries = BotApp.respawnService.scheduleListing(guild.getId, owner)
    val embed = RespawnEmbeds.schedulesEmbed(entries, java.time.ZonedDateTime.now(),
      everyones = owner.isEmpty)
    val buttons =
      if (owner.isEmpty) Some(RespawnThreads.moderatorBookingsButtons)
      else if (entries.nonEmpty) Some(RespawnThreads.bookingsButtons(entries.size))
      else None
    buttons match {
      case Some(row) => event.getHook.sendMessageEmbeds(embed).setComponents(row).queue()
      case None      => replyEmbed(event, embed)
    }
  }

  /** The guard both commands share: in a server, feature switched on, and set up
   *  here. Named so the reply can say which command was refused. */
  private def withSetUpGuild(event: SlashCommandInteractionEvent, command: String)
                            (body: net.dv8tion.jda.api.entities.Guild => Unit): Unit = {
    val guild = event.getGuild
    if (guild == null) reply(event, s"${Config.noEmoji} `$command` only works inside a server.")
    else if (!Config.Respawn.enabled)
      reply(event, s"${Config.noEmoji} The respawn claim system isn't enabled on this bot.")
    else if (BotApp.respawnService.settings(guild.getId).isEmpty) reply(event, notConfiguredText)
    else body(guild)
  }

  /** Points at the forum rather than a command, since setting the system up is
   *  no longer something anybody types: `/setup` creates it, and `/repair`
   *  puts it back if the channel is deleted. */
  private val notConfiguredText: String =
    s"${Config.noEmoji} The respawn claim system isn't set up on this server yet.\n" +
      "Someone with **Manage Server** can run `/setup` to create it."

  private def reply(event: SlashCommandInteractionEvent, text: String): Unit =
    replyEmbed(event, Embeds.response(text))

  private def replyEmbed(event: SlashCommandInteractionEvent, embed: MessageEmbed): Unit =
    event.getHook.sendMessageEmbeds(embed).queue()
}
