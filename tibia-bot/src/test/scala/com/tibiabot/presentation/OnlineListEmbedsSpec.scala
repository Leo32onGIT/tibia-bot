package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class OnlineListEmbedsSpec extends AnyFunSuite with Matchers {

  test("durationString formats seconds as backticked minutes under an hour") {
    OnlineListEmbeds.durationString(0) shouldBe "`0min`"
    OnlineListEmbeds.durationString(59) shouldBe "`0min`"
    OnlineListEmbeds.durationString(60) shouldBe "`1min`"
    OnlineListEmbeds.durationString(3540) shouldBe "`59min`"
  }

  test("durationString switches to hours+minutes at 60 minutes") {
    OnlineListEmbeds.durationString(3600) shouldBe "`1hr 0min`"
    OnlineListEmbeds.durationString(3660) shouldBe "`1hr 1min`"
    OnlineListEmbeds.durationString(7320) shouldBe "`2hr 2min`"
  }
}
