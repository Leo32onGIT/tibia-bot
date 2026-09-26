package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class DeathEmbedsSpec extends AnyFunSuite with Matchers {

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
