package com.tibiabot.statistics

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, Instant}

/** What a press does, and what it is told when it does nothing.
 *
 *  The refusals are as much the subject here as the rebuild: a reader who is
 *  told the wrong one presses again, which is the behaviour the whole gate
 *  exists to avoid.
 */
class StatisticsRefreshSpec extends AnyFunSuite with Matchers {

  private val latest = Instant.parse("2026-09-18T20:40:00Z")
  private val now = latest.plus(Duration.ofMinutes(5))

  private def hourly(count: Int): List[Instant] =
    List.tabulate(count)(back => latest.minus(Duration.ofHours(back.toLong)))

  test("a post that has never been refreshed rebuilds over the last day") {
    StatisticsRefresh.decide(hourly(30), shown = None, lastPressed = None, now) shouldBe
      RefreshDecision.Rebuild(ExperienceWindow(latest.minus(Duration.ofHours(24)), latest))
  }

  test("a post already built from the last reading is told so, with the reading") {
    StatisticsRefresh.decide(hourly(30), shown = Some(latest), lastPressed = None, now) shouldBe
      RefreshDecision.NothingNewer(latest)
  }

  test("an hour later, the same post has something to show again") {
    val swept = latest.plus(Duration.ofHours(1))
    val decision = StatisticsRefresh.decide(
      swept :: hourly(30), shown = Some(latest), lastPressed = None, swept.plus(Duration.ofMinutes(2)))

    decision shouldBe RefreshDecision.Rebuild(ExperienceWindow(swept.minus(Duration.ofHours(24)), swept))
  }

  test("a world with no readings yet is waiting, not current") {
    StatisticsRefresh.decide(Nil, shown = None, lastPressed = None, now) shouldBe
      RefreshDecision.NotEnoughReadings
    // Three hours in: readings exist, but none of them is a day old.
    StatisticsRefresh.decide(hourly(3), shown = None, lastPressed = None, now) shouldBe
      RefreshDecision.NotEnoughReadings
  }

  test("a second press inside the floor is refused before anything is read") {
    val pressed = now.minus(Duration.ofSeconds(10))
    val decision = StatisticsRefresh.decide(hourly(30), shown = None, lastPressed = Some(pressed), now)

    decision shouldBe RefreshDecision.TooSoon(pressed.plus(StatisticsRefresh.Floor))
  }

  test("the floor lets go once it has passed") {
    val pressed = now.minus(StatisticsRefresh.Floor).minusSeconds(1)

    StatisticsRefresh.decide(hourly(30), shown = None, lastPressed = Some(pressed), now) shouldBe
      RefreshDecision.Rebuild(ExperienceWindow(latest.minus(Duration.ofHours(24)), latest))
  }

  test("the floor is checked before the data, so a spammed button reads nothing") {
    // Whatever the readings say — here there are none at all — a press inside
    // the floor is answered without consulting them.
    StatisticsRefresh.decide(Nil, shown = None, lastPressed = Some(now), now) shouldBe
      RefreshDecision.TooSoon(now.plus(StatisticsRefresh.Floor))
  }

  test("a post built from a reading the table no longer has is left alone") {
    // A pruned table or a clock that went backwards. Rebuilding would replace a
    // newer figure with an older one, so this reads as nothing newer.
    val decision = StatisticsRefresh.decide(
      hourly(30), shown = Some(latest.plus(Duration.ofHours(3))), lastPressed = None, now)

    decision shouldBe RefreshDecision.NothingNewer(latest)
  }

  test("retryAfter answers only while the floor is still running") {
    StatisticsRefresh.retryAfter(None, now) shouldBe None
    StatisticsRefresh.retryAfter(Some(now.minus(Duration.ofSeconds(30))), now) shouldBe
      Some(now.minus(Duration.ofSeconds(30)).plus(StatisticsRefresh.Floor))
    StatisticsRefresh.retryAfter(Some(now.minus(Duration.ofHours(1))), now) shouldBe None
  }

  test("the lookback covers a day, the tolerance and an hour of slack") {
    // Bounded on purpose: the readings table keeps a week and a press should
    // not scan six days of it.
    StatisticsRefresh.Lookback shouldBe Duration.ofHours(31)
  }
}
