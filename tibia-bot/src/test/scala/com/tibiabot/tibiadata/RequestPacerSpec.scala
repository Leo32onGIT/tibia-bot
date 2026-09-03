package com.tibiabot.tibiadata

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/** The gap between requests leaving for a burst-sensitive upstream.
 *
 *  Worth testing on a hand-driven clock rather than by inspection: the failure
 *  this class exists to prevent costs the whole IP a block, and it only shows up
 *  under exactly the conditions a live test would not reproduce — a cold lane, a
 *  long idle spell, or an upstream that got faster. */
class RequestPacerSpec extends AnyFunSuite with Matchers {

  /** A clock the test moves by hand, so spacing is proved rather than waited for. */
  private final class Clock {
    private var t: Long = 0L
    def read(): Long = t
    def advance(by: FiniteDuration): Unit = t += by.toNanos
  }

  /** `random` fixed at 0.5 puts the jitter factor at exactly 1.0, so tests that
   *  are about the schedule are not also about the spread. */
  private def pacer(
      clock: Clock,
      spacing: FiniteDuration = 100.millis,
      burst: Int = 1,
      jitter: Double = 0.0,
      random: () => Double = () => 0.5
  ) = new RequestPacer(spacing, burst, jitter, () => clock.read(), random)

  private val generous = 1.hour

  test("the first request out of a cold lane waits for nothing") {
    val pace = pacer(new Clock())
    pace.tryReserve(generous) shouldBe Some(Duration.Zero)
  }

  test("the request after it waits a full gap") {
    val pace = pacer(new Clock())
    pace.tryReserve(generous)
    pace.tryReserve(generous) shouldBe Some(100.millis)
  }

  test("waiting time accumulates, so a queue spaces itself out") {
    val pace = pacer(new Clock())
    val waits = (1 to 4).map(_ => pace.tryReserve(generous).get)
    waits shouldBe Seq(Duration.Zero, 100.millis, 200.millis, 300.millis)
  }

  test("a burst of N lets exactly N out back to back") {
    val pace = pacer(new Clock(), burst = 3)
    val waits = (1 to 4).map(_ => pace.tryReserve(generous).get)
    waits shouldBe Seq(Duration.Zero, Duration.Zero, Duration.Zero, 100.millis)
  }

  test("an idle hour banks a burst and not an hour's worth of requests") {
    val clock = new Clock()
    val pace = pacer(clock, burst = 2)
    clock.advance(1.hour)

    val waits = (1 to 3).map(_ => pace.tryReserve(generous).get)
    waits shouldBe Seq(Duration.Zero, Duration.Zero, 100.millis)
  }

  test("a queue stretching past the limit is refused rather than joined") {
    val pace = pacer(new Clock())
    pace.tryReserve(250.millis) shouldBe Some(Duration.Zero)
    pace.tryReserve(250.millis) shouldBe Some(100.millis)
    pace.tryReserve(250.millis) shouldBe Some(200.millis)
    pace.tryReserve(250.millis) shouldBe None
  }

  test("a refusal costs the schedule nothing, so the next caller is offered the same slot") {
    val pace = pacer(new Clock())
    (1 to 3).foreach(_ => pace.tryReserve(generous))

    pace.tryReserve(250.millis) shouldBe None
    // Had the refusal advanced the schedule, this would be 400ms.
    pace.tryReserve(generous) shouldBe Some(300.millis)
  }

  test("time passing drains the queue") {
    val clock = new Clock()
    val pace = pacer(clock)
    (1 to 4).foreach(_ => pace.tryReserve(generous))

    clock.advance(300.millis)
    pace.tryReserve(generous) shouldBe Some(100.millis)
  }

  test("jitter spreads the gaps without moving the mean rate") {
    val clock = new Clock()
    // Alternating extremes: gaps of 60ms and 140ms against a 100ms mean.
    var flip = false
    val pace = pacer(clock, jitter = 0.4, random = () => { flip = !flip; if (flip) 0.0 else 1.0 })

    val waits = (1 to 5).map(_ => pace.tryReserve(generous).get)
    waits shouldBe Seq(Duration.Zero, 60.millis, 200.millis, 260.millis, 400.millis)
    // Four gaps, two short and two long, landing exactly on 4 x 100ms.
    waits.last shouldBe 100.millis * 4
  }

  test("a jittered gap is never zero, however small the spacing") {
    val pace = pacer(new Clock(), spacing = 1.nano, jitter = 0.99, random = () => 0.0)
    pace.tryReserve(generous)
    pace.tryReserve(generous).get.toNanos should be > 0L
  }

  test("nonsense settings are refused at construction") {
    an[IllegalArgumentException] should be thrownBy pacer(new Clock(), spacing = Duration.Zero)
    an[IllegalArgumentException] should be thrownBy pacer(new Clock(), burst = 0)
    an[IllegalArgumentException] should be thrownBy pacer(new Clock(), jitter = 1.0)
    an[IllegalArgumentException] should be thrownBy pacer(new Clock(), jitter = -0.1)
  }
}
