package com.tibiabot.web

import com.tibiabot.domain.{Respawn, RespawnClaim, RespawnSchedule}
import com.tibiabot.persistence.ScheduleOccurrence
import org.scalatest.LoneElement._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{DayOfWeek, ZonedDateTime}

/** The expansion behind the calendar: rules and rows in, drawable blocks out. */
class CalendarViewSpec extends AnyWordSpec with Matchers {

  private val monday = ZonedDateTime.parse("2026-08-10T00:00:00Z")   // a Monday in Berlin too
  private val weekEnd = monday.plusDays(7)
  private val spawn = Respawn(1L, "415", "Cult Orcs", "Orc", "Edron", "", "", "",
    Respawn.SourceSeed, "seed")

  private def at(day: Int, hour: Int, minute: Int = 0) =
    monday.plusDays(day.toLong).plusHours(hour.toLong).plusMinutes(minute.toLong)

  private def reservation(id: Long, start: ZonedDateTime, minutes: Int = 120,
                          who: String = "u2", character: String = "",
                          scheduleId: Option[Long] = None,
                          askedAt: Option[ZonedDateTime] = None,
                          requester: Option[String] = None) =
    RespawnClaim(id, spawn.id, who, s"name-$who", character, RespawnClaim.StatusReserved, 0,
      monday, Some(start), Some(start.plusMinutes(minutes.toLong)), minutes,
      warned = false, RespawnClaim.KindScheduled, None, None, None, None,
      scheduleId = scheduleId, askedAt = askedAt, requesterUserId = requester)

  private def schedule(id: Long, anchor: ZonedDateTime, minutes: Int = 120,
                       who: String = "u3", character: String = "",
                       days: Int = RespawnSchedule.EveryDay) =
    RespawnSchedule(id, spawn.id, who, s"name-$who", character, anchor,
      RespawnSchedule.Daily, minutes, active = true, monday, days)

  /** A claim that is over: a window it held, when it really stopped, and why. */
  private def finished(id: Long, start: ZonedDateTime, minutes: Int = 120,
                       who: String = "u9", outcome: Option[String] = Some(RespawnClaim.Outcome.Completed),
                       endedAt: Option[ZonedDateTime] = None,
                       status: String = RespawnClaim.StatusFinished) =
    RespawnClaim(id, spawn.id, who, s"name-$who", "", status, 0,
      monday, Some(start), Some(start.plusMinutes(minutes.toLong)), minutes,
      warned = false, RespawnClaim.KindAdHoc, None, None,
      outcome = outcome, endedAt = endedAt)

  private def givenUp(scheduleId: Long, days: ZonedDateTime*) =
    Map(scheduleId -> days.map(_.toInstant).toSet)

  private def assemble(active: Option[RespawnClaim] = None,
                       reservations: List[RespawnClaim] = Nil,
                       schedules: List[RespawnSchedule] = Nil,
                       givenUp: Map[Long, Set[java.time.Instant]] = Map.empty,
                       from: ZonedDateTime = monday, to: ZonedDateTime = weekEnd,
                       history: List[RespawnClaim] = Nil) =
    JdaRespawnActions.assembleCalendar(spawn, active, reservations, schedules, givenUp, from, to, history)

