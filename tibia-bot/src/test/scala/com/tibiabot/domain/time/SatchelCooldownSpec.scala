package com.tibiabot.domain.time

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

class SatchelCooldownSpec extends AnyFunSuite with Matchers {

  test("the satchel cooldown is 30 days") {
    SatchelCooldown.durationDays shouldBe 30L
  }

  test("expiresAtEpoch is the epoch-second 30 days after the start") {
    val start = ZonedDateTime.parse("2026-01-01T00:00:00Z")
    SatchelCooldown.expiresAtEpoch(start) shouldBe start.plusDays(30).toEpochSecond.toString
  }

  test("expiry is exactly 30 days of seconds after the start") {
    val start = ZonedDateTime.parse("2026-05-31T12:00:00Z")
    SatchelCooldown.expiresAtEpoch(start).toLong - start.toEpochSecond shouldBe 30L * 24 * 60 * 60
  }
}
