package com.tibiabot.domain

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{DayOfWeek, ZoneOffset, ZonedDateTime}

/** The recurrence arithmetic behind booked slots.
 *
 *  The time of day is an anchor instant plus a period, so working it out is pure
 *  arithmetic on instants and every time shown is a Discord timestamp rendered
 *  in the reader's own zone. Which weekday a slot falls on is the one part that
 *  needs a calendar, and it is answered in server time — the anchor below is
 *  20:00 UTC on Thursday 30 July 2026, which is 22:00 Thursday in Berlin, so the
 *  two agree here and the weekday cases say what they mean.
 */
class RespawnScheduleSpec extends AnyFunSuite with Matchers {

  private implicit class Pipe[A](value: A) {
    def |>[B](f: A => B): B = f(value)
  }


  private val anchor = ZonedDateTime.parse("2026-07-30T20:00:00Z")

  private def schedule(durationMinutes: Int = 120, period: Int = RespawnSchedule.Daily,
                       days: Int = RespawnSchedule.EveryDay) =
    RespawnSchedule(1L, 1L, "u1", "One", "", anchor, period, durationMinutes,
      active = true, createdAt = anchor, daysOfWeek = days)

  private def onlyOn(days: DayOfWeek*) = schedule(days = RespawnSchedule.maskOf(days))

  test("before the first slot, the next one is the anchor itself") {
    schedule().nextStartAtOrAfter(anchor.minusHours(5)) shouldBe Some(anchor)
    schedule().nextStartAtOrAfter(anchor) shouldBe Some(anchor)
  }

  test("a slot already under way is not offered as the next one") {
    // Mid-slot: the next start is tomorrow's, not the one running now.
    schedule().nextStartAtOrAfter(anchor.plusMinutes(30)) shouldBe Some(anchor.plusDays(1))
  }

  test("the next slot rolls forward a whole number of days, however far ahead you ask") {
    schedule().nextStartAtOrAfter(anchor.plusDays(1)) shouldBe Some(anchor.plusDays(1))
    schedule().nextStartAtOrAfter(anchor.plusDays(3).plusMinutes(1)) shouldBe Some(anchor.plusDays(4))
    schedule().nextStartAtOrAfter(anchor.plusDays(365)) shouldBe Some(anchor.plusDays(365))
  }

  test("the answer is the same instant whatever zone the question is asked in") {
    // The time of day is anchored on an instant: no zone can change the result.
    val inTokyo = anchor.plusMinutes(30).withZoneSameInstant(ZoneOffset.ofHours(9))
    schedule().nextStartAtOrAfter(inTokyo).map(_.toInstant) shouldBe Some(anchor.plusDays(1).toInstant)
  }

  test("only real slot times are recognised as starts") {
    schedule().startsAt(anchor) shouldBe true
    schedule().startsAt(anchor.plusDays(2)) shouldBe true
    schedule().startsAt(anchor.plusMinutes(1)) shouldBe false
    // Before the schedule existed is not a slot, however well it lines up.
    schedule().startsAt(anchor.minusDays(1)) shouldBe false
  }

  // --- one-off bookings -----------------------------------------------------

  test("a one-off booking has its slot and then no more") {
    val once = schedule(days = RespawnSchedule.OneOff)
    once.repeats shouldBe false
    once.nextStartAtOrAfter(anchor.minusHours(5)) shouldBe Some(anchor)
    once.nextStartAtOrAfter(anchor) shouldBe Some(anchor)
    // Once it has started it is spent, which is what retires the booking.
    once.nextStartAtOrAfter(anchor.plusMinutes(1)) shouldBe None
    once.nextStartAtOrAfter(anchor.plusDays(1)) shouldBe None
  }

  test("a one-off recognises its own slot and nothing a day later") {
    val once = schedule(days = RespawnSchedule.OneOff)
    once.startsAt(anchor) shouldBe true
    once.startsAt(anchor.plusDays(1)) shouldBe false
    once.occurrencesBetween(anchor.minusDays(1), anchor.plusDays(30)) shouldBe List(anchor)
  }

  // --- weekdays -------------------------------------------------------------