  "the calendar" should {

    "draw what has already happened, marked as over" in {
      val view = assemble(history = List(finished(1, at(1, 20), minutes = 120)))
      val slot = view.slots.loneElement
      slot.past shouldBe true
      slot.hunted shouldBe true
      slot.startsAt shouldBe at(1, 20)
      slot.endsAt shouldBe at(1, 22)
      // A hunt that simply ran its time explains itself.
      slot.note shouldBe ""
    }

    // The honest length of a hunt is how long somebody was on it, not how long
    // they were entitled to be. Drawing it to its deadline would draw an
    // evening nobody had.
    "draw a hunt given up early to when it actually ended" in {
      val slot = assemble(history = List(finished(1, at(1, 20), minutes = 120,
        outcome = Some(RespawnClaim.Outcome.Released),
        endedAt = Some(at(1, 20, 25))))).slots.loneElement
      slot.endsAt shouldBe at(1, 20, 25)
      slot.hunted shouldBe true
      slot.note shouldBe "given up early"
    }

    "tell an evening nobody turned up for from one that was hunted" in {
      val missed = assemble(history = List(finished(1, at(2, 20),
        outcome = Some(RespawnClaim.Outcome.NoAnswer),
        status = RespawnClaim.StatusCancelled))).slots.loneElement
      missed.past shouldBe true
      missed.hunted shouldBe false
      missed.note shouldBe "no answer, passed on"
    }

    // Every outcome that can only be reached from a hunt already running.
    "count as hunted exactly the outcomes a hunt can end with" in {
      val hunted = List(RespawnClaim.Outcome.Completed, RespawnClaim.Outcome.Released,
        RespawnClaim.Outcome.Forced, RespawnClaim.Outcome.Cleared,
        RespawnClaim.Outcome.TakenOver, RespawnClaim.Outcome.Unconfirmed)
      val never = List(RespawnClaim.Outcome.Missed, RespawnClaim.Outcome.GivenUp,
        RespawnClaim.Outcome.NoAnswer, RespawnClaim.Outcome.Merged,
        RespawnClaim.Outcome.ScheduleCancelled, RespawnClaim.Outcome.SlotRemoved,
        RespawnClaim.Outcome.SlotMoved)
      hunted.foreach(o => JdaRespawnActions.wasHunted(finished(1, at(1, 20), outcome = Some(o))) shouldBe true)
      never.foreach(o => JdaRespawnActions.wasHunted(finished(1, at(1, 20), outcome = Some(o))) shouldBe false)
    }

    // Rows written before outcomes were recorded. A window with nothing said
    // about it is a hunt that happened; reading it as a no-show would be
    // inventing an accusation against somebody who probably turned up.
    "read a row with no outcome as a hunt that happened" in {
      val slot = assemble(history = List(finished(1, at(1, 20), outcome = None))).slots.loneElement
      slot.hunted shouldBe true
      slot.note shouldBe ""
    }

    // A day taken off the calendar the instant it began has both timestamps
    // equal. Zero height is invisible rather than absent, which reads as the
    // grid having lost something.
    "give a row that ended where it started something to draw" in {
      val slot = assemble(history = List(finished(1, at(1, 20), minutes = 120,
        outcome = Some(RespawnClaim.Outcome.SlotRemoved),
        endedAt = Some(at(1, 20))))).slots.loneElement
      slot.endsAt.isAfter(slot.startsAt) shouldBe true
    }

    "leave the past out of a window that ends before it" in {
      assemble(history = List(finished(1, at(9, 20))), to = monday.plusDays(2)).slots shouldBe empty
    }

    "draw the past beside what is still to come, in time order" in {
      val view = assemble(
        history = List(finished(1, at(1, 20))),
        reservations = List(reservation(2, at(3, 20))))
      view.slots.map(_.past) shouldBe List(true, false)
    }

    "carry the spawn through, so the page need not look it up separately" in {
      val view = assemble()
      view.code shouldBe "415"
      view.name shouldBe "Cult Orcs"
      view.creature shouldBe "Orc"
      view.slots shouldBe empty
    }

    "draw a booked slot with both of its ends" in {
      val view = assemble(reservations = List(reservation(1, at(1, 20), minutes = 90)))
      val slot = view.slots.loneElement
      slot.startsAt shouldBe at(1, 20)
      slot.endsAt shouldBe at(1, 21, 30)
      slot.state shouldBe "booked"
      slot.predicted shouldBe false
    }

    "name a block by its character when there is one, and by the account otherwise" in {
      val view = assemble(reservations = List(
        reservation(1, at(1, 18), who = "u2", character = "Bubble"),
        reservation(2, at(2, 18), who = "u4")))
      view.slots.map(_.owner) shouldBe List("Bubble", "name-u4")
      // The id is what decides whose block it is; the name is only for reading.
      view.slots.map(_.ownerId) shouldBe List("u2", "u4")
    }

    "put a live hunt on the grid as the only thing actually happening" in {
      val claim = RespawnClaim(9, spawn.id, "u1", "name-u1", "Bubble", RespawnClaim.StatusActive, 0,
        monday, Some(at(0, 12)), Some(at(0, 14)), 120, warned = false,
        RespawnClaim.KindAdHoc, None, None, None, None)
      val slot = assemble(active = Some(claim)).slots.loneElement
      slot.state shouldBe "claimed"
      slot.scheduleId shouldBe None
      slot.repeats shouldBe false
    }

    "leave a hunt that finished before the window off the grid" in {
      val claim = RespawnClaim(9, spawn.id, "u1", "name-u1", "", RespawnClaim.StatusActive, 0,
        monday, Some(monday.minusHours(4)), Some(monday.minusHours(2)), 120, warned = false,
        RespawnClaim.KindAdHoc, None, None, None, None)
      assemble(active = Some(claim)).slots shouldBe empty
    }

    // A hunt running over the boundary is the one the reader most needs: it is
    // why they cannot take the spawn right now.
    "keep a hunt that started before the window but is still running" in {
      val claim = RespawnClaim(9, spawn.id, "u1", "name-u1", "", RespawnClaim.StatusActive, 0,
        monday, Some(monday.minusHours(1)), Some(monday.plusHours(1)), 120, warned = false,
        RespawnClaim.KindAdHoc, None, None, None, None)
      assemble(active = Some(claim)).slots should have size 1
    }

    "tell a slot whose owner has been asked from one whose answer has come" in {
      val view = assemble(reservations = List(
        reservation(1, at(1, 18), requester = Some("u9")),
        reservation(2, at(2, 18), askedAt = Some(monday)),
        reservation(3, at(3, 18))))
      view.slots.map(_.state) shouldBe List("asked", "confirmed", "booked")
    }

    "take a repeating slot's days from the rule behind it" in {
      val rule = schedule(7, at(1, 20), days = RespawnSchedule.maskOf(List(DayOfWeek.TUESDAY)))
      val view = assemble(reservations = List(reservation(1, at(1, 20), scheduleId = Some(7))),
        schedules = List(rule))
      val slot = view.slots.loneElement
      slot.repeats shouldBe true
      slot.daysOfWeek shouldBe rule.daysOfWeek
      slot.scheduleId shouldBe Some(7L)
    }

    "treat a slot with no rule behind it as a one-off" in {
      val slot = assemble(reservations = List(reservation(1, at(1, 20)))).slots.loneElement
      slot.repeats shouldBe false
      slot.daysOfWeek shouldBe RespawnSchedule.OneOff
      slot.scheduleId shouldBe None
    }

    "put the week's slots in the order they happen" in {
      val view = assemble(reservations = List(
        reservation(1, at(4, 20)), reservation(2, at(1, 18)), reservation(3, at(2, 9))))
      view.slots.map(_.startsAt) shouldBe List(at(1, 18), at(2, 9), at(4, 20))
    }
  }

