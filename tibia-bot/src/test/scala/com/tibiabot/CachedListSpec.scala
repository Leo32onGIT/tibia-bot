package com.tibiabot

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, ZonedDateTime}

/** Pins the TTL/fallback behaviour of CachedList with an injected fetcher and a
 *  controllable clock — no network or ActorSystem involved. */
class CachedListSpec extends AnyFunSuite with Matchers {

  private val t0 = ZonedDateTime.parse("2026-05-31T10:00:00Z")
  private val ttl = Duration.ofHours(1)

  /** A clock whose "now" can be advanced between get() calls. */
  private class MovableClock(var current: ZonedDateTime) { def now(): ZonedDateTime = current }

  test("first get fetches; a second get within the TTL reuses the cached value") {
    var calls = 0
    val clock = new MovableClock(t0)
    val cache = new CachedList[String](
      fetch = () => { calls += 1; Right(List("Antica", "Bona")) },
      fallback = Nil, ttl = ttl, now = () => clock.now()
    )

    cache.get() shouldBe List("Antica", "Bona")
    clock.current = t0.plusMinutes(59)
    cache.get() shouldBe List("Antica", "Bona")
    calls shouldBe 1 // not re-fetched within the hour
  }

  test("after the TTL expires the next get re-fetches") {
    var calls = 0
    val clock = new MovableClock(t0)
    val cache = new CachedList[String](
      fetch = () => { calls += 1; Right(List(s"world-$calls")) },
      fallback = Nil, ttl = ttl, now = () => clock.now()
    )

    cache.get() shouldBe List("world-1")
    clock.current = t0.plusHours(1).plusSeconds(1)
    cache.get() shouldBe List("world-2")
    calls shouldBe 2
  }

  test("a failed fetch falls back to the last good value") {
    var fail = false
    val clock = new MovableClock(t0)
    val cache = new CachedList[String](
      fetch = () => if (fail) Left("api down") else Right(List("Antica")),
      fallback = List("STATIC"), ttl = ttl, now = () => clock.now()
    )

    cache.get() shouldBe List("Antica")        // primes the cache
    clock.current = t0.plusHours(2)             // force expiry so fetch runs again
    fail = true
    cache.get() shouldBe List("Antica")         // keeps the last good value, not STATIC
  }

  test("a failure with no prior value falls back to the provided default") {
    val clock = new MovableClock(t0)
    val cache = new CachedList[String](
      fetch = () => Left("api down"),
      fallback = List("STATIC"), ttl = ttl, now = () => clock.now()
    )

    cache.get() shouldBe List("STATIC")
  }
}
