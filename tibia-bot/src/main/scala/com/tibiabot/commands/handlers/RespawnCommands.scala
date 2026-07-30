package com.tibiabot.commands.handlers

import com.tibiabot.presentation.{Embeds, RespawnEmbeds}
import com.tibiabot.{BotApp, Config}
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/stamina` — the last of the respawn system's slash surface.
 *
 *  Everything else the system does is a button or a modal on the spawns forum:
 *  claiming, queueing, releasing, booking, answering a request and the whole
 *  moderator panel all live on the card for the spawn they act on, where the
 *  state they change is already in front of the person changing it. A command
 *  that names a spawn by code is a worse way to reach the same thing.
 *
 *  Stamina is the exception because it belongs to the member rather than to any
 *  one spawn: there is no card it could sit on, and the answer to "how much have
 *  I got left today" is wanted before deciding which spawn to open.
 */
object RespawnCommands {

  def handle(event: SlashCommandInteractionEvent): Unit = {
    val guild = event.getGuild
    if (guild == null) {
      reply(event, s"${Config.noEmoji} `/stamina` only works inside a server.")
    } else if (!Config.Respawn.enabled) {
      reply(event, s"${Config.noEmoji} The respawn claim system isn't enabled on this bot.")
    } else {
      val service = BotApp.respawnService
      service.settings(guild.getId) match {
        case None => reply(event, notConfiguredText)
        case Some(config) =>
          val tank = service.stamina(guild.getId, event.getUser.getId, config)
          val open = service.openClaimsForUser(guild.getId, event.getUser.getId)
          replyEmbed(event, RespawnEmbeds.staminaEmbed(tank, open, service.nextStaminaReset()))
      }
    }
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
