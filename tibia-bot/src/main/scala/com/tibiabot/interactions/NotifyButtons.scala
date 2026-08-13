package com.tibiabot.interactions

import com.tibiabot.domain.{BountySub, MasslogSub, MuteScale, NotifySettings}
import com.tibiabot.notifications.NotifyIds
import com.tibiabot.presentation.{Embeds, NotifyEmbeds}
import com.tibiabot.{BotApp, Config}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.components.label.{Label, LabelChildComponent}
import net.dv8tion.jda.api.components.selections.{SelectOption, StringSelectMenu}
import net.dv8tion.jda.api.components.textinput.{TextInput, TextInputStyle}
import net.dv8tion.jda.api.entities.{Guild, Role}
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.modals.Modal

import java.time.Instant
import scala.jdk.CollectionConverters._

/** The two notification autoroles' buttons: the pair under the notifications
 *  embed, and the controls under every DM they send.
 *
 *  Split out of [[ButtonHandler]]'s if/else chain the way the respawn buttons
 *  were, because this family shares an id format and a rule — a press either
 *  opens a form or rewrites the message it came from, and the DM controls carry
 *  the subscription's row id rather than a guild, since a direct message has no
 *  guild to read.
 */
object NotifyButtons extends StrictLogging {

  def handles(componentId: String): Boolean = NotifyIds.handlesButton(componentId)

  /** Whether this press answers with a modal, and so must not be acknowledged
   *  before it reaches the handler. */
  def opensModal(componentId: String): Boolean = NotifyIds.opensModal(componentId)

  def handle(event: ButtonInteractionEvent): Unit =
    event.getComponentId match {
      case NotifyIds.MasslogButton => openMasslogForm(event)
      case NotifyIds.BountyButton  => openBountyForm(event)
      case other =>
        NotifyIds.parseControl(other) match {
          case Some(NotifyIds.MasslogToggle(id, enable)) => toggleMasslog(event, id, enable)
          case Some(NotifyIds.BountyToggle(id, enable))  => toggleBounty(event, id, enable)
          case Some(NotifyIds.MasslogMute(id))           => openMuteForm(event, id, bounty = false)
          case Some(NotifyIds.BountyMute(id))            => openMuteForm(event, id, bounty = true)
          case Some(NotifyIds.MasslogThreshold(id))      => openThresholdForm(event, id)
          case None =>
            // A control from a deploy whose id format no longer parses. The DM
            // it sits under is old news by then, so say so rather than throw.
            logger.debug(s"Ignoring unparseable notification button id '$other'")
            refuse(event, s"${Config.noEmoji} That button is out of date — the next alert will carry working ones.")
        }
    }

  // --- opening the forms -------------------------------------------------

  /** The world these buttons belong to, read off the embed they sit under — the
   *  same trick the fullbless/nemesis/allypk toggles use, and the reason the two
   *  autorole ids can stay bare and keep working on embeds already posted. */
  private def worldOf(event: ButtonInteractionEvent): String = {
    val embeds = event.getInteraction.getMessage.getEmbeds
    val title = if (!embeds.isEmpty) Option(embeds.get(0).getTitle).getOrElse("") else ""
    title.replace(":crossed_swords:", "").trim
  }

  private def thresholdInput(current: Int): TextInput =
    TextInput.create(NotifyIds.ThresholdField, TextInputStyle.SHORT)
      .setValue(current.toString)
      .setMaxLength(3)
      .setRequired(true)
      .build()

  private def openMasslogForm(event: ButtonInteractionEvent): Unit = {
    val world = worldOf(event)
    val current = BotApp.notifyService
      .masslogFor(event.getGuild.getId, world, event.getUser.getId)
      .map(_.threshold)
      .getOrElse(NotifySettings.DefaultThreshold)
    val modal = Modal.create(NotifyIds.masslogForm(world), "Mass log alerts")
      .addComponents(label("How many enemies logging in?", NotifySettings.ThresholdHelp, thresholdInput(current)))
      .build()
    event.replyModal(modal).queue()
  }

  private def openBountyForm(event: ButtonInteractionEvent): Unit = {
    val world = worldOf(event)
    val name = TextInput.create(NotifyIds.CharacterField, TextInputStyle.SHORT)
      .setPlaceholder("Character name")
      .setMaxLength(NotifySettings.MaxCharacterName)
      .setRequired(true)
      .build()
    val cooldown = TextInput.create(NotifyIds.CooldownField, TextInputStyle.SHORT)
      .setValue(NotifySettings.DefaultCooldownMinutes.toString)
      .setMaxLength(4)
      .setRequired(true)
      .build()
    val modal = Modal.create(NotifyIds.bountyForm(world), "Track a bounty")
      .addComponents(
        label("Which character?", s"I'll DM you when they log in on $world.", name),
        label("Cooldown between alerts (minutes)", NotifySettings.CooldownHelp, cooldown))
      .build()
    event.replyModal(modal).queue()
  }

  private def openThresholdForm(event: ButtonInteractionEvent, id: Long): Unit =
    ownedMasslog(event, id) match {
      case None => refuse(event, gone)
      case Some(sub) =>
        val modal = Modal.create(NotifyIds.thresholdForm(id), "Mass log alerts")
          .addComponents(label("How many enemies logging in?", NotifySettings.ThresholdHelp, thresholdInput(sub.threshold)))
          .build()
        event.replyModal(modal).queue()
    }

