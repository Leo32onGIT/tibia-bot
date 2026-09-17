package com.tibiabot.statistics

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, Instant}

/** The rules for which two readings a rolling figure is measured between.
 *
 *  Pinned here rather than through the database because every one of them is a
 *  decision about meaning — how long a window may be before it stops being
 *  "lately", which way to err when it cannot be exact — and none of them needs
 *  a row to demonstrate.
 */
class ExperienceWindowSpec extends AnyFunSuite with Matchers {

  private val now = Instant.parse("2026-09-18T20:45:00Z")

  /** The sweep lands on the :40 of each hour, so the readings a real world has
   *  are these. `back` hours before the most recent one. */
  private def hourly(count: Int, latest: Instant = Instant.parse("2026-09-18T20:40:00Z")): List[Instant] =
    List.tabulate(count)(back => latest.minus(Duration.ofHours(back.toLong)))

  test("a day of hourly readings measures exactly a day") {
    val window = ExperienceWindow.choose(hourly(30), now).value

    window.to shouldBe Instant.parse("2026-09-18T20:40:00Z")
    window.from shouldBe Instant.parse("2026-09-17T20:40:00Z")
    window.hours shouldBe 24L
  }

  test("the window ends at the last reading, not at the moment of asking") {
    // Twenty minutes after the reading, and again nineteen minutes after that:
    // the same window both times, which is what stops two presses a minute
    // apart disagreeing about the same day.
    val early = ExperienceWindow.choose(hourly(30), now).value
    val later = ExperienceWindow.choose(hourly(30), now.plus(Duration.ofMinutes(19))).value

    later shouldBe early
  }

  test("a reading in the future is ignored rather than trusted") {
    val ahead = Instant.parse("2026-09-18T21:40:00Z")
    val window = ExperienceWindow.choose(ahead :: hourly(30), now).value

    window.to shouldBe Instant.parse("2026-09-18T20:40:00Z")
  }

  test("nothing to measure against is None rather than a window of no length") {
    ExperienceWindow.choose(Nil, now) shouldBe None
    ExperienceWindow.choose(hourly(1), now) shouldBe None
  }

  test("a world in its first hours has no window yet") {
    // Three hours of readings: the oldest is 2 hours before the latest, which is
    // 22 hours short of a day and outside any tolerance.
    ExperienceWindow.choose(hourly(3), now) shouldBe None
  }

  test("a gap in the sweep gives a longer window, and says so") {
    // The sweep stopped either side of the anchor: readings for the last
    // nineteen hours, then nothing until twenty-eight hours back. That one is
    // four hours from a day ago where the nineteen-hour one is five, so it wins
    // and the window reports its real length instead of claiming a day.
    val latest = Instant.parse("2026-09-18T20:40:00Z")
    val readings = hourly(20, latest) ::: List(latest.minus(Duration.ofHours(28)))
    val window = ExperienceWindow.choose(readings, now).value

    window.from shouldBe latest.minus(Duration.ofHours(28))
    window.hours shouldBe 28L
  }

  test("a gap too large to mean 'lately' is refused") {
    val latest = Instant.parse("2026-09-18T20:40:00Z")
    // Nearest anchor is three days back, which under a heading about today would
    // be wrong in the direction nobody would check.
    val readings = List(latest, latest.minus(Duration.ofHours(72)))

    ExperienceWindow.choose(readings, now) shouldBe None
  }

  test("where two readings are equally near a day ago, the longer window wins") {
    val latest = Instant.parse("2026-09-18T20:40:00Z")
    val readings = List(latest, latest.minus(Duration.ofHours(23)), latest.minus(Duration.ofHours(25)))
    val window = ExperienceWindow.choose(readings, now).value

    window.hours shouldBe 25L
  }

  test("order and duplicates in the readings change nothing") {
    val ordered = ExperienceWindow.choose(hourly(30), now).value
    val shuffled = ExperienceWindow.choose(scala.util.Random.shuffle(hourly(30) ::: hourly(30)), now).value

    shuffled shouldBe ordered
  }

  test("a span rounds to the hour a reader would call it") {
    val to = Instant.parse("2026-09-18T20:40:00Z")

    ExperienceWindow(to.minus(Duration.ofMinutes(1438)), to).hours shouldBe 24L
    ExperienceWindow(to.minus(Duration.ofMinutes(1442)), to).hours shouldBe 24L
  }

  /** ScalaTest's `.value` for Option, without pulling in OptionValues everywhere. */
  private implicit class Unwrap[A](option: Option[A]) {
    def value: A = option.getOrElse(fail("expected a window, got None"))
  }
}
