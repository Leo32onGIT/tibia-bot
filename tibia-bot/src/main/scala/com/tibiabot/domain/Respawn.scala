package com.tibiabot.domain

import java.time.ZonedDateTime

/** A claimable respawn in a guild's catalogue.
 *
 *  `threadId` is the guild's forum post for this spawn. It lives on the
 *  catalogue row rather than on a claim because one thread is reused for a
 *  spawn's whole life: created on the first claim, archived when the spawn goes
 *  free, un-archived on the next claim. That keeps thread count bounded by the
 *  catalogue size and keeps a spawn's history in one place.
 *
 *  `mapperLink` is stored but not rendered yet — the mapper button and minimap
 *  thumbnail are deliberately out of scope for now, so wiring them later is a
 *  display-only change with the data already present.
 */
final case class Respawn(
  id: Long,
  code: String,
  name: String,
  creature: String,
  region: String,
  world: String,
  mapperLink: String,
  threadId: String,
  source: String,
  addedBy: String,
  /** This spawn's own ceiling on how long a single claim may run, when it has
   *  one. `None` — the usual case — means it follows the guild's
   *  `RespawnSettings.maxDurationMinutes`, so retuning the server still moves
   *  every spawn nobody has singled out.
   *
   *  It *replaces* the guild's number rather than capping it, so a spawn worth a
   *  long session can be given one above the server's ceiling as well as below.
   *  Read it through [[RespawnSettings.maxFor]] and never directly: the point of
   *  that method is that no caller does this resolution by hand.
   *
   *  Defaulted so that the many places building one of these for a test — where
   *  the ceiling is beside the point — need say nothing about it. */
  maxDurationMinutes: Option[Int] = None
) {
  /** "415 — Cult Orcs" — the forum post title and the way a spawn is named
   *  everywhere it's displayed. */
  def displayName: String = s"$code — $name"
}

object Respawn {
  /** Catalogue rows that came from the bundled seed file. */
  val SourceSeed: String = "seed"
  /** Catalogue rows a guild's admins added themselves. `/respawn admin seed`
   *  never touches these. */
  val SourceCustom: String = "custom"
}

/** One person's hold on a respawn — either the active claim or a place in its
 *  queue. Rows are never deleted, only moved to a terminal status, so a thread
 *  can show who hunted a spawn before.
 *
 *  `kind` exists so the not-yet-built scheduled-claim feature can add rows of a
 *  different kind without a migration; today every row is `KindAdHoc`.
 */
