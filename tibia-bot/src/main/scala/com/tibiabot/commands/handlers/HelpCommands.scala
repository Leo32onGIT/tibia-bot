package com.tibiabot.commands.handlers

import com.tibiabot.Config
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/help`: posts the bot's help text. */
object HelpCommands {
  def handle(event: SlashCommandInteractionEvent): Unit = {
    val embed = new EmbedBuilder()
    embed.setAuthor("Violent Beams", "https://www.tibia.com/community/?subtopic=characters&name=Violent+Beams", "https://github.com/Leo32onGIT.png")
    embed.setDescription(Config.helpText)
    embed.setThumbnail(Config.webHookAvatar)
    embed.setColor(14397256) // orange for bot auto command
    event.getHook.sendMessageEmbeds(embed.build()).queue()
  }
}
