package com.tibiabot.presentation

import com.tibiabot.domain.{ExperienceDelta, HighscoreEvent}
import com.tibiabot.statistics.{BossKills, DailyReport, DayKillSummary, SpecialKill, SpecialKills}
import com.tibiabot.tibiadata.HighscoreCategory
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Instant, LocalDate}

/** The board and the kill statistics card: what they say, and in what shape. */
class StatisticsEmbedsSpec extends AnyFunSuite with Matchers {

  private val day = LocalDate.of(2026, 9, 10)
  private val up = "<:levelup:1>"
  private val down = "<:lvldown:2>"
  private val news = "<a:news:4>"

  private def delta(name: String, gained: Long, level: Int = 400, vocation: String = "Elite Knight") =
    ExperienceDelta(name.toLowerCase, name, vocation, level, level, 4_200_000_000L, gained)

  private def report(
      gains: List[ExperienceDelta] = Nil,
      losses: List[ExperienceDelta] = Nil,
      advance: Option[HighscoreEvent] = None,
      kills: Option[DayKillSummary] = None,
      topKills: List[BossKills] = Nil,
      specials: List[(SpecialKill, Int)] = Nil
  ) = DailyReport("Antica", day, gains, losses, advance, kills, topKills, specials)

  /** The stored summary is only the gate the creature half is released by — the
   *  rows themselves come from `topKills` — so one shape of it is enough here. */
  private def summary() =
    DayKillSummary("Antica", day, Some(("flimsy lost souls", 23965)), Some(("quara looters", 13)),
      378, 2514276L, 818)

  private def killed(race: String, count: Int) = BossKills("Antica", day, race, count, 0)

  private def advance(category: String, score: Long, name: String = "Zonta") =
    HighscoreEvent("Antica", category, name.toLowerCase, name, "Master Sorcerer", 361, score - 1, score,
      Instant.parse("2026-09-10T18:40:00Z"))

  private def build(r: DailyReport, side: String => String = _ => "") =
    StatisticsEmbeds.build(r, news, side, _ => "<:mlvl:3>", up, down)

  // --- the shape of a row --------------------------------------------------

  test("a row reads like the online list — vocation, level, name, side — then the figure") {
    val board = build(report(gains = List(delta("Arieswar", 182450912, level = 418))), _ => "<:ally:9>")
    board.text should include(
      ":shield: **418** — **[Arieswar](https://www.tibia.com/community/?name=Arieswar)** <:ally:9> · " + up + " **182,450,912**")
  }

  test("a character nobody tracks carries no side icon and the row closes up") {
    // GuildIcons renders an untracked, guildless character as an empty string,
    // so the row must not leave a gap where the icon would have been.
    val bare = build(report(gains = List(delta("Arieswar", 900)))).text
    bare should include("**[Arieswar](https://www.tibia.com/community/?name=Arieswar)** · " + up)
    bare should not include "  ·"
  }

  test("the experience icons stand in for the sign, and never appear together") {
    val board = build(report(gains = List(delta("Arieswar", 900)), losses = List(delta("Unlucky One", -18402993))))
    board.text should include(up + " **900**")
    board.text should include(down + " **18,402,993**")
    // the falling icon already says it; a minus would say it twice
    board.text should not include "-18,402,993"
    board.text should not include "+900"
  }

  // --- the card ------------------------------------------------------------

  test("the date is the card's ## title, and each section a small-caps label under a divider") {
    val board = build(report(
      gains = List(delta("Arieswar", 900)),
      losses = List(delta("Unlucky One", -900)),
      advance = Some(advance("magiclevel", 131))))
    board.blocks.head should startWith(s"## $news [Friday 11 September 2026](")
    board.blocks.tail.map(_.linesIterator.next()) shouldBe List(
      "-# **TOP EXPERIENCE GAINED**",
      "-# **TOP EXPERIENCE LOST**",
      "-# **TOP SKILL ADVANCEMENT**")
  }

  test("the board is green") {
    build(report(gains = List(delta("Arieswar", 900)))).colour shouldBe StatisticsEmbeds.WorldColor
  }

  test("the date is the morning the post goes out, not the save day it reports") {
    // The save day closes at 10:00 the next morning and the post follows it, so
    // a reader opening the channel sees today's date on today's paper. Everybody
    // already knows the figures are yesterday's.
    val board = build(report(gains = List(delta("Arieswar", 900))))
    board.text should include("Friday 11 September 2026")
    board.text should not include "10 September 2026"
  }

