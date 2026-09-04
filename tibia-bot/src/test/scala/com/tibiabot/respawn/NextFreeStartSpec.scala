package com.tibiabot.respawn

import com.tibiabot.domain.RespawnClaim
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.ZonedDateTime

/** Where the button on a held spawn books.
 *
 *  Pressing it is a request for the spawn as soon as it is free, so the answer
 *  has to be a window nobody else has — not merely the moment the holder stops,
 *  which is where the second person to press would land on top of the first.
 */
class NextFreeStartSpec extends AnyWordSpec with Matchers {

  private val now = ZonedDateTime.parse("2026-09-05T18:00:00Z")

  private def slot(from: ZonedDateTime, minutes: Int) =
    RespawnClaim(1, 1, "u1", "Someone", "", RespawnClaim.StatusReserved, 0, now, Some(from),
      Some(from.plusMinutes(minutes.toLong)), minutes, warned = false, RespawnClaim.KindScheduled,
      None, None, None, None)

  private def start(heldUntil: Option[ZonedDateTime], booked: List[RespawnClaim] = Nil,
                    offeredUntil: Option[ZonedDateTime] = None, minutes: Int = 60) =
    RespawnService.nextFreeStart(heldUntil, offeredUntil, booked, minutes, now)

  "the next free window" should {

    "start when the hunt in progress ends" in {
      start(Some(now.plusMinutes(40))) shouldBe now.plusMinutes(40)
    }

    "wait for a handover to finish when one is under way" in {
      // A spawn mid-handover is still spoken for: its next holder is being asked,
      // and booking from the outgoing claim's end would land inside their hunt.
      start(Some(now.plusMinutes(10)), offeredUntil = Some(now.plusMinutes(45))) shouldBe now.plusMinutes(45)
    }

    "step over a booking already sitting in that window" in {
      // The second person to press the button lands behind the first rather than
      // asking them to give up the slot they just took.
      val theirs = slot(now.plusMinutes(40), 60)
      start(Some(now.plusMinutes(40)), List(theirs)) shouldBe now.plusMinutes(100)
    }

    "step over a run of them in one pass" in {
      val first = slot(now.plusMinutes(40), 60)
      val second = slot(now.plusMinutes(100), 30)
      start(Some(now.plusMinutes(40)), List(first, second)) shouldBe now.plusMinutes(130)
    }

    "ignore a booking that ends before the spawn is free" in {
      // Stale rather than impossible: a slot whose window has gone by but which
      // no sweep has closed yet.
      start(Some(now.plusMinutes(40)), List(slot(now.minusMinutes(30), 20))) shouldBe now.plusMinutes(40)
    }

    "ignore a booking that starts after the window being asked for" in {
      // Somebody else's evening, hours away. Booking before it is not a clash,
      // and pushing past it would give up a window that is genuinely free.
      start(Some(now.plusMinutes(40)), List(slot(now.plusHours(5), 60))) shouldBe now.plusMinutes(40)
    }

    "let a longer window run into a booking it would overlap" in {
      // The same booking, now inside the three hours being asked for rather than
      // beyond the one.
      start(Some(now.plusMinutes(40)), List(slot(now.plusHours(2), 60)), minutes = 180) shouldBe now.plusHours(3)
    }

    "never land in the past when the hunt in front has already run out" in {
      // A claim whose end has gone by but that no sweep has closed. Booking from
      // its end would be refused outright for starting in the past.
      start(Some(now.minusMinutes(5))) shouldBe now.plusMinutes(1)
    }
  }
}
