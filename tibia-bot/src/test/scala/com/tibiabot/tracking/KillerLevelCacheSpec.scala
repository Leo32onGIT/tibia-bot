package com.tibiabot.tracking

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime
import scala.concurrent.duration._

/** Pins the TTL + negative-caching behaviour that keeps the death path from
 *  re-fetching the same killer's level on every death they appear in. */
class KillerLevelCacheSpec extends AnyFunSuite with Matchers {

  private val t0 = ZonedDateTime.parse("2026-07-29T12:00:00Z")

  test("an unseen name needs a lookup and has no level") {
    val cache = new KillerLevelCache(10.minutes)
    cache.needsLookup("Bubble", t0) shouldBe true
    cache.levelFor("Bubble", t0) shouldBe None
  }

  test("a recorded level is served without another lookup") {
    val cache = new KillerLevelCache(10.minutes)
    cache.record("Bubble", Some(900), t0)
    cache.needsLookup("Bubble", t0) shouldBe false
    cache.levelFor("Bubble", t0) shouldBe Some(900)
  }

  test("a failed lookup is cached too, so it is not retried immediately") {
    val cache = new KillerLevelCache(10.minutes)
    cache.record("Ghost", None, t0)
    cache.needsLookup("Ghost", t0) shouldBe false // the point of negative caching
    cache.levelFor("Ghost", t0) shouldBe None
  }

  test("names are matched case-insensitively, like the online table") {
    val cache = new KillerLevelCache(10.minutes)
    cache.record("Violent Beams", Some(700), t0)
    cache.levelFor("violent beams", t0) shouldBe Some(700)
    cache.needsLookup("VIOLENT BEAMS", t0) shouldBe false
  }

  test("an entry past its TTL is looked up again and stops answering") {
    val cache = new KillerLevelCache(10.minutes)
    cache.record("Bubble", Some(900), t0)
    val later = t0.plusMinutes(11)
    cache.needsLookup("Bubble", later) shouldBe true
    cache.levelFor("Bubble", later) shouldBe None
  }

  test("an entry just inside its TTL still answers") {
    val cache = new KillerLevelCache(10.minutes)
    cache.record("Bubble", Some(900), t0)
    val later = t0.plusMinutes(9)
    cache.needsLookup("Bubble", later) shouldBe false
    cache.levelFor("Bubble", later) shouldBe Some(900)
  }

  test("re-recording refreshes the entry's age") {
    val cache = new KillerLevelCache(10.minutes)
    cache.record("Bubble", Some(900), t0)
    cache.record("Bubble", Some(901), t0.plusMinutes(9))
    cache.levelFor("Bubble", t0.plusMinutes(17)) shouldBe Some(901)
  }

  test("prune drops expired entries and keeps live ones") {
    val cache = new KillerLevelCache(10.minutes)
    cache.record("Old", Some(100), t0)
    cache.record("New", Some(200), t0.plusMinutes(8))
    cache.prune(t0.plusMinutes(11))
    cache.size shouldBe 1
    cache.levelFor("New", t0.plusMinutes(11)) shouldBe Some(200)
  }

  test("the entry cap evicts the oldest rather than growing without bound") {
    val cache = new KillerLevelCache(10.minutes, maxEntries = 3)
    cache.record("a", Some(1), t0)
    cache.record("b", Some(2), t0.plusSeconds(1))
    cache.record("c", Some(3), t0.plusSeconds(2))
    cache.record("d", Some(4), t0.plusSeconds(3))
    cache.size shouldBe 3
    cache.levelFor("a", t0.plusSeconds(3)) shouldBe None // oldest went
    cache.levelFor("d", t0.plusSeconds(3)) shouldBe Some(4)
  }
}