  "a standing booking" should {

    // Without this the week ahead looks free and somebody plans into a booking
    // that has existed for a month.
    "show every evening its rule will produce, even before any is written down" in {
      val view = assemble(schedules = List(schedule(7, at(0, 20))))
      view.slots should have size 7
      all(view.slots.map(_.predicted)) shouldBe true
      view.slots.map(_.startsAt.getHour).distinct shouldBe List(20)
    }

    "only appear on the days its mask names" in {
      val mask = RespawnSchedule.maskOf(List(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY))
      val view = assemble(schedules = List(schedule(7, at(1, 20), days = mask)))
      view.slots.map(_.startsAt.getDayOfWeek) shouldBe List(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY)
    }

    // The row knows about requests and handovers; the rule does not. Drawing
    // both would show the same evening twice, once wrongly.
    "give way to the row once one has been written for that evening" in {
      val rule = schedule(7, at(0, 20))
      val view = assemble(
        reservations = List(reservation(1, at(1, 20), scheduleId = Some(7), requester = Some("u9"))),
        schedules = List(rule))
      view.slots.count(_.startsAt == at(1, 20)) shouldBe 1
      view.slots.find(_.startsAt == at(1, 20)).map(_.state) shouldBe Some("asked")
      view.slots.find(_.startsAt == at(1, 20)).map(_.predicted) shouldBe Some(false)
      // Every other evening the rule names is still predicted.
      view.slots.count(_.predicted) shouldBe 6
    }

    "still be drawn when another rule has a row at that same time" in {
      // Two rules, one row — the row belongs to the first, so the second's
      // occurrence is not the one already drawn and must not be swallowed.
      val view = assemble(
        reservations = List(reservation(1, at(1, 20), scheduleId = Some(7))),
        schedules = List(schedule(7, at(1, 20), days = RespawnSchedule.maskOf(List(DayOfWeek.TUESDAY))),
                         schedule(8, at(1, 20), days = RespawnSchedule.maskOf(List(DayOfWeek.TUESDAY)),
                           who = "u5")))
      view.slots.count(_.startsAt == at(1, 20)) shouldBe 2
      view.slots.filter(_.startsAt == at(1, 20)).map(_.predicted) should contain theSameElementsAs
        List(false, true)
    }

    // The bug that put two names on one evening. A day handed to whoever asked
    // for it stops being a reservation of the rule that produced it, so drawing
    // predictions against the reservations alone brought the old owner straight
    // back — beside the person who had actually been given the evening.
    "stay away from a day that has been handed to somebody else" in {
      val rule = schedule(7, at(0, 20), who = "u3")
      val view = assemble(
        // The new owner's booking: their own, with no rule behind it.
        reservations = List(reservation(1, at(1, 20), who = "u9", scheduleId = None)),
        schedules = List(rule),
        givenUp = givenUp(7, at(1, 20)))

      view.slots.filter(_.startsAt == at(1, 20)).map(_.ownerId) shouldBe List("u9")
      view.slots.count(_.predicted) shouldBe 6
    }

    // The same blind spot at the other end of a slot's life: once it starts, the
    // row is a live claim rather than a reservation, and the rule would draw its
    // owner a second time over the hunt they are already on.
    "stay away from a day that is being hunted right now" in {
      val running = RespawnClaim(9, spawn.id, "u3", "name-u3", "", RespawnClaim.StatusActive, 0,
        monday, Some(at(1, 20)), Some(at(1, 22)), 120, warned = false,
        RespawnClaim.KindScheduled, None, None, None, None, scheduleId = Some(7))
      // Nothing is given up here — the day is very much still the rule's. What
      // suppresses the prediction is that the day is already on the grid, as
      // the hunt itself.
      val view = assemble(active = Some(running), schedules = List(schedule(7, at(0, 20))))

      view.slots.filter(_.startsAt == at(1, 20)).map(_.state) shouldBe List("claimed")
      view.slots.count(_.predicted) shouldBe 6
    }

    // A day taken off the calendar is settled in exactly the same way, and the
    // rule has to stop offering it or the removal undoes itself on the next
    // page load.
    "stay away from a day a moderator has removed" in {
      val view = assemble(
        schedules = List(schedule(7, at(0, 20))),
        givenUp = givenUp(7, at(2, 20)))

      view.slots.map(_.startsAt) should not contain at(2, 20)
      view.slots should have size 6
    }

    // A booked day the window cannot draw — `reservationsFor` asks for slots
    // starting strictly after the anchor, so a row sitting exactly on it never
    // reaches the blocks. Only a day the rule has *given up* silences it, so the
    // evening is still drawn from the rule rather than vanishing off the grid
    // for the reader to plan straight into.
    "still predict a day whose row the window cannot draw" in {
      val edge = at(0, 20)
      val view = assemble(reservations = Nil, schedules = List(schedule(7, edge)), from = edge)

      view.slots.map(_.startsAt) should contain(edge)
      view.slots.find(_.startsAt == edge).map(_.predicted) shouldBe Some(true)
    }

    "produce nothing once a one-off has been and gone" in {
      val once = schedule(7, monday.minusDays(3), days = RespawnSchedule.OneOff)
      assemble(schedules = List(once)).slots shouldBe empty
    }

    "produce exactly one slot for a one-off inside the window" in {
      val once = schedule(7, at(2, 19), days = RespawnSchedule.OneOff)
      val slot = assemble(schedules = List(once)).slots.loneElement
      slot.startsAt shouldBe at(2, 19)
      slot.repeats shouldBe false
    }
  }