  test("only the title carries an emoji; the section labels are bare") {
    // Repeating the trick on every label under the title turns a hierarchy into
    // a row of badges, so the labels are plain words on purpose.
    val board = build(report(
      gains = List(delta("Arieswar", 900)),
      losses = List(delta("Unlucky One", -900)),
      advance = Some(advance("magiclevel", 131))))
    board.blocks.tail.map(_.linesIterator.next()).foreach(_ should not include ":")
  }

  test("the date links through to the world's experience table, not its tibia.com page") {
    val body = build(report(gains = List(delta("Arieswar", 900)))).text
    body should include("https://guildstats.eu/top-experience/Antica")
    body should not include "subtopic=worlds"
  }

  test("a section with nothing in it is absent rather than an empty label") {
    val board = build(report(gains = List(delta("Arieswar", 900))))
    board.blocks should have size 2
    board.text should not include "LOST"
    board.text should not include "ADVANCEMENT"
  }

  // --- the other sections --------------------------------------------------

  test("magic level is named without doubling the word level") {
    val board = build(report(advance = Some(advance("magiclevel", 131))))
    board.text should include("<:mlvl:3> magic level **131**")
    board.text should not include "magic level level"
  }

  test("a category this build no longer knows renders plainly instead of throwing") {
    build(report(advance = Some(advance("bosspoints", 4200)))).text should include("bosspoints **4200**")
  }

  test("a day nobody gained on says so rather than showing an empty list") {
    build(report(losses = List(delta("Unlucky One", -900)))).text should include("Nobody")
  }

  test("the board does not carry the creature figures") {
    val board = build(report(
      gains = List(delta("Arieswar", 900)),
      kills = Some(summary()),
      topKills = List(killed("flimsy lost souls", 23965))))
    board.text should not include "Kill Statistics"
    board.text should not include "flimsy lost souls"
  }

  // --- the kill statistics card ----------------------------------------------

  private val creatureIcon = "<:creature:6>"

  // Stands in for CreatureWiki, answering with a page title the way it does, so
  // the rows below are cased off a real one. It matches everything, which makes
  // the limits test measure the linked worst case; the row that resolves to
  // nothing has a test of its own.
  private val wiki: String => Option[String] = {
    case "druid's apparitions" => Some("Druid's Apparition")
    case "acolytes of the cult" => Some("Acolyte of the Cult")
    case race => Some(race.split(" ").map(_.capitalize).mkString(" "))
  }

  private def creature(r: DailyReport) =
    StatisticsEmbeds.creatureStats(r, creatureIcon, key => s"<:$key:9>", wiki)

  private def creatureBody(r: DailyReport) = creature(r).get.text

  test("the card is titled Kill Statistics, with the creatures and the special kills as its sections") {
    val card = creature(report(
      kills = Some(summary()),
      topKills = List(killed("flimsy lost souls", 23965)),
      specials = List(SpecialKills.all.head -> 3))).get
    card.blocks.map(_.linesIterator.next()) shouldBe List(
      s"## $creatureIcon Kill Statistics",
      "-# **CREATURES**",
      "-# **SPECIAL KILLS**")
  }

  test("the creatures are listed largest first, count leading") {
    val body = creatureBody(report(
      kills = Some(summary()),
      topKills = List(killed("flimsy lost souls", 23965), killed("quara looters", 13))))
    body should include("**23,965** [Flimsy Lost Souls](https://tibia.fandom.com/wiki/Flimsy_Lost_Souls)")
    body should include("**13** [Quara Looters](https://tibia.fandom.com/wiki/Quara_Looters)")
    body.indexOf("Flimsy") should be < body.indexOf("Quara")
  }

  test("the row reads the same linked or not, so an unresolved race loses only the link") {
    val r = report(kills = Some(summary()), topKills = List(killed("cyclopes", 400)))
    val linked = StatisticsEmbeds.creatureStats(r, creatureIcon, _ => "", wiki).get.text
    val bare = StatisticsEmbeds.creatureStats(r, creatureIcon, _ => "", _ => None).get.text
    bare should include("**400** Cyclopes")
    bare should not include "]("
    linked should include("**400** [Cyclopes](")
    // Same words in the same order either way: only the href is added.
    linked.replaceAll("""\[([^\]]+)\]\([^)]+\)""", "$1") shouldBe bare
  }

  test("no row repeats the verb the title already carries") {
    val body = creatureBody(report(
      kills = Some(summary()),
      topKills = List(killed("flimsy lost souls", 23965)),
      specials = List(SpecialKills.all.head -> 3)))
    body should not include "killed"
  }

  test("a creature and a boss are dressed the same: both capitalised, both linked") {
    val body = creatureBody(report(
      kills = Some(summary()),
      topKills = List(killed("flimsy lost souls", 23965)),
      specials = List(SpecialKills.all.head -> 3)))
    body should include("**23,965** [Flimsy Lost Souls](")
    body should include("**3** [Plunder Patriarches](")
    body should not include "flimsy lost souls"
  }