final case class RespawnClaim(
  id: Long,
  respawnId: Long,
  userId: String,
  userName: String,
  characterName: String,
  status: String,
  queuePosition: Int,
  claimedAt: ZonedDateTime,
  startsAt: Option[ZonedDateTime],
  endsAt: Option[ZonedDateTime],
  durationMinutes: Int,
  warned: Boolean,
  kind: String,
  limboUntil: Option[ZonedDateTime],
  offerExpiresAt: Option[ZonedDateTime],
  /** How this claim finished, once it has — see [[RespawnClaim.Outcome]]. Empty
   *  while it is still running, and on rows that ended before this was recorded. */
  outcome: Option[String],
  /** When it actually stopped, which is not `endsAt`: a claim released early, or
   *  taken over, ends before its deadline. The audit wants the real one. */
  endedAt: Option[ZonedDateTime],
  /** The recurring schedule this occurrence came from, for rows of kind
   *  `scheduled`. Empty for an ordinary claim. */
  scheduleId: Option[Long] = None,
  /** When the slot's owner was asked whether they are actually hunting it. Set
   *  once and never cleared, which is what makes "asked once per slot" hold:
   *  nobody may ask again from then on, whatever the answer was. */
  askedAt: Option[ZonedDateTime] = None,
  /** How long the owner has to answer before the slot passes to whoever asked. */
  requestDeadline: Option[ZonedDateTime] = None,
  requesterUserId: Option[String] = None,
  requesterUserName: Option[String] = None,
  /** What the guild calls whoever asked, kept beside their account name for the
   *  same reason the owner's is: the DM putting the question names them, and a
   *  request granted becomes their booking under both names. Cleared with the
   *  rest of the request, so it never outlives the question it belongs to. */
  requesterNickname: Option[String] = None,
  /** The window the asker actually booked, which merely overlaps this slot
   *  rather than matching it — so granting the request has to create their
   *  window, not hand over this one.
   *
   *  Empty only on a row from before booking over a slot became the one way to
   *  ask; those hand over the slot as it stands. */
  requestedStartsAt: Option[ZonedDateTime] = None,
  requestedDurationMinutes: Option[Int] = None,
  /** When this slot's owner said, in as many words, that they are hunting it —
   *  by pressing Confirm on the reminder before it starts, or Take Claim once
   *  it has. Empty on every ad-hoc claim, which needs no confirming: making one
   *  is itself the act of turning up.
   *
   *  Set once and never cleared. It is what makes a booking stop being
   *  provisional: a confirmed slot can no longer be asked for (see
   *  [[requestable]]) and is never swept away unhunted (see [[confirmBy]]). */
  confirmedAt: Option[ZonedDateTime] = None,
  /** The deadline by which a booking that started on its own has to be
   *  confirmed, or it is given up on the owner's behalf. Stamped by
   *  `startReservation` and never cleared, so it also serves as the marker for
   *  "this claim began as a booking rather than by somebody pressing Claim".
   *
   *  Empty on an ad-hoc claim, and on every row written before confirmation
   *  existed — which is what keeps the sweep off hunts that were already
   *  running when this shipped. */
  confirmBy: Option[ZonedDateTime] = None,
  /** What this person is called in the guild, as they were called when they
   *  took the spawn — their nickname where they have one, their display name
   *  otherwise. Stamped rather than looked up: the rows outlive membership, and
   *  a name resolved at render time is blank for everyone who has since left.
   *
   *  Last in the list only so adding it left every existing construction alone.
   *  Empty on rows written before it existed, which read as the username. */
  nickname: String = ""
) {
  def isActive: Boolean = status == RespawnClaim.StatusActive
  def isQueued: Boolean = status == RespawnClaim.StatusQueued
  def isOffered: Boolean = status == RespawnClaim.StatusOffered
  /** A scheduled slot that hasn't begun. It holds nothing yet — the spawn may be
   *  free, or somebody else's, right now. */
  def isReserved: Boolean = status == RespawnClaim.StatusReserved

  /** Whether this claim being given up means a handover is in flight and has to
   *  move on to the next person.
   *
   *  True only for an *offered* claim. Somebody abandoning a mere queue place
   *  changes nothing about the spawn — its holder is mid-hunt — and advancing a
   *  handover closes out whoever it is replacing, so treating the two alike ended
   *  a live hunt (or offered it away) because a third party left the queue. */
  def leavingAdvancesHandover: Boolean = isOffered

  /** Whether a handover may legitimately finish this claim. Limbo is the marker
   *  for "time up, next person deciding", so anything without it is still a
   *  running hunt and must be left alone. */
  def eligibleForHandover: Boolean = limboUntil.isDefined

  /** Whether its owner has said they are hunting this one. */
  def confirmed: Boolean = confirmedAt.isDefined

  /** Whether this slot can still be asked for. Once its owner has been asked the
   *  answer stands for that slot, so nobody may ask again — and a slot they have
   *  already confirmed is not open to the question at all, which is the point of
   *  confirming early. */
  def requestable: Boolean = isReserved && askedAt.isEmpty && confirmedAt.isEmpty

  /** A booking that started on its own and is waiting for its owner to say they
   *  are actually there. It holds the spawn meanwhile — it is a real claim — but
   *  is given up on their behalf if the deadline goes by (see [[confirmBy]]). */
  def awaitingConfirmation: Boolean = isActive && confirmBy.isDefined && confirmedAt.isEmpty

  /** Whether somebody is waiting on the owner's answer right now. */
  def requestPending: Boolean = isReserved && requesterUserId.isDefined

  /** When a booked slot is due to finish, whatever time it actually starts.
   *
   *  A booking is a window rather than a stopwatch: a slot that starts late runs
   *  to the end it was booked for, and the minutes lost to a late start are lost.
   *  Running the full length from a late start would push it into whatever is
   *  booked next, which is somebody else's hunt. */
  def bookedEnd: Option[ZonedDateTime] =
    endsAt.orElse(startsAt.map(_.plusMinutes(durationMinutes.toLong)))

  /** How much of that window is still to come at `now`. Never negative. */
  def minutesLeftAt(now: ZonedDateTime): Int =
    bookedEnd.map(end => math.max(0L, java.time.Duration.between(now, end).toMinutes).toInt)
      .getOrElse(0)

  /** The window the asker wants, when they asked by trying to book over this
   *  slot. Empty for a Request-button ask, where what they want *is* this slot. */
  def requestedSlot: Option[(ZonedDateTime, Int)] =
    for {
      start <- requestedStartsAt
      minutes <- requestedDurationMinutes
    } yield (start, minutes)

  /** True while this claim's time is up but it is being held open because the
   *  next person in line still has an unanswered handover offer. The spawn goes
   *  on showing this claimant as its holder, and — because `endsAt` is left
   *  untouched — no extra stamina is charged for the wait. */
  def inLimbo(now: ZonedDateTime): Boolean = limboUntil.exists(_.isAfter(now))
}

