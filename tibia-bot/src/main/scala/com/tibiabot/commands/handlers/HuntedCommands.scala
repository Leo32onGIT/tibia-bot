package com.tibiabot.commands.handlers

import com.tibiabot.{BotApp, Config}
import com.tibiabot.commands.Permissions
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

import scala.jdk.CollectionConverters._

/** Handles `/hunted`: manage hunted (enemy) players and guilds. */
object HuntedCommands {
  def handle(event: SlashCommandInteractionEvent): Unit = {
    val subCommand = event.getInteraction.getSubcommandName
    val options = Options.of(event)
    val toggleOption: String = options.getOrElse("option", "")
    val worldOption: String = options.getOrElse("world", "")
    val nameOption: String = options.getOrElse("name", "")
    val reasonOption: String = options.getOrElse("reason", "none")

    var authed = false
    val user = event.getUser // Get the user who ran the command
    val guild = event.getGuild
    val member = guild.retrieveMember(user).complete()
    if (Permissions.hasManageServer(member)) {
      authed = true
    }

    subCommand match {
      case "player" =>
        if (authed) {
          if (toggleOption == "add") {
            BotApp.activityCommandBlocker += (event.getGuild.getId -> true)
            BotApp.addHunted(event, "player", nameOption, reasonOption, embed => {
              event.getHook.sendMessageEmbeds(embed).queue(_ => {
                BotApp.activityCommandBlocker += (event.getGuild.getId -> false)
              })
            })
          } else if (toggleOption == "remove") {
            BotApp.activityCommandBlocker += (event.getGuild.getId -> true)
            BotApp.removeHunted(event, "player", nameOption, embed => {
              event.getHook.sendMessageEmbeds(embed).queue(_ => {
                BotApp.activityCommandBlocker += (event.getGuild.getId -> false)
              })
            })
          }
        } else {
          val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} You do not have permission to use this command.").build()
          event.getHook.sendMessageEmbeds(embed).queue()
        }
      case "guild" =>
        if (authed) {
          if (toggleOption == "add") {
            BotApp.activityCommandBlocker += (event.getGuild.getId -> true)
            BotApp.addHunted(event, "guild", nameOption, reasonOption, embed => {
              event.getHook.sendMessageEmbeds(embed).queue(_ => {
                BotApp.activityCommandBlocker += (event.getGuild.getId -> false)
              })
            })
          } else if (toggleOption == "remove") {
            BotApp.activityCommandBlocker += (event.getGuild.getId -> true)
            BotApp.removeHunted(event, "guild", nameOption, embed => {
              event.getHook.sendMessageEmbeds(embed).queue(_ => {
                BotApp.activityCommandBlocker += (event.getGuild.getId -> false)
              })
            })
          }
        } else {
          val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} You do not have permission to use this command.").build()
          event.getHook.sendMessageEmbeds(embed).queue()
        }
      case "list" =>
        if (authed) {
          BotApp.listAlliesAndHuntedGuilds(event, "hunted", hunteds => {
            val embedsJava = hunteds.asJava
            embedsJava.forEach { embed =>
              event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue()
            }
            BotApp.listAlliesAndHuntedPlayers(event, "hunted", hunteds => {
              val embedsJava = hunteds.asJava
              embedsJava.forEach { embed =>
                event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue()
              }
            })
          })
        } else {
          val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} You do not have permission to use this command.").build()
          event.getHook.sendMessageEmbeds(embed).queue()
        }
      case "clear" =>
        if (authed) {
          val embed = BotApp.clearHunted(event)
          event.getHook.sendMessageEmbeds(embed).queue()
        } else {
          val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} You do not have permission to use this command.").build()
          event.getHook.sendMessageEmbeds(embed).queue()
        }
      case "deaths" =>
        if (authed) {
          if (toggleOption == "show") {
            val embed = BotApp.deathsLevelsHideShow(event, worldOption, "show", "enemies", "deaths")
            event.getHook.sendMessageEmbeds(embed).queue()
          } else if (toggleOption == "hide") {
            val embed = BotApp.deathsLevelsHideShow(event, worldOption, "hide", "enemies", "deaths")
            event.getHook.sendMessageEmbeds(embed).queue()
          }
        } else {
           val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} You do not have permission to use this command.").build()
           event.getHook.sendMessageEmbeds(embed).queue()
         }
      case "levels" =>
        if (authed) {
          if (toggleOption == "show") {
            val embed = BotApp.deathsLevelsHideShow(event, worldOption, "show", "enemies", "levels")
            event.getHook.sendMessageEmbeds(embed).queue()
          } else if (toggleOption == "hide") {
            val embed = BotApp.deathsLevelsHideShow(event, worldOption, "hide", "enemies", "levels")
            event.getHook.sendMessageEmbeds(embed).queue()
          }
        } else {
           val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} You do not have permission to use this command.").build()
           event.getHook.sendMessageEmbeds(embed).queue()
         }
      case "info" =>
        val embed = BotApp.infoHunted(event, "player", nameOption)
        event.getHook.sendMessageEmbeds(embed).queue()
      case "autodetect" =>
        if (authed) {
          val embed = BotApp.detectHunted(event)
          event.getHook.sendMessageEmbeds(embed).queue()
        } else {
           val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} You do not have permission to use this command.").build()
           event.getHook.sendMessageEmbeds(embed).queue()
        }
      case other =>
        val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} Invalid subcommand '$other' for `/hunted`.").build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }
}
