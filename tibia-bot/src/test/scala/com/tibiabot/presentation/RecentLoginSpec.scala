package com.tibiabot.presentation

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** Pins the last-login stamp logic with a fixed clock; date strings are in the
 *  ISO form the TibiaData API returns. */
class RecentLoginSpec extends AnyFunSuite with Matchers {

  private val now = Instant.parse("2026-05-31T12:00:00Z")

  test("a login within 24h renders the daily emoji and a relative timestamp") {
    val login = "2026-05-31T02:00:00Z" // 10h ago
    val expectedEpoch = Instant.parse(login).getEpochSecond.toString
    RecentLogin.stamp(login, now) shouldBe s"<:daily:1133349016814485584><t:$expectedEpoch:R>"
  }

  test("a login exactly 24h ago is still included (<= 24)") {
    RecentLogin.stamp("2026-05-30T12:00:00Z", now) should startWith("<:daily:")
  }

  test("a login older than 24h yields no stamp") {
    RecentLogin.stamp("2026-05-29T11:00:00Z", now) shouldBe ""
  }

  test("a future login within 24h is included (absolute difference)") {
    RecentLogin.stamp("2026-05-31T20:00:00Z", now) should startWith("<:daily:")
  }

  test("empty input yields no stamp") {
    RecentLogin.stamp("", now) shouldBe ""
  }

  test("malformed input yields no stamp instead of throwing") {
    noException should be thrownBy RecentLogin.stamp("not-a-date", now)
    RecentLogin.stamp("not-a-date", now) shouldBe ""
  }
}
