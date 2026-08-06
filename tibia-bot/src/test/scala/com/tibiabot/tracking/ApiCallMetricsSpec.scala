package com.tibiabot.tracking

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ApiCallMetricsSpec extends AnyWordSpec with Matchers {

  /** Injected clock, so the hour-long window can be exercised without waiting
   *  for one. Starts well clear of epoch, matching the real `currentTimeMillis`
   *  values the ring's modular slot arithmetic sees in production. */
  private class TestClock(var millis: Long = 1_700_000_000_000L) {
    def now: () => Long = () => millis
    def advance(seconds: Long): Unit = millis += seconds * 1000
  }

  "ApiCallMetrics" should {

    "count a call once overall and once per supplied dimension" in {
      val clock = new TestClock
      val metrics = new ApiCallMetrics(clock.now)
      metrics.record("endpoint" -> "/v4/character", "status" -> "200")
      metrics.record("endpoint" -> "/v4/character", "status" -> "404")
      metrics.record("endpoint" -> "/v4/world", "status" -> "200")

      val snap = metrics.snapshot()
      snap.total shouldBe 3
      snap.dimensions("endpoint")("/v4/character").total shouldBe 2
      snap.dimensions("endpoint")("/v4/world").total shouldBe 1
      snap.dimensions("status")("200").total shouldBe 2
      snap.dimensions("status")("404").total shouldBe 1
    }

    "leave values within one dimension summing to the overall total" in {
      val clock = new TestClock
      val metrics = new ApiCallMetrics(clock.now)
      (1 to 10).foreach(i => metrics.record("status" -> (if (i % 3 == 0) "503" else "200")))

      val snap = metrics.snapshot()
      snap.dimensions("status").values.map(_.total).sum shouldBe snap.total
    }

    "average per-second over a full minute rather than the current second" in {
      val clock = new TestClock
      val metrics = new ApiCallMetrics(clock.now)
      (1 to 120).foreach(_ => metrics.record())

      // 120 calls all inside one second is 2/s once spread over the 60s window,
      // not 120/s — the headline figure is a rate, not an instantaneous count.
      metrics.snapshot().perSecond shouldBe 2.0 +- 0.0001
    }

    "keep a call in the per-second window for a minute, then drop it" in {
      val clock = new TestClock
      val metrics = new ApiCallMetrics(clock.now)
      (1 to 60).foreach(_ => metrics.record())

      metrics.snapshot().perSecond shouldBe 1.0 +- 0.0001
      clock.advance(59)
      metrics.snapshot().perSecond shouldBe 1.0 +- 0.0001
      clock.advance(1)
      metrics.snapshot().perSecond shouldBe 0.0 +- 0.0001
    }

    "report perHour as an observed trailing-hour count, not an extrapolation" in {
      val clock = new TestClock
      val metrics = new ApiCallMetrics(clock.now)
      // One call a second for ten minutes: 600 calls, and 1/s.
      (1 to 600).foreach { _ =>
        metrics.record()
        clock.advance(1)
      }

      val snap = metrics.snapshot()
      snap.perHour shouldBe 600L        // what actually happened
      snap.perHour should not be 3600L  // not perSecond * 3600
      snap.total shouldBe 600
    }

    "expose how much history backs perHour, capped at the window" in {
      val clock = new TestClock
      val metrics = new ApiCallMetrics(clock.now)
      metrics.snapshot().observedSeconds shouldBe 0

      clock.advance(600)
      metrics.snapshot().observedSeconds shouldBe 600

      clock.advance(7200)
      metrics.snapshot().observedSeconds shouldBe 3600
    }

    "age calls out of the hour window rather than wrapping them back in" in {
      val clock = new TestClock
      val metrics = new ApiCallMetrics(clock.now)
      (1 to 50).foreach(_ => metrics.record())

      metrics.snapshot().perHour shouldBe 50L
      clock.advance(3600)
      // The ring is exactly an hour long, so without zeroing aged-out buckets
      // these would reappear at the same slots an hour later.
      metrics.snapshot().perHour shouldBe 0L
      // The cumulative total is not a window and must survive regardless.
      metrics.snapshot().total shouldBe 50
    }

    "survive a gap longer than the whole ring" in {
      val clock = new TestClock
      val metrics = new ApiCallMetrics(clock.now)
      (1 to 20).foreach(_ => metrics.record())

      clock.advance(100000)
      val snap = metrics.snapshot()
      snap.perHour shouldBe 0L
      snap.perSecond shouldBe 0.0 +- 0.0001
      snap.total shouldBe 20
      snap.history.forall(_ == 0.0) shouldBe true
    }

    "return history oldest-first, in per-second units" in {
      val clock = new TestClock
      val metrics = new ApiCallMetrics(clock.now)
      // 20 calls in one 10-second bucket, then a quiet bucket, so the burst has
      // to land in the second-newest slot if the ordering is right.
      (1 to 20).foreach(_ => metrics.record())
      clock.advance(10)

      val history = metrics.snapshot().history
      history.size shouldBe ApiCallMetrics.HistoryPoints
      history.last shouldBe 0.0 +- 0.0001          // newest bucket: quiet
      history(history.size - 2) shouldBe 2.0 +- 0.0001 // 20 calls / 10s
      history.take(history.size - 2).forall(_ == 0.0) shouldBe true
    }

    // These counters run for months, so an unexpectedly high-cardinality tag
    // must not be able to grow the map without bound.
    "fold dimension values past the cap into a single overflow bucket" in {
      val clock = new TestClock
      val metrics = new ApiCallMetrics(clock.now)
      (1 to ApiCallMetrics.MaxValuesPerDimension + 25).foreach(i => metrics.record("endpoint" -> s"/v4/$i"))

      val endpoints = metrics.snapshot().dimensions("endpoint")
      endpoints.size shouldBe ApiCallMetrics.MaxValuesPerDimension + 1
      endpoints(ApiCallMetrics.OverflowValue).total shouldBe 25
      // Overflowing must not lose calls: the dimension still accounts for the total.
      endpoints.values.map(_.total).sum shouldBe metrics.snapshot().total
    }

    "keep counting a known value after the cap is reached" in {
      val clock = new TestClock
      val metrics = new ApiCallMetrics(clock.now)
      metrics.record("endpoint" -> "/v4/character")
      (1 to ApiCallMetrics.MaxValuesPerDimension + 10).foreach(i => metrics.record("endpoint" -> s"/v4/$i"))
      metrics.record("endpoint" -> "/v4/character")

      // An established value keeps its own counter — the cap only ever affects
      // values first seen after it is hit.
      metrics.snapshot().dimensions("endpoint")("/v4/character").total shouldBe 2
    }

    "start empty" in {
      val snap = new ApiCallMetrics(new TestClock().now).snapshot()
      snap.total shouldBe 0
      snap.perSecond shouldBe 0.0
      snap.perHour shouldBe 0L
      snap.dimensions shouldBe empty
      snap.history.size shouldBe ApiCallMetrics.HistoryPoints
    }
  }
}
