package com.tibiabot.presentation

import com.tibiabot.statistics._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

/** The Bosses Due card: the due bosses in two groups by chance, each under its
 *  dot, names linked to the wiki, and Discord timestamps rather than day counts. */
class BossPredictionEmbedsSpec extends AnyFunSuite with Matchers {

  private val day = LocalDate.of(2026, 9, 10)
  private val title = "<:boss:1195770698401075281>"
  private val icon = "<:nemesis:1024708740810821662>"
  private val charm = "<:charm:1553322090973634601>"

  private def boss(name: String, min: Int = 12, max: Int = 28, spawnPoints: Int = 1, creature: Boolean = false) =
    Boss(name, scala.None, predict = true, min, max, spawnPoints, "Profitable", creature)

  /** A prediction built the way the predictor builds one, so the window instants
   *  are derived rather than asserted into place. */
  private def prediction(name: String, daysSince: Int, min: Int = 12, max: Int = 28, spawns: Int = 1,
                         creature: Boolean = false) =
    BossPredictor.predict(
      boss(name, min, max, spawns, creature),
      List.fill(spawns)((day.minusDays(daysSince.toLong), 1)),
      day).get

  private def report(predictions: List[BossPrediction] = Nil, awaiting: Int = 0) =
    DailyReport("Antica", day, Nil, Nil, scala.None, scala.None,
      predictions = predictions, awaitingSighting = awaiting)

  /** Stands in for the wiki lookup: everything resolves but "Nowiki". */
  private val wiki: String => Option[String] = name => if (name == "Nowiki") scala.None else Some(name)

  private def card(r: DailyReport) = BossPredictionEmbeds.build(r, title, icon, charm, wiki)

  private def build(r: DailyReport) = card(r).get

  // Furyosa 20 days into a 12-28 window is high; White Pale at 11 is short of
  // it by a day, which is only a low chance.
  private def furyosa = prediction("Furyosa", 20)
  private def whitePale = prediction("White Pale", 11)

  test("the title is Bosses Due and carries the boosted-boss icon") {
    build(report(List(furyosa))).blocks.head shouldBe s"## $title Bosses Due"
  }

  test("the card is purple") {
    build(report(List(furyosa))).colour shouldBe BossPredictionEmbeds.PredictionColor
  }

  test("high chance comes first, then low, each group under its dot and a small-caps label") {
    val built = build(report(List(whitePale, furyosa)))
    built.blocks.tail.map(_.linesIterator.next()) shouldBe List(
      "-# :green_circle: **HIGH CHANCE**",
      "-# :yellow_circle: **LOW CHANCE**")
    built.blocks(1) should include("Furyosa")
    built.blocks(2) should include("White Pale")
  }

  test("a day with only one chance has only that group") {
    build(report(List(furyosa))).blocks.tail.map(_.linesIterator.next()) shouldBe List("-# :green_circle: **HIGH CHANCE**")
  }

  test("the rows carry no dot of their own, since the label says the chance") {
    val rows = build(report(List(whitePale, furyosa))).blocks.tail.flatMap(_.linesIterator.drop(1))
    rows should not be empty
    rows.foreach { row =>
      row should not include "circle"
    }
  }

  test("the rows carry the nemesis icon, not the one on the title") {
    // Two different glyphs on purpose: the title means "bosses" in general,
    // a row means this named boss.
    build(report(List(furyosa))).blocks.tail.flatMap(_.linesIterator.drop(1)).foreach { row =>
      row should startWith(icon)
      row should not include title
    }
  }

  test("a rare creature is led by the charm icon, not the nemesis one") {
    // Yeti is a bestiary creature that spawns on a cycle, not a boss.
    val text = build(report(List(furyosa, prediction("Yeti", 20, 18, 25, creature = true)))).text
    text should include(s"$charm **[Yeti](https://tibia.fandom.com/wiki/Yeti)**")
    text should not include s"$icon **[Yeti]"
    text should include(s"$icon **[Furyosa]")
  }

  test("a boss's name links to its wiki page") {
    build(report(List(furyosa))).text should include(s"$icon **[Furyosa](https://tibia.fandom.com/wiki/Furyosa)**")
  }

  test("a boss the wiki lookup does not match reads unlinked") {
    val text = build(report(List(prediction("Nowiki", 20)))).text
    text should include(s"$icon **Nowiki**")
    text should not include "]("
  }

  test("a boss inside its window says when the window closes") {
    // Ferumbras at 165 days into a 161-175 window: it is up now, and what a
    // reader wants is how long they have.
    val text = build(report(List(prediction("Ferumbras", 165, 161, 175)))).text
    text should include("window closes <t:")
    text should include(":R>")
  }

  test("a boss short of its window says when the window opens") {
    build(report(List(whitePale))).text should include("opens <t:")
  }

  test("a boss past a window that had an end says how long it has been overdue") {
    build(report(List(prediction("Man in the Cave", 200, 12, 16)))).text should include("overdue since <t:")
  }

  test("timestamps are relative, so the post stays true after the morning") {
    // A rendered day count freezes at the moment of posting; <t:...:R> does not.
    val text = build(report(List(furyosa))).text
    text should not include "20 days"
    text should include(":R>")
  }

  test("a boss that is not due is left out entirely") {
    val text = build(report(List(furyosa, prediction("Yeti", 2)))).text
    text should include("Furyosa")
    text should not include "Yeti"
  }

  test("a multi-spawn boss says how many of its spawns are up, outside the link") {
    build(report(List(prediction("Rotworm Queen", 20, 12, 24, spawns = 3)))).text should
      include("[Rotworm Queen](https://tibia.fandom.com/wiki/Rotworm_Queen)** ×3")
  }

  test("a single spawn carries no multiplier") {
    build(report(List(furyosa))).text should not include "×"
  }

  test("a long list is carried on to further messages rather than being cut short") {
    val many = (1 to 120).toList.map(i => prediction(s"Averylongbossname$i", 20 + i))
    val packed = StatisticsCard.pack(List(build(report(many))))
    packed.size should be > 1
    packed.foreach(_.flatMap(_._2).map(_.length).sum should be <= StatisticsCard.MaxText)
    val whole = packed.flatten.flatMap(_._2).mkString("\n")
    (1 to 120).foreach(i => whole should include(s"Averylongbossname$i"))
    whole should not include "more"
  }

  // --- the honest part ------------------------------------------------------

  test("a world still waiting for sightings says so, with the count as a grey line at the foot") {
    val built = build(report(Nil, awaiting = 57))
    built.text should include("Not enough history")
    built.text.linesIterator.toList.last shouldBe "-# 57 boss(es) not yet predicted"
  }

  test("the count sits under the last group when there are bosses due") {
    val built = build(report(List(whitePale, furyosa), awaiting = 4))
    built.blocks.last.linesIterator.toList.last shouldBe "-# 4 boss(es) not yet predicted"
    built.blocks.last should include("White Pale")
  }

  test("the count goes once every boss has been seen") {
    build(report(List(furyosa))).text should not include "not yet predicted"
  }

  test("a mature history with nothing due says that plainly") {
    build(report(List(prediction("Yeti", 2)))).text should include("No boss is inside a spawn window")
  }

  test("nothing at all produces no card rather than an empty one") {
    card(report()) shouldBe scala.None
  }
}
