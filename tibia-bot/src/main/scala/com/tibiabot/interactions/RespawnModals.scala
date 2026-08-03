package com.tibiabot.interactions

import com.tibiabot.presentation.{Embeds, RespawnEmbeds}
import com.tibiabot.domain.Respawn
import com.tibiabot.commands.Permissions
import com.tibiabot.respawn.{RespawnButtonId, ScheduleResult}
import com.tibiabot.{BotApp, Config}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.components.checkbox.Checkbox
import net.dv8tion.jda.api.components.label.{Label, LabelChildComponent}
import net.dv8tion.jda.api.components.selections.{EntitySelectMenu, SelectOption, StringSelectMenu}
import net.dv8tion.jda.api.components.textinput.{TextInput, TextInputStyle}
import net.dv8tion.jda.api.entities.{Guild, Member}
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.modals.Modal

import scala.jdk.CollectionConverters._
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
  private val RepeatField = "repeat"
  private val DaysField = "days"
  private val WarnField = "warn"
  private val HolderField = "holder"
  private val MinutesField = "minutes"

  def handles(modalId: String): Boolean = modalId.startsWith(RespawnButtonId.ModalPrefix)

  /** Discord rejects a modal outright if a label runs past 45 characters or its
   *  description past 100 — the interaction fails rather than the text being
   *  trimmed. Several of these interpolate a Discord username or a spawn name,
   *  neither of which the bot bounds, so everything goes through here. */
  private[interactions] def clamp(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max - 1).trim + "\u2026"

  /** Takes any component a Label may wrap, not just a text box — modals also
   *  accept select menus, which is what the schedule picker uses. */
  private def label(text: String, description: String, child: LabelChildComponent): Label =
    Label.of(clamp(text, Label.LABEL_MAX_LENGTH), clamp(description, Label.DESCRIPTION_MAX_LENGTH), child)


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

  /** A moderator editing the claim on a spawn: how long it runs, and who is on
   *  it.
   *
   *  The holder is a user picker rather than a typed name, so a moderator can
   *  hand a hunt to anybody in the server without knowing an id. Left empty it
   *  changes nothing — the common edit is the duration, and a picker that had to
   *  be re-answered every time would make the usual case the fiddly one. */
  def holderDurationModal(guildId: String, respawn: Respawn): Modal = {
    val holder = BotApp.respawnService.holderOf(guildId, respawn.id)
    val maxDuration = BotApp.respawnService.settings(guildId).map(_.maxDurationMinutes).getOrElse(240)

    Modal.create(RespawnButtonId.modalHolderDuration(respawn.id), "Edit claim")
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
            .build()),
        label("Give the hunt to somebody else",
          "Leave empty to keep whoever is on it now.",
          EntitySelectMenu.create(HolderField, EntitySelectMenu.SelectTarget.USER)
            // Not required, rather than required with a minimum of nothing —
            // Discord rejects that pairing outright (COMPONENT_REQUIRED_ZERO_MIN_VALUES)
            // and the whole modal fails to open. Same reasoning as the day
            // picker in scheduleModal below: skipping this is meaningful (keep
            // whoever is on it), but picking nobody is not a handover.
            .setRequired(false)
            .build())
      )
      .build()
  }

  /** A moderator handing somebody stamina, from /stamina.
   *
   *  Minutes to *give* rather than a new total: a moderator does this because
   *  somebody lost time to something that wasn't their fault, and what they know
   *  is how much was lost — not what the tank should read afterwards. A negative
   *  number takes some back, for when it was given in error. */
  def giveStaminaModal(guildId: String): Modal = {
    val budget = BotApp.respawnService.settings(guildId).map(_.staminaMinutes).getOrElse(240)
    Modal.create(RespawnButtonId.modalGiveStamina, "Give stamina")
      .addComponents(
        label("Who to give it to", "Anybody in this server.",
          EntitySelectMenu.create(HolderField, EntitySelectMenu.SelectTarget.USER)
            .setRequiredRange(1, 1)
            .build()),
        label("How many minutes?",
          s"Negative takes it back. Nobody can go above the daily $budget.",
          TextInput.create(MinutesField, TextInputStyle.SHORT)
            .setPlaceholder("60")
            .setRequired(true)
            .setMaxLength(5)
            .build())
      )
      .build()
  }

  /** Server-wide claim rules: how long a hunt runs, how many may wait for one,
   *  and how much hunting a member gets in a day.
   *
   *  Split from the timers below because a modal takes five inputs
   *  ([[Modal.MAX_COMPONENTS]]) and there are six settings. The split is by what
   *  a setting *is* rather than by what fits: these four are the rules of who may
   *  claim what, and the two below are how long the bot waits before acting. */
  def claimRulesModal(guildId: String): Modal = {
    val settings = BotApp.respawnService.settings(guildId)
    Modal.create(RespawnButtonId.modalClaimRules, "Server claim rules")
      .addComponents(
        label("Default claim length (minutes)", "Used when a member has not set their own.",
          numberInput("default", settings.map(_.defaultDurationMinutes))),
        label("Maximum claim length (minutes)", "The longest any single claim may run.",
          numberInput("max", settings.map(_.maxDurationMinutes))),
        label("Queue limit", "How many people may wait behind a claim.",
          numberInput("queue", settings.map(_.queueLimit))),
        label("Daily stamina per member (minutes)",
          "0 means unlimited. Turning a limit on refills everyone.",
          numberInput("stamina", settings.map(_.staminaMinutes)))
      )
      .build()
  }

  /** Server-wide timers: the default reminder, and how long a handover offer
   *  stays open. The daily budget used to live here and now sits with the claim
   *  rules, which is what it is — nobody looking for stamina looked under
   *  "timers". */
  def timersModal(guildId: String): Modal = {
    val settings = BotApp.respawnService.settings(guildId)
    Modal.create(RespawnButtonId.modalTimers, "Server timers")
      .addComponents(
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

  /** Book a slot, repeating or not.
   *
   *  The start is picked from a menu rather than typed, since there is no way to
   *  type an hour that means the same thing to everyone reading it. Each option's
   *  *value* is an absolute instant, so what gets stored stays free of any
   *  timezone and the recurrence arithmetic is untouched — server time only
   *  decides where the hour boundaries fall. The confirmation is a Discord
   *  timestamp, so each person sees the booking in their own zone. */
  def scheduleModal(guildId: String, respawn: Respawn): Modal = {
    val settings = BotApp.respawnService.settings(guildId)
    val zone = com.tibiabot.domain.time.Clock.Berlin
    val maxDuration = settings.map(_.maxDurationMinutes).getOrElse(240)
    val now = java.time.ZonedDateTime.now()

    // Half hours in server time. Each option's *value* is an absolute instant,
    // so what gets stored is still timezone-free — the zone only decides where
    // the boundaries fall.
    val starts = com.tibiabot.domain.RespawnSchedule.upcomingStarts(now, zone, StartOptionCount)
    val startMenu = StringSelectMenu.create(StartField)
      .setPlaceholder("Pick a start time")
      .addOptions(starts.map { start =>
        SelectOption.of(startLabel(start), start.toInstant.getEpochSecond.toString)
          .withDescription(startHint(start, now))
      }.asJava)
      .build()

    // Multi-select, and optional: no days chosen means every day, which is what
    // a repeating booking meant before weekdays existed.
    val dayMenu = StringSelectMenu.create(DaysField)
      .setPlaceholder("Every day")
      .addOptions(java.time.DayOfWeek.values().toList.map { day =>
        SelectOption.of(
          day.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH),
          day.getValue.toString)
      }.asJava)
      // Not required, rather than required with a minimum of nothing — Discord
      // rejects that pairing outright, and the two say different things anyway:
      // this may be skipped entirely, but a pick of no days is not a booking.
      .setRequired(false)
      .setMaxValues(7)
      .build()

    Modal.create(RespawnButtonId.modalSchedule(respawn.id), "Book a slot")
      .addComponents(
        label("Slot starts at", s"${respawn.displayName} — server time, so SS+1 is an hour after save.",
          startMenu),
        // A typed length, the same as every other duration prompt — there is no
        // reason for this one to work differently from the Config and Hunt
        // duration modals.
        label("How long is the slot? (minutes)", s"5 to $maxDuration.",
          TextInput.create(DurationField, TextInputStyle.SHORT)
            .setRequired(true)
            .setMaxLength(4)
            .build()),
        // Default on, because a standing booking is what most people are here
        // for. Turning it off books the one slot and nothing after it, which is
        // how you hold a spawn for a particular night.
        label("Repeat this booking", "Turn off to book the one slot only.",
          Checkbox.of(RepeatField, true)),
        label("Repeat on", "Leave empty for every day. Ignored when repeat is off.", dayMenu)
      )
      .build()
  }

  /** How many half hours ahead the picker offers. Discord caps a select at 25
   *  options, which at this granularity reaches about half a day out. */
  private val StartOptionCount = 25

  /** Hours either side of server save — SS+2, SS-4, SS+1.5 — which is how Tibia
   *  players talk about times, and all a player needs. The wall-clock time is
   *  deliberately absent: it would be server time, which is not the reader's, so
   *  it adds a number to translate rather than removing one.
   *
   *  Every offset within a day is distinct, so the list needs nothing else to
   *  tell its entries apart. */
  private def startLabel(start: java.time.ZonedDateTime): String =
    com.tibiabot.scheduler.ServerSaveSchedule.serverSaveOffsetLabel(start)

  /** The relative form as a hint, which is unambiguous however the reader's own
   *  clock is set. Accurate as of the moment the modal opened. */
  private def startHint(start: java.time.ZonedDateTime, now: java.time.ZonedDateTime): String = {
    val minutes = java.time.Duration.between(now, start).toMinutes
    if (minutes < 60) s"in ${math.max(1, minutes)} minutes"
    else s"in ${RespawnEmbeds.humanDuration(minutes.toInt)}"
  }

  // --- submissions --------------------------------------------------------

  def handle(event: ModalInteractionEvent): Unit = {
    // Already acknowledged by BotListener, on JDA's event thread, before this
    // was ever queued: every branch below touches the database, and the claim
    // and duration ones create or rewrite a forum thread over REST, so none
    // could answer inside Discord's three-second window on its own. Deferring
    // here instead — as this used to — still left the acknowledgement waiting
    // for a free worker. Unlike the buttons, no branch here opens a further
    // modal, so the listener defers every one of them unconditionally. Replies
    // therefore go through the hook — see `reply`.
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
        case RespawnButtonId.modalGiveStamina => submitGiveStamina(event)
        case other =>
          logger.warn(s"Unknown respawn modal '$other'")
          reply(event, s"${Config.noEmoji} I didn't understand that form.")
      }
    }
  }

  private def submitSchedule(event: ModalInteractionEvent, respawnId: Long): Unit = {
    val guild = event.getGuild
    val service = BotApp.respawnService
    // Off means one slot and no more; on with nothing picked means every day,
    // matching what a repeating booking was before weekdays were a choice.
    val repeats = Option(event.getValue(RepeatField)).forall(_.getAsBoolean)
    val chosenDays = selected(event, DaysField)
      .flatMap(day => Try(java.time.DayOfWeek.of(day.toInt)).toOption)
    val daysOfWeek =
      if (!repeats) com.tibiabot.domain.RespawnSchedule.OneOff
      else if (chosenDays.isEmpty) com.tibiabot.domain.RespawnSchedule.EveryDay
      else com.tibiabot.domain.RespawnSchedule.maskOf(chosenDays)

    // The start comes back from a select menu, so it is a value the bot itself
    // put there — an absolute epoch second, not an offset. The length is typed,
    // so it is the one that can arrive as anything.
    val start = selected(event, StartField).headOption.flatMap(epoch => Try(epoch.toLong).toOption)
    (start, Try(value(event, DurationField).toInt).toOption) match {
      case (None, _) =>
        reply(event, s"${Config.noEmoji} Pick a start time.")
      case (_, None) =>
        reply(event, s"${Config.noEmoji} That needs to be a whole number of minutes.")
      case (Some(startEpoch), Some(duration)) =>
        service.listRespawns(guild.getId).find(_.id == respawnId) match {
          case None => reply(event, s"${Config.noEmoji} That respawn is no longer in the catalogue.")
          case Some(respawn) =>
            val existing = service.schedulesForUser(guild.getId, event.getUser.getId)
            if (existing.size >= com.tibiabot.Config.Respawn.maxSchedulesPerUser)
              reply(event, s"${Config.noEmoji} You already have " +
                s"${com.tibiabot.Config.Respawn.maxSchedulesPerUser} bookings — cancel one first.")
            else {
              val firstStart = java.time.Instant.ofEpochSecond(startEpoch)
                .atZone(java.time.ZoneOffset.UTC)
              service.addSchedule(guild, respawn, event.getUser.getId, event.getUser.getName, "",
                firstStart, duration, daysOfWeek) match {
                case Left(problem) => reply(event, s"${Config.noEmoji} $problem")
                case Right(ScheduleResult.Booked(schedule)) =>
                  reply(event, s"${Config.yesEmoji} Booked **${respawn.displayName}** " +
                    s"${schedule.repeatLabel} for " +
                    s"${RespawnEmbeds.humanDuration(schedule.durationMinutes)}, starting " +
                    s"<t:${schedule.anchorAt.toInstant.getEpochSecond}:f>.")
                // Deliberately not phrased as a booking. Nothing has been written
                // for them, and telling somebody they have a slot they may not get
                // is worse than making them wait for the answer.
                case Right(ScheduleResult.Requested(_, slot, deadline)) =>
                  reply(event, s"${Config.yesEmoji} That time is <@${slot.userId}>'s, so I've asked " +
                    "whether they're actually hunting it.\nIf they say no, or don't answer by " +
                    s"<t:${deadline.toInstant.getEpochSecond}:t>, **${respawn.displayName}** is " +
                    s"booked for you from <t:$startEpoch:t> for " +
                    s"${RespawnEmbeds.humanDuration(duration)} and I'll DM you. " +
                    "Nothing is held for you until then.")
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
      val ids = if (claimRules) List("default", "max", "queue", "stamina") else List("warn", "handover")
      if (ids.exists(field(_).isEmpty)) {
        reply(event, s"${Config.noEmoji} Every setting needs to be a whole number.")
      } else {
        val result =
          if (claimRules)
            BotApp.respawnService.updateSettings(guildId, field("default"), field("max"), field("queue"),
              field("stamina"), None, None)
          else
            BotApp.respawnService.updateSettings(guildId, None, None, None,
              None, field("warn"), field("handover"))
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
      case Some(minutes) if forHolder && !moderates(event.getGuild, event.getMember) =>
        // Re-checked on submit rather than trusted from when the panel opened: a
        // modal can sit open long after somebody's role was taken away.
        reply(event, s"${Config.noEmoji} That needs the **Manage Server** permission, " +
          s"or the **${Permissions.ModeratorRoleName}** role.")
      case Some(minutes) =>
        val service = BotApp.respawnService
        // A moderator may also be handing the hunt to somebody else. Done first,
        // so the duration below lands on whoever ends up holding it — the other
        // order sets the length on the outgoing holder and then moves a hunt of
        // the wrong length.
        val giveTo =
          if (!forHolder) None
          else Option(event.getValue(HolderField))
            .map(_.getAsMentions.getUsers.asScala.toList).getOrElse(Nil).headOption

        val reassigned = giveTo match {
          case None       => Right(None)
          case Some(user) => service.reassignClaim(event.getGuild, respawnId, user.getId, user.getName)
                               .map(_ => Some(user.getId))
        }

        reassigned match {
          case Left(problem) => reply(event, s"${Config.noEmoji} $problem")
          case Right(movedTo) =>
            // Whose claim the length applies to: whoever now holds it after any
            // handover, or the caller when they came through their own Config.
            val target =
              if (!forHolder) Some(event.getUser.getId)
              else movedTo.orElse(service.holderOf(event.getGuild.getId, respawnId).map(_.userId))

            target match {
              case None =>
                reply(event, s"${Config.noEmoji} Nobody is holding that respawn any more.")
              case Some(userId) =>
                service.setClaimDuration(event.getGuild, userId, respawnId, minutes) match {
                  case Left(problem) => reply(event, s"${Config.noEmoji} $problem")
                  case Right((respawn, applied)) =>
                    val whose = if (forHolder && userId != event.getUser.getId) s" for <@$userId>" else ""
                    val moved = movedTo.map(id => s"\nIt's <@$id>'s hunt now.").getOrElse("")
                    val note =
                      if (applied != minutes)
                        s"\nThe hunt had already run longer than that, so it's set to " +
                          s"${RespawnEmbeds.humanDuration(applied)} and ends now."
                      else ""
                    reply(event, s"${Config.yesEmoji} **${respawn.displayName}**$whose is now set to " +
                      s"${RespawnEmbeds.humanDuration(applied)}.$moved$note")
                }
            }
        }
    }

  private def submitGiveStamina(event: ModalInteractionEvent): Unit = {
    val guild = event.getGuild
    // Re-checked on submit, like every other moderator form: a modal can sit
    // open long after a role was taken away.
    if (guild == null) reply(event, s"${Config.noEmoji} That only works inside a server.")
    else if (!moderates(guild, event.getMember))
      reply(event, s"${Config.noEmoji} That needs the **Manage Server** permission, " +
        s"or the **${Permissions.ModeratorRoleName}** role.")
    else Try(value(event, MinutesField).toInt).toOption match {
      case None => reply(event, s"${Config.noEmoji} That needs to be a whole number of minutes.")
      case Some(0) => reply(event, s"${Config.noEmoji} That would change nothing.")
      case Some(minutes) =>
        val who = Option(event.getValue(HolderField))
          .map(_.getAsMentions.getUsers.asScala.toList).getOrElse(Nil).headOption
        (who, BotApp.respawnService.settings(guild.getId)) match {
          case (None, _) => reply(event, s"${Config.noEmoji} Pick somebody to give it to.")
          case (_, None) => reply(event, s"${Config.noEmoji} The respawn claim system isn't set up here.")
          case (Some(user), Some(config)) =>
            val tank = BotApp.respawnService.grantStamina(guild.getId, user.getId, minutes, config)
            val verb = if (minutes > 0) "Gave" else "Took"
            reply(event, s"${Config.yesEmoji} $verb **${RespawnEmbeds.humanDuration(math.abs(minutes))}** " +
              s"${if (minutes > 0) "to" else "from"} <@${user.getId}> — they now have " +
              s"**${RespawnEmbeds.humanDuration(tank.remainingMinutes)}** of " +
              s"${RespawnEmbeds.humanDuration(config.staminaMinutes)} left.")
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

  /** What was picked in a select.
   *
   *  Not interchangeable with [[value]], and the reason is worth spelling out:
   *  a select's answer refuses `getAsString` outright — only a text input or a
   *  radio group will answer to that — so reading one the other way throws
   *  rather than coming back empty. Wrapped in a Try, as the schedule modal had
   *  it, that surfaces as "you picked nothing" no matter what you picked. */
  private def selected(event: ModalInteractionEvent, id: String): List[String] =
    Option(event.getValue(id))
      .map(_.getAsStringList.asScala.toList).getOrElse(Nil)
      .map(_.trim).filter(_.nonEmpty)

  /** Answers through the interaction hook, since `handle` always defers first. */
  private def reply(event: ModalInteractionEvent, text: String): Unit =
    replyEmbed(event, Embeds.response(text))

  private def replyEmbed(event: ModalInteractionEvent, embed: net.dv8tion.jda.api.entities.MessageEmbed): Unit =
    event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue()

}
