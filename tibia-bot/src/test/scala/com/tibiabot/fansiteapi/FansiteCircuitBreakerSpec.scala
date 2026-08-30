package com.tibiabot.fansiteapi

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, Instant}

/** The breaker that stops the bot arguing with a Cloudflare block.
 *
 *  Written after a local smoke test got the machine IP-blocked inside four
 *  minutes, silently, while the bot carried on looking perfectly healthy. */
class FansiteCircuitBreakerSpec extends AnyFunSuite with Matchers {

  private val t0 = Instant.parse("2026-08-30T08:00:00Z")

  private class Fixture(openFor: Duration = Duration.ofMinutes(10)) {
    var clock: Instant = t0
    val breaker = new FansiteCircuitBreaker(openFor, () => clock)
    def advance(seconds: Long): Unit = clock = clock.plusSeconds(seconds)
  }

  test("starts closed") {
    new Fixture().breaker.isOpen shouldBe false
  }

  test("a 403 means blocked, not merely failed") {
    // The distinction the whole class exists for: this API refuses the entire
    // IP rather than the request, so there is nothing to retry.
    val f = new Fixture()
    f.breaker.blocks(403) shouldBe true
    f.breaker.blocks(429) shouldBe true
  }

  test("a bad token is not hidden behind the breaker") {
    // 401 is a configuration problem that pausing cannot fix; it should keep
    // surfacing rather than being quietly absorbed.
    new Fixture().breaker.blocks(401) shouldBe false
    new Fixture().breaker.blocks(503) shouldBe false
    new Fixture().breaker.blocks(404) shouldBe false
  }

  test("one 403 opens it — no threshold to cross first") {
    // By the time a block arrives the damage is done. Waiting for a second one
    // means sending another request to confirm we should stop sending.
    val f = new Fixture()
    f.breaker.recordBlocked(403)
    f.breaker.isOpen shouldBe true
  }

  test("it stays open for the configured window") {
    val f = new Fixture(Duration.ofMinutes(10))
    f.breaker.recordBlocked(403)
    f.advance(599)
    f.breaker.isOpen shouldBe true
  }

  test("it closes itself once the window passes, with no timer") {
    val f = new Fixture(Duration.ofMinutes(10))
    f.breaker.recordBlocked(403)
    f.advance(601)
    f.breaker.isOpen shouldBe false
    f.breaker.openUntilInstant shouldBe None
  }

  test("a fresh block while already open extends the pause") {
    // Otherwise a block that outlasts the window would be re-probed on a fixed
    // cadence forever, which is how a short block becomes a long one.
    val f = new Fixture(Duration.ofMinutes(10))
    f.breaker.recordBlocked(403)
    f.advance(300)
    f.breaker.recordBlocked(403)
    f.advance(301) // past the first window, inside the second
    f.breaker.isOpen shouldBe true
  }

  test("it can reopen after closing") {
    val f = new Fixture(Duration.ofMinutes(10))
    f.breaker.recordBlocked(403)
    f.advance(601)
    f.breaker.isOpen shouldBe false
    f.breaker.recordBlocked(429)
    f.breaker.isOpen shouldBe true
  }
}
