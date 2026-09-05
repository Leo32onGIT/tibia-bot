package com.tibiabot.respawn

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.ZonedDateTime

/** A live claim being retimed through Config stops where the next booking starts.
 *
 *  `beginClaim` has always cut a *new* claim short against a booking. Config did
 *  not, so a holder could extend straight over somebody's booked window — and
 *  when that window came due the sweep found a real collision, cancelled the
 *  booking, put its owner in the queue and DM'd them that their slot was taken.
 *  That is the one thing `enqueueClaim` is still reachable from, so this rule is
 *  what keeps the queue empty.
 */
class RetimeCapSpec extends AnyWordSpec with Matchers {

  private val start = ZonedDateTime.parse("2026-09-05T13:00:00Z")
  private def at(hhmm: String) = ZonedDateTime.parse(s"2026-09-05T$hhmm:00Z")

  "retiming a claim" should {

    "grant the whole ask when nothing is booked after it" in {
      RespawnService.retimedTo(start, asked = 240, reservation = None) shouldBe (240, None)
    }

    "grant the whole ask when the booking starts after the hunt would end" in {
      RespawnService.retimedTo(start, asked = 60, reservation = Some(at("15:00"))) shouldBe (60, None)
    }

    // The bug: four hours asked for over a booking an hour away used to be
    // granted in full, and the booking's owner was bumped when it came due.
    "cut the ask back to where the booking starts, and say which booking did it" in {
      val booking = at("14:00")
      RespawnService.retimedTo(start, asked = 240, reservation = Some(booking)) shouldBe (60, Some(booking))
    }

    /** Ending exactly where a booking begins is not being cut short — it is the
     *  back-to-back handoff the whole system is built around, and reporting it as
     *  a truncation would explain something that did not happen. */
    "not count a hunt that ends exactly on the booking as shortened" in {
      RespawnService.retimedTo(start, asked = 60, reservation = Some(at("14:00"))) shouldBe (60, None)
    }

    "leave nothing when the booking has already come round" in {
      RespawnService.retimedTo(start, asked = 240, reservation = Some(start)) shouldBe (0, Some(start))
    }

    /** Shortening is never the cap's business: `endsAtFor` only ever pulls an end
     *  earlier, so a smaller ask passes through whatever is booked later. */
    "pass a shortening through untouched" in {
      RespawnService.retimedTo(start, asked = 30, reservation = Some(at("14:00"))) shouldBe (30, None)
    }

    "round a part-minute booking down rather than handing back time that isn't there" in {
      RespawnService.retimedTo(start, asked = 240,
        reservation = Some(ZonedDateTime.parse("2026-09-05T14:00:45Z"))) shouldBe (60, Some(ZonedDateTime.parse("2026-09-05T14:00:45Z")))
    }
  }

  /** The same rule seen from the function that has always had it, so the two
   *  doors onto a spawn cannot drift apart. */
  "the truncation both doors share" should {

    "stop a claim at the booking" in {
      RespawnService.endsAtFor(start, 240, Some(at("14:00"))) shouldBe at("14:00")
    }

    "leave a claim alone when it finishes first" in {
      RespawnService.endsAtFor(start, 30, Some(at("14:00"))) shouldBe at("13:30")
    }

    "leave a claim alone when nothing is booked" in {
      RespawnService.endsAtFor(start, 240, None) shouldBe at("17:00")
    }
  }
}
