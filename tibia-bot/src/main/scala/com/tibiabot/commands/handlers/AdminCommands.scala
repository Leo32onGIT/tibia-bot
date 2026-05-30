package com.tibiabot.commands.handlers

import com.tibiabot.{BotApp, Config, WorldManager}
import com.tibiabot.commands.Permissions
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

import scala.jdk.CollectionConverters._

/** Handles `/admin`: bot-creator-only maintenance subcommands. */
object AdminCommands {
  def handle(event: SlashCommandInteractionEvent): Unit = {
    val options = Options.of(event)
    val guildOption = options.getOrElse("guildid", "")
    val reasonOption = options.getOrElse("reason", "")
    val messageOption = options.getOrElse("message", "")

    // Only the bot creator (the Discord application owner) may use /admin
    if (!Permissions.isBotCreator(event.getUser.getId, BotApp.botOwner)) {
      val embed = new EmbedBuilder()
        .setDescription(s"${Config.noEmoji} This command is only available to the bot creator.")
        .build()
      event.getHook.sendMessageEmbeds(embed).queue()
      return
    }

    event.getInteraction.getSubcommandName match {
      case "leave" =>
        val embed = BotApp.adminService.leave(guildOption, reasonOption)
        event.getHook.sendMessageEmbeds(embed).queue()
      case "dreamscar" =>
        val embed = BotApp.adminService.resyncDreamCourtBosses()
        event.getHook.sendMessageEmbeds(embed).queue()
      case "message" =>
        val embed = BotApp.adminService.message(guildOption, messageOption)
        event.getHook.sendMessageEmbeds(embed).queue()
      case "worldlist" =>
        try {
          WorldManager.getWorldList()
          val embed = new EmbedBuilder()
            .setDescription(s"${Config.yesEmoji} The worlds list has been refreshed.")
            .build()
          event.getHook.sendMessageEmbeds(embed).queue()
        } catch {
          case _: Exception =>
            val embed = new EmbedBuilder()
              .setDescription(s"${Config.noEmoji} The worlds list has failed to refresh.")
              .build()
            event.getHook.sendMessageEmbeds(embed).queue()
        }
      case "info" =>
        BotApp.adminService.info(embeds => {
          embeds.asJava.forEach { embed =>
            event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue()
          }
        })
      case other =>
        val embed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Invalid subcommand '$other' for `/admin`.").build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }
}
