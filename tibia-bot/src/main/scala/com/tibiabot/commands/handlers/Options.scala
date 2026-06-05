package com.tibiabot.commands.handlers

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

import scala.jdk.CollectionConverters._

/** Shared parsing of slash-command options into a lower-cased, trimmed name -> value map. */
object Options {
  def of(event: SlashCommandInteractionEvent): Map[String, String] =
    event.getInteraction.getOptions.asScala
      .map(option => option.getName.toLowerCase() -> option.getAsString.trim())
      .toMap
}
