package com.tibiabot

import com.tibiabot.BotApp
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.EmbedBuilder
import scala.jdk.CollectionConverters._

class BotListener extends ListenerAdapter {

  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    event.getName match {
      //case "reload" =>
      //  handleReload(event)
      case "setup" =>
        handleSetup(event)
      case "remove" =>
        handleRemove(event)
      case "hunted" =>
        handleHunted(event)
      case "allies" =>
        handleAllies(event)
      case _ =>
    }
  }

  private def handleSetup(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val embed = BotApp.createChannels(event)
    event.getHook().sendMessageEmbeds(embed).queue()
  }
  private def handleRemove(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val embed = BotApp.removeChannels(event)
    event.getHook().sendMessageEmbeds(embed).queue()
  }

  private def handleHunted(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val subCommandGroup = event.getInteraction.getSubcommandGroup
    val subCommand = event.getInteraction.getSubcommandName
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val nameOption: String = options.get("name").getOrElse("")
    val reasonOption: String = options.get("reason").getOrElse("none")

    subCommandGroup match {
      case "player" => {
        subCommand match {
          case "add" => {
            val embed = BotApp.addHunted(event, "player", nameOption, reasonOption)
            event.getHook().sendMessageEmbeds(embed).queue()
          }
          case "remove" => {
            val embed = BotApp.removeHunted(event, "player", nameOption, reasonOption)
            event.getHook().sendMessageEmbeds(embed).queue()
          }
          case _ => {
            val embed = new EmbedBuilder().setDescription(s":x: Invalid subcommand '${subCommand}' for player group.").build()
            event.getHook().sendMessageEmbeds(embed).queue()
          }
        }
      }
      case "guild" => {
        subCommand match {
          case "add" => {
            val embed = BotApp.addHunted(event, "guild", nameOption, reasonOption)
            event.getHook().sendMessageEmbeds(embed).queue()
          }
          case "remove" => {
            val embed = BotApp.removeHunted(event, "guild", nameOption, reasonOption)
            event.getHook().sendMessageEmbeds(embed).queue()
          }
          case _ => {
            val embed = new EmbedBuilder().setDescription(s":x: Invalid subcommand '${subCommand}' for guild group.").build()
            event.getHook().sendMessageEmbeds(embed).queue()
          }
        }
      }
      case _ => {
        subCommand match {
          case "list" => {
            BotApp.listAlliesAndHunted(event, "hunted", (hunteds) => {
              val embedsJava = hunteds.asJava
              event.getHook().sendMessageEmbeds(embedsJava).queue()
            })
          }
          case "info" => {
            val embed = BotApp.infoHunted(event, "player", nameOption)
            event.getHook().sendMessageEmbeds(embed).queue()
          }
        }
      }
    }
  }

  private def handleAllies(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val subCommandGroup = event.getInteraction.getSubcommandGroup
    val subCommand = event.getInteraction.getSubcommandName
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val nameOption: String = options.get("name").getOrElse("")
    val reasonOption: String = options.get("reason").getOrElse("none")

    subCommandGroup match {
      case "player" => {
        subCommand match {
          case "add" => {
            val embed = BotApp.addAlly(event, "player", nameOption, reasonOption)
            event.getHook().sendMessageEmbeds(embed).queue()
          }
          case "remove" => {
            val embed = BotApp.removeAlly(event, "player", nameOption, reasonOption)
            event.getHook().sendMessageEmbeds(embed).queue()
          }
          case _ => {
            val embed = new EmbedBuilder().setDescription(s":x: Invalid subcommand '${subCommand}' for player group.").build()
            event.getHook().sendMessageEmbeds(embed).queue()
          }
        }
      }
      case "guild" => {
        subCommand match {
          case "add" => {
            val embed = BotApp.addAlly(event, "guild", nameOption, reasonOption)
            event.getHook().sendMessageEmbeds(embed).queue()
          }
          case "remove" => {
            val embed = BotApp.removeAlly(event, "guild", nameOption, reasonOption)
            event.getHook().sendMessageEmbeds(embed).queue()
          }
          case _ => {
            val embed = new EmbedBuilder().setDescription(s":x: Invalid subcommand '${subCommand}' for guild group.").build()
            event.getHook().sendMessageEmbeds(embed).queue()
          }
        }
      }
      case _ => {
        subCommand match {
          case "list" => {
            BotApp.listAlliesAndHunted(event, "allies", (allies) => {
              val embedsJava = allies.asJava
              event.getHook().sendMessageEmbeds(embedsJava).queue()
            })
          }
          case "info" => {
            val embed = BotApp.infoAllies(event, "player", nameOption)
            event.getHook().sendMessageEmbeds(embed).queue()
          }
        }
      }
    }
  }
}