object RespawnClaim {
  /** Holding the spawn right now; `startsAt`/`endsAt` are set. */
  val StatusActive: String = "active"
  /** Waiting for the current claim to end; `startsAt`/`endsAt` are empty
   *  because the start time isn't known until the claim ahead actually ends. */
  val StatusQueued: String = "queued"
  /** Reached the front of the queue and been sent a handover offer by DM, but
   *  hasn't pressed Claim yet. `offerExpiresAt` is the deadline; letting it
   *  lapse is treated exactly like leaving the queue.
   *
   *  A separate status from `queued` so an unanswered offer can't be handed out
   *  twice, and so the person isn't silently given a spawn they may have walked
   *  away from. */
  val StatusOffered: String = "offered"
  /** A scheduled slot that hasn't started yet. Visible on the spawn's card so
   *  people can plan around it — and, from phase 2, ask for it. Becomes
   *  [[StatusActive]] when its time comes. */
  val StatusReserved: String = "reserved"
  /** Ran to completion. */
  val StatusFinished: String = "finished"
  /** Released early, declined or ignored a handover offer, skipped for
   *  insufficient stamina, or force-cleared by an admin. */
  val StatusCancelled: String = "cancelled"

  /** Why a claim ended.
   *
   *  The status alone can't answer that — "cancelled" covers a member leaving
   *  early, declining a handover, letting one lapse, running out of stamina and a
   *  moderator stepping in, which are very different things when somebody is
   *  auditing a dispute. Stored as a short string rather than an enum so an
   *  unrecognised value from a future version reads as itself instead of failing
   *  to parse. */
  object Outcome {
    /** Ran its full time. */
    val Completed: String = "completed"
    /** The holder gave it up early. */
    val Released: String = "released"
    /** A moderator moved the holder off it. */
    val Forced: String = "forced"
    /** An admin force-cleared the whole spawn. */
    val Cleared: String = "cleared"
    /** Someone else accepted the handover, so the previous claim ended. */
    val TakenOver: String = "taken-over"
    /** Left the queue before reaching the front. */
    val LeftQueue: String = "left-queue"
    /** Was offered the spawn and said no. */
    val Declined: String = "declined"
    /** Was offered the spawn and never answered. */
    val OfferLapsed: String = "offer-lapsed"
    /** Dropped from the queue because their stamina had gone elsewhere. */
    val NoStamina: String = "no-stamina"
    /** A scheduled slot the bot never got to start — it was still reserved when
     *  its window had already passed, which means the bot was down over it. */
    val Missed: String = "missed"
    /** The schedule behind a reserved slot was cancelled before it started. */
    val ScheduleCancelled: String = "schedule-cancelled"
    /** The slot's owner was already hunting the spawn when it came round, so it
     *  folded into that hunt instead of starting one of its own. */
    val Merged: String = "merged"
    /** The slot's owner said they weren't hunting it, so it went to whoever asked. */
    val GivenUp: String = "given-up"
    /** The slot's owner never answered, so it went to whoever asked. */
    val NoAnswer: String = "no-answer"
    /** A booking started on its own and its owner never confirmed they were
     *  there, so it was given up for them. */
    val Unconfirmed: String = "unconfirmed"
    /** A moderator took this one day off the calendar. The rule behind it, if
     *  there was one, carries on — only the day is written off. */
    val SlotRemoved: String = "slot-removed"
    /** A moderator put this day in somebody else's name. Recorded against the
     *  day the previous owner lost, not against the one they were given. */
    val SlotMoved: String = "slot-moved"
    /** A moderator changed how long this day runs. Recorded only where the day
     *  had to be rewritten to change it — an occurrence a rule had not written
     *  down yet is settled and replaced at the new length, exactly as
     *  [[SlotMoved]] settles one that changes hands. A booking that already had
     *  a row is edited in place and leaves no trail here. */
    val SlotResized: String = "slot-resized"

