package com.tibiabot.domain.time

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Instant, ZonedDateTime}

/** A deterministic Clock for tests — the whole point of the port. */
final class FixedClock(fixed: Instant) extends Clock {
  def instant: Instant = fixed
  def now: ZonedDateTime = ZonedDateTime.ofInstant(fixed, Clock.Berlin)
}

class ClockSpec extends AnyFunSuite with Matchers {

  test("FixedClock returns the same instant every call") {
    val t = Instant.ofEpochSecond(1779868800L)
    val clock = new FixedClock(t)
    clock.instant shouldBe t
    clock.instant shouldBe t
  }

  test("now renders the fixed instant in the Europe/Berlin zone") {
    val clock = new FixedClock(Instant.ofEpochSecond(1779868800L))
    clock.now.getZone shouldBe Clock.Berlin
    clock.now.toInstant shouldBe Instant.ofEpochSecond(1779868800L)
  }
}
