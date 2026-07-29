package com.tibiabot.interactions

import com.tibiabot.presentation.{Embeds, RespawnEmbeds}
import com.tibiabot.respawn.RespawnButtonId
import com.tibiabot.{BotApp, Config}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.{TextInput, TextInputStyle}
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.modals.Modal

import scala.util.Try

/** The two modals behind the board post's buttons.
 *
 *  These exist so the claim system is usable entirely from the forum. A spawn
 *  that nobody has claimed yet has no post of its own, so it has no Claim button
 *  — the board's Claim button plus a code/name prompt is what covers that gap
 *  without making anyone learn a slash command.
 *
 *  Routed separately from [[ModalHandler]] rather than added to it: that handler
 *  opens with `deferEdit()`, which edits the message the modal came from. Here
 *  that message is the pinned board post, which must not be rewritten by
 *  somebody adjusting their own settings.
 */
object RespawnModals extends StrictLogging {

  private val SpawnField = "spawn"
  private val DurationField = "duration"
  private val WarnField = "warn"

  def handles(modalId: String): Boolean =
    modalId == RespawnButtonId.modalClaim || modalId == RespawnButtonId.modalConfig

  // --- opening ------------------------------------------------------------

  /** Prompt for a spawn to claim. Free text rather than a dropdown because a
   *  catalogue runs to several hundred entries and Discord caps a select menu at
   *  25 options — and the same resolver behind `/respawn claim` already accepts
   *  a code or a name. */
  def claimModal: Modal =
    Modal.create(RespawnButtonId.modalClaim, "Claim a respawn")
      .addComponents(
        Label.of("Which respawn?", "Its code or name — for example 415, or Cult Orcs",
          TextInput.create(SpawnField, TextInputStyle.SHORT)
            .setPlaceholder("415")
            .setRequired(true)
            .setMaxLength(100)
            .build())
      )
      .build()

  /** Prompt for a member's own defaults, pre-filled with whatever they'd get
   *  today so the modal shows the current state rather than an empty box. */
  def configModal(guildId: String, userId: String): Modal = {
    val settings = BotApp.respawnService.settings(guildId)
    val prefs = BotApp.respawnService.userPrefs(guildId, userId)
    val currentDuration = prefs.defaultDurationMinutes
      .orElse(settings.map(_.defaultDurationMinutes)).map(_.toString).getOrElse("")
    val currentWarn = prefs.warnMinutes
      .orElse(settings.map(_.warnMinutes)).map(_.toString).getOrElse("")
    val maxDuration = settings.map(_.maxDurationMinutes).getOrElse(240)

    Modal.create(RespawnButtonId.modalConfig, "Your respawn settings")
      .addComponents(
        Label.of("Default claim length (minutes)",
          s"How long your claims run when you don't say. 5–$maxDuration.",
          TextInput.create(DurationField, TextInputStyle.SHORT)
            .setValue(currentDuration)
            .setRequired(true)
            .setMaxLength(4)
            .build()),
        Label.of("Remind me this many minutes before the end",
          "0 turns reminders off. Up to 720 (12 hours).",
          TextInput.create(WarnField, TextInputStyle.SHORT)
            .setValue(currentWarn)
            .setRequired(true)
            .setMaxLength(4)
            .build())
      )
      .build()
  }

  // --- submissions --------------------------------------------------------

  def handle(event: ModalInteractionEvent): Unit = {
    val guild = event.getGuild
    if (guild == null) {
      reply(event, s"${Config.noEmoji} That only works inside a server.")
      return
    }
    event.getModalId match {
      case RespawnButtonId.modalClaim  => submitClaim(event)
      case RespawnButtonId.modalConfig => submitConfig(event)
      case other =>
        logger.warn(s"Unknown respawn modal '$other'")
        reply(event, s"${Config.noEmoji} I didn't understand that form.")
    }
  }

  private def submitClaim(event: ModalInteractionEvent): Unit = {
    val guild = event.getGuild
    val query = value(event, SpawnField)
    val service = BotApp.respawnService
    val user = event.getUser

    // No duration passed, so the member's own default (or the guild's) applies —
    // which is the point of pairing this with the Config button.
    val outcome = service.claim(guild, user.getId, user.getName, "", query, None)
    val threadLink = service.resolve(guild.getId, query)
      .map(_.threadId)
      .filter(id => id.nonEmpty && id != "0")
      .map(id => s"\n<#$id>")
      .getOrElse("")

    event.replyEmbeds(RespawnButtons.claimOutcomeEmbed(outcome, threadLink)).setEphemeral(true).queue()
  }

  private def submitConfig(event: ModalInteractionEvent): Unit = {
    val guild = event.getGuild
    val duration = Try(value(event, DurationField).toInt).toOption
    val warn = Try(value(event, WarnField).toInt).toOption

    (duration, warn) match {
      case (None, _) | (_, None) =>
        reply(event, s"${Config.noEmoji} Both settings need to be whole numbers of minutes.")
      case (Some(minutes), Some(lead)) =>
        BotApp.respawnService.saveUserPrefs(guild.getId, event.getUser.getId, Some(minutes), Some(lead)) match {
          case Left(problem) => reply(event, s"${Config.noEmoji} $problem")
          case Right(prefs) =>
            BotApp.respawnService.settings(guild.getId) match {
              case Some(settings) =>
                event.replyEmbeds(RespawnEmbeds.userPrefsEmbed(prefs, settings)).setEphemeral(true).queue()
              case None => reply(event, s"${Config.noEmoji} The respawn claim system isn't set up here.")
            }
        }
    }
  }

  /** Modal values are keyed by the text input's own id, not the label's. */
  private def value(event: ModalInteractionEvent, id: String): String =
    Option(event.getValue(id)).map(_.getAsString.trim).getOrElse("")

  private def reply(event: ModalInteractionEvent, text: String): Unit =
    event.replyEmbeds(Embeds.response(text)).setEphemeral(true).queue()

}
