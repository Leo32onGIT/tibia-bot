package com.tibiabot.commands.handlers

import com.tibiabot.BotApp
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

import scala.jdk.CollectionConverters._

/** Handles the channel-management commands: `/setup`, `/remove`, `/repair`. */
object ChannelCommands {

  def setup(event: SlashCommandInteractionEvent): Unit = {
    val result = BotApp.channelService.createChannels(event)
    if (result.buttons.nonEmpty) {
      event.getHook.sendMessageEmbeds(result.embed).setComponents(ActionRow.of(result.buttons.asJava)).queue()
    } else {
      event.getHook.sendMessageEmbeds(result.embed).queue()
    }
  }

  def remove(event: SlashCommandInteractionEvent): Unit = {
    val embed = BotApp.channelService.removeChannels(event)
    event.getHook.sendMessageEmbeds(embed).queue()
  }

  def repair(event: SlashCommandInteractionEvent): Unit = {
    val worldOption = Options.of(event).getOrElse("world", "")
    val embed = BotApp.channelService.repairChannel(event, worldOption)
    event.getHook.sendMessageEmbeds(embed).queue()
  }
}
