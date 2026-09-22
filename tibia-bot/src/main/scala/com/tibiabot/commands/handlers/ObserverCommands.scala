package com.tibiabot.commands.handlers

import com.tibiabot.domain.ObserverStatus
import com.tibiabot.presentation.{Embeds, ObserverEmbeds}
import com.tibiabot.{BotApp, Config}
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/observer <world>`: shows the member their Tibia Observer token status
 *  with Add / Remove controls, in the context of a world. When a linked member runs
 *  it for a tracked world, that world's raids channel is ensured. Already deferred
 *  ephemerally by BotListener. */
object ObserverCommands {
  def handle(event: SlashCommandInteractionEvent): Unit = {
    val requested = Options.of(event).getOrElse("world", "").trim
    Option(event.getGuild) match {
      case None =>
        reply(event, s"${Config.noEmoji} `/observer` only works inside a server.")
      case Some(_) if !Config.Observer.storageEnabled =>
        reply(event, s"${Config.noEmoji} Tibia Observer isn't set up on this bot yet.")
      case Some(guild) =>
        BotApp.worldsTrackedBy(guild.getId).find(_.equalsIgnoreCase(requested)) match {
          case None =>
            reply(event, s"${Config.noEmoji} This server isn't tracking **$requested** — an admin can `/setup $requested` first.")
          case Some(world) =>
            val token = BotApp.observerService.statusFor(guild.getId, event.getUser.getId)
            // Already linked: make sure this world's raids channel exists (seeding its
            // dedup on first creation so it starts with raids going forward).
            if (token.exists(_.status == ObserverStatus.Linked) && BotApp.channelService.ensureRaidsChannel(guild, world))
              BotApp.observerRaidPoller.seedPosted(guild.getId, world)
            event.getHook
              .sendMessageEmbeds(ObserverEmbeds.panel(token, world))
              .setComponents(ObserverEmbeds.controls(token, world))
              .queue()
        }
    }
  }

  private def reply(event: SlashCommandInteractionEvent, message: String): Unit =
    event.getHook.sendMessageEmbeds(Embeds.response(message)).queue()
}