  private def openMuteForm(event: ButtonInteractionEvent, id: Long, bounty: Boolean): Unit = {
    val mutedUntil =
      if (bounty) ownedBounty(event, id).map(_.mutedUntil)
      else ownedMasslog(event, id).map(_.mutedUntil)

    mutedUntil match {
      case None => refuse(event, gone)
      case Some(until) =>
        val lengths = MuteScale.options.map { case (minutes, text) => SelectOption.of(text, minutes.toString) }
        // Only offered while a mute is actually running: on an unmuted
        // subscription it would be a no-op sitting at the bottom of the list.
        val options =
          if (until.exists(_.isAfter(Instant.now())))
            lengths :+ SelectOption.of("Unmute now", MuteScale.Unmute.toString).withDescription("End the current mute")
          else lengths
        val menu = StringSelectMenu.create(NotifyIds.MuteField)
          .setPlaceholder("How long?")
          .addOptions(options.asJava)
          .build()
        val modal = Modal.create(NotifyIds.muteForm(id, bounty), "Quiet these alerts")
          .addComponents(label("Mute for", "You'll start hearing from me again after this.", menu))
          .build()
        event.replyModal(modal).queue()
    }
  }

  // --- the toggles -------------------------------------------------------

  /** Disable/Enable rewrites the row it was pressed on, so the message always
   *  shows the current state. A DM lingers, and a stale Disable on an already-off
   *  subscription is exactly the sort of thing that gets pressed twice. */
  private def toggleMasslog(event: ButtonInteractionEvent, id: Long, enable: Boolean): Unit =
    ownedMasslog(event, id).flatMap(_ => BotApp.notifyService.setMasslogEnabled(id, enable)) match {
      case None => refuse(event, gone)
      case Some(updated) =>
        syncRole(event.getJDA, event.getUser.getId, updated.guildId, updated.world, "masslog_role", enable)
        event.getHook.editOriginalComponents(NotifyEmbeds.masslogControls(updated)).queue(_ => (), _ => ())
    }

  private def toggleBounty(event: ButtonInteractionEvent, id: Long, enable: Boolean): Unit =
    ownedBounty(event, id).flatMap(_ => BotApp.notifyService.setBountyEnabled(id, enable)) match {
      case None => refuse(event, gone)
      case Some(updated) =>
        // The role comes off only once nothing is left switched on: it stands
        // for "this user is watching somebody here", not for any one bounty.
        val stillWatching = BotApp.notifyService
          .bountiesFor(updated.guildId, updated.world, updated.userId)
          .exists(_.enabled)
        syncRole(event.getJDA, event.getUser.getId, updated.guildId, updated.world, "bounty_role", stillWatching)
        event.getHook.editOriginalComponents(NotifyEmbeds.bountyControls(updated)).queue(_ => (), _ => ())
    }

  // --- shared ------------------------------------------------------------

  private val gone =
    s"${Config.noEmoji} That subscription is gone — press the role button again to set one up."

  /** A control may only touch the subscription it belongs to, and only for the
   *  person holding it. Button ids are guessable and a DM can be forwarded, so
   *  the row id inside one is a lookup key, never an authorisation. */
  private def ownedMasslog(event: ButtonInteractionEvent, id: Long): Option[MasslogSub] =
    BotApp.notifyService.masslogById(id).filter(_.userId == event.getUser.getId)

  private def ownedBounty(event: ButtonInteractionEvent, id: Long): Option[BountySub] =
    BotApp.notifyService.bountyById(id).filter(_.userId == event.getUser.getId)

  /** A press that would have opened a modal has not been acknowledged yet, so it
   *  has to answer directly; everything else already deferred an edit. */
  private def refuse(event: ButtonInteractionEvent, text: String): Unit = {
    val embed = Embeds.response(text)
    if (NotifyIds.opensModal(event.getComponentId)) event.replyEmbeds(embed).setEphemeral(true).queue(_ => (), _ => ())
    else event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue(_ => (), _ => ())
  }

  /** Keep the Discord role in step with the subscription it stands for.
   *
   *  Best-effort on purpose: the subscription is what actually drives the DMs,
   *  so a role that can't be assigned — missing permission, or a role sitting
   *  above the bot's own — must not cost somebody the alert they just asked for.
   *
   *  Takes ids rather than an event because the press that changes this is
   *  usually made in a DM, where there is no member and no guild to read.
   */
  private[interactions] def syncRole(
    jda: JDA, userId: String, guildId: String, world: String, roleColumn: String, subscribed: Boolean
  ): Unit =
    try {
      val guild: Guild = jda.getGuildById(guildId)
      if (guild != null) {
        val roleId = BotApp.worldRetrieveConfig(guild, world).getOrElse(roleColumn, "0")
        val role: Role = guild.getRoleById(roleId)
        if (role != null) {
          guild.retrieveMemberById(userId).queue({ member =>
            val hasRole = member.getRoles.contains(role)
            if (subscribed && !hasRole) guild.addRoleToMember(member, role).queue(_ => (), _ => ())
            else if (!subscribed && hasRole) guild.removeRoleFromMember(member, role).queue(_ => (), _ => ())
          }, _ => ())
        }
      }
    } catch {
      case ex: Throwable => logger.debug(s"Could not sync '$roleColumn' for guild '$guildId': ${ex.getMessage}")
    }

  /** As RespawnModals: Discord rejects a modal outright when a label runs past
   *  45 characters or its description past 100, so everything interpolated here
   *  goes through the same clamp. */
  private def label(text: String, description: String, child: LabelChildComponent): Label =
    Label.of(
      RespawnModals.clamp(text, Label.LABEL_MAX_LENGTH),
      RespawnModals.clamp(description, Label.DESCRIPTION_MAX_LENGTH),
      child)
}
