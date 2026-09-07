package com.tibiabot.commands.handlers

import com.tibiabot.BotApp
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/settings exiva`: toggles whether the exiva list is shown on death posts.
 *
 *  No subcommand to read — this was `/exiva deaths`, whose one subcommand named
 *  the only thing it could ever have done. */
object ExivaCommands {
  def handle(event: SlashCommandInteractionEvent): Unit = {
    val embed = BotApp.worldSettingsService.exivaList(event)
    event.getHook.sendMessageEmbeds(embed).queue()
  }
}
