package com.tibiabot

import com.tibiabot.BotApp
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
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

  override def onButtonInteraction(event: ButtonInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val embed = event.getInteraction().getMessage().getEmbeds()
    val title = if (!embed.isEmpty) embed.get(0).getTitle() else ""
    val button = event.getComponentId()
    val guild = event.getGuild()
    val roleType = if (title.contains(":crossed_swords:")) "fullbless" else if (title.contains(s"${Config.nemesisEmoji}")) "nemesis" else ""
    val user = event.getUser()
    var responseText = ":x: An issue occured trying to add/remove you from this role, please try again."
    if (roleType == "fullbless"){
      val world = title.replace(":crossed_swords:", "").trim()
      val roles = guild.getRolesByName(s"$world Fullbless", true)
      val role = if (!roles.isEmpty) roles.get(0) else null
      if (role != null){
        if (button == "add"){
          // get role add user to it
          guild.addRoleToMember(user, role).queue()
          responseText = s":gear: You have been added to the <@&${role.getId()}> role."
        } else if (button == "remove"){
          // remove role
          guild.removeRoleFromMember(user, role).queue()
          responseText = s":gear: You have been removed from the <@&${role.getId()}> role."
        }
      } else {
        // role doesn't exist
        responseText = s":x: The role you are trying to add/remove yourself from has been deleted, please contact a discord administrator."
      }
    } else if (roleType == "nemesis") {
      val world = title.replace(s"${Config.nemesisEmoji}", "").trim()
      val roles = guild.getRolesByName(s"$world Nemesis Boss", true)
      val role = if (!roles.isEmpty) roles.get(0) else null
      if (role != null){
        if (button == "add"){
          // get role add user to it
          guild.addRoleToMember(user, role).queue()
          responseText = s":gear: You have been added to the <@&${role.getId()}> role."
        } else if (button == "remove"){
          // remove role
          guild.removeRoleFromMember(user, role).queue()
          responseText = s":gear: You have been removed from the <@&${role.getId()}> role."
        }
      } else {
        // role doesn't exist
        responseText = s":x: The role you are trying to add/remove yourself from has been deleted, please contact a discord administrator."
      }
    }
    val replyEmbed = new EmbedBuilder().setDescription(responseText).build()
    event.getHook().sendMessageEmbeds(replyEmbed).queue()
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
