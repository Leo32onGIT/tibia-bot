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
  private val StartField = "start"
  private val WarnField = "warn"

  def handles(modalId: String): Boolean = modalId.startsWith(RespawnButtonId.ModalPrefix)

  /** Discord rejects a modal outright if a label runs past 45 characters or its
   *  description past 100 — the interaction fails rather than the text being
   *  trimmed. Several of these interpolate a Discord username or a spawn name,
   *  neither of which the bot bounds, so everything goes through here. */
  private[interactions] def clamp(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max - 1).trim + "\u2026"

  private def label(text: String, description: String, input: TextInput): Label =
    Label.of(clamp(text, Label.LABEL_MAX_LENGTH), clamp(description, Label.DESCRIPTION_MAX_LENGTH), input)


  // --- opening ------------------------------------------------------------

  /** Prompt for a spawn to claim. Free text rather than a dropdown because a
   *  catalogue runs to several hundred entries and Discord caps a select menu at
   *  25 options — and the same resolver behind `/respawn claim` already accepts
   *  a code or a name. */
  def claimModal: Modal =
    Modal.create(RespawnButtonId.modalClaim, "Claim a respawn")
      .addComponents(
        label("Which respawn?", "Enter its respawn code",
          TextInput.create(SpawnField, TextInputStyle.SHORT)
            .setPlaceholder("310")
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
        label("Default claim length (minutes)",
          s"How long your claims run when you don't say. 5–$maxDuration.",
          TextInput.create(DurationField, TextInputStyle.SHORT)
            .setValue(currentDuration)
            .setRequired(true)
            .setMaxLength(4)
            .build()),
        label("Remind me this many minutes before the end",
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
        label("How long for, in total? (minutes)",
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
        // Whose hunt it is goes in the description, not the label: a Discord
        // username can be 32 characters on its own and the label allows 45 in
        // total, so interpolating one there fails the interaction outright.
        label("Total hunt length (minutes)",
          s"${respawn.displayName}, choose a new duration. 5 to $maxDuration",
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
        label("Default claim length (minutes)", "Used when a member has not set their own.",
          numberInput("default", settings.map(_.defaultDurationMinutes))),
        label("Maximum claim length (minutes)", "The longest any single claim may run.",
          numberInput("max", settings.map(_.maxDurationMinutes))),
        label("Queue limit", "How many people may wait behind a claim.",
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
        label("Daily stamina per member (minutes)", "0 means unlimited claiming.",
          numberInput("stamina", settings.map(_.staminaMinutes))),
        label("Default reminder (minutes before the end)",
          "Members can override this for themselves. 0 turns it off.",
          numberInput("warn", settings.map(_.warnMinutes))),
        label("Handover window (minutes)",
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

  /** Book a repeating slot.
   *
   *  The first start is asked for as a delay from now rather than a clock time,
   *  because a clock time means nothing without knowing the reader's timezone —
   *  and the whole scheduling model is deliberately free of them. The reply
   *  confirms with a Discord timestamp, which each person sees in their own zone,
   *  so an entry that was a few hours out is obvious immediately. */
  def scheduleModal(guildId: String, respawn: Respawn): Modal = {
    val maxDuration = BotApp.respawnService.settings(guildId).map(_.maxDurationMinutes).getOrElse(240)
    Modal.create(RespawnButtonId.modalSchedule(respawn.id), "Book a repeating slot")
      .addComponents(
        label("First slot starts in (minutes from now)",
          s"${respawn.displayName} — it then repeats every 24 hours. 120 = two hours from now.",
          TextInput.create(StartField, TextInputStyle.SHORT)
            .setPlaceholder("120")
            .setRequired(true)
            .setMaxLength(5)
            .build()),
        label("How long is the slot? (minutes)", s"5 to $maxDuration.",
          TextInput.create(DurationField, TextInputStyle.SHORT)
            .setRequired(true)
            .setMaxLength(4)
            .build())
      )
      .build()
  }

  // --- submissions --------------------------------------------------------

  def handle(event: ModalInteractionEvent): Unit = {
    // Acknowledged before any work: every branch below touches the database, and
    // the claim and duration ones create or rewrite a forum thread over REST.
    // Discord drops an interaction that goes three seconds unacknowledged, and
    // unlike the buttons no branch here opens a further modal, so all of them can
    // defer. Replies therefore go through the hook — see `reply`.
    event.deferReply(true).queue()

    val guild = event.getGuild
    if (guild == null) {
      reply(event, s"${Config.noEmoji} That only works inside a server.")
      return
    }
    val modalId = event.getModalId
    RespawnButtonId.parseSpawnModal(modalId) match {
      case Some(("duration", respawnId)) => submitDuration(event, respawnId, forHolder = false)
      case Some(("holder", respawnId))   => submitDuration(event, respawnId, forHolder = true)
      case Some(("schedule", respawnId))  => submitSchedule(event, respawnId)
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

  private def submitSchedule(event: ModalInteractionEvent, respawnId: Long): Unit = {
    val guild = event.getGuild
    val service = BotApp.respawnService
    (Try(value(event, StartField).toInt).toOption, Try(value(event, DurationField).toInt).toOption) match {
      case (None, _) | (_, None) =>
        reply(event, s"${Config.noEmoji} Both need to be whole numbers of minutes.")
      case (Some(startsIn), Some(duration)) =>
        service.listRespawns(guild.getId).find(_.id == respawnId) match {
          case None => reply(event, s"${Config.noEmoji} That respawn is no longer in the catalogue.")
          case Some(respawn) =>
            val existing = service.schedulesForUser(guild.getId, event.getUser.getId)
            if (existing.size >= com.tibiabot.Config.Respawn.maxSchedulesPerUser)
              reply(event, s"${Config.noEmoji} You already have " +
                s"${com.tibiabot.Config.Respawn.maxSchedulesPerUser} repeating slots — cancel one first.")
            else {
              val firstStart = java.time.ZonedDateTime.now().plusMinutes(math.max(0, startsIn).toLong)
              service.addSchedule(guild, respawn, event.getUser.getId, event.getUser.getName, "",
                firstStart, duration) match {
                case Left(problem) => reply(event, s"${Config.noEmoji} $problem")
                case Right(schedule) =>
                  reply(event, s"${Config.yesEmoji} Booked **${respawn.displayName}** every day for " +
                    s"${RespawnEmbeds.humanDuration(schedule.durationMinutes)}, starting " +
                    s"<t:${schedule.anchorAt.toInstant.getEpochSecond}:f>.\n" +
                    "Check that time reads right — if it doesn't, cancel the booking and try again.")
              }
            }
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
            replyEmbed(event, RespawnEmbeds.serverSettingsEmbed(updated))
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

    replyEmbed(event, RespawnButtons.claimOutcomeEmbed(outcome, threadLink))
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
                replyEmbed(event, RespawnEmbeds.userPrefsEmbed(prefs, settings))
              case None => reply(event, s"${Config.noEmoji} The respawn claim system isn't set up here.")
            }
        }
    }
  }

  /** Modal values are keyed by the text input's own id, not the label's. */
  private def value(event: ModalInteractionEvent, id: String): String =
    Option(event.getValue(id)).map(_.getAsString.trim).getOrElse("")

  /** Answers through the interaction hook, since `handle` always defers first. */
  private def reply(event: ModalInteractionEvent, text: String): Unit =
    replyEmbed(event, Embeds.response(text))

  private def replyEmbed(event: ModalInteractionEvent, embed: net.dv8tion.jda.api.entities.MessageEmbed): Unit =
    event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue()

}