  test("the articles inside a name stay lowercase when the rest is capitalised") {
    creatureBody(report(kills = Some(summary()), topKills = List(killed("acolytes of the cult", 12)))) should
      include("**12** [Acolytes of the Cult](")
  }

  test("a possessive keeps its lowercase s, which no capitalisation rule could tell from Mooh'Tah") {
    creatureBody(report(kills = Some(summary()), topKills = List(killed("druid's apparitions", 7)))) should
      include("**7** [Druid's Apparitions](")
  }

  test("one of a special boss is singular, more than one is plural") {
    val plunder = SpecialKills.all.head
    plunder.plural shouldBe Some("Plunder Patriarches")
    creatureBody(report(kills = Some(summary()), specials = List(plunder -> 1))) should
      include("**1** [Plunder Patriarch](")
    creatureBody(report(kills = Some(summary()), specials = List(plunder -> 4))) should
      include("**4** [Plunder Patriarches](")
  }

  test("a named boss pluralises too, though the endpoint itself never does") {
    val bakragore = SpecialKills.all.find(_.name == "Bakragore").get
    bakragore.race shouldBe "Bakragore"  // the endpoint says this however many died
    creatureBody(report(kills = Some(summary()), specials = List(bakragore -> 3))) should
      include("**3** [Bakragores](")
    creatureBody(report(kills = Some(summary()), specials = List(bakragore -> 1))) should
      include("**1** [Bakragore](")
  }

  test("what killed the most players is not reported") {
    creatureBody(report(kills = Some(summary()), topKills = List(killed("dragon", 900)))) should
      not include "killed by"
  }

  test("a special kill leads with its own boss's emoji") {
    creatureBody(report(
      kills = Some(summary()),
      topKills = List(killed("dragon", 900)),
      specials = List(SpecialKills.all.head -> 3))) should
      include("<:plunder:9> **3** [Plunder Patriarches](https://tibia.fandom.com/wiki/Plunder_Patriarch)")
  }

  test("a special boss is shown by its name, not the race the endpoint counts it under") {
    val kill = SpecialKills.all.head
    kill.race shouldBe "plunder patriarches"
    val body = creatureBody(report(kills = Some(summary()), specials = List(kill -> 1)))
    body should include("**1** [Plunder Patriarch](")
    body should not include "patriarches"
  }

  test("a day none of them died has no Special Kills section") {
    creatureBody(report(kills = Some(summary()), topKills = List(killed("dragon", 900)))) should
      not include "SPECIAL"
  }

  test("a day of special kills alone still opens on the title") {
    val card = creature(report(kills = Some(summary()), specials = List(SpecialKills.all.head -> 2))).get
    card.blocks.map(_.linesIterator.next()) shouldBe List(s"## $creatureIcon Kill Statistics", "-# **SPECIAL KILLS**")
  }

  test("a special boss with no configured emoji renders without one rather than with a gap") {
    val body = StatisticsEmbeds.creatureStats(
      report(kills = Some(summary()), specials = List(SpecialKills.all.head -> 2)),
      news, _ => "", wiki).get.text
    body should include("**2** [Plunder Patriarches](")
    body should not include "  **2**"
  }

  test("a day with nothing killed produces no card at all") {
    creature(report(kills = Some(summary()))) shouldBe None
  }

  test("the kill statistics card wears the bot's yellow, not the board's green") {
    creature(report(kills = Some(summary()), topKills = List(killed("dragon", 900)))).get.colour shouldBe
      StatisticsEmbeds.CreatureColor
    StatisticsEmbeds.CreatureColor should not be StatisticsEmbeds.WorldColor
  }

  // --- limits --------------------------------------------------------------

  test("the fullest board fits one V2 message on its own") {
    val gains = (1 to 10).toList.map(i => delta(s"Averylongcharactername$i", 100000000L - i, level = 400 + i))
    val board = StatisticsEmbeds.build(
      report(gains, (1 to 5).toList.map(i => delta(s"Someoneunlucky$i", -9182993L - i)),
        Some(advance("magiclevel", 131)), Some(summary())),
      news, _ => "<:otherguild:1><:enemy:2>", _ => "<:mlvl:3>", up, down)
    board.text.length should be < StatisticsCard.MaxText
  }

  test("the fullest kill statistics card fits one V2 message on its own") {
    val top = (1 to 10).toList.map(i => killed(s"some very long creature name $i", 100000 - i))
    creature(report(kills = Some(summary()), topKills = top, specials = SpecialKills.all.map(_ -> 3)))
      .get.text.length should be < StatisticsCard.MaxText
  }
}
