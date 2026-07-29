package com.tibiabot.discord

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class AdaptiveRefreshIntervalSpec extends AnyFunSuite with Matchers {

  test("an idle lane refreshes at the fast default") {
    AdaptiveRefreshInterval.intervalSeconds(0) shouldBe 120
    AdaptiveRefreshInterval.intervalSeconds(100) shouldBe 120
  }

  test("crossing the first tier boundary slows the interval") {
    AdaptiveRefreshInterval.intervalSeconds(101) shouldBe 150
    AdaptiveRefreshInterval.intervalSeconds(400) shouldBe 150
  }

  test("crossing the second tier boundary slows further") {
    AdaptiveRefreshInterval.intervalSeconds(401) shouldBe 300
    AdaptiveRefreshInterval.intervalSeconds(800) shouldBe 300
  }

  test("a heavily saturated lane hits the slowest ceiling") {
    AdaptiveRefreshInterval.intervalSeconds(801) shouldBe 600
    AdaptiveRefreshInterval.intervalSeconds(10000) shouldBe 600
  }
}
