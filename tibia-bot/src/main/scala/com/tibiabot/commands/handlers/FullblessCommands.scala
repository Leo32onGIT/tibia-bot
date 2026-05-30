package com.tibiabot.commands.handlers

import com.tibiabot.BotApp
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/fullbless`: shows the fullbless minimum-level config for a world. */
object FullblessCommands {

  val DefaultLevel = 250

  /** The level option, defaulting to [[DefaultLevel]] when absent. */
  def parseLevel(options: Map[String, String]): Int =
    options.get("level").map(_.toInt).getOrElse(DefaultLevel)

  def handle(event: SlashCommandInteractionEvent): Unit = {
    val options = Options.of(event)
    val worldOption = options.getOrElse("world", "")
    val embed = BotApp.fullblessLevel(event, worldOption, parseLevel(options))
    event.getHook.sendMessageEmbeds(embed).queue()
  }
}
