package com.tibiabot.commands.handlers

import com.tibiabot.domain.ObserverStatus
import com.tibiabot.presentation.{Embeds, ObserverEmbeds}
import com.tibiabot.{BotApp, Config}
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/observer`: shows the member their Tibia Observer token status with Add /
 *  Remove controls. Add opens a token-only form; the raids channels follow the worlds
 *  the server has set up. Already deferred ephemerally by BotListener. */
object ObserverCommands {
  def handle(event: SlashCommandInteractionEvent): Unit =
    Option(event.getGuild) match {
      case None =>
        reply(event, s"${Config.noEmoji} `/observer` only works inside a server.")
      case Some(_) if !Config.Observer.storageEnabled =>
        reply(event, s"${Config.noEmoji} Tibia Observer isn't set up on this bot yet.")
      case Some(guild) =>
        val token = BotApp.observerService.statusFor(guild.getId, event.getUser.getId)
        // A linked member running this catches up any worlds set up since they linked.
        if (token.exists(_.status == ObserverStatus.Linked))
          BotApp.worldsTrackedBy(guild.getId).foreach { world =>
            if (BotApp.channelService.ensureRaidsChannel(guild, world))
              BotApp.observerRaidPoller.seedPosted(guild.getId, world)
          }
        event.getHook
          .sendMessageEmbeds(ObserverEmbeds.panel(token))
          .setComponents(ObserverEmbeds.controls(token))
          .queue()
    }

  private def reply(event: SlashCommandInteractionEvent, message: String): Unit =
    event.getHook.sendMessageEmbeds(Embeds.response(message)).queue()
}
