package com.tibiabot.respawn

import com.tibiabot.domain.{Respawn, RespawnClaim}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.ZonedDateTime

/** When each person waiting on a spawn would actually get it.
 *
 *  A projection rather than a fact, and the arithmetic lives here so that
 *  everything showing a queue shows the same answer. See
 *  [[RespawnBoardEntry.projectedQueueStarts]].
 */
class QueueProjectionSpec extends AnyWordSpec with Matchers {

  private val now = ZonedDateTime.parse("2026-08-12T20:00:00Z")

  private val respawn =
    Respawn(1, "415", "Cult Orcs", "Dragon", "Edron", "", "", "", Respawn.SourceSeed, "seed")

  private def claim(id: Long, minutes: Int, position: Int = 0,
                    endsAt: Option[ZonedDateTime] = None) =
    RespawnClaim(id, 1, s"u$id", s"user$id", "", RespawnClaim.StatusQueued, position, now,
      None, endsAt, minutes, warned = false, RespawnClaim.KindAdHoc, None, None, None, None)

  private def entry(active: Option[RespawnClaim], queue: List[RespawnClaim]) =
    RespawnBoardEntry(respawn, active, queue, Nil, None)

  "projected queue starts" should {

    "give nothing back when nobody is waiting" in {
      entry(Some(claim(1, 60, endsAt = Some(now.plusMinutes(30)))), Nil)
        .projectedQueueStarts(now) shouldBe empty
    }

    "start the first in line when the hunt on now ends" in {
      val holder = claim(1, 120, endsAt = Some(now.plusMinutes(45)))
      val waiting = claim(2, 60, position = 1)
      val out = entry(Some(holder), List(waiting)).projectedQueueStarts(now)
      out.map(_._2) shouldBe List(now.plusMinutes(45))
    }

    "stack everyone behind the person ahead of them, at their own length" in {
      val holder = claim(1, 120, endsAt = Some(now.plusMinutes(30)))
      val first = claim(2, 120, position = 1)
      val second = claim(3, 60, position = 2)
      val third = claim(4, 30, position = 3)
      val out = entry(Some(holder), List(first, second, third)).projectedQueueStarts(now)
      out.map(_._2) shouldBe List(
        now.plusMinutes(30),        // when the live hunt ends
        now.plusMinutes(150),       // + the first waiter's two hours
        now.plusMinutes(210)        // + the second's hour
      )
      // Each start is paired with the claim it belongs to, in queue order.
      out.map(_._1.id) shouldBe List(2, 3, 4)
    }

    "count from now when there is no live hunt to count from" in {
      // Should not happen, but does if a claim ends between two reads — and the
      // next person's turn is not in the past.
      val out = entry(None, List(claim(2, 60, position = 1))).projectedQueueStarts(now)
      out.map(_._2) shouldBe List(now)
    }

    "count from now when the hunt on it has already overrun" in {
      val overdue = claim(1, 60, endsAt = Some(now.minusMinutes(5)))
      val out = entry(Some(overdue), List(claim(2, 60, position = 1))).projectedQueueStarts(now)
      out.map(_._2) shouldBe List(now)
    }

    "count from now when the live hunt has no end recorded" in {
      val open = claim(1, 60, endsAt = None)
      val out = entry(Some(open), List(claim(2, 60, position = 1))).projectedQueueStarts(now)
      out.map(_._2) shouldBe List(now)
    }
  }
}