  test("a booking only lands on the days it was given") {
    // The anchor is a Thursday. Asking from the anchor, a Tue/Sun booking skips
    // to Sunday rather than starting on the Thursday it was anchored to.
    val teamNights = onlyOn(DayOfWeek.TUESDAY, DayOfWeek.SUNDAY)
    val next = teamNights.nextStartAtOrAfter(anchor)
    next shouldBe Some(anchor.plusDays(3))
    next.map(_.getDayOfWeek) shouldBe Some(DayOfWeek.SUNDAY)

    teamNights.nextStartAtOrAfter(anchor.plusDays(3).plusMinutes(1))
      .map(_.getDayOfWeek) shouldBe Some(DayOfWeek.TUESDAY)
  }

  test("a weekday booking repeats week after week without drifting") {
    val tuesdays = onlyOn(DayOfWeek.TUESDAY)
    val starts = tuesdays.occurrencesBetween(anchor, anchor.plusDays(28))
    starts.map(_.getDayOfWeek).distinct shouldBe List(DayOfWeek.TUESDAY)
    starts.size shouldBe 4
    // Exactly a week apart, so the time of day never slides.
    starts.zip(starts.drop(1)).foreach { case (a, b) => b shouldBe a.plusDays(7) }
  }

  test("a weekday not in the mask is not a start, however well it lines up") {
    val tuesdays = onlyOn(DayOfWeek.TUESDAY)
    tuesdays.startsAt(anchor) shouldBe false
    tuesdays.startsAt(anchor.plusDays(5)) shouldBe true
  }

  test("every day and all seven days chosen are the same booking") {
    val all = onlyOn(DayOfWeek.values().toIndexedSeq: _*)
    all.daysOfWeek shouldBe RespawnSchedule.EveryDay
    all.occurrencesBetween(anchor, anchor.plusDays(6)) shouldBe
      schedule().occurrencesBetween(anchor, anchor.plusDays(6))
  }

  test("how a booking recurs reads as words, not a bitmask") {
    RespawnSchedule.repeatLabel(RespawnSchedule.OneOff) shouldBe "once"
    RespawnSchedule.repeatLabel(RespawnSchedule.EveryDay) shouldBe "every day"
    RespawnSchedule.maskOf(Seq(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY)) |> { mask =>
      RespawnSchedule.repeatLabel(mask) shouldBe "every Tue, Wed, Sun"
      RespawnSchedule.daysIn(mask) shouldBe
        List(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY)
    }
  }

  test("a window with no slots in it comes back empty rather than looping") {
    onlyOn(DayOfWeek.TUESDAY).occurrencesBetween(anchor, anchor.plusDays(2)) shouldBe empty
    schedule(days = RespawnSchedule.OneOff)
      .occurrencesBetween(anchor.plusDays(1), anchor.plusDays(400)) shouldBe empty
  }

  // --- clashes --------------------------------------------------------------

  test("two bookings clash when their windows overlap on a day they share") {
    val evening = schedule(durationMinutes = 120)
    val overlapping = evening.copy(anchorAt = anchor.plusMinutes(60), userId = "u2")
    val after = evening.copy(anchorAt = anchor.plusMinutes(120), userId = "u2")

    RespawnSchedule.clash(evening, overlapping) shouldBe true
    // Starting exactly as the other ends is not an overlap — that is a handover.
    RespawnSchedule.clash(evening, after) shouldBe false
  }

  test("bookings on different weekdays never clash, however well the times line up") {
    val tuesdays = onlyOn(DayOfWeek.TUESDAY)
    val wednesdays = onlyOn(DayOfWeek.WEDNESDAY).copy(userId = "u2")
    // Same time of day, same spawn — but they are never in the room together.
    RespawnSchedule.clash(tuesdays, wednesdays) shouldBe false
    RespawnSchedule.clash(tuesdays, onlyOn(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY)) shouldBe true
  }

  test("a one-off only clashes on the day it actually falls on") {
    val tuesdays = onlyOn(DayOfWeek.TUESDAY)
    // The anchor is a Thursday, so a one-off there misses the Tuesday booking;
    // five days on it lands squarely on one.
    val onThursday = schedule(days = RespawnSchedule.OneOff).copy(userId = "u2")
    val onTuesday = onThursday.copy(anchorAt = anchor.plusDays(5))

    RespawnSchedule.clash(tuesdays, onThursday) shouldBe false
    RespawnSchedule.clash(tuesdays, onTuesday) shouldBe true
  }

