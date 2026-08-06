package com.tibiabot.wiki

import com.tibiabot.domain.{BossEntry, DreamScarSnapshot}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.DayOfWeek

/** Pure parse tests for the Fandom wiki HTML, using fixture strings (no network). */
class FandomWikiParserSpec extends AnyFunSuite with Matchers {

  test("parseDreamScarBosses reads (world, boss) from the wikitable, skipping the header") {
    val html =
      """<table class="wikitable">
        |<tr><th>World</th><th>Boss</th></tr>
        |<tr><td>Antica</td><td>Plagueroot</td></tr>
        |<tr><td>Bona</td><td>Maxxenius</td></tr>
        |</table>""".stripMargin

    FandomWikiParser.parseDreamScarBosses(html) shouldBe List(
      BossEntry("Antica", "Plagueroot"),
      BossEntry("Bona", "Maxxenius"))
  }

  test("parseDreamScarBosses returns Nil when there is no wikitable") {
    FandomWikiParser.parseDreamScarBosses("<p>no table here</p>") shouldBe Nil
  }

  // Shape taken from the live page: the sentence is prose broken up by links,
  // so it only reads cleanly off the document text.
  private val dayHtml =
    """<div>
      |Today is <b>Wednesday</b> and the <a href="/wiki/Dream_Scar">Dream Scar</a> boss on
      |<b>most worlds</b> should be <b><a href="/wiki/Maxxenius">Maxxenius</a></b>.
      |</div>""".stripMargin

  test("parseDreamScarDay reads the day the page says it was rendered for") {
    FandomWikiParser.parseDreamScarDay(dayHtml) shouldBe Some(DayOfWeek.WEDNESDAY)
  }

  test("parseDreamScarDay is empty when the page doesn't say") {
    FandomWikiParser.parseDreamScarDay("<p>no date here</p>") shouldBe None
    FandomWikiParser.parseDreamScarDay("<p>Today is Blursday</p>") shouldBe None
  }

  test("parseDreamScarSnapshot returns the day and the table from one read") {
    val html =
      dayHtml +
        """<table class="wikitable">
          |<tr><th>World</th><th>Boss</th></tr>
          |<tr><td>Victoris</td><td>Malofur Mangrinder</td></tr>
          |</table>""".stripMargin

    FandomWikiParser.parseDreamScarSnapshot(html) shouldBe DreamScarSnapshot(
      Some(DayOfWeek.WEDNESDAY),
      List(BossEntry("Victoris", "Malofur Mangrinder")))
  }

  test("parseCreatureNames keeps /wiki/ creature links, dedups, drops namespaced/list links") {
    val html =
      """<div>
        |<a href="/wiki/Dragon">Dragon</a>
        |<a href="/wiki/Demon">Demon</a>
        |<a href="/wiki/Category:Creatures">Category:Creatures</a>
        |<a href="/wiki/List_of_Creatures_(Ordered)">List</a>
        |<a href="/wiki/Dragon">Dragon</a>
        |<a href="https://example.com/external">External</a>
        |</div>""".stripMargin

    FandomWikiParser.parseCreatureNames(html) shouldBe List("Dragon", "Demon")
  }
}
