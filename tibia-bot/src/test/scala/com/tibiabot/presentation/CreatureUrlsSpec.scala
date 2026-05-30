package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Characterization of the creature name-parsing shared by the image/wiki URL
 *  builders. Uses an explicit mappings map so the parsing fallback is exercised. */
class CreatureUrlsSpec extends AnyFunSuite with Matchers {

  private val noMappings = Map.empty[String, String]

  test("simple name is capitalised") {
    Urls.creatureFileName("dragon", noMappings) shouldBe "Dragon"
  }

  test("capitalises the letter after punctuation and spaces") {
    Urls.creatureFileName("mooh'tah warrior", noMappings) shouldBe "Mooh'Tah_Warrior"
    Urls.creatureFileName("two-headed turtle", noMappings) shouldBe "Two-Headed_Turtle"
  }

  test("lowercases interior articles/prepositions but capitalises the first word") {
    Urls.creatureFileName("the voice of ruin", noMappings) shouldBe "The_Voice_of_Ruin"
  }

  test("an explicit mapping short-circuits the parsing") {
    Urls.creatureFileName("foo", Map("foo" -> "Custom_Name")) shouldBe "Custom_Name"
  }

  test("image and wiki URLs wrap the resolved file name") {
    Urls.creatureImageUrl("dragon", noMappings) shouldBe
      "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Dragon.gif"
    Urls.creatureWikiUrl("dragon", noMappings) shouldBe
      "https://www.tibiawiki.com.br/wiki/Dragon"
  }
}
