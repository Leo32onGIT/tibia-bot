package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class NamesSpec extends AnyFunSuite with Matchers {

  test("capitalizeWords upper-cases the first letter of each word") {
    Names.capitalizeWords("violent beams") shouldBe "Violent Beams"
    Names.capitalizeWords("the count") shouldBe "The Count"
  }

  test("capitalizeWords leaves the rest of each word untouched (not a full title-case)") {
    Names.capitalizeWords("violent BEAMS") shouldBe "Violent BEAMS"
    Names.capitalizeWords("mcDonald") shouldBe "McDonald"
  }

  test("capitalizeWords handles a single word and an empty string") {
    Names.capitalizeWords("morgaroth") shouldBe "Morgaroth"
    Names.capitalizeWords("") shouldBe ""
  }
}
