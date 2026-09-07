package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The hunted and allies lists reuse the online list's packer so a world heading
 *  starts a fresh embed instead of landing halfway down one. They head worlds
 *  with "## " where the online list uses "### ", so the packer has to recognise
 *  both — this pins that, and that teaching it "## " changed nothing for the
 *  online list's own headings.
 */
class WorldHeaderPackingSpec extends AnyFunSuite with Matchers {

  private def embedsOf(lines: List[String]): List[String] =
    OnlineListEmbeds.packMessages(lines).flatten

  test("a world heading opens a fresh embed rather than continuing the last one") {
    val embeds = embedsOf(List("## Antica", "player one", "## Bona", "player two"))
    embeds should have size 2
    embeds.head should include("Antica")
    embeds.head should include("player one")
    embeds.head should not include "Bona"
    embeds(1) should include("Bona")
    embeds(1) should include("player two")
  }

  test("a heading is never left without the players it introduces") {
    val embeds = embedsOf(List("## Antica", "a", "## Bona", "b", "## Zuna", "z"))
    embeds.foreach { description =>
      // Every embed that names a world also carries at least one line under it.
      if (description.contains("## ")) description.linesIterator.size should be > 1
    }
  }

  test("the first heading does not strand an empty embed above it") {
    val embeds = embedsOf(List("## Antica", "a"))
    embeds should have size 1
    embeds.head should include("Antica")
  }

  test("rows on their own are packed without breaks") {
    val embeds = embedsOf(List("a", "b", "c"))
    embeds should have size 1
  }

  /** The online list's own headings must behave exactly as they did. */
  test("### section and guild headings are unaffected") {
    val embeds = embedsOf(List("### Allies", "### [A Guild]", "row", "### Enemies", "### [B Guild]", "row"))
    embeds.size should be >= 2
    embeds.mkString should include("### Allies")
    embeds.mkString should include("### Enemies")
  }

  test("nothing is lost, whatever the headings") {
    val lines = List("## Antica", "a", "b", "## Bona", "c", "## Not checked yet", "d")
    val joined = embedsOf(lines).mkString("\n")
    lines.foreach(line => joined should include(line))
  }
}
