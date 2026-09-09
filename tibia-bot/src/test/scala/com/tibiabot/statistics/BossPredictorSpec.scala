package com.tibiabot.statistics

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

/** The spawn-window arithmetic, and the rule that decides what may be predicted
 *  at all. */
class BossPredictorSpec extends AnyFunSuite with Matchers {

  private val today = LocalDate.of(2026, 9, 10)

  private def seenDaysAgo(days: Int): LocalDate = today.minusDays(days.toLong)

  /** A 12–28 day boss, the shape most of the catalogue is. */
  private def chance(daysSince: Int, min: Int = 12, max: Int = 28) =
    BossPredictor.chanceFor(today, seenDaysAgo(daysSince), min, max)

  private def boss(
      name: String = "Furyosa",
      min: Int = 12,
      max: Int = 28,
      spawnPoints: Int = 1,
      predict: Boolean = true
  ) = Boss(name, scala.None, predict, min, max, spawnPoints, "Profitable")

  // --- inside the first window --------------------------------------------

  test("a boss killed today is not due again") {
    chance(0).chance shouldBe Chance.None
  }

  test("a boss well short of its window is not due") {
    chance(5).chance shouldBe Chance.None
    chance(10).chance shouldBe Chance.None
  }

  test("a day before the window opens is a low chance, not a high one") {
    // The low test widens the window a day at each side, so a cycle running
    // slightly off the catalogue's figures still shows up.
    chance(11).chance shouldBe Chance.Low
  }

  test("inside the window is a high chance") {
    chance(12).chance shouldBe Chance.High
    chance(20).chance shouldBe Chance.High
    chance(28).chance shouldBe Chance.High
  }

  test("the first window is always shown, even before the boss is due") {
    // So a reader can see how far off it is rather than just "not due".
    chance(5).windowMin shouldBe 12
    chance(5).windowMax shouldBe Some("28")
  }

  // --- past the first window ----------------------------------------------

  test("a wide window tiles, so past its opening the boss is always due") {
    // A 12–28 boss overlaps itself: window one is 12–28 and window two 24–56, so
    // from day 12 onward every day is inside some window and an upper bound says
    // nothing. That is what a "12+" window means, and why such a boss reads as
    // due forever once it is overdue at all — a real property of the arithmetic,
    // not a rounding artefact.
    chance(30).chance shouldBe Chance.High
    chance(30).windowMin shouldBe 12
    chance(30).windowMax shouldBe scala.None
    chance(400).windowMax shouldBe scala.None
  }

  test("a narrow window really does count towards a later one") {
    // Ferumbras is 161–175: the windows do not touch, so a boss missed for a
    // cycle counts towards 322–350 rather than staying on its first.
    val second = BossPredictor.chanceFor(today, seenDaysAgo(340), 161, 175)
    second.chance shouldBe Chance.High
    second.windowMin shouldBe 322
    second.windowMax shouldBe Some("350")
    second.daysSince shouldBe 340
  }

  test("a narrow window between two cycles is not due") {
    // 200 days on Ferumbras: past the first window, short of the second.
    BossPredictor.chanceFor(today, seenDaysAgo(200), 161, 175).chance shouldBe Chance.None
  }

  test("a long-cycle boss behaves the same way on its own scale") {
    // Ferumbras: 161–175 days.
    BossPredictor.chanceFor(today, seenDaysAgo(100), 161, 175).chance shouldBe Chance.None
    BossPredictor.chanceFor(today, seenDaysAgo(165), 161, 175).chance shouldBe Chance.High
  }

  // --- guards the original did not need -----------------------------------

  test("a hand-edited catalogue cannot divide by zero") {
    // The bundled file has no windowMin of 1 and no windowMax equal to
    // windowMin, but it is a resource anybody can edit and both would divide by
    // zero in the original arithmetic.
    noException should be thrownBy BossPredictor.chanceFor(today, seenDaysAgo(5), 1, 1)
    noException should be thrownBy BossPredictor.chanceFor(today, seenDaysAgo(30), 10, 10)
    noException should be thrownBy BossPredictor.chanceFor(today, seenDaysAgo(0), 0, 0)
  }