  test("two one-offs on the same weekday but different weeks do not clash") {
    // The old offset arithmetic could not tell these apart: same time of day,
    // same weekday, and no shared slot at all.
    val first = schedule(days = RespawnSchedule.OneOff)
    val weekLater = first.copy(anchorAt = anchor.plusDays(7), userId = "u2")
    RespawnSchedule.clash(first, weekLater) shouldBe false
    RespawnSchedule.clash(first, first.copy(userId = "u2")) shouldBe true
  }

  test("a booking that has not started yet cannot clash with one already spent") {
    val spent = schedule(days = RespawnSchedule.OneOff)
    val muchLater = schedule().copy(anchorAt = anchor.plusDays(30), userId = "u2")
    RespawnSchedule.clash(spent, muchLater) shouldBe false
  }

  test("a window running past midnight still clashes with the next day's booking") {
    // 23:00 for three hours reaches into Friday, where a Friday booking is
    // waiting — the case a same-day comparison would miss.
    val lateThursday = onlyOn(DayOfWeek.THURSDAY)
      .copy(anchorAt = anchor.plusHours(1), durationMinutes = 180)
    val earlyFriday = onlyOn(DayOfWeek.FRIDAY)
      .copy(anchorAt = anchor.plusHours(3), durationMinutes = 60, userId = "u2")
    RespawnSchedule.clash(lateThursday, earlyFriday) shouldBe true
  }

  test("a slot ends its duration after it starts") {
    schedule(durationMinutes = 90).endOf(anchor) shouldBe anchor.plusMinutes(90)
  }

  test("a zero or negative period can't wedge the arithmetic") {
    // Guarded rather than trusted: a bad row would otherwise divide by zero, or
    // step backwards forever in the materialiser, which walks slot by slot up to
    // the look-ahead. Both clamp to a one-minute period and still answer with a
    // time at or after the one asked about.
    val from = anchor.plusMinutes(5)
    schedule(period = 0).nextStartAtOrAfter(from) shouldBe Some(from)
    schedule(period = -10).nextStartAtOrAfter(from) shouldBe Some(from)
  }

  test("the picker offers half hours in the guild's own clock") {
    val berlin = java.time.ZoneId.of("Europe/Berlin")
    // 20:17 Berlin — the first option is the next half hour, not 17 past.
    val from = ZonedDateTime.parse("2026-07-30T18:17:00Z")
    val starts = RespawnSchedule.upcomingStarts(from, berlin, 4)

    starts.map(_.withZoneSameInstant(berlin).getHour) shouldBe List(20, 21, 21, 22)
    starts.map(_.withZoneSameInstant(berlin).getMinute) shouldBe List(30, 0, 30, 0)
    starts.head.isAfter(from) shouldBe true
  }

  test("a picker opened on the half hour offers the next one, not this one") {
    // Otherwise the first option is a start time that has already arrived.
    val berlin = java.time.ZoneId.of("Europe/Berlin")
    val onTheHalf = ZonedDateTime.parse("2026-07-30T18:30:00Z")
    RespawnSchedule.upcomingStarts(onTheHalf, berlin, 1).head shouldBe
      ZonedDateTime.parse("2026-07-30T19:00:00Z").withZoneSameInstant(berlin)

    val onTheHour = ZonedDateTime.parse("2026-07-30T18:00:00Z")
    RespawnSchedule.upcomingStarts(onTheHour, berlin, 1).head shouldBe
      ZonedDateTime.parse("2026-07-30T18:30:00Z").withZoneSameInstant(berlin)
  }

  test("boundaries follow the zone, not UTC") {
    // Nepal is 5:45 off UTC, so its half hours land at :15 and :45 in UTC terms.
    // Rounding in UTC would offer times that are not on the half hour there.
    val kathmandu = java.time.ZoneId.of("Asia/Kathmandu")
    val starts = RespawnSchedule.upcomingStarts(
      ZonedDateTime.parse("2026-07-30T18:17:00Z"), kathmandu, 2)
    starts.map(_.withZoneSameInstant(kathmandu).getMinute) shouldBe List(30, 0)
    starts.map(_.toInstant.getEpochSecond % 1800) shouldBe List(900L, 900L)
  }

