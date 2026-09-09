package com.tibiabot.presentation

import com.tibiabot.domain.{ExperienceDelta, HighscoreEvent}
import com.tibiabot.statistics.DailyReport
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Instant, LocalDate}

/** How a day reads, and that a full one still fits inside Discord's limits. */
class StatisticsEmbedsSpec extends AnyFunSuite with Matchers {

  private val day = LocalDate.of(2026, 9, 10)

  private def delta(name: String, gained: Long, level: Int = 400, previousLevel: Int = 400) =
    ExperienceDelta(name.toLowerCase, name, "Elite Knight", level, previousLevel, 4_200_000_000L, gained)

  private def report(
      gains: List[ExperienceDelta] = Nil,
      loss: Option[ExperienceDelta] = None,
      advance: Option[HighscoreEvent] = None
  ) = DailyReport("Antica", day, gains, loss, advance)

  private def advance(category: String, score: Long, name: String = "Bubble") =
    HighscoreEvent("Antica", category, name.toLowerCase, name, "Master Sorcerer", 402, score - 1, score,
      Instant.parse("2026-09-10T18:40:00Z"))

  test("the title names the world and the day it covers") {
    val embed = StatisticsEmbeds.build(report(gains = List(delta("Bubble", 900))))
    embed.getTitle should include("Antica")
    embed.getTitle should include("Thursday 10 September 2026")
  }

  test("gains are numbered, largest first, with thousands separators") {
    val embed = StatisticsEmbeds.build(report(gains = List(delta("Bubble", 182450912), delta("Arieswar", 900))))
    val description = embed.getDescription
    description should include("+182,450,912")
    description should include("Bubble")
    description.indexOf("Bubble") should be < description.indexOf("Arieswar")
  }

  test("a level that moved is shown either side of the day, one that did not is shown once") {
    StatisticsEmbeds.build(report(gains = List(delta("Bubble", 900, level = 418, previousLevel = 412))))
      .getDescription should include("level 412 → 418")
    // "412 → 412" reads as a mistake, and this is the common case.
    StatisticsEmbeds.build(report(gains = List(delta("Bubble", 900, level = 412, previousLevel = 412))))
      .getDescription should include("(level 412)")
  }

  test("a day nobody gained on says so rather than showing an empty list") {
    StatisticsEmbeds.build(report(loss = Some(delta("Bubble", -900)))).getDescription should include("Nobody")
  }

  test("the loss keeps its minus sign and needs no plus") {
    val field = StatisticsEmbeds.build(report(loss = Some(delta("Bubble", -4182993)))).getFields.get(0)
    field.getName should include("Biggest experience loss")
    field.getValue should include("-4,182,993")
    field.getValue should not include "+"
  }

  test("the loss and advance fields are absent when there is nothing to put in them") {
    StatisticsEmbeds.build(report(gains = List(delta("Bubble", 900)))).getFields shouldBe empty
  }

  test("magic level is named without doubling the word level") {
    val field = StatisticsEmbeds.build(report(advance = Some(advance("magiclevel", 131)))).getFields.get(0)
    field.getValue should include("magic level **131**")
    field.getValue should not include "magic level level"
  }

  test("a weapon skill gets the word level appended") {
    StatisticsEmbeds.build(report(advance = Some(advance("swordfighting", 137)))).getFields.get(0)
      .getValue should include("sword fighting level **137**")
  }

  test("a category this build no longer knows renders plainly instead of throwing") {
    // An older row should read as itself rather than take the day's post with it.
    StatisticsEmbeds.build(report(advance = Some(advance("bosspoints", 4200)))).getFields.get(0)
      .getValue should include("bosspoints **4200**")
  }

  test("a full day fits inside Discord's limits") {
    // Ten linked names plus a figure runs to roughly 1,200 characters, which is
    // why the leaderboard is in the description and not in a field.
    val gains = (1 to 10).toList.map(n => delta(s"Averylongcharactername$n", 100000000L - n))
    val embed = StatisticsEmbeds.build(report(gains, Some(delta("Someoneunlucky", -9182993)), Some(advance("magiclevel", 131))))
    embed.getDescription.length should be < 4096
    embed.getFields.forEach(field => field.getValue.length should be < 1024)
    embed.getLength should be < 6000
  }
}
