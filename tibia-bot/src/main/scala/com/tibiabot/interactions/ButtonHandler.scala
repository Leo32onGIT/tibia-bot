package com.tibiabot.interactions

import com.tibiabot.{BotApp, Config, presentation}
import com.tibiabot.cooldowns.CooldownIds
import com.tibiabot.domain.{CooldownKind, PendingScreenshot}
import com.tibiabot.presentation.CooldownEmbeds
import com.tibiabot.state.StreamState
import com.typesafe.scalalogging.StrictLogging

import java.time.ZonedDateTime
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.{TextInput, TextInputStyle}
import net.dv8tion.jda.api.modals.Modal

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import com.tibiabot.presentation.Names

/** Handles all button-click interactions (cooldowns, boosted, screenshot nav,
 *  role toggles). Moved verbatim from BotListener.onButtonInteraction; the
 *  shared pendingScreenshots map is passed in. */
object ButtonHandler extends StrictLogging {
  def handle(event: ButtonInteractionEvent, pendingScreenshots: mutable.Map[String, PendingScreenshot], streamState: StreamState): Unit = {
    val embed = event.getInteraction.getMessage.getEmbeds
    val title = if (!embed.isEmpty) embed.get(0).getTitle else ""
    val button = event.getComponentId
    val guild = event.getGuild
    val user = event.getUser
    var responseText = s"${Config.noEmoji} An unknown error occurred, please try again."

    val footer = if (!embed.isEmpty) Option(embed.get(0).getFooter) else None
    val tagId = footer.map(_.getText.replace("Tag: ", "")).getOrElse("")

    if (CooldownIds.handles(button)) {
      CooldownIds.parse(button).foreach { case (kind, action) => cooldown(event, kind, action, tagId) }
    } else if (button == "boosted add") {
      val inputWindow = TextInput.create("boosted add", TextInputStyle.SHORT)
        .setPlaceholder("Grand Master Oberon")
        .build()
      val modal = Modal.create("add modal", "Add a Boss or Creature").addComponents(Label.of("Boss or Creature name", inputWindow)).build()
      event.replyModal(modal).queue()
    } else if (button == "boosted remove") {

      val inputWindow = TextInput.create("boosted remove", TextInputStyle.SHORT).build()
      val modal = Modal.create("remove modal", "Add Server Save Notificiations:").addComponents(Label.of("Boss or Creature name", inputWindow)).build()
      event.replyModal(modal).queue()
    } else if (button == "boosted list") {
      event.deferReply(true).queue()
      val allCheck = BotApp.boostedService.boostedList(event.getUser.getId)
      if (allCheck) {
        val embed = BotApp.boostedService.boosted(event.getUser.getId, "list", "")
        event.getHook.sendMessageEmbeds(embed).setComponents(ActionRow.of(
          Button.success("boosted add", "Add").asDisabled,
          Button.danger("boosted remove", "Remove").asDisabled,
          Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOnEmoji))
        )).queue()
      } else {
        val embed = BotApp.boostedService.boosted(event.getUser.getId, "list", "")
        event.getHook.sendMessageEmbeds(embed).setComponents(ActionRow.of(
          Button.success("boosted add", "Add"),
          Button.danger("boosted remove", "Remove"),
          Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOffEmoji))
        )).queue()
      }
    } else if (button == "boosted toggle") {
      event.deferEdit().queue()

      val allCheck = BotApp.boostedService.boostedList(event.getUser.getId)
      if (allCheck) {
        val embed = BotApp.boostedService.boosted(event.getUser.getId, "toggle", "all")
        event.getHook.editOriginalEmbeds(embed).setComponents(ActionRow.of(
          Button.success("boosted add", "Add"),
          Button.danger("boosted remove", "Remove"),
          Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOffEmoji))
        )).queue()
      } else {
        val embed = BotApp.boostedService.boosted(event.getUser.getId, "toggle", "all")
        event.getHook.editOriginalEmbeds(embed).setComponents(ActionRow.of(
          Button.success("boosted add", "Add").asDisabled,
          Button.danger("boosted remove", "Remove").asDisabled,
          Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOnEmoji))
        )).queue()
      }
    } else if (button == "observer add") {
      val tokenInput = TextInput.create(ObserverModals.TokenField, TextInputStyle.SHORT)
        .setPlaceholder("FNP68")
        .setRequired(true)
        .build()
      val modal = Modal.create(ObserverModals.ModalId, "Link your Tibia Observer token")
        .addComponents(Label.of("Token from tibia.com", tokenInput)).build()
      event.replyModal(modal).queue()
    } else if (button == "observer remove") {
      event.deferEdit().queue()
      Option(event.getGuild).foreach(guild => BotApp.observerService.unlink(guild.getId, event.getUser.getId))
      val token = Option(event.getGuild).flatMap(guild => BotApp.observerService.statusFor(guild.getId, event.getUser.getId))
      event.getHook.editOriginalEmbeds(presentation.ObserverEmbeds.panel(token))
        .setComponents(presentation.ObserverEmbeds.controls(token)).queue()
    } else if (button == "fullbless") {
        event.deferReply(true).queue()
        val world = presentation.RoleCard.worldOf(event.getMessage, BotApp.worldsData.getOrElse(guild.getId, List())).getOrElse("")
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
      val world = presentation.RoleCard.worldOf(event.getMessage, BotApp.worldsData.getOrElse(guild.getId, List())).getOrElse("")
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
      val world = presentation.RoleCard.worldOf(event.getMessage, BotApp.worldsData.getOrElse(guild.getId, List())).getOrElse("")
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
    } else if (button.startsWith("death_exiva_")) {
      event.deferEdit().queue()
      // Everything the block needs is in the post already — the killers are the
      // only names a death description links to a character page — so the press
      // reads them back out rather than relying on anything the bot still
      // remembers about a death it may have posted weeks ago.
      val embeds = event.getMessage.getEmbeds
      if (!embeds.isEmpty) {
        val original = embeds.get(0)
        val description = Option(original.getDescription).getOrElse("")
        val section = presentation.ExivaList.sectionFor(description)
        if (section.isEmpty) {
          // Two people pressed at once, or the post names nobody to chase. The
          // button has nothing to add either way.
          event.getHook.editOriginalComponents().queue()
        } else {
          val updated = new EmbedBuilder(original).setDescription(description + section).build()
          // The block stays once written, so the button is done. It is the only
          // component an ally death carries, hence clearing rather than filtering.
          event.getHook.editOriginalEmbeds(updated).setComponents().queue()
        }
      }
    } else if (button.startsWith("death_screenshot_")) {
      val buttonParts = button.split("_")
      if (buttonParts.length >= 4) {
        val charName = buttonParts(2)
        val deathTime = buttonParts(3).toLong
        val messageId = event.getInteraction.getMessage.getId

        val worldOpt = streamState.worldsData.get(guild.getId).flatMap(_.headOption).map(_.name)

        worldOpt match {
          case Some(world) =>
            val pendingKey = s"${event.getUser.getId}_${guild.getId}"
            pendingScreenshots.put(pendingKey, PendingScreenshot(charName, deathTime, messageId, guild.getId, world, event.getUser.getId, event.getChannel.getId))

            // Reached from either step of the DM. Discord can refuse the channel
            // open itself — "no mutual guilds" (50278) arrives there as readily as
            // on the send — and an open with no failure consumer left the click
            // with no answer at all beyond JDA's own logged ERROR.
            def promptInChannel(): Unit = {
              val fallbackEmbed = new EmbedBuilder()
                .setColor(16711680) // red
                .setTitle(s"Upload Screenshot for ${charName}")
                .setDescription(s"Could not send you a DM. Please upload an image file (PNG, JPG, GIF, Webp) in this channel within the next 5 minutes, If you wish to cancel, simply respond with the word **cancel**.\n\n" +
                              s"The screenshot will be added to the death message for **[${charName}](${BotApp.charUrl(charName)})**.")
                .setFooter("You can also paste an image directly from your clipboard")
                .build()

              event.reply("").addEmbeds(fallbackEmbed).setEphemeral(true).queue()
            }

            event.getUser.openPrivateChannel().queue((privateChannel: PrivateChannel) => {
              val embed = new EmbedBuilder()
                .setColor(presentation.Embeds.BrandColor)
                .setTitle(s"Upload Screenshot for ${charName}")
                .setDescription(s"Please upload an image file (PNG, JPG, GIF, Webp) to this DM within the next 5 minutes.\n\n" +
                              s"The screenshot will be added to the death message for **[${charName}](${BotApp.charUrl(charName)})** in **${guild.getName}**.")
                .setFooter("You can also paste an image directly from your clipboard")
                .build()

              privateChannel.sendMessageEmbeds(embed).queue(
                _ => {
                  event.reply(s"${Config.yesEmoji} Screenshot upload request sent to your DMs for **[${charName}](${BotApp.charUrl(charName)})**.").setEphemeral(true).queue()
                },
                _ => promptInChannel()
              )
            }, (_: Throwable) => promptInChannel())

            // Expire the pending request after 5 minutes
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

        val worldOpt = streamState.worldsData.get(guild.getId).flatMap(_.headOption).map(_.name)

        worldOpt.foreach { world =>
          val screenshots = BotApp.getDeathScreenshots(guild.getId, world, charName, deathTime)

          if (screenshots.nonEmpty) {
            val newIndex = if (button.startsWith("prev_")) {
              if (currentIndex > 0) currentIndex - 1 else screenshots.length - 1
            } else {
              if (currentIndex < screenshots.length - 1) currentIndex + 1 else 0
            }

            val currentScreenshot = screenshots(newIndex)

            // Copy the existing death embed, only swapping the image/footer
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
              List(ActionRow.of(buttonsWithDelete.asJava))
            } else {
              val baseButtons = List(Button.secondary(s"death_screenshot_${charName}_${deathTime}_${messageId}", "Add Screenshot"))
              val buttonsWithDelete = baseButtons :+ Button.danger(s"delete_screenshot_${charName}_${deathTime}_${messageId}_${newIndex}", "🗑️")
              List(ActionRow.of(buttonsWithDelete.asJava))
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

        val screenshots = BotApp.getDeathScreenshots(guild.getId, guild.getName, charName, deathTime)
        if (screenshots.nonEmpty && currentIndex < screenshots.length) {
          val screenshotToDelete = screenshots(currentIndex)

          if (BotApp.deleteDeathScreenshot(guild.getId, charName, deathTime, screenshotToDelete.screenshotUrl, user.getId)) {
            val updatedScreenshots = BotApp.getDeathScreenshots(guild.getId, guild.getName, charName, deathTime)
            val embeds = originalMessage.getEmbeds

            if (embeds.size() > 0 && updatedScreenshots.nonEmpty) {
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
                List(ActionRow.of(buttonsWithDelete.asJava))
              } else {
                val baseButtons = List(Button.secondary(s"death_screenshot_${charName}_${deathTime}_${messageId}", "Add Screenshot"))
                val buttonsWithDelete = baseButtons :+ Button.danger(s"delete_screenshot_${charName}_${deathTime}_${messageId}_${newIndex}", "🗑️")
                List(ActionRow.of(buttonsWithDelete.asJava))
              }

              event.getHook.editOriginalEmbeds(updatedEmbed).setComponents(components: _*).queue()
            } else {
              // No screenshots left: drop the image and show only the add button
              val originalEmbed = embeds.get(0)
              val updatedEmbed = new EmbedBuilder(originalEmbed)
                .setImage(null)
                .setFooter(null)
                .build()

              val addButton = List(ActionRow.of(Button.secondary(s"death_screenshot_${charName}_${deathTime}_${messageId}", "Add Screenshot")))
              event.getHook.editOriginalEmbeds(updatedEmbed).setComponents(addButton: _*).queue()
            }
          } else {
            // deleteDeathScreenshot rejects anyone but the uploader or a server admin
            event.getHook.sendMessage(s"${Config.noEmoji} You can only delete screenshots you uploaded.").setEphemeral(true).queue()
          }
        } else {
          event.getHook.sendMessage(s"${Config.noEmoji} Screenshot not found.").setEphemeral(true).queue()
        }
      } else {
        event.getHook.sendMessage(s"${Config.noEmoji} Invalid button format.").setEphemeral(true).queue()
      }
    } else if (button.startsWith("paywall_reassign_yes_")) {
      event.deferEdit().queue()
      val world = button.stripPrefix("paywall_reassign_yes_")
      val guildId = guild.getId
      // Re-checked here, not just trusted from when /setup was run — guards
      // against a race (someone else reassigns first, or the clicker's own
      // seat count changes) in the window between the prompt and the click.
      if (BotApp.paywallService.canReassignSeat(user.getId, guildId, world)) {
        BotApp.paywallService.reassignSeat(user.getId, user.getName, guildId, world)
        val embed = new EmbedBuilder()
          .setDescription(s"${Config.yesEmoji} Tracking for **$world** has been reassigned to ${Names.user(user.getName)} and resumed.")
          .setColor(presentation.Embeds.BrandColor)
          .build()
        event.getHook.editOriginalEmbeds(embed).setComponents().queue()
      } else {
        val embed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} This world can no longer be reassigned to you — you may be at your Patreon seat limit, or someone else already took it over.")
          .build()
        event.getHook.editOriginalEmbeds(embed).setComponents().queue()
      }
    } else if (button == "paywall_reassign_no") {
      event.deferEdit().queue()
      event.getHook.editOriginalComponents().queue()
    } else if (button.startsWith("paywall_claim_yes_")) {
      event.deferEdit().queue()
      val world = button.stripPrefix("paywall_claim_yes_")
      val guildId = guild.getId
      // Re-checked here, not just trusted from when /setup was run — guards
      // against a race (the clicker's own seat count changes, or someone
      // else claims it first via /setup) in the window between the prompt
      // and the click.
      if (BotApp.paywallService.canAssignSeat(user.getId, guildId, world)) {
        BotApp.paywallService.assignSeat(user.getId, user.getName, guildId, world)
        val embed = new EmbedBuilder()
          .setDescription(s"${Config.yesEmoji} **$world** has been assigned to ${Names.user(user.getName)}")
          .setColor(presentation.Embeds.BrandColor)
          .build()
        event.getHook.editOriginalEmbeds(embed).setComponents().queue()
      } else {
        val embed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} This world can no longer be assigned to you — you may be at your Patreon seat limit, or someone else already claimed it.")
          .build()
        event.getHook.editOriginalEmbeds(embed).setComponents().queue()
      }
    } else if (button == "paywall_claim_no") {
      event.deferEdit().queue()
      event.getHook.editOriginalComponents().queue()
    } else if (button.startsWith("patreon_release_")) {
      event.deferEdit().queue()
      // /patreon's own release button — unlike the /setup-flow buttons above,
      // this can be clicked from a different guild than the seat itself (the
      // command lists every seat across every server), so the target guildId
      // has to travel in the payload rather than coming from event.getGuild.
      // guildId is a pure-digit snowflake and world never contains an
      // underscore (see PatreonCommands), so splitting on the first '_' is safe.
      val payload = button.stripPrefix("patreon_release_")
      val (targetGuildId, worldRaw) = payload.span(_ != '_')
      val world = worldRaw.stripPrefix("_")
      BotApp.paywallService.releaseSeat(targetGuildId, world)
      val embed = new EmbedBuilder()
        .setDescription(s"${Config.yesEmoji} Your seat for **$world** has been released. Use `/setup` to assign it to a different discord and/or world.")
        .setColor(presentation.Embeds.BrandColor)
        .build()
      event.getHook.editOriginalEmbeds(embed).setComponents().queue()
    } else {
      // Any component not matched above is from a superseded message layout;
      // acknowledge it gracefully instead of leaving the interaction to time out.
      event.deferReply(true).queue()
      val replyEmbed = new EmbedBuilder()
        .setDescription(s"${Config.noEmoji} This button is no longer supported. Please re-run the command that created it.")
        .build()
      event.getHook.sendMessageEmbeds(replyEmbed).queue()
    }
  }

  /** Every cooldown button, for either kind.
   *
   *  The tag, where one matters, comes from the `Tag:` footer of the embed the
   *  press landed on — the only place it survives, since the expiry DM's row is
   *  deleted by the same sweep that sent it. */
  private def cooldown(
    event: ButtonInteractionEvent,
    kind: CooldownKind,
    action: CooldownIds.Action,
    tagId: String
  ): Unit = {
    import CooldownIds.{Action => A}
    val user = event.getUser
    val emoji = CooldownEmbeds.emoji(kind)
    def tagDisplay: String = if (tagId.isEmpty) Names.user(user.getName) else s"**`$tagId`**"
    def note(text: String) =
      new EmbedBuilder().setDescription(text).setColor(presentation.Embeds.BrandColor)
    // Somebody's own card, redrawn with what just changed leading it — see
    // CooldownEmbeds.personal. A press on one of those comes from a message laid
    // out with Discord's layout components, which can only be rewritten as one;
    // the embed replies further down are for messages posted before it existed.
    def card(said: String = "") = CooldownEmbeds.personal(
      k => BotApp.cooldownService.getStamps(user.getId, k).getOrElse(Nil), user.getName, said)
    def onCard: Boolean = event.getMessage.isUsingComponentsV2

    action match {
      case A.Set =>
        event.deferEdit().queue()
        val now = ZonedDateTime.now()
        BotApp.cooldownService.add(user.getId, kind, now, tagId)
        if (onCard)
          event.getHook.editOriginalComponents(card(
            s"${Config.yesEmoji} ${CooldownEmbeds.itemName(kind)} marked **${CooldownEmbeds.doneLabel(kind).toLowerCase}**: " +
              s"ready again <t:${kind.expiresAtEpoch(now)}:R>.")).useComponentsV2().queue()
        else
          event.getHook.editOriginalEmbeds(
            note(s"$emoji can be collected by $tagDisplay <t:${kind.expiresAtEpoch(now)}:R>").build()
          ).setComponents().queue()

      case A.Remind =>
        event.deferEdit().queue()
        val now = ZonedDateTime.now()
        BotApp.cooldownService.add(user.getId, kind, now, tagId)
        event.getHook.editOriginalComponents().queue()
        event.getHook.editOriginalEmbeds(
          note(s"$emoji ${CooldownEmbeds.doneLabel(kind)} for $tagDisplay: ready again <t:${kind.expiresAtEpoch(now)}:R>")
            .setFooter("You will be sent a message when the cooldown expires").build()
        ).queue()

      case A.Remove =>
        event.deferEdit().queue()
        BotApp.cooldownService.del(user.getId, kind, tagId)
        event.getHook.editOriginalComponents().queue()
        event.getHook.editOriginalEmbeds(
          note(s"$emoji cooldown tracker for $tagDisplay has been **Disabled**.").build()).queue()

      case A.RemoveAll =>
        event.deferEdit().queue()
        BotApp.cooldownService.delAll(user.getId, kind)
        if (onCard)
          event.getHook.editOriginalComponents(card(
            s"${Config.yesEmoji} Stopped tracking ${CooldownEmbeds.itemName(kind)}.")).useComponentsV2().queue()
        else {
          event.getHook.editOriginalComponents().queue()
          event.getHook.editOriginalEmbeds(
            note(s"$emoji ${kind.label} cooldown tracker has been **Disabled**.").build()).queue()
        }

      case A.Lock =>
        event.deferEdit().queue()
        event.getHook.editOriginalComponents(ActionRow.of(
          Button.secondary(CooldownIds.button(kind, A.Unlock), "🔓"),
          Button.danger(CooldownIds.button(kind, A.RemoveAll), "Clear All")
        )).queue()

      case A.Unlock =>
        event.deferEdit().queue()
        event.getHook.editOriginalComponents(ActionRow.of(
          Button.secondary(CooldownIds.button(kind, A.Lock), "🔒"),
          Button.danger(CooldownIds.button(kind, A.RemoveAll), "Clear All").asDisabled
        )).queue()

      case A.Dismiss =>
        event.deferEdit().queue()
        event.getHook.editOriginalComponents().queue()

      case A.AddForm    => event.replyModal(cooldownForm(kind, adding = true)).queue()
      case A.RemoveForm => event.replyModal(cooldownForm(kind, adding = false)).queue()

      // The tracker in the notifications channel is the same message for everyone,
      // so either of its buttons opens the presser's own card, just for them —
      // both items at once, whichever was pressed.
      case A.Panel | A.Open =>
        event.deferReply(true).queue()
        event.getHook.sendMessageComponents(card()).useComponentsV2().queue()
    }
  }

  private def cooldownForm(kind: CooldownKind, adding: Boolean): Modal = {
    val verb = if (adding) "Add" else "Remove"
    val input = TextInput.create(CooldownIds.field(kind, adding), TextInputStyle.SHORT)
      .setPlaceholder(s"Character Name or Tag to $verb")
      .build()
    Modal.create(CooldownIds.modal(kind, adding), s"$verb a ${kind.label} cooldown")
      .addComponents(Label.of(s"Tag/Name for ${if (adding) "this" else "the"} cooldown", input))
      .build()
  }
}
