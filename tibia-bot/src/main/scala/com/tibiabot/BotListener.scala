package com.tibiabot

import com.tibiabot.BotApp.commands
import com.tibiabot.BotApp.SatchelStamp
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.commands.CommandAutoCompleteInteraction
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.interactions.components.buttons._
import java.time.ZonedDateTime
import net.dv8tion.jda.api.interactions.components.ActionRow
import scala.jdk.CollectionConverters._
import net.dv8tion.jda.api.interactions.modals.Modal
import net.dv8tion.jda.api.interactions.components.text.{TextInput, TextInputStyle}

class BotListener extends ListenerAdapter with StrictLogging {

  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    if (BotApp.startUpComplete) {
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
        case "online" =>
          handleOnlineList(event)
        case "boosted" =>
          handleBoosted(event)
        case _ =>
      }
    } else {
      val responseText = s"${Config.noEmoji} The bot is still starting up, try running your command later."
      val embed = new EmbedBuilder().setDescription(responseText).setColor(3092790).build()
      event.getHook.sendMessageEmbeds(embed).queue()
    }
  }

  override def onGuildJoin(event: GuildJoinEvent): Unit = {
    val guild = event.getGuild
    if (Config.verifiedDiscords.contains(guild.getId)) {
      guild.updateCommands().addCommands(commands.asJava).complete()
      BotApp.discordJoin(event)
    } else {
      guild.updateCommands().queue()
    }
  }

  override def onGuildLeave(event: GuildLeaveEvent): Unit = {
    BotApp.discordLeave(event)
  }

  override def onModalInteraction(event: ModalInteractionEvent): Unit = {
    event.deferEdit().queue()
     val user = event.getUser
     val modalValues = event.getValues.asScala.toList
     modalValues.map { element =>
       val id = element.getId
       var inputName = element.getAsString.trim.toLowerCase
       val shortName = Map(
         "oberon" -> "grand master oberon",
         "scarlett" -> "scarlett etzel",
         "scarlet" -> "scarlett etzel",
         "timira" -> "timira the many-headed",
         "timira the many headed" -> "timira the many-headed",
         "timira many headed" -> "timira the many-headed",
         "timira many-headed" -> "timira the many-headed",
         "magma" -> "magma bubble",
         "rotten final" -> "bakragore",
         "yselda" -> "megasylvan yselda",
         "zelos" -> "king zelos"
       )
       if (shortName.contains(inputName)) {
         inputName = shortName(inputName)
       }
       if (id == "boosted add") {
         val newEmbed = BotApp.boosted(user.getId, "add", inputName)
         event.getHook().editOriginalEmbeds(newEmbed).setActionRow(
           Button.success("boosted add", "Add"),
           Button.danger("boosted remove", "Remove"),
           Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOffEmoji))
         ).queue()
       } else if (id == "boosted remove") {
         val newEmbed = BotApp.boosted(user.getId, "remove", inputName)
         event.getHook().editOriginalEmbeds(newEmbed).setActionRow(
           Button.success("boosted add", "Add"),
           Button.danger("boosted remove", "Remove"),
           Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOffEmoji))
         ).queue()
       } else {
         // Do something else
       }
     }
   }

  override def onButtonInteraction(event: ButtonInteractionEvent): Unit = {
    val embed = event.getInteraction.getMessage.getEmbeds
    val title = if (!embed.isEmpty) embed.get(0).getTitle else ""
    val button = event.getComponentId
    val guild = event.getGuild
    val user = event.getUser
    var responseText = s"${Config.noEmoji} An unknown error occured, please try again."

    val footer = if (!embed.isEmpty) Option(embed.get(0).getFooter) else None
    val tagId = footer.map(_.getText.replace("Tag: ", "")).getOrElse("")

    /**
    if (button == "galthen board") {
      event.deferReply(true).queue()
      //WIP
      val satchelTimeOption: Option[List[SatchelStamp]] = BotApp.getGalthenTable(event.getUser.getId)
      satchelTimeOption match {
        case Some(satchelTimeList) =>
          val fullList = satchelTimeList.collect {
            case satchel =>
              val when = satchel.when.plusDays(30).toEpochSecond.toString()
              val displayTag = if (satchel.tag == "") s"<@${event.getUser.getId}>" else s"**`${satchel.tag}`**"
              s"<:satchel:1030348072577945651> can be collected by $displayTag <t:$when:R>"
          } else {
            embed.setColor(178877)
            embed.setDescription("This is a **[Galthen's Satchel](https://tibia.fandom.com/wiki/Galthen's_Satchel)** cooldown tracker.\nMark the <:satchel:1030348072577945651> as **Collected** and I will message you: ```when the 30 day cooldown expires```")
            embed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
            event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
              Button.success("galthenSet", "Collected"),
              Button.danger("galthenRemove", "Clear").asDisabled
            ).queue()
          }
        // /HERE
        case None =>
          embed.setColor(178877)
          embed.setDescription("This is a **[Galthen's Satchel](https://tibia.fandom.com/wiki/Galthen's_Satchel)** cooldown tracker.\nMark the <:satchel:1030348072577945651> as **Collected** and I will message you: ```when the 30 day cooldown expires```")
          embed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
          event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
            Button.success("galthenSet", "Collected"),
            Button.danger("galthenRemove", "Clear").asDisabled
          ).queue()
      }
    } else
    **/
    if (button == "galthenSet") {
      event.deferEdit().queue();
      val when = ZonedDateTime.now().plusDays(30).toEpochSecond.toString()
      BotApp.addGalthen(user.getId, ZonedDateTime.now(), tagId)
      val tagDisplay = if (tagId == "") s"<@${event.getUser.getId}>" else s"**`$tagId`**"
      responseText = s"<:satchel:1030348072577945651> can be collected by $tagDisplay <t:$when:R>"
      event.getHook().editOriginalComponents(ActionRow.of(
        Button.success("galthenSet", "Collected").asDisabled,
        Button.danger("galthenRemove", "Clear")
      )).queue();
      val newEmbed = new EmbedBuilder()
      newEmbed.setDescription(responseText)
      newEmbed.setColor(9855533)
      if (tagId != "") {
        newEmbed.setFooter(s"Tag: ${tagId}")
      }
      event.getHook().editOriginalEmbeds(newEmbed.build()).queue();
    } else if (button == "galthenRemove") {
      event.deferEdit().queue()
      BotApp.delGalthen(user.getId, tagId)
      val tagDisplay = if (tagId == "") s"<@${event.getUser.getId}>" else s"**`$tagId`**"
      responseText = s"Your <:satchel:1030348072577945651> cooldown tracker for $tagDisplay has been **Disabled**."
      event.getHook().editOriginalComponents().queue();
      val newEmbed = new EmbedBuilder().setDescription(responseText).setColor(178877).build()
      event.getHook().editOriginalEmbeds(newEmbed).queue();
    } else if (button == "galthenRemoveAll") {
      event.deferEdit().queue()
      BotApp.delAllGalthen(user.getId)
      responseText = s"Your <:satchel:1030348072577945651> cooldown tracker has been **Disabled**."
      event.getHook().editOriginalComponents().queue();
      val newEmbed = new EmbedBuilder().setDescription(responseText).setColor(178877).build()
      event.getHook().editOriginalEmbeds(newEmbed).queue();
    } else if (button == "galthenLock") {
      event.deferEdit().queue()
      event.getHook().editOriginalComponents(ActionRow.of(
        Button.secondary("galthenUnLock", "🔓"),
        Button.danger("galthenRemoveAll", "Clear All")
      )).queue();
    } else if (button == "galthenUnLock") {
      event.deferEdit().queue()
      event.getHook().editOriginalComponents(ActionRow.of(
        Button.secondary("galthenLock", "🔒"),
        Button.danger("galthenRemoveAll", "Clear All").asDisabled
      )).queue();
    } else if (button == "galthenRemind") { // WIP
      event.deferEdit().queue()
      val when = ZonedDateTime.now().plusDays(30).toEpochSecond.toString()
      BotApp.addGalthen(user.getId, ZonedDateTime.now(), tagId)
      val tagDisplay = if (tagId == "") s"<@${event.getUser.getId}>" else s"**`$tagId`**"
      responseText = s"<:satchel:1030348072577945651> can be collected by $tagDisplay <t:$when:R>"
      event.getHook().editOriginalComponents().queue();
      val newEmbed = new EmbedBuilder().setDescription(responseText).setColor(9855533).setFooter("You will be sent a message when the cooldown expires").build()
      event.getHook().editOriginalEmbeds(newEmbed).queue()
    } else if (button == "galthenClear") { // WIP
      event.deferEdit().queue()
      event.getHook().editOriginalComponents().queue()
    } else if (button == "boosted") {
      event.deferReply(true).queue()
      val replyEmbed = new EmbedBuilder()
      replyEmbed.setTitle(s"Receiving boosted boss & creature notifications:")
      responseText = s"Use the `/boosted` command to filter specific `bosses` & `creatures`."
      replyEmbed.setDescription(responseText)
      event.getHook.sendMessageEmbeds(replyEmbed.build()).queue()
    } else if (button == "boosted add") {
      //WIP
      val inputWindow = TextInput.create("boosted add", "Boss or Creature name", TextInputStyle.SHORT)
        .setPlaceholder("Grand Master Oberon")
        .build()
      val modal = Modal.create("add modal", "Add a Boss or Creature").addComponents(ActionRow.of(inputWindow)).build()
      event.replyModal(modal).queue()
    } else if (button == "boosted remove") {

      val inputWindow = TextInput.create("boosted remove", "Boss or Creature name", TextInputStyle.SHORT).build()
      val modal = Modal.create("remove modal", "Add Server Save Notificiations:").addComponents(ActionRow.of(inputWindow)).build()
      event.replyModal(modal).queue()
    } else if (button == "boosted list") {
      event.deferReply(true).queue()
      //WIP
      val allCheck = BotApp.boostedList(event.getUser.getId)
      if (allCheck) {
        val embed = BotApp.boosted(event.getUser.getId, "list", "")
        event.getHook.sendMessageEmbeds(embed).setActionRow(
          Button.success("boosted add", "Add").asDisabled,
          Button.danger("boosted remove", "Remove").asDisabled,
          Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOnEmoji))
        ).queue()
      } else {
        val embed = BotApp.boosted(event.getUser.getId, "list", "")
        event.getHook.sendMessageEmbeds(embed).setActionRow(
          Button.success("boosted add", "Add"),
          Button.danger("boosted remove", "Remove"),
          Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOffEmoji))
        ).queue()
      }
    } else if (button == "boosted toggle") {
      event.deferEdit().queue()

      //WIP
      val allCheck = BotApp.boostedList(event.getUser.getId)
      if (allCheck) {
        val embed = BotApp.boosted(event.getUser.getId, "toggle", "all")
        event.getHook.editOriginalEmbeds(embed).setActionRow(
          Button.success("boosted add", "Add"),
          Button.danger("boosted remove", "Remove"),
          Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOffEmoji))
        ).queue()
      } else {
        val embed = BotApp.boosted(event.getUser.getId, "toggle", "all")
        event.getHook.editOriginalEmbeds(embed).setActionRow(
          Button.success("boosted add", "Add").asDisabled,
          Button.danger("boosted remove", "Remove").asDisabled,
          Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOnEmoji))
        ).queue()
      }

    }  else {
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
                responseText = s"${Config.noEmoji} Failed to add you to the <@&${role.getId}> role."
                val discordInfo = BotApp.discordRetrieveConfig(guild)
                val adminChannelId = if (discordInfo.nonEmpty) discordInfo("admin_channel") else "0"
                val adminTextChannel = guild.getTextChannelById(adminChannelId)
                if (adminTextChannel != null) {
                  val commandPlayer = s"<@${user.getId}>"
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s"${Config.noEmoji} a player interaction has failed:")
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
                responseText = s"${Config.noEmoji} Failed to remove you from the <@&${role.getId}> role."
                val discordInfo = BotApp.discordRetrieveConfig(guild)
                val adminChannelId = if (discordInfo.nonEmpty) discordInfo("admin_channel") else "0"
                val adminTextChannel = guild.getTextChannelById(adminChannelId)
                if (adminTextChannel != null) {
                  val commandPlayer = s"<@${user.getId}>"
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s"${Config.noEmoji} a player interaction has failed:")
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
          responseText = s"${Config.noEmoji} The role you are trying to add/remove yourself from has been deleted, please notify a discord mod for this server."
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
                responseText = s"${Config.noEmoji} Failed to add you to the <@&${role.getId}> role."
                val discordInfo = BotApp.discordRetrieveConfig(guild)
                val adminChannelId = if (discordInfo.nonEmpty) discordInfo("admin_channel") else "0"
                val adminTextChannel = guild.getTextChannelById(adminChannelId)
                if (adminTextChannel != null) {
                  val commandPlayer = s"<@${user.getId}>"
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s"${Config.noEmoji} a player interaction has failed:")
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
                responseText = s"${Config.noEmoji} Failed to remove you from the <@&${role.getId}> role."
                val discordInfo = BotApp.discordRetrieveConfig(guild)
                val adminChannelId = if (discordInfo.nonEmpty) discordInfo("admin_channel") else "0"
                val adminTextChannel = guild.getTextChannelById(adminChannelId)
                if (adminTextChannel != null) {
                  val commandPlayer = s"<@${user.getId}>"
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s"${Config.noEmoji} a player interaction has failed:")
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
          responseText = s"${Config.noEmoji} The role you are trying to add/remove yourself from has been deleted, please notify a discord mod for this server."
        }
      }
      val replyEmbed = new EmbedBuilder().setDescription(responseText).build()
      event.getHook.sendMessageEmbeds(replyEmbed).queue()
    }
  }

  private def handleSetup(event: SlashCommandInteractionEvent): Unit = {
    val embed = BotApp.createChannels(event)
    event.getHook.sendMessageEmbeds(embed).queue()
  }
  private def handleRemove(event: SlashCommandInteractionEvent): Unit = {
    val embed = BotApp.removeChannels(event)
    event.getHook.sendMessageEmbeds(embed).queue()
  }

  private def handleGalthen(event: SlashCommandInteractionEvent): Unit = {
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val tagOption: String = options.getOrElse("character", "")
    val satchelTimeOption: Option[List[SatchelStamp]] = BotApp.getGalthenTable(event.getUser.getId)
    val embed = new EmbedBuilder()

    satchelTimeOption match {
      //
      case Some(satchelTimeList) if satchelTimeList.isEmpty =>
        embed.setColor(178877)
        if (tagOption.nonEmpty) embed.setFooter(s"Tag: ${tagOption.toLowerCase}")
        embed.setDescription("This is a **[Galthen's Satchel](https://tibia.fandom.com/wiki/Galthen's_Satchel)** cooldown tracker.\nMark the <:satchel:1030348072577945651> as **Collected** and I will message you: ```when the 30 day cooldown expires```")
        embed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
        event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
          Button.success("galthenSet", "Collected"),
          Button.danger("galthenRemove", "Clear").asDisabled
        ).queue()
      //
      case Some(satchelTimeList) =>
        val tagList = satchelTimeList.collect {
          case satchel if tagOption.equalsIgnoreCase(satchel.tag) =>
            val when = satchel.when.plusDays(30).toEpochSecond.toString()
            s"<:satchel:1030348072577945651> can be collected by **`${satchel.tag}`** <t:$when:R>"
        }

        val fullList = satchelTimeList.collect {
          case satchel =>
            val when = satchel.when.plusDays(30).toEpochSecond.toString()
            val displayTag = if (satchel.tag == "") s"<@${event.getUser.getId}>" else s"**`${satchel.tag}`**"
            s"<:satchel:1030348072577945651> can be collected by $displayTag <t:$when:R>"
        }

        if (tagOption.isEmpty && fullList.nonEmpty) {
          embed.setTitle("Existing Cooldowns:")
          val descriptionTruncate = fullList.mkString("\n")
          if (descriptionTruncate.length > 4050) {
            val truncatedDescription = descriptionTruncate.substring(0, 4050)
            val lastNewLineIndex = truncatedDescription.lastIndexOf("\n")
            val finalDescription = if (lastNewLineIndex >= 0) truncatedDescription.substring(0, lastNewLineIndex) else truncatedDescription
            embed.setDescription(finalDescription)
          } else {
            embed.setDescription(descriptionTruncate)
          }
          embed.setColor(13773097)
          embed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
          if (fullList.size == 1){
            event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
              Button.success("galthenSet", "Collected").asDisabled,
              Button.danger("galthenRemoveAll", "Clear")
            ).queue()
          } else {
            event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
              Button.secondary("galthenLock", "🔒"),
              Button.danger("galthenRemoveAll", "Clear All").asDisabled
            ).queue()
          }
        } else if (tagOption.nonEmpty && tagList.nonEmpty) { // tag picked up
          embed.setFooter(s"Tag: ${tagOption.toLowerCase}")
          embed.setDescription(tagList.mkString("\n"))
          embed.setColor(9855533)
          event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
            Button.success("galthenSet", "Collected").asDisabled,
            Button.danger("galthenRemove", "Clear")
          ).queue()
          // Add any other modifications to the embed if needed
        } else {
          embed.setColor(178877)
          if (tagOption.nonEmpty) embed.setFooter(s"Tag: ${tagOption.toLowerCase}")
          embed.setDescription("This is a **[Galthen's Satchel](https://tibia.fandom.com/wiki/Galthen's_Satchel)** cooldown tracker.\nMark the <:satchel:1030348072577945651> as **Collected** and I will message you: ```when the 30 day cooldown expires```")
          embed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
          event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
            Button.success("galthenSet", "Collected"),
            Button.danger("galthenRemove", "Clear").asDisabled
          ).queue()
        }
      // /HERE
      case None =>
        embed.setColor(178877)
        if (tagOption.nonEmpty) embed.setFooter(s"Tag: ${tagOption.toLowerCase}")
        embed.setDescription("This is a **[Galthen's Satchel](https://tibia.fandom.com/wiki/Galthen's_Satchel)** cooldown tracker.\nMark the <:satchel:1030348072577945651> as **Collected** and I will message you: ```when the 30 day cooldown expires```")
        embed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
        event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
          Button.success("galthenSet", "Collected"),
          Button.danger("galthenRemove", "Clear").asDisabled
        ).queue()
      //
    }
  }

  private def handleHunted(event: SlashCommandInteractionEvent): Unit = {
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
      case "guild" =>
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
        val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} Invalid subcommand '$subCommand' for `/hunted`.").build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }

  private def handleAllies(event: SlashCommandInteractionEvent): Unit = {
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
        val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} Invalid subcommand '$subCommand' for `/allies`.").build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }

  }

  private def handleNeutrals(event: SlashCommandInteractionEvent): Unit = {
    val subCommand = event.getInteraction.getSubcommandName
    val subcommandGroupName = event.getInteraction.getSubcommandGroup
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val toggleOption: String = options.getOrElse("option", "")
    val worldOption: String = options.getOrElse("world", "")

    if (subcommandGroupName != null) {
      subcommandGroupName match {
        case "tag" =>
          subCommand match {
            case "add" =>
              val typeOption: String = options.getOrElse("type", "")
              val nameOption: String = options.getOrElse("name", "").trim
              val labelOption: String = options.getOrElse("label", "").replaceAll("[^a-zA-Z0-9\\s]", "").trim
              val emojiOption: String = options.getOrElse("emoji", "").trim
              if (labelOption == "" || emojiOption == ""){
                val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} You must supply a **label** and **emoji** when tagging a guild or player.").setColor(3092790).build()
                event.getHook.sendMessageEmbeds(embed).queue()
              } else {

                // default emoji regex
                val emojiPattern = """/<a?:.+?:\d{18}>|\\p{Extended_Pictographic}/"""
                val matched = emojiOption.matches(emojiPattern)
                if (matched) {
                  BotApp.addOnlineListCategory(event, typeOption, nameOption, labelOption, emojiOption, embed => {
                    event.getHook.sendMessageEmbeds(embed).queue()
                  })
                } else {
                  val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} The provided emoji is invalid - use a standard discord emoji.").setColor(3092790).build()
                  event.getHook.sendMessageEmbeds(embed).queue()
                }
              }
            case "remove" =>
              val typeOption: String = options.getOrElse("type", "")
              val nameOption: String = options.getOrElse("name", "").trim
              val embed = BotApp.removeOnlineListCategory(event, typeOption, nameOption)
              event.getHook.sendMessageEmbeds(embed).queue()
            case "clear" =>
              val labelOption: String = options.getOrElse("label", "").replaceAll("[^a-zA-Z0-9\\s]", "").trim
              val embed = BotApp.clearOnlineListCategory(event, labelOption)
              event.getHook.sendMessageEmbeds(embed).queue()
            case "list" =>
              val embeds = BotApp.listOnlineListCategory(event)
              embeds.foreach { embed =>
                event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue()
              }
          }
        case _ =>
          val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} Invalid subcommandGroup '$subcommandGroupName' for `/neutral`.").setColor(3092790).build()
          event.getHook.sendMessageEmbeds(embed).queue()
      }
    } else {
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
          val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} Invalid subcommand '$subCommand' for `/neutral`.").setColor(3092790).build()
          event.getHook.sendMessageEmbeds(embed).queue()
      }
    }
  }

  private def handleFullbless(event: SlashCommandInteractionEvent): Unit = {
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val worldOption: String = options.getOrElse("world", "")
    val levelOption: Int = options.get("level").map(_.toInt).getOrElse(250)

    val embed = BotApp.fullblessLevel(event, worldOption, levelOption)
    event.getHook.sendMessageEmbeds(embed).queue()
  }

  private def handleFilter(event: SlashCommandInteractionEvent): Unit = {
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
        val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} Invalid subcommand '$subCommand' for `/filter`.").build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }

  private def handleAdmin(event: SlashCommandInteractionEvent): Unit = {
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
        val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} Invalid subcommand '$subCommand' for `/admin`.").build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }

  private def handleExiva(event: SlashCommandInteractionEvent): Unit = {
    val subCommand = event.getInteraction.getSubcommandName

    subCommand match {
      case "deaths" =>
        val embed = BotApp.exivaList(event)
        event.getHook.sendMessageEmbeds(embed).queue()
      case _ =>
        val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} Invalid subcommand '$subCommand' for `/exiva`.").build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }

  private def handleOnlineList(event: SlashCommandInteractionEvent): Unit = {
    val subCommand = event.getInteraction.getSubcommandName
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val toggleOption: String = options.getOrElse("option", "")

    subCommand match {
      case "list" =>
        val worldOption: String = options.getOrElse("world", "")
        if (toggleOption == "separate") {
          val embed = BotApp.onlineListConfig(event, worldOption, "separate")
          event.getHook.sendMessageEmbeds(embed).queue()
        } else if (toggleOption == "combine") {
          val embed = BotApp.onlineListConfig(event, worldOption, "combine")
          event.getHook.sendMessageEmbeds(embed).queue()
        }
      case _ =>
        val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} Invalid subcommand '$subCommand' for `/online`.").build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }

  private def handleBoosted(event: SlashCommandInteractionEvent): Unit = {
    val subCommand = event.getInteraction.getSubcommandName
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val toggleOption: String = options.getOrElse("option", "")

    if (toggleOption == "disable") { // "disabled"
      val embed = BotApp.boosted(event.getUser.getId, "disable", "")
      event.getHook.sendMessageEmbeds(embed).queue()
    } else if (toggleOption == "list") {
      val embed = BotApp.boosted(event.getUser.getId, "list", "")
      val allCheck = BotApp.boostedList(event.getUser.getId)
      if (allCheck) {
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
      val embed = new EmbedBuilder().setDescription(s"${Config.noEmoji} Invalid option for `/boosted`.").setColor(3092790).build()
      event.getHook.sendMessageEmbeds(embed).queue()
    }
  }

  private def handleHelp(event: SlashCommandInteractionEvent): Unit = {
    val embedBuilder = new EmbedBuilder()
    val descripText = Config.helpText
    embedBuilder.setAuthor("Violent Beams", "https://www.tibia.com/community/?subtopic=characters&name=Violent+Beams", "https://github.com/Leo32onGIT.png")
    embedBuilder.setDescription(descripText)
    embedBuilder.setThumbnail(Config.webHookAvatar)
    embedBuilder.setColor(14397256) // orange for bot auto command
    event.getHook.sendMessageEmbeds(embedBuilder.build()).queue()
  }

  private def handleRepair(event: SlashCommandInteractionEvent): Unit = {
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val worldOption: String = options.getOrElse("world", "")

    val embed = BotApp.repairChannel(event, worldOption)
    event.getHook.sendMessageEmbeds(embed).queue()
  }

}
