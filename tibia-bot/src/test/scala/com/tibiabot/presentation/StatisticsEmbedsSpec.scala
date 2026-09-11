package com.tibiabot.presentation

import com.tibiabot.domain.{ExperienceDelta, HighscoreEvent}
import com.tibiabot.statistics.{BossKills, DailyReport, DayKillSummary, SpecialKill, SpecialKills}
import com.tibiabot.tibiadata.HighscoreCategory
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Instant, LocalDate}

/** The world embed: what it says, and that it stays inside Discord's limits. */
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

  private def pages(r: DailyReport, side: String => String = _ => "") =
    StatisticsEmbeds.build(r, news, side, _ => "<:mlvl:3>", up, down)

  /** The one page an ordinary day produces. */
  private def build(r: DailyReport, side: String => String = _ => "") = pages(r, side).head

  // --- the shape of a row --------------------------------------------------

  test("a row reads vocation, name, side, level, then the figure") {
    val embed = build(report(gains = List(delta("Arieswar", 182450912, level = 418))), _ => "<:ally:9>")
    embed.getDescription should include(
      ":shield: **[Arieswar](https://www.tibia.com/community/?name=Arieswar)** <:ally:9> · *418* · " + up + " **182,450,912**")
  }

  test("a character nobody tracks carries no side icon and the row closes up") {
    // GuildIcons renders an untracked, guildless character as an empty string,
    // so the row must not leave a gap where the icon would have been.
    val bare = build(report(gains = List(delta("Arieswar", 900)))).getDescription
    bare should include("**[Arieswar](https://www.tibia.com/community/?name=Arieswar)** · *400*")
    bare should not include "  ·"
  }

  test("the experience icons stand in for the sign, and never appear together") {
    val embed = build(report(gains = List(delta("Arieswar", 900)), losses = List(delta("Unlucky One", -18402993))))
    embed.getDescription should include(up + " **900**")
    embed.getDescription should include(down + " **18,402,993**")
    // the falling icon already says it; a minus would say it twice
    embed.getDescription should not include "-18,402,993"
    embed.getDescription should not include "+900"
  }

  // --- headings ------------------------------------------------------------

  test("the date is an h2 and outranks its own sections") {
    val embed = build(report(gains = List(delta("Arieswar", 900))))
    embed.getDescription should startWith(s"## $news [Thursday 10 September 2026](")
    embed.getDescription should include("### Top Experience Gained")
  }

  test("only the title carries an emoji; the section labels are bare") {
    // Repeating the trick on every heading under the title turns a hierarchy
    // into a row of badges, so the labels are plain words on purpose.
    val embed = build(report(
      gains = List(delta("Arieswar", 900)),
      losses = List(delta("Unlucky One", -900)),
      advance = Some(advance("magiclevel", 131)),
      kills = Some(summary())))
    embed.getDescription.linesIterator.filter(_.startsWith("### ")).foreach { heading =>
      heading should not include ":"
    }
  }

  test("the date links through to the world") {
    build(report(gains = List(delta("Arieswar", 900)))).getDescription should
      include("https://www.tibia.com/community/?subtopic=worlds&world=Antica")
  }

  test("a section with nothing in it is absent rather than an empty heading") {
    val embed = build(report(gains = List(delta("Arieswar", 900))))
    embed.getDescription should not include "Top Experience Lost"
    embed.getDescription should not include "Top Skill Advancement"
    embed.getDescription should not include "Creature Stats"
  }

  test("there are no fields at all") {
    // Which is what frees the post from the 1,024-character cap and from
    // reflowing differently on a phone.
    build(report(gains = List(delta("Arieswar", 900)), kills = Some(summary()))).getFields shouldBe empty
  }

  // --- the other three sections -------------------------------------------

  test("magic level is named without doubling the word level") {
    val embed = build(report(advance = Some(advance("magiclevel", 131))))
    embed.getDescription should include("<:mlvl:3> magic level **131**")
    embed.getDescription should not include "magic level level"
  }

  test("a category this build no longer knows renders plainly instead of throwing") {
    build(report(advance = Some(advance("bosspoints", 4200)))).getDescription should include("bosspoints **4200**")
  }

  test("a day nobody gained on says so rather than showing an empty list") {
    build(report(losses = List(delta("Unlucky One", -900)))).getDescription should include("Nobody")
  }

  test("the board no longer carries the creature figures at all") {
    // They are their own embed now, because they travel in a different message
    // on a morning tibia.com is slow and because they carry their own colour.
    val embed = build(report(
      gains = List(delta("Arieswar", 900)),
      kills = Some(summary()),
      topKills = List(killed("flimsy lost souls", 23965))))
    embed.getDescription should not include "Creature Stats"
    embed.getDescription should not include "flimsy lost souls"
  }

  // --- the creature embed --------------------------------------------------

  private val gold = "<:gold:5>"

  private def creature(r: DailyReport) =
    StatisticsEmbeds.creatureStats(r, news, gold, key => s"<:$key:9>")

  private def creatureBody(r: DailyReport) = creature(r).head.getDescription

  test("the creatures are listed largest first, count leading") {
    val body = creatureBody(report(
      kills = Some(summary()),
      topKills = List(killed("flimsy lost souls", 23965), killed("quara looters", 13))))
    body should startWith(s"## $news Creature Stats")
    body should include("**23,965** flimsy lost souls killed")
    body should include("**13** quara looters killed")
    body.indexOf("flimsy") should be < body.indexOf("quara")
  }

  test("what killed the most players is not reported") {
    creatureBody(report(kills = Some(summary()), topKills = List(killed("dragon", 900)))) should
      not include "killed by"
  }

  test("special kills get their own heading, one rank up, led by the gold icon") {
    val body = creatureBody(report(
      kills = Some(summary()),
      topKills = List(killed("dragon", 900)),
      specials = List(SpecialKills.all.head -> 3)))
    body should include(s"## $gold Special Kills")
    body should include("<:plunder:9> **3** Plunder Patriarch killed")
  }

  test("a special boss is shown by its name, not the race the endpoint counts it under") {
    val kill = SpecialKills.all.head
    kill.race shouldBe "plunder patriarches"
    val body = creatureBody(report(kills = Some(summary()), specials = List(kill -> 1)))
    body should include("Plunder Patriarch killed")
    body should not include "patriarches"
  }

  test("a day none of them died has no Special Kills heading") {
    creatureBody(report(kills = Some(summary()), topKills = List(killed("dragon", 900)))) should
      not include "Special Kills"
  }

  test("a special boss with no configured emoji renders without one rather than with a gap") {
    val body = StatisticsEmbeds.creatureStats(
      report(kills = Some(summary()), specials = List(SpecialKills.all.head -> 2)),
      news, gold, _ => "").head.getDescription
    body should include("**2** Plunder Patriarch killed")
    body should not include "  **2**"
  }

  test("a day with nothing killed produces no embed at all") {
    creature(report(kills = Some(summary()))) shouldBe empty
  }

  test("the creature embed wears the bot's yellow, not the board's green") {
    val built = creature(report(kills = Some(summary()), topKills = List(killed("dragon", 900))))
    built.foreach(_.getColor.getRGB & 0xFFFFFF shouldBe StatisticsEmbeds.CreatureColor)
    StatisticsEmbeds.CreatureColor should not be StatisticsEmbeds.WorldColor
  }

  test("the fullest creature embed fits inside Discord's limits") {
    val top = (1 to 10).toList.map(i => killed(s"some very long creature name $i", 100000 - i))
    val built = creature(report(
      kills = Some(summary()),
      topKills = top,
      specials = SpecialKills.all.map(_ -> 3)))
    built should have size 1
    built.head.getDescription.length should be < 4096
  }

  // --- limits --------------------------------------------------------------

  test("the fullest world embed fits inside Discord's limits") {
    val gains = (1 to 10).toList.map(i => delta(s"Averylongcharactername$i", 100000000L - i, level = 400 + i))
    val built = StatisticsEmbeds.build(
      report(gains, List(delta("Someoneunlucky", -9182993)), Some(advance("magiclevel", 131)), Some(summary())),
      news, _ => "<:otherguild:1><:enemy:2>", _ => "<:mlvl:3>", up, down)
    built should have size 1
    built.head.getDescription.length should be < 4096
    built.head.getLength should be < 6000
  }

  test("a board too long for one description spills onto a second embed") {
    // Not reachable with ten gainers, but the guard has to hold whatever the
    // list grows to: the tenth name is never dropped to make the post fit.
    val many = (1 to 200).toList.map(i => delta(s"Averylongcharactername$i", 100000000L - i))
    val built = pages(report(gains = many))
    built.size should be > 1
    built.foreach(_.getDescription.length should be <= 4096)
    // every name survives the split
    val whole = built.map(_.getDescription).mkString("\n")
    many.foreach(mover => whole should include(mover.displayName))
  }

  test("no page carries a thumbnail; the animated title icon is the picture") {
    val many = (1 to 200).toList.map(i => delta(s"Averylongcharactername$i", 100000000L - i))
    val built = pages(report(gains = many))
    built.foreach(_.getThumbnail shouldBe null)
    built.foreach(_.getColor.getRGB & 0xFFFFFF shouldBe StatisticsEmbeds.WorldColor)
  }
}
