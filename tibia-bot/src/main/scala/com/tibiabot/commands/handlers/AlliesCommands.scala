package com.tibiabot.commands.handlers

import com.tibiabot.{BotApp, Config}
import com.tibiabot.commands.Permissions
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

import scala.jdk.CollectionConverters._

/** Handles `/allies`: manage allied players and guilds. */
object AlliesCommands {
  def handle(event: SlashCommandInteractionEvent): Unit = {
    val subCommand = event.getInteraction.getSubcommandName
    val options = Options.of(event)
    val toggleOption: String = options.getOrElse("option", "")
    val nameOption: String = options.getOrElse("name", "")
    val reasonOption: String = options.getOrElse("reason", "none")
    val worldOption: String = options.getOrElse("world", "")

    // Manage Server or the guild's moderator role — see Permissions.isModerator.
    val authed = Permissions.callerIsModerator(event, BotApp.moderatorRoleId(event.getGuild.getId))

    subCommand match {
      case "player" =>
        if (authed) {
          if (toggleOption == "add") {
            BotApp.modifyActivityCommandBlocker(_ + (event.getGuild.getId -> true))
            BotApp.huntedAlliedService.addAlly(event, "player", nameOption, reasonOption, embed => {
              event.getHook.sendMessageEmbeds(embed).queue(_ => {
                BotApp.modifyActivityCommandBlocker(_ + (event.getGuild.getId -> false))
              })
            })
          } else if (toggleOption == "remove") {
            BotApp.modifyActivityCommandBlocker(_ + (event.getGuild.getId -> true))
            BotApp.huntedAlliedService.removeAlly(event, "player", nameOption, embed => {
              event.getHook.sendMessageEmbeds(embed).queue(_ => {
                BotApp.modifyActivityCommandBlocker(_ + (event.getGuild.getId -> false))
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
            BotApp.modifyActivityCommandBlocker(_ + (event.getGuild.getId -> true))
            BotApp.huntedAlliedService.addAlly(event, "guild", nameOption, reasonOption, embed => {
              event.getHook.sendMessageEmbeds(embed).queue(_ => {
                BotApp.modifyActivityCommandBlocker(_ + (event.getGuild.getId -> false))
              })
            })
          } else if (toggleOption == "remove") {
            BotApp.modifyActivityCommandBlocker(_ + (event.getGuild.getId -> true))
            BotApp.huntedAlliedService.removeAlly(event, "guild", nameOption, embed => {
              event.getHook.sendMessageEmbeds(embed).queue(_ => {
                BotApp.modifyActivityCommandBlocker(_ + (event.getGuild.getId -> false))
              })
            })
          }
        } else {
           val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} You do not have permission to use this command.").build()
           event.getHook.sendMessageEmbeds(embed).queue()
        }
      case "list" =>
        if (authed) {
          BotApp.huntedAlliedService.listAlliesAndHuntedGuilds(event, "allies", allies => {
            val embedsJava = allies.asJava
            embedsJava.forEach { embed =>
              event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue()
            }
            BotApp.huntedAlliedService.listAlliesAndHuntedPlayers(event, "allies", allies => {
              val embedsJava = allies.asJava
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
          val embed = BotApp.huntedAlliedService.clearAllies(event)
          event.getHook.sendMessageEmbeds(embed).queue()
        } else {
           val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} You do not have permission to use this command.").build()
           event.getHook.sendMessageEmbeds(embed).queue()
        }
      case "deaths" =>
        if (authed) {
          if (toggleOption == "show") {
            val embed = BotApp.worldSettingsService.deathsLevelsHideShow(event, worldOption, "show", "allies", "deaths")
            event.getHook.sendMessageEmbeds(embed).queue()
          } else if (toggleOption == "hide") {
            val embed = BotApp.worldSettingsService.deathsLevelsHideShow(event, worldOption, "hide", "allies", "deaths")
            event.getHook.sendMessageEmbeds(embed).queue()
          }
        } else {
           val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} You do not have permission to use this command.").build()
           event.getHook.sendMessageEmbeds(embed).queue()
        }
      case "levels" =>
        if (authed) {
          if (toggleOption == "show") {
            val embed = BotApp.worldSettingsService.deathsLevelsHideShow(event, worldOption, "show", "allies", "levels")
            event.getHook.sendMessageEmbeds(embed).queue()
          } else if (toggleOption == "hide") {
            val embed = BotApp.worldSettingsService.deathsLevelsHideShow(event, worldOption, "hide", "allies", "levels")
            event.getHook.sendMessageEmbeds(embed).queue()
          }
        } else {
           val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} You do not have permission to use this command.").build()
           event.getHook.sendMessageEmbeds(embed).queue()
        }
      case "info" =>
        val embed = BotApp.huntedAlliedService.infoAllies(event, "player", nameOption)
        event.getHook.sendMessageEmbeds(embed).queue()
      case other =>
        val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} Invalid subcommand '$other' for `/allies`.").build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }

  }
}
