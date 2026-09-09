package com.tibiabot.presentation

import com.tibiabot.statistics._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

/** What the prediction embed shows, and — more importantly — what it refuses to
 *  claim while the history is young. */
class BossPredictionEmbedsSpec extends AnyFunSuite with Matchers {

  private val day = LocalDate.of(2026, 9, 10)

  private def boss(name: String, min: Int = 12, max: Int = 28, spawnPoints: Int = 1) =
    Boss(name, scala.None, predict = true, min, max, spawnPoints, "Profitable")

  private def prediction(name: String, chance: Chance, daysSince: Int, spawns: Int = 1) =
    BossPrediction(
      boss(name, spawnPoints = spawns),
      List.fill(spawns)(BossChance(chance, daysSince, 12, Some("28"))))

  private def report(predictions: List[BossPrediction] = Nil, awaiting: Int = 0) =
    DailyReport("Antica", day, Nil, scala.None, scala.None, scala.None, predictions, awaiting)

  test("a due boss is named with how long it has been and its window") {
    val embed = BossPredictionEmbeds.build(report(List(prediction("Furyosa", Chance.High, 20)))).get
    embed.getTitle should include("Antica")
    embed.getDescription should include("Furyosa")
    embed.getDescription should include("20 days")
    embed.getDescription should include("12–28")
  }

  test("high and low chances are kept in separate bands") {
    val embed = BossPredictionEmbeds.build(report(List(
      prediction("Furyosa", Chance.High, 20),
      prediction("Yeti", Chance.Low, 11)))).get
    val description = embed.getDescription
    description should include("Due now")
    description should include("Possible")
    description.indexOf("Furyosa") should be < description.indexOf("Yeti")
  }

  test("a boss that is not due is left out entirely") {
    // Fifty-seven bosses mostly a few days into long windows would bury the
    // handful somebody came for.
    val embed = BossPredictionEmbeds.build(report(List(
      prediction("Furyosa", Chance.High, 20),
      prediction("Yeti", Chance.None, 2)))).get
    embed.getDescription should include("Furyosa")
    embed.getDescription should not include "Yeti"
  }

  test("a multi-spawn boss says how many of its spawns are up") {
    val embed = BossPredictionEmbeds.build(report(List(
      prediction("Rotworm Queen", Chance.High, 20, spawns = 3)))).get
    embed.getDescription should include("×3")
  }

  test("a single spawn carries no multiplier") {
    BossPredictionEmbeds.build(report(List(prediction("Furyosa", Chance.High, 20)))).get
      .getDescription should not include "×"
  }

  test("a long band is capped and says how many it left out") {
    val many = (1 to 30).toList.map(i => prediction(s"Boss$i", Chance.High, 20 + i))
    val embed = BossPredictionEmbeds.build(report(many)).get
    embed.getDescription should include("and " + (30 - BossPredictionEmbeds.MaxHigh) + " more")
  }

  // --- the honest part ------------------------------------------------------

  test("a world still waiting for sightings says so rather than looking broken") {
    // A short list on a young history means "we do not know yet", not "nothing
    // is due" — and the two read identically without this.
    val embed = BossPredictionEmbeds.build(report(Nil, awaiting = 57)).get
    embed.getDescription should include("Not enough history")
    embed.getFooter.getText should include("57")
  }

  test("the footer is dropped once every boss has been seen") {
    val embed = BossPredictionEmbeds.build(report(List(prediction("Furyosa", Chance.High, 20)))).get
    embed.getFooter shouldBe null
  }

  test("a mature history with nothing due says that plainly") {
    val embed = BossPredictionEmbeds.build(report(List(prediction("Yeti", Chance.None, 2)))).get
    embed.getDescription should include("No boss is inside a spawn window")
    embed.getDescription should include("1 being tracked")
  }

  test("nothing at all produces no embed rather than an empty one") {
    BossPredictionEmbeds.build(report()) shouldBe scala.None
  }

  test("a full prediction embed leaves room for the statistics one beside it") {
    // The two share a 6,000-character message, and the statistics embed runs to
    // about 2,100 at full stretch.
    val high = (1 to 30).toList.map(i => prediction(s"Averylongbossname$i", Chance.High, 20 + i))
    val low = (1 to 30).toList.map(i => prediction(s"Anotherlongbossname$i", Chance.Low, 11))
    val embed = BossPredictionEmbeds.build(report(high ::: low, awaiting = 12)).get
    embed.getDescription.length should be < 3000
    embed.getLength should be < 3500
  }
}
