package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class WorldTransfersSpec extends AnyFunSuite with Matchers {

  test("sources: a former world on the world we are polling is an arrival") {
    WorldTransfers.sources("Antica", "Antica", List("Nefera")) shouldBe List("Nefera")
  }

  test("sources: no former world is not an arrival") {
    WorldTransfers.sources("Antica", "Antica", Nil) shouldBe Nil
  }

  test("sources: a character who has since transferred out is not an arrival here") {
    // Still in the recently-online set for this world, but the sheet says they left.
    WorldTransfers.sources("Bona", "Antica", List("Nefera")) shouldBe Nil
  }

  test("sources drops blanks, whitespace and this world itself") {
    WorldTransfers.sources("Antica", "Antica", List("", "  ", " Nefera ", "antica")) shouldBe List("Nefera")
  }

  test("sources keeps several former worlds, deduplicated") {
    WorldTransfers.sources("Antica", "Antica", List("Nefera", "Bona", "Nefera")) shouldBe List("Nefera", "Bona")
  }

  test("unreported announces a transfer this guild has not posted") {
    WorldTransfers.unreported("Antica", "Antica", List("Nefera"), None) shouldBe Some(List("Nefera"))
  }

  test("unreported stays quiet for a transfer already posted") {
    WorldTransfers.unreported("Antica", "Antica", List("Nefera"), Some(List("Nefera"))) shouldBe None
  }

  test("unreported ignores case and ordering when comparing against what was posted") {
    WorldTransfers.unreported("Antica", "Antica", List("Bona", "nefera"), Some(List("Nefera", "bona"))) shouldBe None
  }

  test("unreported announces a second, different transfer by the same character") {
    WorldTransfers.unreported("Antica", "Antica", List("Nefera", "Bona"), Some(List("Nefera"))) shouldBe
      Some(List("Nefera", "Bona"))
  }

  test("unreported announces again once a former world has cleared and a new one appears") {
    WorldTransfers.unreported("Antica", "Antica", List("Bona"), Some(List("Nefera"))) shouldBe Some(List("Bona"))
  }

  test("unreported stays quiet when there is nothing to announce, posted or not") {
    WorldTransfers.unreported("Antica", "Antica", Nil, None) shouldBe None
    WorldTransfers.unreported("Antica", "Antica", Nil, Some(List("Nefera"))) shouldBe None
    WorldTransfers.unreported("Bona", "Antica", List("Nefera"), None) shouldBe None
  }

  test("sourceText reads as a phrase for one, two or more worlds") {
    WorldTransfers.sourceText(List("Nefera")) shouldBe "Nefera"
    WorldTransfers.sourceText(List("Nefera", "Bona")) shouldBe "Nefera and Bona"
    WorldTransfers.sourceText(List("Nefera", "Bona", "Antica")) shouldBe "Nefera, Bona and Antica"
  }

  test("the arrow follows the side: hunted red, allied green, stranger grey") {
    WorldTransfers.side(hunted = true, allied = false) shouldBe WorldTransfers.Side.Hunted
    WorldTransfers.side(hunted = false, allied = true) shouldBe WorldTransfers.Side.Allied
    WorldTransfers.side(hunted = false, allied = false) shouldBe WorldTransfers.Side.Neutral
  }

  test("somebody on both lists arrives as hunted, as they read everywhere else") {
    WorldTransfers.side(hunted = true, allied = true) shouldBe WorldTransfers.Side.Hunted
  }
}
