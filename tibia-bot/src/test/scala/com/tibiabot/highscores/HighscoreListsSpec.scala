package com.tibiabot.highscores

import com.tibiabot.tibiadata.{HighscoreCategory, HighscoreSource, HighscoreVocation, Highscores}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The catalogue's shape, and the one invariant that keeps the load honest:
 *  nothing but magic level may cost us a request to our own instance. */
class HighscoreListsSpec extends AnyFunSuite with Matchers {

  test("the catalogue is twelve distinct lists") {
    HighscoreLists.all should have size 12
    HighscoreLists.all.distinct shouldBe HighscoreLists.all
  }

  test("magic level is the only category fetched through a vocation filter") {
    // The measured reason: every other skill list is already single-vocation
    // under `all`, so filtering it would return the same characters for the
    // price of a tibia.com scrape from the VPS IP. If a second category ever
    // appears here it should be because the mix was measured again, not by
    // accident.
    HighscoreLists.local.map(_.category).distinct shouldBe List(HighscoreCategory.MagicLevel)
    HighscoreLists.local should have size HighscoreVocation.vocations.size
  }

  test("magic level covers every vocation, so no vocation is shut out of it") {
    HighscoreLists.magicLevel.map(_.vocation) should contain theSameElementsAs HighscoreVocation.vocations
  }

  test("every public list is unfiltered, and every unfiltered list is public") {
    HighscoreLists.public.foreach(_.vocation shouldBe HighscoreVocation.All)
    HighscoreLists.all.filter(_.vocation == HighscoreVocation.All) shouldBe HighscoreLists.public
  }

  test("all skills post advances; experience alone does not") {
    HighscoreLists.skills.foreach(_.postsAdvances shouldBe true)
    HighscoreLists.experience.postsAdvances shouldBe false
    HighscoreLists.experience.source shouldBe HighscoreSource.Public
  }

  test("no non-skill category is fetched") {
    // Fishing, achievements, charm points, boss points, drome and loyalty are
    // all reachable at the same cost per list, and none of them is a skill
    // advancement. Their absence is a decision, so it gets a test.
    HighscoreLists.all.map(_.category).distinct should contain theSameElementsAs HighscoreCategory.all
    HighscoreCategory.all should have size 8
  }

  test("the per-world page budget is 240, of which 100 touch our own instance") {
    HighscoreLists.pagesPerWorld(HighscoreLists.all) shouldBe 240
    HighscoreLists.pagesPerWorld(HighscoreLists.local) shouldBe 100
    HighscoreLists.pagesPerWorld(HighscoreLists.public) shouldBe 140
    Highscores.pages should have size Highscores.MaxPages
  }
}
