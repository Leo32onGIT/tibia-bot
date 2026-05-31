package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class BoostedEmbedsSpec extends AnyFunSuite with Matchers {

  test("builds the boosted embed with thumbnail, fixed colour and description; no title") {
    val e = BoostedEmbeds.create("https://x/thumb.gif", "The boosted boss today is X")
    e.getDescription shouldBe "The boosted boss today is X"
    e.getThumbnail.getUrl shouldBe "https://x/thumb.gif"
    (e.getColor.getRGB & 0xFFFFFF) shouldBe 3092790
    e.getTitle shouldBe null // title is intentionally commented out upstream
  }
}
