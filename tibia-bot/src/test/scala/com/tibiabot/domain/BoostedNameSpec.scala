package com.tibiabot.domain

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class BoostedNameSpec extends AnyFunSuite with Matchers {

  test("trims surrounding space and lowercases") {
    BoostedName.sanitize("  Grand Master Oberon  ") shouldBe "grand master oberon"
  }

  test("keeps apostrophes and hyphens so real boss names survive") {
    BoostedName.sanitize("Yselda's") shouldBe "yselda's"
    BoostedName.sanitize("Mega-Magmaoid") shouldBe "mega-magmaoid"
  }

  test("strips digits and other punctuation") {
    BoostedName.sanitize("Oberon123!") shouldBe "oberon"
    BoostedName.sanitize("a.b,c") shouldBe "abc"
  }

  test("input that is entirely stripped (or empty) yields the empty string") {
    BoostedName.sanitize("") shouldBe ""
    BoostedName.sanitize("12345") shouldBe ""
  }
}