    /** Plain-English form for the audit log. Unknown values are shown as-is
     *  rather than hidden, so a row written by a newer version still says
     *  something. */
    def label(outcome: String): String = outcome match {
      case Completed   => "ran its full time"
      case Released    => "left early"
      case Forced      => "moved off by a moderator"
      case Cleared     => "cleared by an admin"
      case TakenOver   => "handed over"
      case LeftQueue   => "left the queue"
      case Declined    => "declined the handover"
      case OfferLapsed => "didn't answer the handover"
      case NoStamina   => "dropped, out of stamina"
      case Missed      => "scheduled slot missed"
      case ScheduleCancelled => "schedule cancelled"
      case Merged      => "folded into a hunt already running"
      case GivenUp     => "given up for the night"
      case NoAnswer    => "no answer, passed on"
      case Unconfirmed => "never confirmed, given up"
      case SlotRemoved => "taken off the calendar by a moderator"
      case SlotMoved   => "moved to somebody else by a moderator"
      case SlotResized => "lengthened or shortened by a moderator"
      case other       => other
    }
  }

  /** A claim someone made themselves via `/respawn claim` or the Next button. */
  val KindAdHoc: String = "adhoc"
  /** RESERVED — materialised from a recurring schedule. Not produced by any
   *  code path yet; see the scheduled-claim notes in RespawnService. */
  val KindScheduled: String = "scheduled"
}

/** A booking on a respawn: one slot, repeating on chosen weekdays or not at all.
 *
 *  The rule is an **anchor instant** plus a period, so a slot's time of day is
 *  pure arithmetic on instants and every time the bot shows is a Discord
 *  timestamp rendered in each reader's own zone.
 *
 *  [[RespawnSchedule.daysOfWeek]] is the one part that needs a calendar, and it
 *  is read in server time — the same clock the SS labels use, and the one whose
 *  "Tuesday" a Tibia team means when they say Tuesday. `0` means the booking
 *  does not repeat at all: a single slot, held ahead of time.
 *
 *  The trade on the anchor is that a slot stays fixed in absolute terms, so
 *  after a daylight saving change it lands an hour off relative to server time —
 *  and a slot within an hour of midnight can land on the neighbouring day.
 *  Editing the schedule re-anchors it.
 */
