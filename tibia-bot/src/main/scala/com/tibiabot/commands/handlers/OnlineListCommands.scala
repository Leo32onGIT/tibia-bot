package com.tibiabot.commands.handlers

import com.tibiabot.{BotApp, Config}
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/online`: configures the per-world online list as separate or combined. */
object OnlineListCommands {
  def handle(event: SlashCommandInteractionEvent): Unit = {
    val options = Options.of(event)
    val toggleOption = options.getOrElse("option", "")

    event.getInteraction.getSubcommandName match {
      case "list" if toggleOption == "separate" || toggleOption == "combine" =>
        val worldOption = options.getOrElse("world", "")
        val embed = BotApp.onlineListConfig(event, worldOption, toggleOption)
        event.getHook.sendMessageEmbeds(embed).queue()
      case "list" =>
        () // unknown toggle: preserve prior no-op behaviour
      case other =>
        val embed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Invalid subcommand '$other' for `/online`.").build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }
}
