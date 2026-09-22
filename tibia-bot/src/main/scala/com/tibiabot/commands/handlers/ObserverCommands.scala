package com.tibiabot.commands.handlers

import com.tibiabot.{BotApp, Config}
import com.tibiabot.presentation.{Embeds, ObserverEmbeds}
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/observer`: shows the member their Tibia Observer token status with
 *  Add / Remove controls. Already deferred ephemerally by BotListener. */
object ObserverCommands {
  def handle(event: SlashCommandInteractionEvent): Unit =
    Option(event.getGuild) match {
      case None =>
        event.getHook.sendMessageEmbeds(
          Embeds.response(s"${Config.noEmoji} `/observer` only works inside a server.")).queue()
      case Some(_) if !Config.Observer.storageEnabled =>
        event.getHook.sendMessageEmbeds(
          Embeds.response(s"${Config.noEmoji} Tibia Observer isn't set up on this bot yet.")).queue()
      case Some(guild) =>
        val token = BotApp.observerService.statusFor(guild.getId, event.getUser.getId)
        event.getHook
          .sendMessageEmbeds(ObserverEmbeds.panel(token))
          .setComponents(ObserverEmbeds.controls(token))
          .queue()
    }
}