  "the window" should {

    "read a pair of instants" in {
      RespawnDashboardRoute.window("2026-08-10T00:00:00Z", "2026-08-17T00:00:00Z")
        .map { case (from, to) => (from.toInstant.toString, to.toInstant.toString) } shouldBe
        Some(("2026-08-10T00:00:00Z", "2026-08-17T00:00:00Z"))
    }

    "refuse anything that is not an instant" in {
      RespawnDashboardRoute.window("next tuesday", "2026-08-17T00:00:00Z") shouldBe None
      RespawnDashboardRoute.window("2026-08-10T00:00:00Z", "") shouldBe None
    }

    "refuse a window that runs backwards or nowhere" in {
      RespawnDashboardRoute.window("2026-08-17T00:00:00Z", "2026-08-10T00:00:00Z") shouldBe None
      RespawnDashboardRoute.window("2026-08-10T00:00:00Z", "2026-08-10T00:00:00Z") shouldBe None
    }

    // Rejected rather than clamped: a clamped window draws a different week from
    // the one asked for, and the page would render it as though it were right.
    "refuse a span wider than a month and a half" in {
      RespawnDashboardRoute.window("2026-08-10T00:00:00Z", "2027-08-10T00:00:00Z") shouldBe None
      RespawnDashboardRoute.window("2026-08-10T00:00:00Z", "2026-09-24T00:00:00Z") should not be empty
    }
  }

