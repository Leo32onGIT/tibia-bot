package com.tibiabot.commands.handlers

import com.tibiabot.presentation.{Embeds, ObserverEmbeds}
import com.tibiabot.{BotApp, Config}
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/observer`: shows the member their Tibia Observer token status with Add /
 *  Remove controls. Add opens a form asking for the world and the token together.
 *  Already deferred ephemerally by BotListener. */
object ObserverCommands {
  def handle(event: SlashCommandInteractionEvent): Unit =
    Option(event.getGuild) match {
      case None =>
        reply(event, s"${Config.noEmoji} `/observer` only works inside a server.")
      case Some(_) if !Config.Observer.storageEnabled =>
        reply(event, s"${Config.noEmoji} Tibia Observer isn't set up on this bot yet.")
      case Some(guild) =>
        val token = BotApp.observerService.statusFor(guild.getId, event.getUser.getId)
        event.getHook
          .sendMessageEmbeds(ObserverEmbeds.panel(token))
          .setComponents(ObserverEmbeds.controls(token))
          .queue()
    }

  private def reply(event: SlashCommandInteractionEvent, message: String): Unit =
    event.getHook.sendMessageEmbeds(Embeds.response(message)).queue()
}
