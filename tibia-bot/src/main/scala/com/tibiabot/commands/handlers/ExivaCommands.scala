package com.tibiabot.commands.handlers

import com.tibiabot.{BotApp, Config}
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/exiva`: toggles whether the exiva list is shown on death posts. */
object ExivaCommands {
  def handle(event: SlashCommandInteractionEvent): Unit = {
    event.getInteraction.getSubcommandName match {
      case "deaths" =>
        val embed = BotApp.worldSettingsService.exivaList(event)
        event.getHook.sendMessageEmbeds(embed).queue()
      case other =>
        val embed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Invalid subcommand '$other' for `/exiva`.").build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }
}