  test("the picker never offers more than Discord allows in a select") {
    val berlin = java.time.ZoneId.of("Europe/Berlin")
    RespawnSchedule.upcomingStarts(anchor, berlin, 25) should have size 25
    RespawnSchedule.upcomingStarts(anchor, berlin, 0) shouldBe empty
    RespawnSchedule.upcomingStarts(anchor, berlin, -1) shouldBe empty
  }

  test("every claim outcome reads as something, including ones added later") {
    // The label map is what an audit shows. An outcome with no case falls through
    // to itself rather than blanking the row — which is how "merged" behaved
    // before it had a label, and why this is checked rather than assumed.
    RespawnClaim.Outcome.label(RespawnClaim.Outcome.Merged) should include("folded into")
    RespawnClaim.Outcome.label("something-new-later") shouldBe "something-new-later"
  }

  // --- clashing with slots somebody has already booked ---------------------

  private val window = (anchor.minusDays(1), anchor.plusDays(2))

  private def slot(start: ZonedDateTime, durationMinutes: Int = 120, owner: String = "u2",
                   scheduleId: Option[Long] = Some(9L), askedAt: Option[ZonedDateTime] = None,
                   confirmedAt: Option[ZonedDateTime] = None) =
    RespawnClaim(7L, 1L, owner, owner, "", RespawnClaim.StatusReserved, 0, anchor,
      Some(start), Some(start.plusMinutes(durationMinutes.toLong)), durationMinutes,
      warned = false, RespawnClaim.KindScheduled, None, None, None, None,
      scheduleId = scheduleId, askedAt = askedAt, confirmedAt = confirmedAt)

  private def oneOff(start: ZonedDateTime, durationMinutes: Int = 120, owner: String = "u1") =
    RespawnSchedule(0L, 1L, owner, owner, "", start, RespawnSchedule.Daily, durationMinutes,
      active = true, createdAt = anchor, daysOfWeek = RespawnSchedule.OneOff)

  test("a booking runs over a slot it overlaps, whichever end it hangs off") {
    val booked = slot(anchor)
    oneOff(anchor.plusMinutes(60)).overlapsSlot(booked, window._1, window._2) shouldBe true
    oneOff(anchor.minusMinutes(60)).overlapsSlot(booked, window._1, window._2) shouldBe true
    // Touching end to end is not an overlap: one starts exactly as the other stops.
    oneOff(anchor.plusMinutes(120)).overlapsSlot(booked, window._1, window._2) shouldBe false
    oneOff(anchor.minusMinutes(120)).overlapsSlot(booked, window._1, window._2) shouldBe false
  }

  test("a repeating booking runs over a slot on any night it lands on") {
    // Tomorrow's slot, hit by the second occurrence of a daily booking.
    schedule().overlapsSlot(slot(anchor.plusDays(1)), window._1, window._2) shouldBe true
    // The same slot, missed by a booking that only runs the day the anchor is on.
    onlyOn(anchor.getDayOfWeek).overlapsSlot(slot(anchor.plusDays(1)), window._1, window._2) shouldBe false
  }

  test("a booked slot ends when it was booked to, however late it starts") {
    val booked = slot(anchor, durationMinutes = 120)
    // Answering four minutes into your own hunt costs you those four minutes —
    // it does not move the end. Running the full length from a late start would
    // push into whatever is booked next, and the person who booked it would find
    // the spawn held and lose their slot to a queue place.
    booked.bookedEnd shouldBe Some(anchor.plusMinutes(120))
    booked.minutesLeftAt(anchor) shouldBe 120
    booked.minutesLeftAt(anchor.plusMinutes(4)) shouldBe 116
    // Nothing left, and never a negative — a window fully gone by is closed as
    // missed rather than started.
    booked.minutesLeftAt(anchor.plusMinutes(120)) shouldBe 0
    booked.minutesLeftAt(anchor.plusHours(3)) shouldBe 0
  }

