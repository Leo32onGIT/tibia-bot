package com.tibiabot.domain

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{ZoneOffset, ZonedDateTime}

/** The recurrence arithmetic behind repeating slots.
 *
 *  Deliberately free of any timezone: a schedule is an anchor instant plus a
 *  period, so working out the next slot is pure arithmetic on instants and every
 *  time shown is a Discord timestamp rendered in the reader's own zone. These
 *  tests are the guard on that — anything that needed a zone would show up here
 *  as a case that can't be expressed.
 */
class RespawnScheduleSpec extends AnyFunSuite with Matchers {

  private val anchor = ZonedDateTime.parse("2026-07-30T20:00:00Z")

  private def schedule(durationMinutes: Int = 120, period: Int = RespawnSchedule.Daily) =
    RespawnSchedule(1L, 1L, "u1", "One", "", anchor, period, durationMinutes,
      active = true, createdAt = anchor)

  test("before the first slot, the next one is the anchor itself") {
    schedule().nextStartAtOrAfter(anchor.minusHours(5)) shouldBe anchor
    schedule().nextStartAtOrAfter(anchor) shouldBe anchor
  }

  test("a slot already under way is not offered as the next one") {
    // Mid-slot: the next start is tomorrow's, not the one running now.
    schedule().nextStartAtOrAfter(anchor.plusMinutes(30)) shouldBe anchor.plusDays(1)
  }

  test("the next slot rolls forward a whole number of days, however far ahead you ask") {
    schedule().nextStartAtOrAfter(anchor.plusDays(1)) shouldBe anchor.plusDays(1)
    schedule().nextStartAtOrAfter(anchor.plusDays(3).plusMinutes(1)) shouldBe anchor.plusDays(4)
    schedule().nextStartAtOrAfter(anchor.plusDays(365)) shouldBe anchor.plusDays(365)
  }

  test("the answer is the same instant whatever zone the question is asked in") {
    // The whole point of anchoring on an instant: no zone can change the result.
    val inTokyo = anchor.plusMinutes(30).withZoneSameInstant(ZoneOffset.ofHours(9))
    schedule().nextStartAtOrAfter(inTokyo).toInstant shouldBe anchor.plusDays(1).toInstant
  }

  test("only real slot times are recognised as starts") {
    schedule().startsAt(anchor) shouldBe true
    schedule().startsAt(anchor.plusDays(2)) shouldBe true
    schedule().startsAt(anchor.plusMinutes(1)) shouldBe false
    // Before the schedule existed is not a slot, however well it lines up.
    schedule().startsAt(anchor.minusDays(1)) shouldBe false
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
    schedule(period = 0).nextStartAtOrAfter(from) shouldBe from
    schedule(period = -10).nextStartAtOrAfter(from) shouldBe from
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
}
