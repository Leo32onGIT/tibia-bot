package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class EmbedsSpec extends AnyFunSuite with Matchers {

  test("response builds a brand-coloured embed with only the description") {
    val e = Embeds.response("hello world")
    e.getDescription shouldBe "hello world"
    (e.getColor.getRGB & 0xFFFFFF) shouldBe Embeds.BrandColor
    e.getThumbnail shouldBe null
    e.getTitle shouldBe null
  }

  test("BrandColor is the bot's standard embed colour") {
    Embeds.BrandColor shouldBe 3092790
  }
}
