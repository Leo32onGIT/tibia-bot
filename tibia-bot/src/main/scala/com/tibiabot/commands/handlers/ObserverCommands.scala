package com.tibiabot.commands.handlers

import com.tibiabot.presentation.{Embeds, ObserverEmbeds}
import com.tibiabot.{BotApp, Config}
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

import scala.jdk.CollectionConverters._

/** Handles `/observer`: shows the member their Tibia Observer link and the raid-area
 *  coverage of this server's worlds, with Add / Remove controls (see
 *  ObserverEmbeds.panel). Only shows: the raids channels are made when a token is
 *  added, for the worlds it covers — see interactions.ObserverModals. Already
 *  deferred ephemerally by BotListener. */
object ObserverCommands {
  def handle(event: SlashCommandInteractionEvent): Unit =
    Option(event.getGuild) match {
      case None =>
        reply(event, s"${Config.noEmoji} `/observer` only works inside a server.")
      case Some(_) if !Config.Observer.available =>
        reply(event, s"${Config.noEmoji} Tibia Observer isn't set up on this bot yet.")
      case Some(guild) =>
        val view = BotApp.observerService.panel(guild.getId, event.getUser.getId)
        event.getHook.sendMessageComponents(ObserverEmbeds.panel(view).asJava).useComponentsV2().queue()
    }

  private def reply(event: SlashCommandInteractionEvent, message: String): Unit =
    event.getHook.sendMessageEmbeds(Embeds.response(message)).queue()
}
