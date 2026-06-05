package com.tibiabot.wiki

import com.tibiabot.domain.BossEntry
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

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