final case class RespawnSchedule(
  id: Long,
  respawnId: Long,
  userId: String,
  userName: String,
  characterName: String,
  /** The first slot's start. Every later one is this plus a whole number of
   *  periods. */
  anchorAt: ZonedDateTime,
  periodMinutes: Int,
  durationMinutes: Int,
  active: Boolean,
  createdAt: ZonedDateTime,
  /** Which weekdays this repeats on, as a bitmask — Monday is the low bit. Last
   *  in the list only so adding it left every existing construction alone. */
  daysOfWeek: Int = RespawnSchedule.EveryDay,
  /** As RespawnClaim.nickname. */
  nickname: String = ""
) {
  /** A booking that comes back around, as opposed to a single slot held ahead of
   *  time. */
  def repeats: Boolean = daysOfWeek != RespawnSchedule.OneOff

  /** Whether this booking runs on the weekday `when` falls on, read in server
   *  time. */
  def coversDay(when: ZonedDateTime): Boolean =
    repeats && (daysOfWeek & RespawnSchedule.bitFor(
      when.withZoneSameInstant(time.Clock.Berlin).getDayOfWeek)) != 0

  /** The first slot starting at or after `from`, if there is one.
   *
   *  `None` once a one-off booking has been and gone: a booking that does not
   *  repeat genuinely has no next slot, and saying so is what lets the caller
   *  stop asking. */
  def nextStartAtOrAfter(from: ZonedDateTime): Option[ZonedDateTime] = {
    val period = math.max(1, periodMinutes).toLong
    val elapsed = java.time.Duration.between(anchorAt, from).toMinutes
    // Round up, so a slot already under way is not offered as the next one.
    val firstDue =
      if (elapsed <= 0) anchorAt
      else anchorAt.plusMinutes(((elapsed + period - 1) / period) * period)

    if (!repeats) Some(anchorAt).filter(!_.isBefore(from))
    else {
      // A week of steps always reaches the next allowed weekday, whatever the
      // period — and cannot spin if a bad row stored a silly one.
      val stepsPerWeek = math.max(1, (7L * RespawnSchedule.Daily / period).toInt)
      Iterator.iterate(firstDue)(_.plusMinutes(period))
        .take(stepsPerWeek + 1).find(coversDay)
    }
  }

  /** The next slot this rule will actually produce, skipping days it has given
   *  up — see `persistence.ScheduleOccurrence`.
   *
   *  What every surface that says "your next booking is…" wants. Asking without
   *  the days given up is what left a card naming tonight after tonight had been
   *  handed to somebody else: the rule says every day, and by itself it has no
   *  way to know one of those days is no longer its own to offer.
   *
   *  Bounded like [[occurrencesBetween]], and for the same reason — though it
   *  cannot realistically walk far, since only a day a row was written for can
   *  be given up and rows exist only inside the look-ahead.
   */
  def nextStartAtOrAfter(from: ZonedDateTime, givenUp: Set[java.time.Instant]): Option[ZonedDateTime] = {
    var cursor = nextStartAtOrAfter(from)
    var guard = 0
    while (cursor.exists(start => givenUp.contains(start.toInstant)) &&
           guard < RespawnSchedule.OccurrenceLimit) {
      cursor = cursor.flatMap(start => nextStartAtOrAfter(start.plusMinutes(1)))
      guard += 1
    }
    cursor
  }

  /** Every slot starting inside a window. Bounded, so a bad row cannot make this
   *  run away. */
  def occurrencesBetween(from: ZonedDateTime, to: ZonedDateTime): List[ZonedDateTime] = {
    val found = List.newBuilder[ZonedDateTime]
    var cursor = nextStartAtOrAfter(from)
    var guard = 0
    while (cursor.exists(!_.isAfter(to)) && guard < RespawnSchedule.OccurrenceLimit) {
      val start = cursor.get
      found += start
      cursor = nextStartAtOrAfter(start.plusMinutes(1))
      guard += 1
    }
    found.result()
  }

  /** Whether `start` is genuinely one of this schedule's slots — the guard
   *  against materialising an occurrence at a time the rule never names. */
  def startsAt(start: ZonedDateTime): Boolean = {
    val period = math.max(1, periodMinutes).toLong
    val elapsed = java.time.Duration.between(anchorAt, start).toMinutes
    if (!repeats) elapsed == 0
    else elapsed >= 0 && elapsed % period == 0 && coversDay(start)
  }

  def endOf(start: ZonedDateTime): ZonedDateTime = start.plusMinutes(durationMinutes.toLong)

  /** How this booking recurs, in words: "once", "every day", "every Tue, Wed". */
  def repeatLabel: String = RespawnSchedule.repeatLabel(daysOfWeek)

  /** Whether any slot this booking produces between `from` and `to` runs over an
   *  already-booked one.
   *
   *  Needed alongside [[RespawnSchedule.clash]] because a slot handed to whoever
   *  asked for it has no schedule behind it — comparing rules to rules cannot see
   *  it, and would let somebody book straight over it. */
  def overlapsSlot(slot: RespawnClaim, from: ZonedDateTime, to: ZonedDateTime): Boolean =
    slot.startsAt.exists { start =>
      val end = start.plusMinutes(slot.durationMinutes.toLong)
      occurrencesBetween(from, to).exists(occurrence =>
        occurrence.isBefore(end) && start.isBefore(endOf(occurrence)))
    }
}

