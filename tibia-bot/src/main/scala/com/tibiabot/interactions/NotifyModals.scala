package com.tibiabot.interactions

import com.tibiabot.domain.MuteScale
import com.tibiabot.notifications.NotifyIds
import com.tibiabot.presentation.{Embeds, NotifyEmbeds}
import com.tibiabot.{BotApp, Config}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters._

/** The forms behind the notification autoroles: the threshold prompt, the bounty
 *  prompt, and the mute picker.
 *
 *  Routed apart from [[ModalHandler]] for the same reason the respawn forms are:
 *  that handler opens with `deferEdit()`, which rewrites the message the modal
 *  came from. Here that message is either a notifications embed the whole server
 *  reads or somebody's alert DM, and neither should be replaced by the reply to
 *  a form. These answer ephemerally and edit the source message's controls
 *  separately, when there are controls to bring up to date.
 */
object NotifyModals extends StrictLogging {

  def handles(modalId: String): Boolean = NotifyIds.handlesModal(modalId)

  def handle(event: ModalInteractionEvent): Unit =
    NotifyIds.parseForm(event.getModalId) match {
      case Some(NotifyIds.MasslogForm(world))    => subscribeMasslog(event, world)
      case Some(NotifyIds.BountyForm(world))     => addBounty(event, world)
      case Some(NotifyIds.ThresholdForm(id))     => adjustThreshold(event, id)
      case Some(NotifyIds.MuteForm(id, bounty))  => mute(event, id, bounty)
      case None =>
        logger.debug(s"Ignoring unparseable notification modal id '${event.getModalId}'")
        reply(event, Embeds.response(s"${Config.noEmoji} That form is out of date — press the button again."))
    }

  private def value(event: ModalInteractionEvent, field: String): String =
    Option(event.getValue(field)).map(_.getAsString.trim).getOrElse("")

  /** What was picked in a select. Not interchangeable with [[value]]: a select's
   *  answer refuses `getAsString` outright and throws rather than coming back
   *  empty — see the same pair in RespawnModals. */
  private def selected(event: ModalInteractionEvent, field: String): Option[String] =
    Option(event.getValue(field))
      .toList
      .flatMap(_.getAsStringList.asScala.toList)
      .map(_.trim)
      .find(_.nonEmpty)

  // --- subscribing -------------------------------------------------------

  private def subscribeMasslog(event: ModalInteractionEvent, world: String): Unit =
    Option(event.getGuild) match {
      case None => reply(event, Embeds.response(s"${Config.noEmoji} That button only works inside a server."))
      case Some(guild) =>
        com.tibiabot.domain.NotifySettings.parseThreshold(value(event, NotifyIds.ThresholdField)) match {
          case Left(problem) => reply(event, Embeds.response(s"${Config.noEmoji} $problem"))
          case Right(threshold) =>
            val sub = BotApp.notifyService.subscribeMasslog(guild.getId, world, event.getUser.getId, threshold)
            NotifyButtons.syncRole(event.getJDA, event.getUser.getId, guild.getId, world, "masslog_role", subscribed = true)
            reply(
              event,
              NotifyEmbeds.masslogSettings(sub, world, s"${Config.yesEmoji} You're on the mass log alert list."),
              Some(NotifyEmbeds.masslogControls(sub)))
        }
    }

