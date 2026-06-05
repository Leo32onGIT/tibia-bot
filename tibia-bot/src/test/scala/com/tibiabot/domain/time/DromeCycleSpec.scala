package com.tibiabot.domain.time

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class DromeCycleSpec extends AnyFunSuite with Matchers {

  private val initial = DromeCycle.initial // 27 May 2026 (CEST), well clear of DST changes
  private val twoWeeks = 14L * 24 * 3600   // exactly 14 days while Berlin stays on CEST

  test("initial anchor is the 27 May 2026 server save") {
    initial shouldBe Instant.ofEpochSecond(1779868800L)
  }

  test("a target at or before the current anchor leaves it unchanged") {
    DromeCycle.advanceFrom(initial, initial) shouldBe initial
    DromeCycle.advanceFrom(initial, initial.minusSeconds(100)) shouldBe initial
  }

  test("a target just past the anchor advances exactly one 2-week step") {
    DromeCycle.advanceFrom(initial, initial.plusSeconds(1)) shouldBe initial.plusSeconds(twoWeeks)
  }

  test("a target on a step boundary lands on that boundary, not the next") {
    DromeCycle.advanceFrom(initial, initial.plusSeconds(twoWeeks)) shouldBe initial.plusSeconds(twoWeeks)
  }

  test("advances over multiple cycles until no longer before the target") {
    DromeCycle.advanceFrom(initial, initial.plusSeconds(twoWeeks * 2 + 5)) shouldBe
      initial.plusSeconds(twoWeeks * 3)
  }
}