/** What to do about a booking that clashes with one already there.
 *
 *  Only one shape of clash is worth putting to the other person, and the rest
 *  are refusals with different reasons — which the caller needs to keep apart,
 *  since "try again nearer the day" and "that time is taken" ask for very
 *  different things from whoever is booking. */
sealed trait ClashVerdict
object ClashVerdict {
  /** Ask this slot's owner whether they are actually hunting it. */
  final case class Ask(slot: RespawnClaim) extends ClashVerdict
  /** The clash is with the asker's own booking. */
  case object Yours extends ClashVerdict
  /** A repeating booking, where an answer about one evening cannot settle every
   *  week from now on. */
  case object Repeats extends ClashVerdict
  /** The clashing rule's slot hasn't been booked yet — too far ahead to ask. */
  case object TooFarAhead extends ClashVerdict
  /** It runs over more than one booking, so there is nobody single to ask. */
  case object ManySlots extends ClashVerdict
  /** That slot's owner has already been asked once, which is the limit. */
  case object AlreadyAsked extends ClashVerdict
  /** Its owner has already confirmed they are hunting it, so there is nothing
   *  to ask — confirming early is exactly how somebody says "don't ask me". */
  case object Confirmed extends ClashVerdict
}

object RespawnSchedule {
  /** The only period on offer: a slot recurs at the same time of day, and
   *  the day mask decides which days it is allowed to land on. */
  val Daily: Int = 24 * 60

  /** A booking that happens once and is then spent. */
  val OneOff: Int = 0

  /** All seven days set — what a plain daily booking is, and what every schedule
   *  made before weekdays existed becomes. */
  val EveryDay: Int = 127

  /** Ceiling on how many slots a window walk will produce, so a nonsense period
   *  or an over-wide window cannot spin. */
  private[domain] val OccurrenceLimit: Int = 400

  def bitFor(day: java.time.DayOfWeek): Int = 1 << (day.getValue - 1)

  def maskOf(days: Iterable[java.time.DayOfWeek]): Int =
    days.foldLeft(0)((mask, day) => mask | bitFor(day))

  def daysIn(mask: Int): List[java.time.DayOfWeek] =
    java.time.DayOfWeek.values().toList.filter(day => (mask & bitFor(day)) != 0)

  /** Whether two bookings on the same spawn ever run at the same time.
   *
   *  Answered by walking the slots each one actually produces rather than by
   *  arithmetic on offsets: with weekday masks and one-offs in the mix, "same
   *  time of day" is no longer the same question as "same slot", and a rule
   *  clever enough to answer it in closed form would be a rule nobody could
   *  check. A week and a bit from the later anchor covers every case — the
   *  patterns repeat weekly — and starting a day early catches a window that
   *  opens before that anchor and runs past it.
   */
  def clash(a: RespawnSchedule, b: RespawnSchedule): Boolean = {
    val later = if (a.anchorAt.isAfter(b.anchorAt)) a.anchorAt else b.anchorAt
    val from = later.minusDays(1)
    val to = from.plusDays(9)
    val slotsOfB = b.occurrencesBetween(from, to)
    a.occurrencesBetween(from, to).exists { startA =>
      val endA = a.endOf(startA)
      slotsOfB.exists(startB => startA.isBefore(b.endOf(startB)) && startB.isBefore(endA))
    }
  }

