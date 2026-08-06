package com.tibiabot.scheduler

import com.tibiabot.domain.time.Clock
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{DayOfWeek, Duration, Instant, LocalTime, ZoneId, ZonedDateTime}

class ServerSaveScheduleSpec extends AnyFunSuite with Matchers {

  test("isServerSaveWindow is the open interval 10:00 to 10:45") {
    ServerSaveSchedule.isServerSaveWindow(LocalTime.of(9, 59)) shouldBe false
    ServerSaveSchedule.isServerSaveWindow(LocalTime.of(10, 0)) shouldBe false
    ServerSaveSchedule.isServerSaveWindow(LocalTime.of(10, 1)) shouldBe true
    ServerSaveSchedule.isServerSaveWindow(LocalTime.of(10, 44)) shouldBe true
    ServerSaveSchedule.isServerSaveWindow(LocalTime.of(10, 45)) shouldBe false
  }

  test("rashidLocation maps every weekday to its canonical Tibia city") {
    // The full fixed rotation, so a typo in any city name is caught.
    ServerSaveSchedule.rashidLocation(DayOfWeek.MONDAY) shouldBe "Svargrond"
    ServerSaveSchedule.rashidLocation(DayOfWeek.TUESDAY) shouldBe "Liberty Bay"
    ServerSaveSchedule.rashidLocation(DayOfWeek.WEDNESDAY) shouldBe "Port Hope"
    ServerSaveSchedule.rashidLocation(DayOfWeek.THURSDAY) shouldBe "Ankrahmun"
    ServerSaveSchedule.rashidLocation(DayOfWeek.FRIDAY) shouldBe "Darashia"
    ServerSaveSchedule.rashidLocation(DayOfWeek.SATURDAY) shouldBe "Edron"
    ServerSaveSchedule.rashidLocation(DayOfWeek.SUNDAY) shouldBe "Carlin"
    // every weekday is covered (the match is exhaustive, never throws)
    DayOfWeek.values.foreach(d => ServerSaveSchedule.rashidLocation(d) should not be empty)
  }

  // The game's day turns over at server save, not midnight — and this is the
  // convention the Dream Courts wiki page's stated day is compared against, so
  // getting the boundary wrong would silently mark fresh reads as stale.
  test("gameDayOfWeek turns over at server save, not midnight") {
    def gameDay(berlin: String) =
      ServerSaveSchedule.gameDayOfWeek(ZonedDateTime.parse(berlin).withZoneSameInstant(Clock.Berlin))

    // Thursday 6 Aug 2026, either side of the 10:00 Berlin save
    gameDay("2026-08-06T00:30+02:00") shouldBe DayOfWeek.WEDNESDAY
    gameDay("2026-08-06T09:59+02:00") shouldBe DayOfWeek.WEDNESDAY
    gameDay("2026-08-06T10:01+02:00") shouldBe DayOfWeek.THURSDAY
    gameDay("2026-08-06T23:30+02:00") shouldBe DayOfWeek.THURSDAY
  }

  // Berlin minus 10h is UTC-8 in summer, which is the day the wiki page renders
  // itself against — the equivalence the staleness check leans on.
  test("gameDayOfWeek is read off the instant, not the caller's zone") {
    val instant = "2026-08-06T08:30Z" // 10:30 Berlin — after the save
    ServerSaveSchedule.gameDayOfWeek(ZonedDateTime.parse(instant)) shouldBe DayOfWeek.THURSDAY
    ServerSaveSchedule.gameDayOfWeek(
      ZonedDateTime.parse(instant).withZoneSameInstant(ZoneId.of("Australia/Sydney"))) shouldBe DayOfWeek.THURSDAY
  }

  test("shouldShowDrome only when drome is in the future and within 3 days") {
    val now = Instant.parse("2026-01-01T00:00:00Z")
    ServerSaveSchedule.shouldShowDrome(now, now.minusSeconds(10)) shouldBe false
    ServerSaveSchedule.shouldShowDrome(now, now.plusSeconds(3600)) shouldBe true
    ServerSaveSchedule.shouldShowDrome(now, now.plus(Duration.ofDays(3))) shouldBe true
    ServerSaveSchedule.shouldShowDrome(now, now.plus(Duration.ofDays(4))) shouldBe false
  }
}