  test("a slot with no end recorded falls back to its booked length") {
    val open = slot(anchor, durationMinutes = 90).copy(endsAt = None)
    open.bookedEnd shouldBe Some(anchor.plusMinutes(90))
    open.minutesLeftAt(anchor.plusMinutes(30)) shouldBe 60
  }

  test("a clash with a slot nobody has asked about becomes a question for its owner") {
    val booked = slot(anchor)
    RespawnSchedule.verdict(oneOff(anchor), Nil, List(booked)) shouldBe ClashVerdict.Ask(booked)
  }

  test("your own booking is never something to ask yourself about") {
    // Whether it is seen as a slot or as the rule behind it.
    RespawnSchedule.verdict(oneOff(anchor), Nil, List(slot(anchor, owner = "u1"))) shouldBe
      ClashVerdict.Yours
    RespawnSchedule.verdict(oneOff(anchor), List(schedule()), List(slot(anchor))) shouldBe
      ClashVerdict.Yours
  }

  test("a repeating booking is refused rather than asked about") {
    // One evening's answer cannot settle every night from now on.
    RespawnSchedule.verdict(schedule(days = RespawnSchedule.EveryDay).copy(userId = "u3"),
      Nil, List(slot(anchor))) shouldBe ClashVerdict.Repeats
  }

  test("a rule whose slot hasn't been booked yet is too far ahead to ask about") {
    val theirs = schedule().copy(id = 9L, userId = "u2")
    // The rule clashes, but the night in question is past the look-ahead, so no
    // slot of it exists to hang the question on.
    RespawnSchedule.verdict(oneOff(anchor), List(theirs), Nil) shouldBe ClashVerdict.TooFarAhead
    // The same rule, once that night's slot is on the books.
    RespawnSchedule.verdict(oneOff(anchor), List(theirs), List(slot(anchor, scheduleId = Some(9L)))) shouldBe
      ClashVerdict.Ask(slot(anchor, scheduleId = Some(9L)))
  }

  test("a booking across two slots has nobody single to ask") {
    RespawnSchedule.verdict(oneOff(anchor, durationMinutes = 240),
      Nil, List(slot(anchor), slot(anchor.plusMinutes(120), owner = "u3"))) shouldBe
      ClashVerdict.ManySlots
  }

  test("a slot its owner has already been asked about is not asked about again") {
    RespawnSchedule.verdict(oneOff(anchor), Nil, List(slot(anchor, askedAt = Some(anchor)))) shouldBe
      ClashVerdict.AlreadyAsked
  }

  test("a slot its owner has confirmed is refused as confirmed, not as already asked") {
    // Confirming early is how somebody says "don't ask me about this one", and it
    // is a different answer from having used up the one question — so it gets its
    // own verdict rather than falling into AlreadyAsked.
    val settled = slot(anchor, confirmedAt = Some(anchor.minusMinutes(15)))
    settled.requestable shouldBe false
    RespawnSchedule.verdict(oneOff(anchor), Nil, List(settled)) shouldBe ClashVerdict.Confirmed
  }

  test("an unconfirmed slot nobody has asked about is still open to the question") {
    slot(anchor).requestable shouldBe true
  }

  // --- days a rule has given up --------------------------------------------
  // A rule speaks for every day; what became of one of them lives in its row.
  // Without consulting those, a day handed to somebody else — or taken off the
  // calendar by a moderator — left the old rule still defending it, and the
  // next person to want that evening was refused on behalf of a booking that no
  // longer existed.

  // What a rule offers as its next evening, once one of its days has gone. The
  // card listed tonight twice without this — once as the booking that had taken
  // it, and once as the rule that used to hold it, at the very same hour.
  test("the next slot skips a day the booking has given up") {
    val daily = schedule()
    daily.nextStartAtOrAfter(anchor, Set.empty) shouldBe Some(anchor)
    daily.nextStartAtOrAfter(anchor, Set(anchor.toInstant)) shouldBe Some(anchor.plusDays(1))
    daily.nextStartAtOrAfter(anchor, Set(anchor.toInstant, anchor.plusDays(1).toInstant)) shouldBe
      Some(anchor.plusDays(2))
  }