  private def addBounty(event: ModalInteractionEvent, world: String): Unit =
    Option(event.getGuild) match {
      case None => reply(event, Embeds.response(s"${Config.noEmoji} That button only works inside a server."))
      case Some(guild) =>
        val parsed = for {
          character <- com.tibiabot.domain.NotifySettings.parseCharacter(value(event, NotifyIds.CharacterField))
          cooldown  <- com.tibiabot.domain.NotifySettings.parseCooldown(value(event, NotifyIds.CooldownField))
        } yield (character, cooldown)

        parsed match {
          case Left(problem) => reply(event, Embeds.response(s"${Config.noEmoji} $problem"))
          case Right((character, cooldown)) =>
            val sub = BotApp.notifyService.addBounty(guild.getId, world, event.getUser.getId, character, cooldown)
            NotifyButtons.syncRole(event.getJDA, event.getUser.getId, guild.getId, world, "bounty_role", subscribed = true)
            val held = BotApp.notifyService.bountiesFor(guild.getId, world, event.getUser.getId)
            reply(
              event,
              NotifyEmbeds.bountySettings(sub, held, world, s"${Config.yesEmoji} I'll DM you when **$character** logs in."),
              Some(NotifyEmbeds.bountyControls(sub)))
        }
    }

  // --- adjusting from a DM ------------------------------------------------

  private def adjustThreshold(event: ModalInteractionEvent, id: Long): Unit =
    if (!owned(event, BotApp.notifyService.masslogById(id).map(_.userId))) reply(event, Embeds.response(gone))
    else com.tibiabot.domain.NotifySettings.parseThreshold(value(event, NotifyIds.ThresholdField)) match {
      case Left(problem) => reply(event, Embeds.response(s"${Config.noEmoji} $problem"))
      case Right(threshold) =>
        BotApp.notifyService.setMasslogThreshold(id, threshold) match {
          case None => reply(event, Embeds.response(gone))
          case Some(updated) =>
            // The row carries the threshold on its own button, so the DM this
            // was opened from is now showing the old number.
            refreshControls(event, NotifyEmbeds.masslogControls(updated))
            reply(event, NotifyEmbeds.masslogSettings(updated, updated.world, s"${Config.yesEmoji} Updated."))
        }
    }

  private def mute(event: ModalInteractionEvent, id: Long, bounty: Boolean): Unit = {
    val holder = if (bounty) BotApp.notifyService.bountyById(id).map(_.userId) else BotApp.notifyService.masslogById(id).map(_.userId)
    if (!owned(event, holder)) reply(event, Embeds.response(gone))
    else selected(event, NotifyIds.MuteField).flatMap(MuteScale.parse) match {
      case None => reply(event, Embeds.response(s"${Config.noEmoji} Pick one of the offered lengths."))
      case Some(minutes) =>
        // "Unmute now" is a mute that has already expired, so one code path
        // covers both and there is no second way for the two to disagree.
        val until = Instant.now().plus(minutes.toLong, ChronoUnit.MINUTES)
        val updated =
          if (bounty) BotApp.notifyService.muteBounty(id, until).map(sub => NotifyEmbeds.bountyControls(sub))
          else BotApp.notifyService.muteMasslog(id, until).map(sub => NotifyEmbeds.masslogControls(sub))
        updated match {
          case None => reply(event, Embeds.response(gone))
          case Some(controls) =>
            refreshControls(event, controls)
            reply(event, Embeds.response(NotifyEmbeds.muteConfirmation(minutes, until)))
        }
    }
  }

  // --- shared -------------------------------------------------------------

  private val gone =
    s"${Config.noEmoji} That subscription is gone — press the role button again to set one up."

  private def owned(event: ModalInteractionEvent, holder: Option[String]): Boolean =
    holder.contains(event.getUser.getId)

  /** Bring the message this form was opened from back in step. Best-effort: the
   *  reply below is what the user is actually waiting on, and an alert DM that
   *  has since been deleted is not a failure worth reporting. */
  private def refreshControls(event: ModalInteractionEvent, controls: ActionRow): Unit =
    Option(event.getMessage).foreach(_.editMessageComponents(controls).queue(_ => (), _ => ()))

  /** Every reply here is ephemeral and already deferred by BotListener. */
  private def reply(event: ModalInteractionEvent, embed: MessageEmbed, controls: Option[ActionRow] = None): Unit = {
    val action = event.getHook.sendMessageEmbeds(embed).setEphemeral(true)
    controls.foreach(row => action.setComponents(row))
    action.queue(_ => (), _ => ())
  }
}
