package com.tibiabot.scheduler

import com.tibiabot.domain.time.Clock
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{LocalDateTime, ZonedDateTime}

/** The server-save boundary the respawn system's claim stamina resets on.
 *
 *  Worth pinning precisely: getting this wrong doesn't crash anything, it just
 *  quietly hands everyone a second tank at the wrong hour (or twice a year, at
 *  a daylight-saving change).
 */
class ServerSaveResetSpec extends AnyFunSuite with Matchers {

  private def berlin(text: String): ZonedDateTime =
    LocalDateTime.parse(text).atZone(Clock.Berlin)

  test("after 10:00, the boundary is today's server save") {
    ServerSaveSchedule.lastServerSave(berlin("2026-07-30T14:30:00")) shouldBe berlin("2026-07-30T10:00:00")
  }

  test("before 10:00, the boundary is still yesterday's — the day hasn't rolled over yet") {
    ServerSaveSchedule.lastServerSave(berlin("2026-07-30T09:59:00")) shouldBe berlin("2026-07-29T10:00:00")
  }

  test("exactly at 10:00 the new day has started") {
    ServerSaveSchedule.lastServerSave(berlin("2026-07-30T10:00:00")) shouldBe berlin("2026-07-30T10:00:00")
  }

  test("just after midnight still belongs to the previous server-save day") {
    ServerSaveSchedule.lastServerSave(berlin("2026-07-30T00:05:00")) shouldBe berlin("2026-07-29T10:00:00")
  }

  test("the boundary is resolved in Berlin regardless of the caller's zone") {
    // 08:00 UTC in July is 10:00 in Berlin (CEST, UTC+2), so this instant is
    // exactly the server save even though its own zone says otherwise.
    val utc = ZonedDateTime.parse("2026-07-30T08:00:00Z")
    ServerSaveSchedule.lastServerSave(utc) shouldBe berlin("2026-07-30T10:00:00")
  }

  test("the next reset is one server save ahead") {
    ServerSaveSchedule.nextServerSave(berlin("2026-07-30T14:30:00")) shouldBe berlin("2026-07-31T10:00:00")
    ServerSaveSchedule.nextServerSave(berlin("2026-07-30T09:00:00")) shouldBe berlin("2026-07-30T10:00:00")
  }

  test("the reset stays at 10:00 local across the daylight-saving change") {
    // Europe/Berlin leaves CEST on 25 Oct 2026. Adding a calendar day (not 24
    // hours) is what keeps the boundary on the game's clock rather than
    // drifting an hour.
    val beforeChange = berlin("2026-10-24T12:00:00")
    val next = ServerSaveSchedule.nextServerSave(beforeChange)
    next shouldBe berlin("2026-10-25T10:00:00")
    next.toLocalTime shouldBe ServerSaveSchedule.serverSaveTime
  }

  test("times read as hours either side of server save, the way players say them") {
    // 10:00 Berlin is the save itself.
    ServerSaveSchedule.serverSaveOffsetLabel(berlin("2026-07-30T10:00:00")) shouldBe "SS+0"
    ServerSaveSchedule.serverSaveOffsetLabel(berlin("2026-07-30T11:00:00")) shouldBe "SS+1"
    ServerSaveSchedule.serverSaveOffsetLabel(berlin("2026-07-30T22:00:00")) shouldBe "SS+12"
  }

  test("half hours read as .5, since that is what the schedule picker offers") {
    ServerSaveSchedule.serverSaveOffsetLabel(berlin("2026-07-30T11:30:00")) shouldBe "SS+1.5"
    ServerSaveSchedule.serverSaveOffsetLabel(berlin("2026-07-31T05:30:00")) shouldBe "SS-4.5"
    // Anything finer belongs to the half below it rather than rounding up.
    ServerSaveSchedule.serverSaveOffsetLabel(berlin("2026-07-30T11:59:00")) shouldBe "SS+1.5"
    ServerSaveSchedule.serverSaveOffsetLabel(berlin("2026-07-30T10:29:00")) shouldBe "SS+0"
  }

  test("the last six hours count down to the next save rather than up from the last") {
    // Nobody says "SS+20" for 06:00 — it's SS-4, four hours before the next save.
    ServerSaveSchedule.serverSaveOffsetLabel(berlin("2026-07-31T04:00:00")) shouldBe "SS-6"
    ServerSaveSchedule.serverSaveOffsetLabel(berlin("2026-07-31T06:00:00")) shouldBe "SS-4"
    ServerSaveSchedule.serverSaveOffsetLabel(berlin("2026-07-31T09:00:00")) shouldBe "SS-1"
    ServerSaveSchedule.serverSaveOffsetLabel(berlin("2026-07-31T09:30:00")) shouldBe "SS-0.5"
  }

  test("anything earlier than SS-6 counts up instead, since SS-7 is not said") {
    // The countdown is a six-hour band, not half the day: 03:00 is SS+17 to the
    // people using this, however tidy "SS-7" might look.
    ServerSaveSchedule.serverSaveOffsetLabel(berlin("2026-07-31T03:00:00")) shouldBe "SS+17"
    ServerSaveSchedule.serverSaveOffsetLabel(berlin("2026-07-31T03:30:00")) shouldBe "SS+17.5"
    ServerSaveSchedule.serverSaveOffsetLabel(berlin("2026-07-30T23:00:00")) shouldBe "SS+13"
    // The two forms meet without a gap or an overlap at the band's edge.
    ServerSaveSchedule.serverSaveOffsetLabel(berlin("2026-07-31T03:59:00")) shouldBe "SS+17.5"
    ServerSaveSchedule.serverSaveOffsetLabel(berlin("2026-07-31T04:00:00")) shouldBe "SS-6"
  }

  test("the label is the same wherever the reader is") {
    // 08:00 UTC in July is 10:00 Berlin — the save, however the instant is written.
    ServerSaveSchedule.serverSaveOffsetLabel(ZonedDateTime.parse("2026-07-30T08:00:00Z")) shouldBe "SS+0"
  }

  test("a daylight-saving day can't produce an SS+24") {
    // Europe/Berlin leaves CEST on 25 Oct 2026, making that day 25 hours long.
    val labels = (0 to 51).map(halves =>
      ServerSaveSchedule.serverSaveOffsetLabel(
        berlin("2026-10-25T10:00:00").plusMinutes(halves.toLong * 30)))
    labels should not contain "SS+24"
    labels should not contain "SS+25"
    labels should not contain "SS-0"
    // The 25th and 25.5th hour still precede that day's next save; both clamp
    // onto the last half hour before it rather than running off the end.
    labels should contain ("SS-0.5")
  }

  test("consecutive boundaries are always exactly one day apart in local time") {
    val start = berlin("2026-10-23T12:00:00")
    val boundaries = (0 to 4).map(days => ServerSaveSchedule.lastServerSave(start.plusDays(days.toLong)))
    boundaries.map(_.toLocalTime).distinct shouldBe Seq(ServerSaveSchedule.serverSaveTime)
  }
}
