package com.tibiabot.domain

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class VocationsSpec extends AnyFunSuite with Matchers {

  test("display order is druids-first, none-last, with no duplicates") {
    Vocations.displayOrder shouldBe List("druid", "knight", "paladin", "sorcerer", "monk", "none")
    Vocations.displayOrder.distinct shouldBe Vocations.displayOrder
    Vocations.displayOrder.last shouldBe "none"
  }

  test("reverse equals the order WorldList folds in (regression pin)") {
    // WorldList.byWorld folds in reverse so druids end up first; this pins the
    // exact list that was previously hard-coded there.
    Vocations.displayOrder.reverse shouldBe List("none", "monk", "sorcerer", "paladin", "knight", "druid")
  }
}
