package com.tibiabot.commands.handlers

import com.tibiabot.presentation.Embeds
import com.tibiabot.{BotApp, Config}
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/raids`: set (or clear) this guild's raids channel. Raids for the
 *  worlds the guild tracks are then posted here, pooled across every linked member
 *  everywhere. Manage-Server gated; already deferred ephemerally by BotListener. */
object RaidChannelCommands {
  def handle(event: SlashCommandInteractionEvent): Unit = {
    val action = Options.of(event).getOrElse("action", "set")
    Option(event.getGuild) match {
      case None =>
        reply(event, s"${Config.noEmoji} `/raids` only works inside a server.")
      case Some(_) if !Config.Observer.storageEnabled =>
        reply(event, s"${Config.noEmoji} Tibia Observer isn't set up on this bot yet.")
      case Some(guild) if action == "clear" =>
        BotApp.observerRaidRepository.clearChannel(guild.getId)
        reply(event, s"${Config.yesEmoji} Raids channel cleared — no more raid posts here.")
      case Some(guild) =>
        BotApp.observerRaidRepository.setChannel(guild.getId, event.getChannel.getId)
        // Seed dedup so it starts with raids going forward, not a dump of everything
        // currently live across the guild's worlds.
        BotApp.observerRaidPoller.seedPosted(guild.getId, BotApp.worldsTrackedBy(guild.getId))
        reply(event, s"${Config.yesEmoji} This is now the raids channel. Raids on the worlds this server " +
          s"tracks will be posted here, pooled from every linked member.")
    }
  }

  private def reply(event: SlashCommandInteractionEvent, message: String): Unit =
    event.getHook.sendMessageEmbeds(Embeds.response(message)).queue()
}
