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

  test("consecutive boundaries are always exactly one day apart in local time") {
    val start = berlin("2026-10-23T12:00:00")
    val boundaries = (0 to 4).map(days => ServerSaveSchedule.lastServerSave(start.plusDays(days.toLong)))
    boundaries.map(_.toLocalTime).distinct shouldBe Seq(ServerSaveSchedule.serverSaveTime)
  }
}
