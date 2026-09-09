package com.tibiabot.presentation

import com.tibiabot.domain.{ExperienceDelta, FragTally, HighscoreEvent}
import com.tibiabot.statistics.{DailyReport, DayKillSummary}
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
      advance: Option[HighscoreEvent] = None,
      kills: Option[DayKillSummary] = None
  ) = DailyReport("Antica", day, gains, loss, advance, kills)

  private def summary(
      mostKilled: Option[(String, Int)] = Some(("flimsy lost souls", 23965)),
      deadliest: Option[(String, Int)] = Some(("quara looters", 13)),
      playerDeaths: Int = 378,
      totalKilled: Long = 2514276L
  ) = DayKillSummary("Antica", day, mostKilled, deadliest, playerDeaths, totalKilled, 818)

  private def fieldNamed(embed: net.dv8tion.jda.api.entities.MessageEmbed, fragment: String) =
    embed.getFields.stream().filter(_.getName.contains(fragment)).findFirst()

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

  test("every optional field is absent when there is nothing to put in it") {
    StatisticsEmbeds.build(report(gains = List(delta("Bubble", 900)))).getFields shouldBe empty
  }

  // --- what the world was killing -----------------------------------------

  test("the kill statistics field names both creatures and the PvP count") {
    val field = fieldNamed(StatisticsEmbeds.build(report(kills = Some(summary()))), "Around the world")
    field.isPresent shouldBe true
    val value = field.get.getValue
    value should include("flimsy lost souls")
    value should include("23,965")
    value should include("quara looters")
    value should include("13 players")
    value should include("378")
    value should include("2,514,276")
  }

  test("a single player killed reads as one player, not one players") {
    val field = fieldNamed(StatisticsEmbeds.build(report(kills = Some(summary(deadliest = Some(("wyrm", 1)))))), "Around the world")
    field.get.getValue should include("(1 player)")
  }

  test("lines are dropped individually rather than the whole field") {
    // A world can genuinely have a day where no creature killed a player.
    val field = fieldNamed(
      StatisticsEmbeds.build(report(kills = Some(summary(deadliest = None, playerDeaths = 0)))), "Around the world")
    field.get.getValue should include("Most killed")
    field.get.getValue should not include "Deadliest"
    field.get.getValue should not include "Killed by other players"
  }

  test("a snapshot with nothing in it produces no field at all") {
    val nothing = summary(mostKilled = None, deadliest = None, playerDeaths = 0, totalKilled = 0)
    fieldNamed(StatisticsEmbeds.build(report(kills = Some(nothing))), "Around the world").isPresent shouldBe false
  }

  // --- frags ---------------------------------------------------------------

  test("both sides' totals are shown even when one is zero") {
    // "0 allies lost" is the good half of the news; dropping it would leave a
    // reader wondering whether it was zero or unmeasured.
    val frags = FragTally(6, 0, List(("Bubble", 4), ("Arieswar", 2)), Nil)
    val field = fieldNamed(StatisticsEmbeds.build(report(), frags), "Frags")
    field.get.getValue should include("Enemies killed — **6**")
    field.get.getValue should include("Allies lost — **0**")
  }

  test("each side's leaderboard is its own field, and an empty side has none") {
    val frags = FragTally(6, 0, List(("Bubble", 4), ("Arieswar", 2)), Nil)
    val embed = StatisticsEmbeds.build(report(), frags)
    fieldNamed(embed, "Top fraggers").isPresent shouldBe true
    fieldNamed(embed, "Top fraggers").get.getValue should include("Bubble")
    fieldNamed(embed, "Enemy fraggers").isPresent shouldBe false
  }

  test("a day with no frags carries no frag fields") {
    StatisticsEmbeds.build(report(gains = List(delta("Bubble", 900))), FragTally.empty)
      .getFields shouldBe empty
  }

  test("frags are shown even when nothing else happened") {
    val frags = FragTally(2, 1, List(("Bubble", 2)), List(("Arieswar", 1)))
    val embed = StatisticsEmbeds.build(report(), frags)
    fieldNamed(embed, "Frags").isPresent shouldBe true
    fieldNamed(embed, "Enemy fraggers").isPresent shouldBe true
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

  test("the fullest possible day still fits inside Discord's limits") {
    // Everything at once, with names at the length Tibia actually allows: ten
    // gainers, a loss, an advance, the kill statistics, and both frag
    // leaderboards full. The description is where the leaderboard has to live —
    // ten linked names plus a figure is about 1,200 characters against a field's
    // 1,024 — and the two fragger fields are the ones most likely to be full,
    // which is why they carry plain names rather than links.
    val long = "Averylongcharactername"
    val gains = (1 to 10).toList.map(i => delta(s"$long$i", 100000000L - i))
    val fraggers = (1 to FragTally.TopFraggers).toList.map(i => (s"$long$i", 20 - i))
    val embed = StatisticsEmbeds.build(
      report(gains, Some(delta("Someoneunlucky", -9182993)), Some(advance("magiclevel", 131)), Some(summary())),
      FragTally(99, 99, fraggers, fraggers))
    embed.getDescription.length should be < 4096
    embed.getFields.forEach(field => field.getValue.length should be < 1024)
    embed.getFields.size should be <= 25
    embed.getLength should be < 6000
  }
}
