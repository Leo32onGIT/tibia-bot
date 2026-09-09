package com.tibiabot.highscores

import com.tibiabot.tibiadata.Highscores
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/** The pacing arithmetic, which is the whole of what keeps a snapshot's work
 *  from arriving at tibia.com as a burst. */
class HighscorePaceSpec extends AnyFunSuite with Matchers {

  private val window = 45.minutes
  private val minGap = 150.millis

  test("a snapshot's work is worlds times lists times pages") {
    HighscorePace.requestsFor(68, HighscoreLists.all.size, Highscores.MaxPages) shouldBe 16320
  }

  test("the fleet's real load spreads to a walk rather than a burst") {
    val requests = HighscorePace.requestsFor(68, HighscoreLists.all.size, Highscores.MaxPages)
    val gap = HighscorePace.perRequestGap(requests, window, workers = 4, minGap)

    // Four lanes, each waiting this long between its own requests.
    gap.toMillis shouldBe (window.toMillis * 4 / requests)
    // Which is an aggregate rate in single digits per second, not hundreds.
    val perSecond = 4000.0 / gap.toMillis
    perSecond should be < 10.0
  }

  test("the whole sweep fits inside the window it was sized for") {
    val requests = HighscorePace.requestsFor(93, HighscoreLists.all.size, Highscores.MaxPages)
    val gap = HighscorePace.perRequestGap(requests, window, workers = 4, minGap)
    HighscorePace.estimatedDuration(requests, gap, workers = 4).toMillis should be <= (window.toMillis + gap.toMillis)
  }

  test("a small world count still walks, held by the floor") {
    // One world is 240 requests in 45 minutes, which the arithmetic alone would
    // spread to 45 seconds apart. The floor is what stops that being silly in
    // the other direction — but it must not turn it into a burst either.
    val gap = HighscorePace.perRequestGap(
      HighscorePace.requestsFor(1, HighscoreLists.all.size, Highscores.MaxPages), window, workers = 4, minGap)
    gap should be > minGap
  }

  test("the floor holds when the arithmetic would go below it") {
    HighscorePace.perRequestGap(requests = 1000000, window, workers = 4, minGap) shouldBe minGap
  }

  test("nothing to do yields the floor rather than an infinity") {
    HighscorePace.perRequestGap(requests = 0, window, workers = 4, minGap) shouldBe minGap
    HighscorePace.perRequestGap(requests = -1, window, workers = 4, minGap) shouldBe minGap
  }

  test("a nonsense worker count is treated as one lane, not a division by zero") {
    HighscorePace.perRequestGap(requests = 100, window, workers = 0, minGap) shouldBe
      HighscorePace.perRequestGap(requests = 100, window, workers = 1, minGap)
    HighscorePace.estimatedDuration(requests = 100, 1.second, workers = 0).toSeconds shouldBe 100L
  }
}