  /** Whether `schedule` has given up every day it would contest with
   *  `candidate`, and so no longer stands in its way.
   *
   *  [[clash]] compares two rules, and a rule is a sentence about every day. It
   *  cannot know that one of those days was handed to somebody else, or taken
   *  off the calendar — that lives in the row for the day, and `settled` is the
   *  starts of the days whose rows say they are over. Without this, giving a
   *  Thursday away left the rule still defending it, and the next person to want
   *  that evening was refused on behalf of a booking nobody was going to hunt.
   *
   *  All-or-nothing on purpose. A repeating rule that has given up one Thursday
   *  still owns every other, so it takes every contested day being settled
   *  before it stops counting. A day too far ahead to have a row has settled
   *  nothing, which is what keeps [[ClashVerdict.TooFarAhead]] meaning what it
   *  says rather than quietly becoming a yes — and why no contested day at all
   *  inside the window is false rather than true.
   */
  def surrendered(schedule: RespawnSchedule, candidate: RespawnSchedule,
                  settled: Set[java.time.Instant],
                  from: ZonedDateTime, to: ZonedDateTime): Boolean = {
    val theirs = candidate.occurrencesBetween(from, to)
    val contested = schedule.occurrencesBetween(from, to).filter { start =>
      val end = schedule.endOf(start)
      theirs.exists(other => other.isBefore(end) && start.isBefore(candidate.endOf(other)))
    }
    contested.nonEmpty && contested.forall(start => settled.contains(start.toInstant))
  }

  /** What a clashing booking should do about it, given the rules and the booked
   *  slots it runs over.
   *
   *  A judgement rather than a refusal, and pure so the whole table can be
   *  checked without a database. The order matters: "that's your own booking"
   *  outranks every other complaint, and a repeating booking is settled before
   *  anything is said about which slots it hit, since the answer would be the
   *  same however many there were. */
  def verdict(candidate: RespawnSchedule, schedules: List[RespawnSchedule],
              slots: List[RespawnClaim]): ClashVerdict =
    if (slots.exists(_.userId == candidate.userId) || schedules.exists(_.userId == candidate.userId))
      ClashVerdict.Yours
    else if (candidate.repeats) ClashVerdict.Repeats
    // Every clashing rule has to have produced one of these slots, or the ask
    // would have nothing to hang on.
    else if (!schedules.forall(schedule => slots.exists(_.scheduleId.contains(schedule.id))))
      ClashVerdict.TooFarAhead
    else slots match {
      case slot :: Nil if slot.requestable => ClashVerdict.Ask(slot)
      // Ahead of AlreadyAsked, which it would otherwise fall into now that
      // `requestable` covers both — and they are not the same refusal: one says
      // the question has been used up, this one says it was never open.
      case slot :: Nil if slot.confirmed   => ClashVerdict.Confirmed
      case _ :: Nil                        => ClashVerdict.AlreadyAsked
      case _                               => ClashVerdict.ManySlots
    }

  /** How a mask reads to a person. Named days rather than a count, because
   *  "every Tue, Wed, Thu, Sun" is the thing a team checks at a glance. */
  def repeatLabel(mask: Int): String =
    if (mask == OneOff) "once"
    else if (mask == EveryDay) "every day"
    else daysIn(mask)
      .map(_.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH))
      .mkString("every ", ", ", "")

  /** The next `count` half hours in `zone`, for the schedule picker.
   *
   *  Half hours rather than whole ones because plenty of hunts start on the
   *  half — the picker reads SS+1, SS+1.5, SS+2.
   *
   *  Rounded *in that zone* rather than in UTC, which matters for the zones
   *  offset by three quarters of an hour — Nepal, Chatham — where the boundaries
   *  don't line up with UTC's.
   *
   *  Returns instants. The zone is only used to decide where the boundaries fall
   *  and to label them; what a booking stores is still an absolute instant, so
   *  the recurrence itself stays free of any timezone. */
  def upcomingStarts(from: ZonedDateTime, zone: java.time.ZoneId, count: Int): List[ZonedDateTime] = {
    val local = from.withZoneSameInstant(zone)
    val hour = local.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
    // Strictly after `from`, so a picker opened at exactly half past does not
    // offer the half hour that is already here.
    val first = if (local.getMinute < 30) hour.plusMinutes(30) else hour.plusHours(1)
    (0 until math.max(0, count)).map(step => first.plusMinutes(30L * step)).toList
  }
}

