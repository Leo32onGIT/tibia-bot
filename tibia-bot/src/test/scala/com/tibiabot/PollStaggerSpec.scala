package com.tibiabot

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/** How a world's first poll is placed in time. Every stream is built in the
 *  same startup loop, so without an offset they all poll on the same second
 *  forever and every world's character fetches leave as one burst. */
class PollStaggerSpec extends AnyFunSuite with Matchers {

  test("the offset never lands before the process has had a moment to settle") {
    TibiaBot.firstPollDelay(_ => 0) shouldBe TibiaBot.SettleDelay
  }

  test("the offset spreads across one whole poll interval and no further") {
    val widest = TibiaBot.firstPollDelay(bound => bound - 1)
    widest should be < (TibiaBot.SettleDelay + TibiaBot.PollInterval)
    widest should be >= TibiaBot.SettleDelay
  }

  test("the jitter is asked for exactly one interval's worth of seconds") {
    var askedFor = -1
    TibiaBot.firstPollDelay { bound => askedFor = bound; 0 }
    askedFor shouldBe TibiaBot.PollInterval.toSeconds.toInt
  }

  test("worlds starting together land on different seconds") {
    // The property that matters: given a real random source, a fleet of worlds
    // built in one loop does not end up polling in unison.
    val offsets = (1 to 200).map(_ => TibiaBot.firstPollDelay(scala.util.Random.nextInt).toSeconds).toSet
    offsets.size should be > 30
  }

  test("every offset is a whole number of seconds inside the window") {
    (0 until TibiaBot.PollInterval.toSeconds.toInt).foreach { j =>
      val d = TibiaBot.firstPollDelay(_ => j)
      d.toSeconds shouldBe (TibiaBot.SettleDelay.toSeconds + j)
      d should be < (TibiaBot.SettleDelay + TibiaBot.PollInterval)
    }
  }
}
