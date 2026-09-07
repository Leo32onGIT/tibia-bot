package com.tibiabot.commands.handlers

import com.tibiabot.BotApp
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/settings layout`: shows the per-world online list as separate
 *  channels or combined into one.
 *
 *  No subcommand to read — this was `/online list`, whose one subcommand named
 *  the only thing it could ever have done. */
object OnlineListCommands {
  def handle(event: SlashCommandInteractionEvent): Unit = {
    val options = Options.of(event)
    val toggleOption = options.getOrElse("option", "")

    // Discord only offers the two choices, so anything else means a malformed
    // interaction rather than a user mistake — no-op, as before.
    if (toggleOption == "separate" || toggleOption == "combine") {
      val worldOption = options.getOrElse("world", "")
      val embed = BotApp.worldSettingsService.onlineListConfig(event, worldOption, toggleOption)
      event.getHook.sendMessageEmbeds(embed).queue()
    }
  }
}
