package com.tibiabot

import com.tibiabot.BotApp.commands
import com.tibiabot.BotApp.SatchelStamp
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.interactions.components.buttons._
import java.time.ZonedDateTime
import net.dv8tion.jda.api.interactions.components.ActionRow

import scala.jdk.CollectionConverters._

class BotListener extends ListenerAdapter with StrictLogging {

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
      case "neutral" =>
        handleNeutrals(event)
      case "fullbless" =>
        handleFullbless(event)
      case "filter" =>
        handleFilter(event)
      case "admin" =>
        handleAdmin(event)
      case "exiva" =>
        handleExiva(event)
      case "help" =>
        handleHelp(event)
      case "repair" =>
        handleRepair(event)
      case "galthen" =>
        handleGalthen(event)
      case _ =>
    }
  }

  override def onGuildJoin(event: GuildJoinEvent): Unit = {
    val guild = event.getGuild
    guild.updateCommands().addCommands(commands.asJava).complete()
    BotApp.discordJoin(event)
  }

  override def onGuildLeave(event: GuildLeaveEvent): Unit = {
    BotApp.discordLeave(event)
  }

  override def onButtonInteraction(event: ButtonInteractionEvent): Unit = {
    val embed = event.getInteraction.getMessage.getEmbeds
    val title = if (!embed.isEmpty) embed.get(0).getTitle else ""
    val button = event.getComponentId
    val guild = event.getGuild
    val user = event.getUser
    var responseText = ":x: An unknown error occured, please try again."

    if (button == "galthenSet") {
      event.deferEdit().queue();
      val when = ZonedDateTime.now().plusDays(30).toEpochSecond.toString()
      BotApp.addGalthen(user.getId, ZonedDateTime.now())
      responseText = s"a <:satchel:1030348072577945651> can be collected by <@${user.getId()}> <t:$when:R>."
      event.getHook().editOriginalComponents(ActionRow.of(
        Button.success("galthenSet", "Collected").asDisabled,
        Button.danger("galthenRemove", "Clear")
      )).queue();
      val newEmbed = new EmbedBuilder().setDescription(responseText).setColor(9855533).setFooter("You will be sent a message when the cooldown expires").build()
      event.getHook().editOriginalEmbeds(newEmbed).queue();
    } else if (button == "galthenRemove") {
      event.deferEdit().queue()
      BotApp.delGalthen(user.getId)
      responseText = s"Your <:satchel:1030348072577945651> cooldown tracker has been **Disabled**."
      event.getHook().editOriginalComponents().queue();
      val newEmbed = new EmbedBuilder().setDescription(responseText).setColor(178877).build()
      event.getHook().editOriginalEmbeds(newEmbed).queue();
    } else if (button == "galthenRemind") { // WIP
      event.deferEdit().queue()
      val when = ZonedDateTime.now().plusDays(30).toEpochSecond.toString()
      BotApp.addGalthen(user.getId, ZonedDateTime.now())
      responseText = s"a <:satchel:1030348072577945651> can be collected by <@${user.getId()}> <t:$when:R>."
      event.getHook().editOriginalComponents().queue();
      val newEmbed = new EmbedBuilder().setDescription(responseText).setColor(9855533).setFooter("You will be sent a message when the cooldown expires").build()
      event.getHook().editOriginalEmbeds(newEmbed).queue()
    } else if (button == "galthenClear") { // WIP
      event.deferEdit().queue()
      event.getHook().editOriginalComponents().queue()
    } else {
      event.deferReply(true).queue()
      val roleType = if (title.contains(":crossed_swords:")) "fullbless" else if (title.contains(s"${Config.nemesisEmoji}")) "nemesis" else ""
      if (roleType == "fullbless") {
        val world = title.replace(":crossed_swords:", "").trim()
        val worldConfigData = BotApp.worldRetrieveConfig(guild, world)
        val role = guild.getRoleById(worldConfigData("fullbless_role"))
        if (role != null) {
          if (button == "add") {
            // get role add user to it
            try {
              guild.addRoleToMember(user, role).queue()
              responseText = s":gear: You have been added to the <@&${role.getId}> role."
            } catch {
              case _: Throwable =>
                responseText = s":x: Failed to add you to the <@&${role.getId}> role."
                val discordInfo = BotApp.discordRetrieveConfig(guild)
                val adminChannelId = if (discordInfo.nonEmpty) discordInfo("admin_channel") else "0"
                val adminTextChannel = guild.getTextChannelById(adminChannelId)
                if (adminTextChannel != null) {
                  val commandPlayer = s"<@${user.getId}>"
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(":x: a player interaction has failed:")
                  adminEmbed.setDescription(s"Failed to add user $commandPlayer to the <@&${role.getId}> role.\n\n:speech_balloon: *Ensure the role <@&${role.getId}> is `below` <@${BotApp.botUser}> on the roles list, or the bot cannot interact with it.*")
                  adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Warning_Sign.gif")
                  adminEmbed.setColor(3092790) // orange for bot auto command
                  try {
                    adminTextChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                  } catch {
                    case ex: Exception => logger.error(s"Failed to send message to 'command-log' channel for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
                    case _: Throwable => logger.info(s"Failed to send message to 'command-log' channel for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'")
                  }
                }
            }
          } else if (button == "remove") {
            // remove role
            try {
              guild.removeRoleFromMember(user, role).queue()
              responseText = s":gear: You have been removed from the <@&${role.getId}> role."
            } catch {
              case _: Throwable =>
                responseText = s":x: Failed to remove you from the <@&${role.getId}> role."
                val discordInfo = BotApp.discordRetrieveConfig(guild)
                val adminChannelId = if (discordInfo.nonEmpty) discordInfo("admin_channel") else "0"
                val adminTextChannel = guild.getTextChannelById(adminChannelId)
                if (adminTextChannel != null) {
                  val commandPlayer = s"<@${user.getId}>"
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(":x: a player interaction has failed:")
                  adminEmbed.setDescription(s"Failed to remove user $commandPlayer to the <@&${role.getId}> role.\n\n:speech_balloon: *Ensure the role <@&${role.getId}> is `below` <@${BotApp.botUser}> on the roles list, or the bot cannot interact with it.*")
                  adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Warning_Sign.gif")
                  adminEmbed.setColor(3092790) // orange for bot auto command
                  try {
                    adminTextChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                  } catch {
                    case ex: Exception => logger.error(s"Failed to send message to 'command-log' channel for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
                    case _: Throwable => logger.info(s"Failed to send message to 'command-log' channel for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'")
                  }
                }
            }
          }
        } else {
          // role doesn't exist
          responseText = s":x: The role you are trying to add/remove yourself from has been deleted, please notify a discord mod for this server."
        }
      } else if (roleType == "nemesis") {
        val world = title.replace(s"${Config.nemesisEmoji}", "").trim()
        val worldConfigData = BotApp.worldRetrieveConfig(guild, world)
        val role = guild.getRoleById(worldConfigData("nemesis_role"))
        if (role != null) {
          if (button == "add") {
            // get role add user to it
            try {
              guild.addRoleToMember(user, role).queue()
              responseText = s":gear: You have been added to the <@&${role.getId}> role."
            } catch {
              case _: Throwable =>
                responseText = s":x: Failed to add you to the <@&${role.getId}> role."
                val discordInfo = BotApp.discordRetrieveConfig(guild)
                val adminChannelId = if (discordInfo.nonEmpty) discordInfo("admin_channel") else "0"
                val adminTextChannel = guild.getTextChannelById(adminChannelId)
                if (adminTextChannel != null) {
                  val commandPlayer = s"<@${user.getId}>"
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(":x: a player interaction has failed:")
                  adminEmbed.setDescription(s"Failed to add user $commandPlayer to the <@&${role.getId}> role.\n\n:speech_balloon: *Ensure the role <@&${role.getId}> is `below` <@${BotApp.botUser}> on the roles list, or the bot cannot interact with it.*")
                  adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Warning_Sign.gif")
                  adminEmbed.setColor(3092790) // orange for bot auto command
                  try {
                    adminTextChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                  } catch {
                    case ex: Exception => logger.error(s"Failed to send message to 'command-log' channel for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
                    case _: Throwable => logger.info(s"Failed to send message to 'command-log' channel for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'")
                  }
                }
            }
          } else if (button == "remove") {
            // remove role
            try {
              guild.removeRoleFromMember(user, role).queue()
              responseText = s":gear: You have been removed from the <@&${role.getId}> role."
            } catch {
              case _: Throwable =>
                responseText = s":x: Failed to remove you from the <@&${role.getId}> role."
                val discordInfo = BotApp.discordRetrieveConfig(guild)
                val adminChannelId = if (discordInfo.nonEmpty) discordInfo("admin_channel") else "0"
                val adminTextChannel = guild.getTextChannelById(adminChannelId)
                if (adminTextChannel != null) {
                  val commandPlayer = s"<@${user.getId}>"
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(":x: a player interaction has failed:")
                  adminEmbed.setDescription(s"Failed to remove user $commandPlayer from the <@&${role.getId}> role.\n\n:speech_balloon: *Ensure the role <@&${role.getId}> is `below` <@${BotApp.botUser}> on the roles list, or the bot cannot interact with it.*")
                  adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Warning_Sign.gif")
                  adminEmbed.setColor(3092790) // orange for bot auto command
                  try {
                    adminTextChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                  } catch {
                    case ex: Exception => logger.error(s"Failed to send message to 'command-log' channel for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
                    case _: Throwable => logger.info(s"Failed to send message to 'command-log' channel for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'")
                  }
                }
            }
          }
        } else {
          // role doesn't exist
          responseText = s":x: The role you are trying to add/remove yourself from has been deleted, please notify a discord mod for this server."
        }
      }
      val replyEmbed = new EmbedBuilder().setDescription(responseText).build()
      event.getHook.sendMessageEmbeds(replyEmbed).queue()
    }
  }

  private def handleSetup(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val embed = BotApp.createChannels(event)
    event.getHook.sendMessageEmbeds(embed).queue()
  }
  private def handleRemove(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val embed = BotApp.removeChannels(event)
    event.getHook.sendMessageEmbeds(embed).queue()
  }

  private def handleGalthen(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val satchelTime = BotApp.getGalthenTable(event.getUser.getId)
    val embed = new EmbedBuilder()
    val firstSatchel = satchelTime.headOption
    firstSatchel match {
      case Some(satchel) =>
        val when = satchel.when.plusDays(30).toEpochSecond.toString()
        embed.setColor(9855533)
        embed.setFooter("You will be sent a message when the cooldown expires")
        embed.setDescription(s"a <:satchel:1030348072577945651> can be collected by <@${event.getUser.getId}> <t:$when:R>.")
        event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
          Button.success("galthenSet", "Collected").asDisabled,
          Button.danger("galthenRemove", "Clear")
        ).queue()
      case None =>
        embed.setColor(178877)
        embed.setDescription(s"This is a **Galthen Satchel** cooldown tracker.\nMark the <:satchel:1030348072577945651> as **Collected** and I will message you when the `30 day cooldown` expires.")
        embed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
        event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
          Button.success("galthenSet", "Collected"),
          Button.danger("galthenRemove", "Clear").asDisabled
        ).queue()
    }
  }

  private def handleHunted(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val subCommand = event.getInteraction.getSubcommandName
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val toggleOption: String = options.getOrElse("option", "")
    val worldOption: String = options.getOrElse("world", "")
    val nameOption: String = options.getOrElse("name", "")
    val reasonOption: String = options.getOrElse("reason", "none")

    subCommand match {
      case "player" =>
        if (toggleOption == "add") {
          BotApp.activityCommandBlocker += (event.getGuild.getId -> true)
          BotApp.addHunted(event, "player", nameOption, reasonOption, embed => {
            event.getHook.sendMessageEmbeds(embed).queue()
          })
        } else if (toggleOption == "remove") {
          BotApp.activityCommandBlocker += (event.getGuild.getId -> true)
          BotApp.removeHunted(event, "player", nameOption, embed => {
            event.getHook.sendMessageEmbeds(embed).queue()
          })
        }
      case "guild" =>
        if (toggleOption == "add") {
          BotApp.activityCommandBlocker += (event.getGuild.getId -> true)
          BotApp.addHunted(event, "guild", nameOption, reasonOption, embed => {
            event.getHook.sendMessageEmbeds(embed).queue()
          })
        } else if (toggleOption == "remove") {
          BotApp.activityCommandBlocker += (event.getGuild.getId -> true)
          BotApp.removeHunted(event, "guild", nameOption, embed => {
            event.getHook.sendMessageEmbeds(embed).queue()
          })
        }
      case "list" =>
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
      case "deaths" =>
        if (toggleOption == "show") {
          val embed = BotApp.deathsLevelsHideShow(event, worldOption, "show", "enemies", "deaths")
          event.getHook.sendMessageEmbeds(embed).queue()
        } else if (toggleOption == "hide") {
          val embed = BotApp.deathsLevelsHideShow(event, worldOption, "hide", "enemies", "deaths")
          event.getHook.sendMessageEmbeds(embed).queue()
        }
      case "levels" =>
        if (toggleOption == "show") {
          val embed = BotApp.deathsLevelsHideShow(event, worldOption, "show", "enemies", "levels")
          event.getHook.sendMessageEmbeds(embed).queue()
        } else if (toggleOption == "hide") {
          val embed = BotApp.deathsLevelsHideShow(event, worldOption, "hide", "enemies", "levels")
          event.getHook.sendMessageEmbeds(embed).queue()
        }
      case "info" =>
        val embed = BotApp.infoHunted(event, "player", nameOption)
        event.getHook.sendMessageEmbeds(embed).queue()
      case "autodetect" =>
        val embed = BotApp.detectHunted(event)
        event.getHook.sendMessageEmbeds(embed).queue()
      case _ =>
        val embed = new EmbedBuilder().setDescription(s":x: Invalid subcommand '$subCommand' for `/hunted`.").build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }

  private def handleAllies(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val subCommand = event.getInteraction.getSubcommandName
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val toggleOption: String = options.getOrElse("option", "")
    val nameOption: String = options.getOrElse("name", "")
    val reasonOption: String = options.getOrElse("reason", "none")
    val worldOption: String = options.getOrElse("world", "")

    subCommand match {
      case "player" =>
        if (toggleOption == "add") {
          BotApp.activityCommandBlocker += (event.getGuild.getId -> true)
          BotApp.addAlly(event, "player", nameOption, reasonOption, embed => {
            event.getHook.sendMessageEmbeds(embed).queue(_ => {
              BotApp.activityCommandBlocker += (event.getGuild.getId -> false)
            })
          })
        } else if (toggleOption == "remove") {
          BotApp.activityCommandBlocker += (event.getGuild.getId -> true)
          BotApp.removeAlly(event, "player", nameOption, embed => {
            event.getHook.sendMessageEmbeds(embed).queue(_ => {
              BotApp.activityCommandBlocker += (event.getGuild.getId -> false)
            })
          })
        }
      case "guild" =>
        if (toggleOption == "add") {
          BotApp.activityCommandBlocker += (event.getGuild.getId -> true)
          BotApp.addAlly(event, "guild", nameOption, reasonOption, embed => {
            event.getHook.sendMessageEmbeds(embed).queue(_ => {
              BotApp.activityCommandBlocker += (event.getGuild.getId -> false)
            })
          })
        } else if (toggleOption == "remove") {
          BotApp.activityCommandBlocker += (event.getGuild.getId -> true)
          BotApp.removeAlly(event, "guild", nameOption, embed => {
            event.getHook.sendMessageEmbeds(embed).queue(_ => {
              BotApp.activityCommandBlocker += (event.getGuild.getId -> false)
            })
          })
        }
      case "list" =>
        BotApp.listAlliesAndHuntedGuilds(event, "allies", allies => {
          val embedsJava = allies.asJava
          embedsJava.forEach { embed =>
            event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue()
          }
          BotApp.listAlliesAndHuntedPlayers(event, "allies", allies => {
            val embedsJava = allies.asJava
            embedsJava.forEach { embed =>
              event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue()
            }
          })
        })
      case "deaths" =>
        if (toggleOption == "show") {
          val embed = BotApp.deathsLevelsHideShow(event, worldOption, "show", "allies", "deaths")
          event.getHook.sendMessageEmbeds(embed).queue()
        } else if (toggleOption == "hide") {
          val embed = BotApp.deathsLevelsHideShow(event, worldOption, "hide", "allies", "deaths")
          event.getHook.sendMessageEmbeds(embed).queue()
        }
      case "levels" =>
        if (toggleOption == "show") {
          val embed = BotApp.deathsLevelsHideShow(event, worldOption, "show", "allies", "levels")
          event.getHook.sendMessageEmbeds(embed).queue()
        } else if (toggleOption == "hide") {
          val embed = BotApp.deathsLevelsHideShow(event, worldOption, "hide", "allies", "levels")
          event.getHook.sendMessageEmbeds(embed).queue()
        }
      case "info" =>
        val embed = BotApp.infoAllies(event, "player", nameOption)
        event.getHook.sendMessageEmbeds(embed).queue()
      case _ =>
        val embed = new EmbedBuilder().setDescription(s":x: Invalid subcommand '$subCommand' for `/allies`.").build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }

  }

  private def handleNeutrals(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val subCommand = event.getInteraction.getSubcommandName
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val toggleOption: String = options.getOrElse("option", "")
    val worldOption: String = options.getOrElse("world", "")

    subCommand match {
      case "deaths" =>
        if (toggleOption == "show") {
          val embed = BotApp.deathsLevelsHideShow(event, worldOption, "show", "neutrals", "deaths")
          event.getHook.sendMessageEmbeds(embed).queue()
        } else if (toggleOption == "hide") {
          val embed = BotApp.deathsLevelsHideShow(event, worldOption, "hide", "neutrals", "deaths")
          event.getHook.sendMessageEmbeds(embed).queue()
        }
      case "levels" =>
        if (toggleOption == "show") {
          val embed = BotApp.deathsLevelsHideShow(event, worldOption, "show", "neutrals", "levels")
          event.getHook.sendMessageEmbeds(embed).queue()
        } else if (toggleOption == "hide") {
          val embed = BotApp.deathsLevelsHideShow(event, worldOption, "hide", "neutrals", "levels")
          event.getHook.sendMessageEmbeds(embed).queue()
        }
      case _ =>
        val embed = new EmbedBuilder().setDescription(s":x: Invalid subcommand '$subCommand' for `/neutral`.").build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }

  private def handleFullbless(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val worldOption: String = options.getOrElse("world", "")
    val levelOption: Int = options.get("level").map(_.toInt).getOrElse(250)

    val embed = BotApp.fullblessLevel(event, worldOption, levelOption)
    event.getHook.sendMessageEmbeds(embed).queue()
  }

  private def handleFilter(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val subCommand = event.getInteraction.getSubcommandName
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val worldOption: String = options.getOrElse("world", "")
    val levelOption: Int = options.get("level").map(_.toInt).getOrElse(8)

    subCommand match {
      case "levels" =>
        val embed = BotApp.minLevel(event, worldOption, levelOption, "levels")
        event.getHook.sendMessageEmbeds(embed).queue()
      case "deaths" =>
        val embed = BotApp.minLevel(event, worldOption, levelOption, "deaths")
        event.getHook.sendMessageEmbeds(embed).queue()
      case _ =>
        val embed = new EmbedBuilder().setDescription(s":x: Invalid subcommand '$subCommand' for `/filter`.").build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }

  private def handleAdmin(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val subCommand = event.getInteraction.getSubcommandName
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val guildOption: String = options.getOrElse("guildid", "")
    val reasonOption: String = options.getOrElse("reason", "")
    val messageOption: String = options.getOrElse("message", "")

    subCommand match {
      case "leave" =>
        val embed = BotApp.adminLeave(event, guildOption, reasonOption)
        event.getHook.sendMessageEmbeds(embed).queue()
      case "message" =>
        val embed = BotApp.adminMessage(event, guildOption, messageOption)
        event.getHook.sendMessageEmbeds(embed).queue()
      case _ =>
        val embed = new EmbedBuilder().setDescription(s":x: Invalid subcommand '$subCommand' for `/admin`.").build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }

  private def handleExiva(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val subCommand = event.getInteraction.getSubcommandName

    subCommand match {
      case "deaths" =>
        val embed = BotApp.exivaList(event)
        event.getHook.sendMessageEmbeds(embed).queue()
      case _ =>
        val embed = new EmbedBuilder().setDescription(s":x: Invalid subcommand '$subCommand' for `/exiva`.").build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }

  private def handleHelp(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val embedBuilder = new EmbedBuilder()
    val descripText = s"**How to use the bot:**\n" +
      "Simply use `/setup <World Name>` to setup the bot.\n\n" +
      "**Commands & Features:**\n" +
      "All interactions with the bot are done through **[slash commands](https://support.discord.com/hc/en-us/articles/1500000368501-Slash-Commands-FAQ)**.\n" +
      "If you type `/` and click on **Violent Bot** - you will see all the commands available to you.\n\n" +
      "*If you have any issues or suggestions or would like to donate, use the links below or simply send <:tibiacoin:1117280875818778637> to* **`Violent Beams`** 👍\n\n" +
      "[Website](https://violentbot.xyz) | [Discord](https://discord.gg/SWMq9Pz8ud) | [Donate](http://donate.violentbot.xyz)"
    embedBuilder.setAuthor("Violent Beams", "https://www.tibia.com/community/?subtopic=characters&name=Violent+Beams", "https://github.com/Leo32onGIT.png")
    embedBuilder.setDescription(descripText)
    embedBuilder.setThumbnail("https://raw.githubusercontent.com/Leo32onGIT/tibia-bot-resources/main/Violent%20Bot.png")
    embedBuilder.setColor(14397256) // orange for bot auto command
    event.getHook.sendMessageEmbeds(embedBuilder.build()).queue()
  }

  private def handleRepair(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val worldOption: String = options.getOrElse("world", "")

    val embed = BotApp.repairChannel(event, worldOption)
    event.getHook.sendMessageEmbeds(embed).queue()
  }

}
