package com.tibiabot.interactions

import com.tibiabot.{BotApp, Config, presentation}
import com.tibiabot.BotApp.worldsData
import com.tibiabot.domain.{PendingScreenshot, SatchelStamp}
import com.typesafe.scalalogging.StrictLogging

import java.time.ZonedDateTime
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.{Guild, Member, Role}
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.text.{TextInput, TextInputStyle}
import net.dv8tion.jda.api.interactions.modals.Modal

import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Handles all button-click interactions (galthen, boosted, screenshot nav,
 *  role toggles). Moved verbatim from BotListener.onButtonInteraction; the
 *  shared pendingScreenshots map is passed in. */
object ButtonHandler extends StrictLogging {
  def handle(event: ButtonInteractionEvent, pendingScreenshots: mutable.Map[String, PendingScreenshot]): Unit = {
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
      val satchelTimeOption: Option[List[SatchelStamp]] = BotApp.galthenService.getStamps(event.getUser.getId)
      satchelTimeOption match {
        case Some(satchelTimeList) =>
          val fullList = satchelTimeList.collect {
            case satchel =>
              val when = satchel.when.plusDays(30).toEpochSecond.toString()
              val displayTag = if (satchel.tag == "") s"<@${event.getUser.getId}>" else s"**`${satchel.tag}`**"
              s"<:satchel:1030348072577945651> can be collected by $displayTag <t:$when:R>"
          } else {
            embed.setColor(178877)
            embed.setDescription("This is a **[Galthen's Satchel](https://www.tibiawiki.com.br/wiki/Galthen's_Satchel)** cooldown tracker.\nMark the <:satchel:1030348072577945651> as **Collected** and I will message you: ```when the 30 day cooldown expires```")
            embed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
            event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
              Button.success("galthenSet", "Collected"),
              Button.danger("galthenRemove", "Clear").asDisabled
            ).queue()
          }
        // /HERE
        case None =>
          embed.setColor(178877)
          embed.setDescription("This is a **[Galthen's Satchel](https://www.tibiawiki.com.br/wiki/Galthen's_Satchel)** cooldown tracker.\nMark the <:satchel:1030348072577945651> as **Collected** and I will message you: ```when the 30 day cooldown expires```")
          embed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
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
      BotApp.galthenService.add(user.getId, ZonedDateTime.now(), tagId)
      val tagDisplay = if (tagId == "") s"<@${event.getUser.getId}>" else s"**`$tagId`**"
      responseText = s"${Config.satchelEmoji} can be collected by $tagDisplay <t:$when:R>"
      val newEmbed = new EmbedBuilder()
      newEmbed.setDescription(responseText)
      newEmbed.setColor(178877)
      event.getHook().editOriginalEmbeds(newEmbed.build()).setComponents().queue();
    } else if (button == "galthenRemove") {
      event.deferEdit().queue()
      BotApp.galthenService.del(user.getId, tagId)
      val tagDisplay = if (tagId == "") s"<@${event.getUser.getId}>" else s"**`$tagId`**"
      responseText = s"${Config.satchelEmoji} cooldown tracker for $tagDisplay has been **Disabled**."
      event.getHook().editOriginalComponents().queue();
      val newEmbed = new EmbedBuilder().setDescription(responseText).setColor(178877).build()
      event.getHook().editOriginalEmbeds(newEmbed).queue();
    } else if (button == "galthenRemoveAll") {
      event.deferEdit().queue()
      BotApp.galthenService.delAll(user.getId)
      responseText = s"${Config.satchelEmoji} cooldown tracker has been **Disabled**."
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
      BotApp.galthenService.add(user.getId, ZonedDateTime.now(), tagId)
      val tagDisplay = if (tagId == "") s"<@${event.getUser.getId}>" else s"**`$tagId`**"
      responseText = s"${Config.satchelEmoji} can be collected by $tagDisplay <t:$when:R>"
      event.getHook().editOriginalComponents().queue();
      val newEmbed = new EmbedBuilder().setDescription(responseText).setColor(178877).setFooter("You will be sent a message when the cooldown expires").build()
      event.getHook().editOriginalEmbeds(newEmbed).queue()
    } else if (button == "galthenClear") { // WIP
      event.deferEdit().queue()
      event.getHook().editOriginalComponents().queue()
    } else if (button == "galthenAdd") {
      val inputWindow = TextInput.create("galthen add", "Tag/Name for this cooldown", TextInputStyle.SHORT)
        .setPlaceholder("Character Name or Tag to Add")
        .build()
      val modal = Modal.create("add galthen", "Add a Galthen Satchel cooldown").addComponents(ActionRow.of(inputWindow)).build()
      event.replyModal(modal).queue()
    } else if (button == "galthenButtonRem") {
      val inputWindow = TextInput.create("galthen rem", "Tag/Name for the cooldown", TextInputStyle.SHORT)
        .setPlaceholder("Character Name or Tag to Remove")
        .build()
      val modal = Modal.create("rem galthen", "Remove a Galthen Satchel cooldown").addComponents(ActionRow.of(inputWindow)).build()
      event.replyModal(modal).queue()
    } else if (button == "boosted") {
      event.deferReply(true).queue()
      val replyEmbed = new EmbedBuilder()
      replyEmbed.setTitle(s"Receiving boosted boss & creature notifications:")
      responseText = s"Use the `/boosted` command to filter specific `bosses` & `creatures`."
      replyEmbed.setDescription(responseText)
      event.getHook.sendMessageEmbeds(replyEmbed.build()).queue()
    } else if (button == "boosted add") {
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
      val allCheck = BotApp.boostedService.boostedList(event.getUser.getId)
      if (allCheck) {
        val embed = BotApp.boostedService.boosted(event.getUser.getId, "list", "")
        event.getHook.sendMessageEmbeds(embed).setActionRow(
          Button.success("boosted add", "Add").asDisabled,
          Button.danger("boosted remove", "Remove").asDisabled,
          Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOnEmoji))
        ).queue()
      } else {
        val embed = BotApp.boostedService.boosted(event.getUser.getId, "list", "")
        event.getHook.sendMessageEmbeds(embed).setActionRow(
          Button.success("boosted add", "Add"),
          Button.danger("boosted remove", "Remove"),
          Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOffEmoji))
        ).queue()
      }
    } else if (button == "boosted toggle") {
      event.deferEdit().queue()

      val allCheck = BotApp.boostedService.boostedList(event.getUser.getId)
      if (allCheck) {
        val embed = BotApp.boostedService.boosted(event.getUser.getId, "toggle", "all")
        event.getHook.editOriginalEmbeds(embed).setActionRow(
          Button.success("boosted add", "Add"),
          Button.danger("boosted remove", "Remove"),
          Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOffEmoji))
        ).queue()
      } else {
        val embed = BotApp.boostedService.boosted(event.getUser.getId, "toggle", "all")
        event.getHook.editOriginalEmbeds(embed).setActionRow(
          Button.success("boosted add", "Add").asDisabled,
          Button.danger("boosted remove", "Remove").asDisabled,
          Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOnEmoji))
        ).queue()
      }
    } else if (button == "galthen default") {
      event.deferReply(true).queue()
      val embed = new EmbedBuilder()

      val satchelTimeOption: Option[List[SatchelStamp]] = BotApp.galthenService.getStamps(event.getUser.getId)
      satchelTimeOption match {
        //
        case Some(satchelTimeList) if satchelTimeList.isEmpty =>
          embed.setColor(3092790)
          embed.setDescription(s"Mark the ${Config.satchelEmoji} as **Collected** and I will message you when the 30 day cooldown expires.")
          event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
            Button.success("galthenSet", "Collected").withEmoji(Emoji.fromFormatted(Config.satchelEmoji))
          ).queue()
        //
        case Some(satchelTimeList) =>
          val fullList = satchelTimeList.collect {
            case satchel =>
              val when = satchel.when.plusDays(30).toEpochSecond.toString()
              val displayTag = if (satchel.tag == "") s"<@${event.getUser.getId}>" else s"**`${satchel.tag}`**"
              s"${Config.satchelEmoji} can be collected by $displayTag <t:$when:R>"
          }
          if (fullList.nonEmpty) {
            embed.setTitle("Existing Cooldowns:")
            embed.setDescription(presentation.GalthenEmbeds.truncate(fullList))
            embed.setColor(3092790)
            if (fullList.size == 1){
              event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
                Button.success("galthenAdd", "Add Cooldown").withEmoji(Emoji.fromFormatted(Config.satchelEmoji)), //WIP
                Button.danger("galthenRemoveAll", "Remove")
              ).queue()
            } else {
              event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
                Button.success("galthenAdd", "Add Cooldown").withEmoji(Emoji.fromFormatted(Config.satchelEmoji)),
                Button.danger("galthenButtonRem", "Remove"),
                Button.secondary("galthenRemoveAll", "Clear All")
              ).queue()
            }
          } else {
            embed.setColor(3092790)
            embed.setDescription(s"Mark the ${Config.satchelEmoji} as **Collected** and I will message you when the 30 day cooldown expires.")
            event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
              Button.success("galthenSet", "Collected").withEmoji(Emoji.fromFormatted(Config.satchelEmoji))
            ).queue()
          }
        // /HERE
        case None =>
          embed.setColor(3092790)
          embed.setDescription(s"Mark the ${Config.satchelEmoji} as **Collected** and I will message you when the 30 day cooldown expires.")
          event.getHook.sendMessageEmbeds(embed.build()).addActionRow(
            Button.success("galthenSet", "Collected").withEmoji(Emoji.fromFormatted(Config.satchelEmoji))
          ).queue()
        //
      }
    } else if (button == "fullbless") {
        event.deferReply(true).queue()
        val world = title.replace(":crossed_swords:", "").trim()
        val worldConfigData = BotApp.worldRetrieveConfig(guild, world)
        val role = guild.getRoleById(worldConfigData("fullbless_role"))
        if (role != null) {
          guild.retrieveMemberById(user.getId).queue { member =>
            val hasRole = member.getRoles.contains(role)
            val action =
              if (hasRole) guild.removeRoleFromMember(member, role)
              else guild.addRoleToMember(member, role)

            action.queue(
              _ => {
                val msg =
                  if (hasRole)
                    s":gear: You have been removed from the <@&${role.getId}> role."
                  else
                    s":gear: You have been added to the <@&${role.getId}> role."

                event.getHook.sendMessageEmbeds(new EmbedBuilder().setDescription(msg).build()).queue()
              },
              _ => ()
            )
          }
        }
    } else if (button == "nemesis") {
      event.deferReply(true).queue()
      val world = title.replace(":crossed_swords:", "").trim()
      val worldConfigData = BotApp.worldRetrieveConfig(guild, world)
      val role = guild.getRoleById(worldConfigData("nemesis_role"))
      if (role != null) {
        guild.retrieveMemberById(user.getId).queue { member =>
          val hasRole = member.getRoles.contains(role)
          val action =
            if (hasRole) guild.removeRoleFromMember(member, role)
            else guild.addRoleToMember(member, role)

          action.queue(
            _ => {
              val msg =
                if (hasRole)
                  s":gear: You have been removed from the <@&${role.getId}> role."
                else
                  s":gear: You have been added to the <@&${role.getId}> role."

              event.getHook.sendMessageEmbeds(new EmbedBuilder().setDescription(msg).build()).queue()
            },
            _ => ()
          )
        }
      }
    } else if (button == "allypk") {
      event.deferReply(true).queue()
      val world = title.replace(":crossed_swords:", "").trim
      val worldConfigData = BotApp.worldRetrieveConfig(guild, world)
      val role = guild.getRoleById(worldConfigData("allypk_role"))
      if (role != null) {
        guild.retrieveMemberById(user.getId).queue { member =>
          val hasRole = member.getRoles.contains(role)
          val action =
            if (hasRole) guild.removeRoleFromMember(member, role)
            else guild.addRoleToMember(member, role)

          action.queue(
            _ => {
              val msg =
                if (hasRole)
                  s":gear: You have been removed from the <@&${role.getId}> role."
                else
                  s":gear: You have been added to the <@&${role.getId}> role."

              event.getHook.sendMessageEmbeds(new EmbedBuilder().setDescription(msg).build()).queue()
            },
            _ => ()
          )
        }
      }
    } else if (button == "masslog") {
      event.deferReply(true).queue()
      val world = title.replace(":crossed_swords:", "").trim
      val worldConfigData = BotApp.worldRetrieveConfig(guild, world)
      val role = guild.getRoleById(worldConfigData("masslog_role"))
      if (role != null) {
        guild.retrieveMemberById(user.getId).queue { member =>
          val hasRole = member.getRoles.contains(role)
          val action =
            if (hasRole) guild.removeRoleFromMember(member, role)
            else guild.addRoleToMember(member, role)

          action.queue(
            _ => {
              val msg =
                if (hasRole)
                  s":gear: You have been removed from the <@&${role.getId}> role."
                else
                  s":gear: You have been added to the <@&${role.getId}> role."

              event.getHook.sendMessageEmbeds(new EmbedBuilder().setDescription(msg).build()).queue()
            },
            _ => ()
          )
        }
      }
    } else if (button.startsWith("death_screenshot_")) {
      // Handle death screenshot button clicks
      val buttonParts = button.split("_")
      if (buttonParts.length >= 4) {
        val charName = buttonParts(2)
        val deathTime = buttonParts(3).toLong
        val messageId = event.getInteraction.getMessage.getId

        // Get world from guild configuration
        val worldOpt = worldsData.get(guild.getId).flatMap(_.headOption).map(_.name)

        worldOpt match {
          case Some(world) =>
            // Store pending screenshot request
            val pendingKey = s"${event.getUser.getId}_${guild.getId}"
            pendingScreenshots.put(pendingKey, PendingScreenshot(charName, deathTime, messageId, guild.getId, world, event.getUser.getId, event.getChannel.getId))

            // Send DM to user
            event.getUser.openPrivateChannel().queue(privateChannel => {
              val embed = new EmbedBuilder()
                .setColor(3092790)
                .setTitle(s"Upload Screenshot for ${charName}")
                .setDescription(s"Please upload an image file (PNG, JPG, GIF, Webp) to this DM within the next 5 minutes.\n\n" +
                              s"The screenshot will be added to the death message for **[${charName}](${BotApp.charUrl(charName)})** in **${guild.getName}**.")
                .setFooter("You can also paste an image directly from your clipboard")
                .build()

              privateChannel.sendMessageEmbeds(embed).queue(
                _ => {
                  // Confirm to user that DM was sent
                  event.reply(s"${Config.yesEmoji} Screenshot upload request sent to your DMs for **[${charName}](${BotApp.charUrl(charName)})**.").setEphemeral(true).queue()
                },
                error => {
                  // Fallback if DM fails
                  val fallbackEmbed = new EmbedBuilder()
                    .setColor(16711680) // Red color
                    .setTitle(s"Upload Screenshot for ${charName}")
                    .setDescription(s"Could not send you a DM. Please upload an image file (PNG, JPG, GIF, Webp) in this channel within the next 5 minutes, If you wish to cancel, simply respond with the word **cancel**.\n\n" +
                                  s"The screenshot will be added to the death message for **[${charName}](${BotApp.charUrl(charName)})**.")
                    .setFooter("You can also paste an image directly from your clipboard")
                    .build()

                  event.reply("").addEmbeds(fallbackEmbed).setEphemeral(true).queue()
                }
              )
            })

            // Set a timeout to remove the pending request after 5 minutes
            scala.concurrent.ExecutionContext.global.execute(() => {
              Thread.sleep(300000) // 5 minutes
              pendingScreenshots.remove(pendingKey)
            })

          case None =>
            responseText = s"${Config.noEmoji} Could not determine world for this guild."
            val replyEmbed = new EmbedBuilder().setDescription(responseText).build()
            event.reply("").addEmbeds(replyEmbed).setEphemeral(true).queue()
        }
      } else {
        responseText = s"${Config.noEmoji} Invalid button format."
        val replyEmbed = new EmbedBuilder().setDescription(responseText).build()
        event.reply("").addEmbeds(replyEmbed).setEphemeral(true).queue()
      }
    } else if (button.startsWith("prev_screenshot_") || button.startsWith("next_screenshot_")) {
      event.deferEdit().queue()

      val buttonParts = button.split("_")
      if (buttonParts.length >= 6) {
        val charName = buttonParts(2)
        val deathTime = buttonParts(3).toLong
        val messageId = event.getInteraction.getMessage.getId
        val currentIndex = buttonParts(5).toInt

        // Get world from guild configuration
        val worldOpt = worldsData.get(guild.getId).flatMap(_.headOption).map(_.name)

        worldOpt.foreach { world =>
          val screenshots = BotApp.getDeathScreenshots(guild.getId, world, charName, deathTime)

          if (screenshots.nonEmpty) {
            val newIndex = if (button.startsWith("prev_")) {
              if (currentIndex > 0) currentIndex - 1 else screenshots.length - 1
            } else {
              if (currentIndex < screenshots.length - 1) currentIndex + 1 else 0
            }

            val currentScreenshot = screenshots(newIndex)

            // Preserve the original death message embed and just update the image
            val originalEmbed = event.getMessage.getEmbeds.get(0)
            val embed = new EmbedBuilder(originalEmbed)
              .setImage(currentScreenshot.screenshotUrl)
              .setFooter(s"Screenshot added by ${currentScreenshot.addedName} • ${newIndex + 1}/${screenshots.length}")
              .build()

            val components = if (screenshots.length > 1) {
              val baseButtons = List(
                Button.secondary(s"death_screenshot_${charName}_${deathTime}_${messageId}", "Add Screenshot"),
                Button.primary(s"prev_screenshot_${charName}_${deathTime}_${messageId}_${newIndex}", "◀"),
                Button.secondary(s"screenshot_info_${charName}_${deathTime}_${messageId}", s"${newIndex + 1}/${screenshots.length}").asDisabled(),
                Button.primary(s"next_screenshot_${charName}_${deathTime}_${messageId}_${newIndex}", "▶")
              )
              val buttonsWithDelete = baseButtons :+ Button.danger(s"delete_screenshot_${charName}_${deathTime}_${messageId}_${newIndex}", "🗑️")
              List(ActionRow.of(buttonsWithDelete: _*))
            } else {
              val baseButtons = List(Button.secondary(s"death_screenshot_${charName}_${deathTime}_${messageId}", "Add Screenshot"))
              val buttonsWithDelete = baseButtons :+ Button.danger(s"delete_screenshot_${charName}_${deathTime}_${messageId}_${newIndex}", "🗑️")
              List(ActionRow.of(buttonsWithDelete: _*))
            }

            event.getHook.editOriginalEmbeds(embed).setComponents(components: _*).queue()
          }
        }
      }
    } else if (button.startsWith("delete_screenshot_")) {
      event.deferEdit().queue()

      val buttonParts = button.split("_")
      if (buttonParts.length >= 6) {
        val charName = buttonParts(2)
        val deathTime = buttonParts(3).toLong
        val messageId = event.getInteraction.getMessage.getId
        val currentIndex = buttonParts(5).toInt

        val guild = event.getGuild
        val user = event.getUser
        val originalMessage = event.getMessage

        // Get current screenshots to find the URL of the screenshot to delete
        val screenshots = BotApp.getDeathScreenshots(guild.getId, guild.getName, charName, deathTime)
        if (screenshots.nonEmpty && currentIndex < screenshots.length) {
          val screenshotToDelete = screenshots(currentIndex)

          // Attempt to delete the screenshot
          if (BotApp.deleteDeathScreenshot(guild.getId, guild.getName, charName, deathTime, screenshotToDelete.screenshotUrl, user.getId)) {
            // Successfully deleted, update the embed
            val updatedScreenshots = BotApp.getDeathScreenshots(guild.getId, guild.getName, charName, deathTime)
            val embeds = originalMessage.getEmbeds

            if (embeds.size() > 0 && updatedScreenshots.nonEmpty) {
              // Still have screenshots, show another one
              val newIndex = Math.min(currentIndex, updatedScreenshots.length - 1)
              val newCurrentScreenshot = updatedScreenshots(newIndex)

              val originalEmbed = embeds.get(0)
              val updatedEmbed = new EmbedBuilder(originalEmbed)
                .setImage(newCurrentScreenshot.screenshotUrl)
                .setFooter(s"Screenshot added by ${newCurrentScreenshot.addedName} • ${newIndex + 1}/${updatedScreenshots.length}")
                .build()

              val components = if (updatedScreenshots.length > 1) {
                val baseButtons = List(
                  Button.secondary(s"death_screenshot_${charName}_${deathTime}_${messageId}", "Add Screenshot"),
                  Button.primary(s"prev_screenshot_${charName}_${deathTime}_${messageId}_${newIndex}", "◀"),
                  Button.secondary(s"screenshot_info_${charName}_${deathTime}_${messageId}", s"${newIndex + 1}/${updatedScreenshots.length}").asDisabled(),
                  Button.primary(s"next_screenshot_${charName}_${deathTime}_${messageId}_${newIndex}", "▶")
                )
                val buttonsWithDelete = baseButtons :+ Button.danger(s"delete_screenshot_${charName}_${deathTime}_${messageId}_${newIndex}", "🗑️")
                List(ActionRow.of(buttonsWithDelete: _*))
              } else {
                val baseButtons = List(Button.secondary(s"death_screenshot_${charName}_${deathTime}_${messageId}", "Add Screenshot"))
                val buttonsWithDelete = baseButtons :+ Button.danger(s"delete_screenshot_${charName}_${deathTime}_${messageId}_${newIndex}", "🗑️")
                List(ActionRow.of(buttonsWithDelete: _*))
              }

              event.getHook.editOriginalEmbeds(updatedEmbed).setComponents(components: _*).queue()
            } else {
              // No more screenshots, remove image and show only add button
              val originalEmbed = embeds.get(0)
              val updatedEmbed = new EmbedBuilder(originalEmbed)
                .setImage(null)
                .setFooter(null)
                .build()

              val addButton = List(ActionRow.of(Button.secondary(s"death_screenshot_${charName}_${deathTime}_${messageId}", "Add Screenshot")))
              event.getHook.editOriginalEmbeds(updatedEmbed).setComponents(addButton: _*).queue()
            }
          } else {
            // Failed to delete - not the author or other error
            event.getHook.sendMessage(s"${Config.noEmoji} You can only delete screenshots you uploaded.").setEphemeral(true).queue()
          }
        } else {
          event.getHook.sendMessage(s"${Config.noEmoji} Screenshot not found.").setEphemeral(true).queue()
        }
      } else {
        event.getHook.sendMessage(s"${Config.noEmoji} Invalid button format.").setEphemeral(true).queue()
      }
    } else {
      event.deferReply(true).queue()
      if (title != "") {
        val roleType = if (title.contains(":crossed_swords:")) "fullbless" else if (title.contains(s"${Config.nemesisEmoji}")) "nemesis" else if (title.contains(s"${Config.hazardEmoji}")) "allypk" else ""
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
                    adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Warning_Sign.gif")
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
                    adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Warning_Sign.gif")
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
                    adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Warning_Sign.gif")
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
                    adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Warning_Sign.gif")
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
        } else if (roleType == "allypk") {
          val world = title.replace(s"${Config.hazardEmoji}", "").trim()
          val worldConfigData = BotApp.worldRetrieveConfig(guild, world)
          val role = guild.getRoleById(worldConfigData("allypk_role"))
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
                    adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Warning_Sign.gif")
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
                    adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Warning_Sign.gif")
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
      }
      val replyEmbed = new EmbedBuilder().setDescription(responseText).build()
      event.getHook.sendMessageEmbeds(replyEmbed).queue()
    }
  }
}
