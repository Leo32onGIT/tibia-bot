package com.tibiabot.commands.handlers

import com.tibiabot.{BotApp, Config}
import com.tibiabot.presentation.Embeds.BrandColor
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/settings neutral`: per-world death, level and activity toggles for
 *  players in neither the allies nor the enemies list. */
object NeutralCommands {

  def handle(event: SlashCommandInteractionEvent): Unit = {
    val subCommand = event.getInteraction.getSubcommandName
    val options = Options.of(event)
    val toggleOption: String = options.getOrElse("option", "")
    val worldOption: String = options.getOrElse("world", "")

    subCommand match {
      case channel @ ("deaths" | "levels" | "activity") =>
        if (toggleOption == "show" || toggleOption == "hide") {
          val embed = BotApp.worldSettingsService.deathsLevelsHideShow(event, worldOption, toggleOption, "neutrals", channel)
          event.getHook.sendMessageEmbeds(embed).queue()
        }
      case other =>
        val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} Invalid subcommand '$other' for `/settings neutral`.").setColor(BrandColor).build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }
}