  test("a day given up that is not the next one changes nothing") {
    schedule().nextStartAtOrAfter(anchor, Set(anchor.plusDays(3).toInstant)) shouldBe Some(anchor)
  }

  test("a one-off that has given up its only evening has no next slot at all") {
    val once = schedule(days = RespawnSchedule.OneOff)
    once.nextStartAtOrAfter(anchor, Set(anchor.toInstant)) shouldBe None
  }

  test("giving up a day of a weekly booking moves it on a week, not a day") {
    val tuesdays = onlyOn(DayOfWeek.TUESDAY)
    val firstTuesday = tuesdays.nextStartAtOrAfter(anchor).getOrElse(fail("no Tuesday ahead"))
    tuesdays.nextStartAtOrAfter(anchor, Set(firstTuesday.toInstant)) shouldBe
      Some(firstTuesday.plusDays(7))
  }

  private val givenUpWindow = (anchor.minusDays(1), anchor.plusDays(6))

  private def hasGivenUp(schedule: RespawnSchedule, candidate: RespawnSchedule,
                         settled: ZonedDateTime*) =
    RespawnSchedule.surrendered(schedule, candidate, settled.map(_.toInstant).toSet,
      givenUpWindow._1, givenUpWindow._2)

  test("a rule that has given up the one evening being asked for stands aside") {
    val standing = schedule()
    val wanted = RespawnSchedule(2L, 1L, "u2", "Two", "", anchor.plusDays(1),
      RespawnSchedule.Daily, 120, active = true, anchor, RespawnSchedule.OneOff)
    hasGivenUp(standing, wanted) shouldBe false
    hasGivenUp(standing, wanted, anchor.plusDays(1)) shouldBe true
  }

  test("giving up one evening does not give up the rest of the week") {
    val standing = schedule()
    // A daily booking wants every evening, so one settled day leaves six it
    // still owns and the clash stands.
    val wanted = RespawnSchedule(2L, 1L, "u2", "Two", "", anchor.plusDays(1),
      RespawnSchedule.Daily, 120, active = true, anchor, RespawnSchedule.EveryDay)
    hasGivenUp(standing, wanted, anchor.plusDays(1)) shouldBe false
  }

  test("a settled evening nobody was asking about changes nothing") {
    val standing = schedule()
    val wanted = RespawnSchedule(2L, 1L, "u2", "Two", "", anchor.plusDays(1),
      RespawnSchedule.Daily, 120, active = true, anchor, RespawnSchedule.OneOff)
    // Thursday is settled; the evening being asked for is Friday.
    hasGivenUp(standing, wanted, anchor) shouldBe false
  }

  test("a rule that contests nothing inside the window has surrendered nothing") {
    // Different times of day, so the two never meet. Answering "yes, given up"
    // here would read as permission drawn from an absence of evidence.
    val standing = schedule()
    val elsewhere = RespawnSchedule(2L, 1L, "u2", "Two", "", anchor.plusHours(6),
      RespawnSchedule.Daily, 60, active = true, anchor, RespawnSchedule.EveryDay)
    hasGivenUp(standing, elsewhere) shouldBe false
  }

  // --- confirming a booking that has started -------------------------------

  test("a started booking is awaiting confirmation until its owner takes the claim") {
    val started = slot(anchor).copy(status = RespawnClaim.StatusActive,
      confirmBy = Some(anchor.plusMinutes(15)))
    started.awaitingConfirmation shouldBe true
    started.copy(confirmedAt = Some(anchor.plusMinutes(2))).awaitingConfirmation shouldBe false
  }

  test("a claim with no confirmation deadline is never awaiting one") {
    // Every ad-hoc claim, and every hunt that was already running when
    // confirmation shipped — making one is itself the act of turning up.
    val adhoc = slot(anchor).copy(status = RespawnClaim.StatusActive, kind = RespawnClaim.KindAdHoc)
    adhoc.awaitingConfirmation shouldBe false
  }

  test("a booking still waiting to start is not awaiting confirmation either") {
    // The deadline only means anything once the hunt is live; a reserved slot has
    // its whole reminder window to settle itself.
    slot(anchor).copy(confirmBy = Some(anchor.plusMinutes(15))).awaitingConfirmation shouldBe false
  }
}
