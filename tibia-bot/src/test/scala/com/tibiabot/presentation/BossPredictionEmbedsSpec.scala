package com.tibiabot.presentation

import com.tibiabot.statistics._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

/** The boss embed: one list, a dot per row, and Discord timestamps rather than
 *  day counts. */
class BossPredictionEmbedsSpec extends AnyFunSuite with Matchers {

  private val day = LocalDate.of(2026, 9, 10)
  private val title = "<:boss:1195770698401075281>"
  private val icon = "<:nemesis:1024708740810821662>"

  private def boss(name: String, min: Int = 12, max: Int = 28, spawnPoints: Int = 1) =
    Boss(name, scala.None, predict = true, min, max, spawnPoints, "Profitable")

  /** A prediction built the way the predictor builds one, so the window instants
   *  are derived rather than asserted into place. */
  private def prediction(name: String, daysSince: Int, min: Int = 12, max: Int = 28, spawns: Int = 1) =
    BossPredictor.predict(
      boss(name, min, max, spawns),
      List.fill(spawns)((day.minusDays(daysSince.toLong), 1)),
      day).get

  private def report(predictions: List[BossPrediction] = Nil, awaiting: Int = 0) =
    DailyReport("Antica", day, Nil, scala.None, scala.None, scala.None, predictions, awaiting)

  private def pages(r: DailyReport) = BossPredictionEmbeds.build(r, title, icon)

  /** The one page an ordinary day produces. */
  private def build(r: DailyReport) = pages(r).head

  test("the title is Bosses Due and carries the boosted-boss icon") {
    build(report(List(prediction("Furyosa", 20)))).getDescription should
      startWith(s"## $title Bosses Due")
  }

  test("the rows carry the nemesis icon, not the one on the title") {
    // Two different glyphs on purpose: the heading means "bosses" in general,
    // a row means this named boss.
    val description = build(report(List(prediction("Furyosa", 20)))).getDescription
    description.linesIterator.drop(1).toList.foreach { row =>
      row should include(icon)
      row should not include title
    }
  }

  test("every row leads with a dot for the chance and the boss icon") {
    val embed = build(report(List(prediction("Furyosa", 20))))
    embed.getDescription should include(s":green_circle: $icon **Furyosa**")
  }

  test("a low chance gets the other dot") {
    build(report(List(prediction("White Pale", 11)))).getDescription should include(":yellow_circle:")
  }

  test("a boss inside its window says when the window closes") {
    // Ferumbras at 165 days into a 161-175 window: it is up now, and what a
    // reader wants is how long they have.
    val embed = build(report(List(prediction("Ferumbras", 165, 161, 175))))
    embed.getDescription should include("window closes <t:")
    embed.getDescription should include(":R>")
  }

  test("a boss short of its window says when the window opens") {
    val embed = build(report(List(prediction("White Pale", 11))))
    embed.getDescription should include("opens <t:")
  }

  test("a boss past a window that had an end says how long it has been overdue") {
    build(report(List(prediction("Man in the Cave", 200, 12, 16))))
      .getDescription should include("overdue since <t:")
  }

  test("timestamps are relative, so the post stays true after the morning") {
    // A rendered day count freezes at the moment of posting; <t:...:R> does not.
    val embed = build(report(List(prediction("Furyosa", 20))))
    embed.getDescription should not include "20 days"
    embed.getDescription should include(":R>")
  }

  test("a boss that is not due is left out entirely") {
    val embed = build(report(List(prediction("Furyosa", 20), prediction("Yeti", 2))))
    embed.getDescription should include("Furyosa")
    embed.getDescription should not include "Yeti"
  }

  test("a multi-spawn boss says how many of its spawns are up") {
    build(report(List(prediction("Rotworm Queen", 20, 12, 24, spawns = 3))))
      .getDescription should include("×3")
  }

  test("a single spawn carries no multiplier") {
    build(report(List(prediction("Furyosa", 20)))).getDescription should not include "×"
  }

  test("a long list spills onto a second embed rather than being cut short") {
    // It used to stop at twenty and count the rest. The cap was there because
    // this embed shared one message with the other two, which it no longer must.
    val many = (1 to 120).toList.map(i => prediction(s"Averylongbossname$i", 20 + i))
    val built = pages(report(many))
    built.size should be > 1
    built.foreach(_.getDescription.length should be <= 4096)
    val whole = built.map(_.getDescription).mkString("\n")
    (1 to 120).foreach(i => whole should include(s"Averylongbossname$i"))
    whole should not include "more"
  }

  // --- the honest part ------------------------------------------------------

  test("a world still waiting for sightings says so rather than looking broken") {
    val embed = build(report(Nil, awaiting = 57))
    embed.getDescription should include("Not enough history")
    embed.getFooter.getText should include("57")
  }

  test("the footer goes once every boss has been seen") {
    build(report(List(prediction("Furyosa", 20)))).getFooter shouldBe null
  }

  test("a mature history with nothing due says that plainly") {
    val embed = build(report(List(prediction("Yeti", 2))))
    embed.getDescription should include("No boss is inside a spawn window")
  }

  test("nothing at all produces no embed rather than an empty one") {
    pages(report()) shouldBe empty
  }

  test("there are no fields") {
    build(report(List(prediction("Furyosa", 20)))).getFields shouldBe empty
  }

  test("the footer goes on the last page, where a reader looks for it") {
    val many = (1 to 120).toList.map(i => prediction(s"Averylongbossname$i", 20 + i))
    val built = pages(report(many, awaiting = 12))
    built.size should be > 1
    built.init.foreach(_.getFooter shouldBe null)
    built.last.getFooter.getText should include("12")
  }
}
