package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class DeathEmbedsSpec extends AnyFunSuite with Matchers {

  test("assembles title, description, thumbnail and colour") {
    val e = DeathEmbeds.build("Bobeek", "Elite Knight", "Died at level 100", "https://x/shot.gif", 3092790).build()
    e.getTitle shouldBe ":shield: Bobeek :shield:"
    e.getDescription shouldBe "Died at level 100"
    e.getThumbnail.getUrl shouldBe "https://x/shot.gif"
    (e.getColor.getRGB & 0xFFFFFF) shouldBe 3092790
  }

  test("title links to the character page and uses the monk emoji where applicable") {
    val e = DeathEmbeds.build("Some Monk", "Exalted Monk", "desc", "https://x/t.gif", 1).build()
    e.getUrl shouldBe "https://www.tibia.com/community/?name=Some+Monk"
    e.getTitle should startWith(":fist::skin-tone-3:")
  }

  // --- shouldShow: allegiance colour -> per-category visibility ---

  test("shouldShow gates each neutral colour on showNeutralDeaths") {
    for (c <- Seq(3092790, 14869218, 4540237, 14397256)) {
      DeathEmbeds.shouldShow(c, "true", "true", "true") shouldBe true
      DeathEmbeds.shouldShow(c, "false", "true", "true") shouldBe false
    }
  }

  test("shouldShow gates the enemy colour only on showEnemiesDeaths") {
    DeathEmbeds.shouldShow(36941, "true", "true", "false") shouldBe false
    DeathEmbeds.shouldShow(36941, "false", "false", "true") shouldBe true
  }

  test("shouldShow gates the ally colour only on showAlliesDeaths") {
    DeathEmbeds.shouldShow(13773097, "true", "false", "true") shouldBe false
    DeathEmbeds.shouldShow(13773097, "false", "true", "false") shouldBe true
  }

  test("shouldShow always shows colours outside the three allegiance groups (e.g. purple notable)") {
    DeathEmbeds.shouldShow(11563775, "false", "false", "false") shouldBe true
  }
}
