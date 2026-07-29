package com.tibiabot.discord

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class AdaptiveRefreshIntervalSpec extends AnyFunSuite with Matchers {

  test("an idle lane refreshes at the fast default") {
    AdaptiveRefreshInterval.intervalSeconds(0) shouldBe 120
    AdaptiveRefreshInterval.intervalSeconds(100) shouldBe 120
  }

  test("crossing the first tier boundary slows the interval") {
    AdaptiveRefreshInterval.intervalSeconds(101) shouldBe 200
    AdaptiveRefreshInterval.intervalSeconds(400) shouldBe 200
  }

  test("crossing the second tier boundary slows further") {
    AdaptiveRefreshInterval.intervalSeconds(401) shouldBe 300
    AdaptiveRefreshInterval.intervalSeconds(800) shouldBe 300
  }

  test("a heavily saturated lane hits the slowest ceiling") {
    AdaptiveRefreshInterval.intervalSeconds(801) shouldBe 600
    AdaptiveRefreshInterval.intervalSeconds(10000) shouldBe 600
  }

  test("with no previous cadence, the depth's own tier is taken") {
    AdaptiveRefreshInterval.intervalSeconds(0, 0) shouldBe 120
    AdaptiveRefreshInterval.intervalSeconds(500, 0) shouldBe 300
  }

  test("backing off is immediate — congestion is not something to ease into") {
    AdaptiveRefreshInterval.intervalSeconds(101, 120) shouldBe 200
    AdaptiveRefreshInterval.intervalSeconds(801, 120) shouldBe 600
  }

  test("speeding up waits until the depth is clear of the faster tier's ceiling") {
    // Tier 1 tops out at 100, so 15% clearance means 85.
    AdaptiveRefreshInterval.intervalSeconds(100, 200) shouldBe 200 // at the ceiling: hold
    AdaptiveRefreshInterval.intervalSeconds(86, 200) shouldBe 200  // close, but not clear
    AdaptiveRefreshInterval.intervalSeconds(85, 200) shouldBe 120  // clear: speed up
  }

  test("a depth parked on a tier boundary holds one cadence instead of flapping") {
    // Production sits at ~400, right on the second tier's ceiling. Without
    // hysteresis this oscillates 200/300 as the depth samples either side.
    val depths = List(399, 401, 398, 402, 400, 405, 396)
    val cadences = depths.scanLeft(200)((current, depth) => AdaptiveRefreshInterval.intervalSeconds(depth, current)).tail
    // The first sample over the line backs off to 300, and it stays there:
    // dropping back to 200 needs a depth of 340 or lower.
    cadences shouldBe List(200, 300, 300, 300, 300, 300, 300)
    AdaptiveRefreshInterval.intervalSeconds(340, 300) shouldBe 200
  }

  test("the slowest tier has no ceiling to clear, so it is never held onto") {
    AdaptiveRefreshInterval.intervalSeconds(10000, 600) shouldBe 600
    AdaptiveRefreshInterval.intervalSeconds(680, 600) shouldBe 300 // 15% clear of 800
    AdaptiveRefreshInterval.intervalSeconds(681, 600) shouldBe 600
  }
}