/** A guild's respawn-system settings. Defaults come from Config.Respawn and are
 *  written into the row when the forum is first created, so a later change to
 *  the bot's defaults never silently re-tunes a guild that already went live. */
final case class RespawnSettings(
  forumChannel: String,
  boardThread: String,
  defaultDurationMinutes: Int,
  maxDurationMinutes: Int,
  queueLimit: Int,
  staminaMinutes: Int,
  warnMinutes: Int,
  /** How long someone has to accept a handover offer before it's assumed they
   *  walked away and the spawn moves on to the next person. */
  handoverMinutes: Int
) {
  /** The longest a single claim on `respawn` may run.
   *
   *  The one place the guild ceiling and a spawn's own override are reconciled.
   *  Every check that used to read `maxDurationMinutes` straight off these
   *  settings goes through here instead — claiming, extending, a moderator
   *  setting somebody else's length, and booking a repeating slot — because ten
   *  call sites each doing their own resolution is how a limit ends up
   *  disagreeing with itself depending on which door you came in.
   *
   *  A spawn's own number wins outright, above the guild's as well as below.
   *  "Maximum claim time for this spawn" reads as an absolute, and an admin who
   *  types six hours on a raid spawn should not have it silently clamped to the
   *  server's four.
   *
   *  Not applied to a member's *personal* default — see
   *  `RespawnService.saveUserPrefs`. A preference with no spawn attached has no
   *  spawn's ceiling to be measured against, so that one stays guild-level. */
  def maxFor(respawn: Respawn): Int =
    respawn.maxDurationMinutes.getOrElse(maxDurationMinutes)
}

/** One member's own preferences, overriding the guild defaults for their own
 *  claims. Set through the Config button on the spawns board.
 *
 *  Both are `None` until the member changes them, which is what lets a guild
 *  retune its defaults and have everyone who never expressed a preference
 *  follow along.
 */
final case class RespawnUserPrefs(
  userId: String,
  /** How long their claims run when they don't say otherwise. */
  defaultDurationMinutes: Option[Int],
  /** How far ahead of a claim ending they want their reminder DM. `Some(0)`
   *  means they've deliberately turned reminders off, which is why this is an
   *  Option of a possibly-zero value rather than just zero-means-unset. */
  warnMinutes: Option[Int]
) {
  def defaultDurationOr(guildDefault: Int): Int = defaultDurationMinutes.getOrElse(guildDefault)
  def warnMinutesOr(guildDefault: Int): Int = warnMinutes.getOrElse(guildDefault)
}

object RespawnUserPrefs {
  def none(userId: String): RespawnUserPrefs = RespawnUserPrefs(userId, None, None)

  /** Longest reminder lead time a member may ask for. A claim can only run for
   *  `maxDurationMinutes` anyway, so anything beyond this would fire the moment
   *  the claim started. */
  val MaxWarnMinutes: Int = 12 * 60
}

/** A user's remaining claim budget for the current server-save day.
 *
 *  Stamina is reserved up front for a claim's full duration rather than
 *  accrued as it's spent — that's what lets two spawns be held at once
 *  (explicitly wanted) while still bounding the total to the tank. Releasing
 *  early refunds the unused remainder.
 */
final case class Stamina(userId: String, usedMinutes: Int, budgetMinutes: Int, resetAt: ZonedDateTime) {
  /** Unlimited when the budget is zero (stamina disabled for the guild). */
  def unlimited: Boolean = budgetMinutes <= 0
  def remainingMinutes: Int = if (unlimited) Int.MaxValue else math.max(0, budgetMinutes - usedMinutes)
  def canAfford(minutes: Int): Boolean = unlimited || minutes <= remainingMinutes
}
