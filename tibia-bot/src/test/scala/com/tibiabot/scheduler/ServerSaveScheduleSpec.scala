package com.tibiabot.scheduler

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{DayOfWeek, Duration, Instant, LocalTime}

class ServerSaveScheduleSpec extends AnyFunSuite with Matchers {

  test("isServerSaveWindow is the open interval 10:00 to 10:45") {
    ServerSaveSchedule.isServerSaveWindow(LocalTime.of(9, 59)) shouldBe false
    ServerSaveSchedule.isServerSaveWindow(LocalTime.of(10, 0)) shouldBe false
    ServerSaveSchedule.isServerSaveWindow(LocalTime.of(10, 1)) shouldBe true
    ServerSaveSchedule.isServerSaveWindow(LocalTime.of(10, 44)) shouldBe true
    ServerSaveSchedule.isServerSaveWindow(LocalTime.of(10, 45)) shouldBe false
  }

  test("rashidLocation maps each weekday to its city") {
    ServerSaveSchedule.rashidLocation(DayOfWeek.MONDAY) shouldBe "Svargrond"
    ServerSaveSchedule.rashidLocation(DayOfWeek.THURSDAY) shouldBe "Ankrahmun"
    ServerSaveSchedule.rashidLocation(DayOfWeek.SUNDAY) shouldBe "Carlin"
  }

  test("shouldShowDrome only when drome is in the future and within 3 days") {
    val now = Instant.parse("2026-01-01T00:00:00Z")
    ServerSaveSchedule.shouldShowDrome(now, now.minusSeconds(10)) shouldBe false
    ServerSaveSchedule.shouldShowDrome(now, now.plusSeconds(3600)) shouldBe true
    ServerSaveSchedule.shouldShowDrome(now, now.plus(Duration.ofDays(3))) shouldBe true
    ServerSaveSchedule.shouldShowDrome(now, now.plus(Duration.ofDays(4))) shouldBe false
  }
}
