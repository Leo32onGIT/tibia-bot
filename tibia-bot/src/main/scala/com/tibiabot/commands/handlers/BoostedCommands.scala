package com.tibiabot.commands.handlers

import com.tibiabot.{BotApp, Config}
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.components.buttons.Button

/** Handles `/boosted`: manage per-user boosted boss/creature notifications. */
object BoostedCommands {
  def handle(event: SlashCommandInteractionEvent): Unit = {
    val toggleOption = Options.of(event).getOrElse("option", "")
    val userId = event.getUser.getId

    if (toggleOption == "disable") {
      val embed = BotApp.boostedService.boosted(userId, "disable", "")
      event.getHook.sendMessageEmbeds(embed).queue()
    } else if (toggleOption == "list") {
      val embed = BotApp.boostedService.boosted(userId, "list", "")
      if (BotApp.boostedService.boostedList(userId)) {
        event.getHook.sendMessageEmbeds(embed).setActionRow(
          Button.success("boosted add", "Add").asDisabled,
          Button.danger("boosted remove", "Remove").asDisabled,
          Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOnEmoji))
        ).queue()
      } else {
        event.getHook.sendMessageEmbeds(embed).setActionRow(
          Button.success("boosted add", "Add"),
          Button.danger("boosted remove", "Remove")
        ).queue()
      }
    } else {
      val embed = new EmbedBuilder()
        .setDescription(s"${Config.noEmoji} Invalid option for `/boosted`.").setColor(3092790).build()
      event.getHook.sendMessageEmbeds(embed).queue()
    }
  }
}
