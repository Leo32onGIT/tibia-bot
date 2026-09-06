package com.tibiabot.commands.handlers

import com.tibiabot.{BotApp, Config}
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/filter`: sets the minimum level for level/death notifications. */
object FilterCommands {

  val DefaultLevel = 8

  /** The level option, defaulting to [[DefaultLevel]] when absent. */
  def parseLevel(options: Map[String, String]): Int =
    options.get("level").map(_.toInt).getOrElse(DefaultLevel)

  def handle(event: SlashCommandInteractionEvent): Unit = {
    val options = Options.of(event)
    val worldOption = options.getOrElse("world", "")
    val levelOption = parseLevel(options)

    event.getInteraction.getSubcommandName match {
      case channel @ ("levels" | "deaths") =>
        val embed = BotApp.worldSettingsService.minLevel(event, worldOption, levelOption, channel)
        event.getHook.sendMessageEmbeds(embed).queue()
      case "online" =>
        // Read straight rather than through parseLevel: its DefaultLevel of 8 is
        // the floor these lists exist to get rid of, and 0 — turn the filter off
        // — is a real answer here that must not be mistaken for "absent".
        val level = options.get("level").map(_.toInt).getOrElse(0)
        val embed = BotApp.worldSettingsService.onlineMinLevel(
          event, worldOption, level, options.getOrElse("list", "enemies"))
        event.getHook.sendMessageEmbeds(embed).queue()
      case other =>
        val embed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Invalid subcommand '$other' for `/filter`.").build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }
}
