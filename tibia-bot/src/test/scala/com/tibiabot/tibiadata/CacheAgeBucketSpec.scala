package com.tibiabot.tibiadata

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Bucketing for the dashboard's cache-age histogram. Pure, so it is pinned
 *  here rather than through HTTP — the labels are what the panel renders, and
 *  the boundaries are what decide whether a 300s TTL is legible in it. */
class CacheAgeBucketSpec extends AnyFunSuite with Matchers {

  test("an age lands in its own 60-second bucket, boundaries included") {
    TibiaDataClient.cacheAgeBucket(0) shouldBe "0-59s"
    TibiaDataClient.cacheAgeBucket(59) shouldBe "0-59s"
    TibiaDataClient.cacheAgeBucket(60) shouldBe "60-119s"
    TibiaDataClient.cacheAgeBucket(299) shouldBe "240-299s"
  }

  test("the buckets run past the observed 300s TTL, so a longer-cached entry is visible rather than hidden at the boundary") {
    TibiaDataClient.cacheAgeBucket(300) shouldBe "300-359s"
    TibiaDataClient.cacheAgeBucket(359) shouldBe "300-359s"
  }

  test("anything past the last bucket collapses into the overflow row") {
    TibiaDataClient.cacheAgeBucket(360) shouldBe "360s+"
    TibiaDataClient.cacheAgeBucket(100000) shouldBe "360s+"
  }

  test("a nonsensical negative age reads as the youngest bucket, not a negative label") {
    TibiaDataClient.cacheAgeBucket(-5) shouldBe "0-59s"
  }

  test("every bucket label is one the dashboard knows how to order") {
    // Mirrors CACHE_AGE_ORDER in dashboard.html — a bucket the frontend has
    // never heard of sorts to the end of the histogram instead of into place.
    val known = Set("0-59s", "60-119s", "120-179s", "180-239s", "240-299s", "300-359s", "360s+")
    val produced = (0L to 400L).map(TibiaDataClient.cacheAgeBucket).toSet
    produced diff known shouldBe empty
  }
}
