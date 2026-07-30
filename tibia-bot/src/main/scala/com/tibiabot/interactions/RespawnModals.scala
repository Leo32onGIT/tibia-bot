package com.tibiabot.interactions

import com.tibiabot.presentation.{Embeds, RespawnEmbeds}
import com.tibiabot.domain.Respawn
import com.tibiabot.commands.Permissions
import com.tibiabot.respawn.RespawnButtonId
import com.tibiabot.{BotApp, Config}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.{TextInput, TextInputStyle}
import net.dv8tion.jda.api.entities.{Guild, Member}
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

  def handles(modalId: String): Boolean = modalId.startsWith(RespawnButtonId.ModalPrefix)

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

  /** Adjust the length of the caller's own claim on one spawn, pre-filled with
   *  what it is now. Same field as the board's Config, but scoped to this spawn
   *  rather than to their default. */
  def durationModal(guildId: String, userId: String, respawn: Respawn): Modal = {
    val current = BotApp.respawnService.openClaimsForUser(guildId, userId)
      .find(_._2.respawnId == respawn.id)
      .map(_._2.durationMinutes.toString)
      .getOrElse("")
    val maxDuration = BotApp.respawnService.settings(guildId).map(_.maxDurationMinutes).getOrElse(240)

    Modal.create(RespawnButtonId.modalDuration(respawn.id), "Hunt duration")
      .addComponents(
        Label.of(s"How long for, in total? (minutes)",
          s"${respawn.displayName} — 5 to $maxDuration. Counts from when the hunt started.",
          TextInput.create(DurationField, TextInputStyle.SHORT)
            .setValue(current)
            .setRequired(true)
            .setMaxLength(4)
            .build())
      )
      .build()
  }

  /** The current holder's duration, changed by a moderator. Named so it is obvious
   *  whose hunt is being altered. */
  def holderDurationModal(guildId: String, respawn: Respawn): Modal = {
    val holder = BotApp.respawnService.holderOf(guildId, respawn.id)
    val who = holder.map(c => if (c.characterName.nonEmpty) c.characterName else c.userName).getOrElse("the holder")
    val maxDuration = BotApp.respawnService.settings(guildId).map(_.maxDurationMinutes).getOrElse(240)

    Modal.create(RespawnButtonId.modalHolderDuration(respawn.id), "Change hunt duration")
      .addComponents(
        Label.of(s"Total hunt length for $who (minutes)",
          s"${respawn.displayName} - 5 to $maxDuration, counting from when their hunt started.",
          TextInput.create(DurationField, TextInputStyle.SHORT)
            .setValue(holder.map(_.durationMinutes.toString).getOrElse(""))
            .setRequired(true)
            .setMaxLength(4)
            .build())
      )
      .build()
  }

  /** Server-wide claim rules. Split from the timers below because Discord allows a
   *  modal only five inputs and there are six settings. */
  def claimRulesModal(guildId: String): Modal = {
    val settings = BotApp.respawnService.settings(guildId)
    Modal.create(RespawnButtonId.modalClaimRules, "Server claim rules")
      .addComponents(
        Label.of("Default claim length (minutes)", "Used when a member has not set their own.",
          numberInput("default", settings.map(_.defaultDurationMinutes))),
        Label.of("Maximum claim length (minutes)", "The longest any single claim may run.",
          numberInput("max", settings.map(_.maxDurationMinutes))),
        Label.of("Queue limit", "How many people may wait behind a claim.",
          numberInput("queue", settings.map(_.queueLimit)))
      )
      .build()
  }

  /** Server-wide timers: the daily budget, the default reminder, and how long a
   *  handover offer stays open. */
  def timersModal(guildId: String): Modal = {
    val settings = BotApp.respawnService.settings(guildId)
    Modal.create(RespawnButtonId.modalTimers, "Server timers")
      .addComponents(
        Label.of("Daily stamina per member (minutes)", "0 means unlimited claiming.",
          numberInput("stamina", settings.map(_.staminaMinutes))),
        Label.of("Default reminder (minutes before the end)",
          "Members can override this for themselves. 0 turns it off.",
          numberInput("warn", settings.map(_.warnMinutes))),
        Label.of("Handover window (minutes)",
          "How long the next in line has to accept before it passes on.",
          numberInput("handover", settings.map(_.handoverMinutes)))
      )
      .build()
  }

  private def numberInput(id: String, current: Option[Int]): TextInput =
    TextInput.create(id, TextInputStyle.SHORT)
      .setValue(current.map(_.toString).getOrElse(""))
      .setRequired(true)
      .setMaxLength(4)
      .build()

  // --- submissions --------------------------------------------------------

  def handle(event: ModalInteractionEvent): Unit = {
    val guild = event.getGuild
    if (guild == null) {
      reply(event, s"${Config.noEmoji} That only works inside a server.")
      return
    }
    val modalId = event.getModalId
    RespawnButtonId.parseSpawnModal(modalId) match {
      case Some(("duration", respawnId)) => submitDuration(event, respawnId, forHolder = false)
      case Some(("holder", respawnId))   => submitDuration(event, respawnId, forHolder = true)
      case _ => modalId match {
        case RespawnButtonId.modalClaim      => submitClaim(event)
        case RespawnButtonId.modalConfig     => submitConfig(event)
        case RespawnButtonId.modalClaimRules => submitSettings(event, claimRules = true)
        case RespawnButtonId.modalTimers     => submitSettings(event, claimRules = false)
        case other =>
          logger.warn(s"Unknown respawn modal '$other'")
          reply(event, s"${Config.noEmoji} I didn't understand that form.")
      }
    }
  }

  /** Manage Server or the guild's moderator role. */
  private[interactions] def moderates(guild: Guild, member: Member): Boolean =
    Permissions.isModerator(member, BotApp.moderatorRoleId(guild.getId))

  private def submitSettings(event: ModalInteractionEvent, claimRules: Boolean): Unit = {
    val guildId = event.getGuild.getId
    // Re-checked on submit rather than trusted from when the panel opened: a modal
    // can sit open long after somebody's role was taken away.
    if (!moderates(event.getGuild, event.getMember)) {
      reply(event, s"${Config.noEmoji} That needs the **Manage Server** permission, " +
        s"or the **${Permissions.ModeratorRoleName}** role.")
    } else {
      def field(id: String): Option[Int] = Try(value(event, id).toInt).toOption
      val ids = if (claimRules) List("default", "max", "queue") else List("stamina", "warn", "handover")
      if (ids.exists(field(_).isEmpty)) {
        reply(event, s"${Config.noEmoji} Every setting needs to be a whole number.")
      } else {
        val result =
          if (claimRules)
            BotApp.respawnService.updateSettings(guildId, field("default"), field("max"), field("queue"),
              None, None, None)
          else
            BotApp.respawnService.updateSettings(guildId, None, None, None,
              field("stamina"), field("warn"), field("handover"))
        result match {
          case Left(problem) => reply(event, s"${Config.noEmoji} $problem")
          case Right(updated) =>
            event.replyEmbeds(RespawnEmbeds.serverSettingsEmbed(updated)).setEphemeral(true).queue()
        }
      }
    }
  }

  private def submitDuration(event: ModalInteractionEvent, respawnId: Long, forHolder: Boolean): Unit =
    Try(value(event, DurationField).toInt).toOption match {
      case None => reply(event, s"${Config.noEmoji} That needs to be a whole number of minutes.")
      case Some(minutes) =>
        // Whose claim changes: the current holder's when a moderator came through
        // the spawn panel, otherwise the caller's own. Stamina settles against
        // whoever actually owns the claim either way.
        val target =
          if (!forHolder) Some(event.getUser.getId)
          else if (!moderates(event.getGuild, event.getMember)) None
          else BotApp.respawnService.holderOf(event.getGuild.getId, respawnId).map(_.userId)

        target match {
          case None =>
            reply(event, s"${Config.noEmoji} Nobody is holding that respawn any more.")
          case Some(userId) =>
            BotApp.respawnService.setClaimDuration(event.getGuild, userId, respawnId, minutes) match {
              case Left(problem) => reply(event, s"${Config.noEmoji} $problem")
              case Right((respawn, applied)) =>
                val whose = if (forHolder && userId != event.getUser.getId) s" for <@$userId>" else ""
                val note =
                  if (applied != minutes)
                    s"\nThe hunt had already run longer than that, so it's set to " +
                      s"${RespawnEmbeds.humanDuration(applied)} and ends now."
                  else ""
                reply(event, s"${Config.yesEmoji} **${respawn.displayName}**$whose is now set to " +
                  s"${RespawnEmbeds.humanDuration(applied)}.$note")
            }
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