  test("a sighting dated in the future does not produce negative days") {
    BossPredictor.chanceFor(today, today.plusDays(3), 12, 28).daysSince shouldBe 0
  }

  // --- what may be predicted at all ---------------------------------------

  test("a boss never seen is not predicted") {
    // There is no anchor: the last spawn could be the day before our first
    // snapshot or a year before it, and guessing would make it look overdue
    // purely because the bot is new.
    BossPredictor.predict(boss(), Nil, today) shouldBe scala.None
  }

  test("a boss the catalogue marks unpredictable is not predicted") {
    BossPredictor.predict(boss(predict = false), List((seenDaysAgo(20), 1)), today) shouldBe scala.None
  }

  test("a boss seen once is predicted from that sighting") {
    val prediction = BossPredictor.predict(boss(), List((seenDaysAgo(20), 1)), today)
    prediction.map(_.best) shouldBe Some(Chance.High)
    prediction.map(_.daysSince) shouldBe Some(20)
  }

  test("only the most recent sighting counts for a single-spawn boss") {
    val prediction = BossPredictor.predict(
      boss(), List((seenDaysAgo(2), 1), (seenDaysAgo(40), 1)), today)
    prediction.map(_.daysSince) shouldBe Some(2)
    prediction.map(_.chances.size) shouldBe Some(1)
  }

  // --- several spawn points ------------------------------------------------

  test("a boss with several spawn points tracks each of them") {
    // Four Rotworm Queens are four independent cycles.
    val prediction = BossPredictor.predict(
      boss(spawnPoints = 4),
      List((seenDaysAgo(2), 1), (seenDaysAgo(15), 1), (seenDaysAgo(20), 1), (seenDaysAgo(40), 1)),
      today)
    prediction.map(_.chances.size) shouldBe Some(4)
    // One seen two days ago is not due; the others are.
    prediction.map(_.best) shouldBe Some(Chance.High)
    prediction.map(_.daysSince) shouldBe Some(2)
  }

  test("several killed on one day count as several sightings") {
    // Three killed on one day is three spawn points reset, not one.
    val prediction = BossPredictor.predict(
      boss(spawnPoints = 4), List((seenDaysAgo(3), 3), (seenDaysAgo(40), 1)), today)
    prediction.map(_.chances.count(_.daysSince == 3)) shouldBe Some(3)
    prediction.map(_.chances.count(_.daysSince == 40)) shouldBe Some(1)
  }

  test("a multi-spawn boss seen fewer times than it has spawn points is still predicted") {
    val prediction = BossPredictor.predict(boss(spawnPoints = 4), List((seenDaysAgo(20), 1)), today)
    prediction.map(_.chances.size) shouldBe Some(1)
    prediction.map(_.best) shouldBe Some(Chance.High)
  }

  // --- the whole catalogue -------------------------------------------------

  test("predictions are ordered best chance first") {
    val sightings = Map(
      "furyosa" -> List((seenDaysAgo(20), 1)),   // high
      "yetis" -> List((seenDaysAgo(1), 1)),      // not due
      "man in the cave" -> List((seenDaysAgo(11), 1)))
    val predicted = BossPredictor.predictAll(sightings, today)
    predicted.map(_.best.rank) shouldBe predicted.map(_.best.rank).sorted.reverse
  }

  test("an empty history predicts nothing and says every boss is waiting") {
    BossPredictor.predictAll(Map.empty, today) shouldBe empty
    BossPredictor.awaitingFirstSighting(Map.empty) shouldBe BossCatalogue.bosses.count(_.predict)
  }

  test("a boss that has been seen no longer counts as waiting") {
    val sightings = Map("furyosa" -> List((seenDaysAgo(20), 1)))
    BossPredictor.awaitingFirstSighting(sightings) shouldBe BossCatalogue.bosses.count(_.predict) - 1
  }

  test("unpredictable bosses are never counted as waiting for a sighting") {
    // They would otherwise inflate the "not yet predicted" note forever.
    BossPredictor.awaitingFirstSighting(Map.empty) should be < BossCatalogue.bosses.size
  }
}