  "the calendar JSON" should {
    val slot = CalendarSlot(Some(3L), "u2", "Bubble", "violentbeams", "Violent Beams",
      at(1, 20), at(1, 22), "booked",
      repeats = true, daysOfWeek = 3, predicted = false)
    val view = CalendarView("415", "Cult Orcs", "Orc", List(slot))

    "send both ends as instants, so the page places them in its own zone" in {
      val fields = RespawnDashboardRoute.calendarJson(view, "u1")
        .fields("slots").asInstanceOf[spray.json.JsArray].elements.head.asJsObject.fields
      fields("startsAt") shouldBe spray.json.JsString("2026-08-11T20:00:00Z")
      fields("endsAt") shouldBe spray.json.JsString("2026-08-11T22:00:00Z")
    }

    "decide whose block it is by account and never by name" in {
      def mineFor(viewer: String) =
        RespawnDashboardRoute.calendarJson(view, viewer)
          .fields("slots").asInstanceOf[spray.json.JsArray].elements.head.asJsObject.fields("mine")
      mineFor("u2") shouldBe spray.json.JsBoolean(true)
      mineFor("Bubble") shouldBe spray.json.JsBoolean(false)
    }

    "leave the schedule id off a block that has none, rather than sending a zero" in {
      val live = view.copy(slots = List(slot.copy(scheduleId = None)))
      RespawnDashboardRoute.calendarJson(live, "u1")
        .fields("slots").asInstanceOf[spray.json.JsArray].elements.head.asJsObject
        .fields.keySet should not contain "scheduleId"
    }

    // A block is labelled with a Tibia character, which tells the reader nothing
    // about who to go and ask. The account and the nickname travel beside it so
    // the page can say who that is.
    "carry the account behind the name on the block" in {
      val fields = RespawnDashboardRoute.calendarJson(view, "u1")
        .fields("slots").asInstanceOf[spray.json.JsArray].elements.head.asJsObject.fields
      fields("owner") shouldBe spray.json.JsString("Bubble")
      fields("account") shouldBe spray.json.JsString("violentbeams")
      fields("nickname") shouldBe spray.json.JsString("Violent Beams")
    }

    "point the sprite at our own domain, never at the wiki" in {
      val sprite = RespawnDashboardRoute.calendarJson(view, "u1").fields.get("sprite")
      sprite.map(_.asInstanceOf[spray.json.JsString].value)
        .foreach(_ should startWith("/dashboard/sprites/"))
    }
  }
}
