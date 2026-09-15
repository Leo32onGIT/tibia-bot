package com.tibiabot.highscores

import com.tibiabot.tibiadata.Highscores
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/** The pacing arithmetic, which is the whole of what keeps a snapshot's work
 *  from arriving at tibia.com as a burst — and, since September 2026, the whole
 *  of what keeps it finishing before the next snapshot lands. */
class HighscorePaceSpec extends AnyFunSuite with Matchers {

  private val window = 45.minutes
  private val minGap = 150.millis
  private val latency = HighscorePace.SeedLatency

  /** The fleet's own numbers: 68 worlds, every list, 20 pages each. */
  private val fleet = HighscorePace.requestsFor(68, HighscoreLists.all.size, Highscores.MaxPages)

  test("a snapshot's work is worlds times lists times pages") {
    fleet shouldBe 16320
  }

  test("the fleet's real load spreads to a walk rather than a burst") {
    val gap = HighscorePace.perRequestGap(fleet, window, workers = 4, minGap, latency)

    // Four lanes, each spending the sleep and then the request on every page.
    gap.toMillis shouldBe (window.toMillis * 4 / fleet) - latency.toMillis
    // Which is an aggregate rate in single digits per second, not hundreds.
    val perSecond = 4000.0 / (gap + latency).toMillis
    perSecond should be < 10.0
  }

  test("the whole sweep fits inside the window it was sized for") {
    val gap = HighscorePace.perRequestGap(fleet, window, workers = 4, minGap, latency)
    // Above the floor, so the gap is whatever makes the sum come out to the
    // window rather than whatever the floor allows.
    gap should be > minGap
    HighscorePace.estimatedDuration(fleet, gap, workers = 4, latency).toMillis should
      be <= (window.toMillis + gap.toMillis)
  }

  test("past the world count the floor can carry, the sweep runs long rather than faster") {
    // 93 worlds is every world in the game. Four lanes spending 150ms of floor
    // and 400ms of request on each of 22,320 pages is 51 minutes, and no gap
    // this side of zero makes that 45. The floor wins on purpose — going under
    // it is the burst the floor exists to stop — so the honest outcome is a
    // sweep that runs past its window.
    //
    // What must never happen is the estimate quietly claiming otherwise, which
    // is exactly what hid the September 2026 drift for as long as it did. The
    // arithmetic says so here, and HighscoreService warns when it does.
    val requests = HighscorePace.requestsFor(93, HighscoreLists.all.size, Highscores.MaxPages)
    val gap = HighscorePace.perRequestGap(requests, window, workers = 4, minGap, latency)

    gap shouldBe minGap
    HighscorePace.estimatedDuration(requests, gap, workers = 4, latency) should be > window
  }

  test("the request is part of the budget, not free on top of it") {
    // September 2026: a 68-world sweep took 72 minutes inside a 45-minute
    // window, drifted a quarter of an hour later every time, and once the delay
    // passed an hour began skipping whole snapshots. The sleeps were never the
    // problem — they accounted for 45 of those 72 minutes. The request did.
    val drifted = HighscorePace.observedLatency(72.minutes, attempted = 15984, gap = 661.millis, workers = 4)
    drifted.map(_.toMillis) shouldBe Some(420L)

    val gap = HighscorePace.perRequestGap(fleet, window, workers = 4, minGap, drifted.get)
    HighscorePace.estimatedDuration(fleet, gap, workers = 4, drifted.get).toMinutes should
      be <= window.toMinutes
  }

  test("a small world count still walks, held by the floor") {
    // One world is 240 requests in 45 minutes, which the arithmetic alone would
    // spread to 45 seconds apart. The floor is what stops that being silly in
    // the other direction — but it must not turn it into a burst either.
    val gap = HighscorePace.perRequestGap(
      HighscorePace.requestsFor(1, HighscoreLists.all.size, Highscores.MaxPages),
      window, workers = 4, minGap, latency)
    gap should be > minGap
  }

  test("the floor holds when the arithmetic would go below it") {
    HighscorePace.perRequestGap(requests = 1000000, window, workers = 4, minGap, latency) shouldBe minGap
  }

  test("a slow upstream is held at the floor rather than sent negative") {
    // The whole budget for a page and more, spent on the request. The sleep
    // cannot pay that back, and the floor is what stops it trying: the real
    // rate is lanes / (gap + latency), so this sweep simply runs slower rather
    // than hammering something that is already struggling.
    HighscorePace.perRequestGap(fleet, window, workers = 4, minGap, latency = 2.seconds) shouldBe minGap
  }

  test("nothing to do yields the floor rather than an infinity") {
    HighscorePace.perRequestGap(requests = 0, window, workers = 4, minGap, latency) shouldBe minGap
    HighscorePace.perRequestGap(requests = -1, window, workers = 4, minGap, latency) shouldBe minGap
  }

  test("a nonsense worker count is treated as one lane, not a division by zero") {
    HighscorePace.perRequestGap(requests = 100, window, workers = 0, minGap, latency) shouldBe
      HighscorePace.perRequestGap(requests = 100, window, workers = 1, minGap, latency)
    HighscorePace.estimatedDuration(requests = 100, 1.second, workers = 0, Duration.Zero).toSeconds shouldBe 100L
    HighscorePace.observedLatency(100.seconds, attempted = 100, gap = 1.second, workers = 0) shouldBe
      HighscorePace.observedLatency(100.seconds, attempted = 100, gap = 1.second, workers = 1)
  }

  test("the estimate a sweep is measured against is the one it can meet") {
    // What the pacing promises and what a lane spends are the same arithmetic
    // read in both directions, so a sweep that runs to its estimate hands back
    // the latency it was paced with and the next one is sized the same way.
    val gap = HighscorePace.perRequestGap(fleet, window, workers = 4, minGap, latency)
    val estimate = HighscorePace.estimatedDuration(fleet, gap, workers = 4, latency)

    val measured = HighscorePace.observedLatency(estimate, attempted = fleet, gap, workers = 4)
    measured.get.toMillis shouldBe latency.toMillis
  }

  test("pages never asked for are not counted as pages that went quickly") {
    // A list shorter than 20 pages stops at its end, so those pages cost
    // neither a sleep nor a request. Dividing the sweep by them instead would
    // read as an upstream that got faster, and pace the next sweep too hard.
    val asked = HighscorePace.observedLatency(72.minutes, attempted = 15984, gap = 661.millis, workers = 4)
    val everything = HighscorePace.observedLatency(72.minutes, attempted = 16320, gap = 661.millis, workers = 4)
    asked.get should be > everything.get
  }

  test("a sweep with nothing to divide by measures nothing") {
    HighscorePace.observedLatency(10.minutes, attempted = 0, gap = 100.millis, workers = 4) shouldBe None
    HighscorePace.observedLatency(10.minutes, attempted = -1, gap = 100.millis, workers = 4) shouldBe None
  }

  test("a sweep that beat its own sleeps measures zero, never less") {
    HighscorePace.observedLatency(1.second, attempted = 4000, gap = 10.seconds, workers = 4) shouldBe
      Some(Duration.Zero)
  }
}
