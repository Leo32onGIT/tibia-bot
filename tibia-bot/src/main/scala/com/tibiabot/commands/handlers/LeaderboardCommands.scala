package com.tibiabot.commands.handlers

import com.tibiabot.BotApp
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/leaderboards`: posts the world leaderboard (defined but not currently registered). */
object LeaderboardCommands {
  def handle(event: SlashCommandInteractionEvent): Unit = {
    val worldOption = Options.of(event).getOrElse("world", "")
    BotApp.leaderboards(event, worldOption, embed => {
      event.getHook.sendMessageEmbeds(embed).queue()
    })
  }
}
