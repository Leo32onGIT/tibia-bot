package com.tibiabot.scheduler

import com.tibiabot.domain.time.Clock
import com.tibiabot.statistics.DailyStatistics
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{LocalDate, ZonedDateTime}

/** Which day tibia.com's nightly batch has published, and when. */
class KillStatisticsScheduleSpec extends AnyFunSuite with Matchers {

  private def berlin(text: String) = ZonedDateTime.parse(text).withZoneSameInstant(Clock.Berlin)

  private val tenth = LocalDate.of(2026, 9, 10)
  private val eleventh = LocalDate.of(2026, 9, 11)

  test("after the batch, the day published is the one before it ran") {
    KillStatisticsSchedule.reportedDay(berlin("2026-09-11T04:05:00+02:00")) shouldBe tenth
  }

  test("before the batch, it is still the day before that") {
    // Two in the morning on the 11th: the batch has not run, and what the
    // endpoint is showing was published the previous night.
    KillStatisticsSchedule.reportedDay(berlin("2026-09-11T02:00:00+02:00")) shouldBe tenth.minusDays(1)
  }

  test("the day does not change again for the rest of the day") {
    val day = KillStatisticsSchedule.reportedDay(berlin("2026-09-11T04:05:00+02:00"))
    List("06:00", "10:01", "10:44", "18:00", "23:59").foreach { time =>
      KillStatisticsSchedule.reportedDay(berlin(s"2026-09-11T$time:00+02:00")) shouldBe day
    }
  }

  test("the boundary itself counts as published") {
    KillStatisticsSchedule.published(berlin("2026-09-11T04:00:00+02:00")) shouldBe true
    KillStatisticsSchedule.published(berlin("2026-09-11T03:59:00+02:00")) shouldBe false
  }

  test("the batch is believed to fall between three and twenty past") {
    // Written down so the probe's readings have something to disagree with.
    KillStatisticsSchedule.publishedFrom.toString shouldBe "03:00"
    KillStatisticsSchedule.publishedBy.toString shouldBe "03:20"
    KillStatisticsSchedule.boundary.isAfter(KillStatisticsSchedule.publishedBy) shouldBe true
  }

  test("it agrees with the daily post throughout the server-save window") {
    // The two are computed from different boundaries and the post finds its row
    // only because the batch falls between them. If the batch ever moved past
    // ten in the morning, this is what would break.
    List("10:01", "10:15", "10:30", "10:44").foreach { time =>
      val at = berlin(s"2026-09-11T$time:00+02:00")
      KillStatisticsSchedule.reportedDay(at) shouldBe DailyStatistics.reportedDay(at)
    }
  }

  test("it is read in Berlin however the caller expresses the time") {
    // 04:05 Berlin is 02:05 UTC; the same instant must name the same day.
    KillStatisticsSchedule.reportedDay(ZonedDateTime.parse("2026-09-11T02:05:00Z")) shouldBe tenth
    KillStatisticsSchedule.reportedDay(ZonedDateTime.parse("2026-09-11T01:05:00Z")) shouldBe tenth.minusDays(1)
  }

  test("a day rolls into the next one the following morning") {
    KillStatisticsSchedule.reportedDay(berlin("2026-09-12T04:05:00+02:00")) shouldBe eleventh
  }
}
