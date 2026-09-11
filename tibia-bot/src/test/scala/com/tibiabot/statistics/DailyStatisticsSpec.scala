package com.tibiabot.statistics

import com.tibiabot.domain.ExperienceDelta
import com.tibiabot.domain.time.Clock
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, LocalDate, ZonedDateTime}

/** Which day a post covers, what window that day spans, and what counts as a
 *  gain. All three are places where being an hour or a day out would be
 *  invisible in the output and wrong in the numbers. */
class DailyStatisticsSpec extends AnyFunSuite with Matchers {

  private def berlin(text: String) = ZonedDateTime.parse(text).withZoneSameInstant(Clock.Berlin)

  private def delta(name: String, gained: Long, level: Int = 400, previousLevel: Int = 400) =
    ExperienceDelta(name.toLowerCase, name, "Elite Knight", level, previousLevel, 1000000L, gained)

  // --- which day ----------------------------------------------------------

  test("a post inside the server-save window reports the day that just closed") {
    // 10:15 on the 11th: the 11th's save day is fifteen minutes old and worth
    // nothing. The day worth reporting ran 10:00 on the 10th to 10:00 on the
    // 11th, and experience_daily keys it as the 10th.
    DailyStatistics.reportedDay(berlin("2026-09-11T10:15:00+02:00")) shouldBe LocalDate.of(2026, 9, 10)
  }

  test("the day does not change across the whole window") {
    DailyStatistics.reportedDay(berlin("2026-09-11T10:01:00+02:00")) shouldBe LocalDate.of(2026, 9, 10)
    DailyStatistics.reportedDay(berlin("2026-09-11T10:44:00+02:00")) shouldBe LocalDate.of(2026, 9, 10)
  }

  test("before the save, the day that just closed is still the one before yesterday's save") {
    // 09:00 on the 11th is inside the save day keyed the 10th, which has not
    // finished. The last *closed* day is the 9th. Nothing posts here — the
    // window gate in StatisticsService sees to that — but the arithmetic has to
    // stay honest either side of it.
    DailyStatistics.reportedDay(berlin("2026-09-11T09:00:00+02:00")) shouldBe LocalDate.of(2026, 9, 9)
  }

  test("the reported day is read in Berlin however the caller expresses the time") {
    // The same instant, handed over as UTC. 08:15Z is 10:15 Berlin in summer.
    DailyStatistics.reportedDay(ZonedDateTime.parse("2026-09-11T08:15:00Z")) shouldBe LocalDate.of(2026, 9, 10)
  }

  // --- the window it spans ------------------------------------------------

  test("a save day runs from its own server save to the next one") {
    val (from, to) = DailyStatistics.window(LocalDate.of(2026, 9, 10))
    from shouldBe berlin("2026-09-10T10:00:00+02:00").toInstant
    to shouldBe berlin("2026-09-11T10:00:00+02:00").toInstant
    Duration.between(from, to).toHours shouldBe 24
  }

  test("the day the clocks go back is twenty-five hours long") {
    // Europe/Berlin leaves summer time at 03:00 on 25 October 2026. A window
    // built as "start plus 24 hours" would put that day's last hour of advances
    // into the next day's post.
    val (from, to) = DailyStatistics.window(LocalDate.of(2026, 10, 24))
    Duration.between(from, to).toHours shouldBe 25
  }

  test("the day the clocks go forward is twenty-three hours long") {
    // Berlin enters summer time at 02:00 on 29 March 2026.
    val (from, to) = DailyStatistics.window(LocalDate.of(2026, 3, 28))
    Duration.between(from, to).toHours shouldBe 23
  }

  // --- what counts as a gain ----------------------------------------------

  test("gains are the biggest movers, largest first") {
    val movers = List(delta("Bubble", 50), delta("Arieswar", 900), delta("Mateusz", 300))
    DailyStatistics.gains(movers).map(_.displayName) shouldBe List("Arieswar", "Mateusz", "Bubble")
  }

  test("gains stop at the limit") {
    val movers = (1 to 30).toList.map(n => delta(s"Char$n", n.toLong))
    DailyStatistics.gains(movers) should have size DailyStatistics.TopGains
    DailyStatistics.gains(movers, 3).map(_.gained) shouldBe List(30L, 29L, 28L)
  }

  test("somebody who lost experience is never listed under gains") {
    // On a quiet world a loser can surface inside the top ten of a query ordered
    // by the delta. Listing them under "top experience gained" would be wrong.
    val movers = List(delta("Bubble", 900), delta("Arieswar", -4000), delta("Mateusz", 0))
    DailyStatistics.gains(movers).map(_.displayName) shouldBe List("Bubble")
  }

  test("a day where nobody gained anything is empty rather than padded") {
    DailyStatistics.gains(List(delta("Arieswar", -4000), delta("Bubble", 0))) shouldBe Nil
  }

  test("losses are the worst first, and only when they are really losses") {
    val movers = List(delta("Bubble", 900), delta("Arieswar", -4000), delta("Mateusz", -12000))
    DailyStatistics.losses(movers).map(_.displayName) shouldBe List("Mateusz", "Arieswar")
    DailyStatistics.losses(List(delta("Bubble", 900), delta("Mateusz", 0))) shouldBe Nil
  }

  // --- the report ---------------------------------------------------------

  test("a report with nothing in it is empty") {
    // The ordinary cause is a world on its first day of history: a gain needs
    // two consecutive rollups, so there is nothing to measure against yet.
    DailyReport("Antica", LocalDate.of(2026, 9, 10), Nil, Nil, None).isEmpty shouldBe true
  }

  test("a report is non-empty if any one of its three parts has something") {
    val day = LocalDate.of(2026, 9, 10)
    DailyReport("Antica", day, List(delta("Bubble", 900)), Nil, None).nonEmpty shouldBe true
    DailyReport("Antica", day, Nil, List(delta("Bubble", -900)), None).nonEmpty shouldBe true
  }
}
