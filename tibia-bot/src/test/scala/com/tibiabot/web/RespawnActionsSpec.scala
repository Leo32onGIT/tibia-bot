package com.tibiabot.web

import com.tibiabot.domain.{Respawn, RespawnClaim, Stamina}
import com.tibiabot.respawn.{ClaimOutcome, ReleaseOutcome}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.ZonedDateTime

class RespawnActionsSpec extends AnyWordSpec with Matchers {

  private val now = ZonedDateTime.parse("2026-08-07T12:00:00Z")
  private val spawn = Respawn(1, "415", "Cult Orcs", "Dragon", "Edron", "", "", "", Respawn.SourceSeed, "seed")

  private def claim(minutes: Int = 120, who: String = "Nubbz") =
    RespawnClaim(1, 1, "u1", who, "", RespawnClaim.StatusActive, 0, now, Some(now),
      Some(now.plusMinutes(minutes.toLong)), minutes, warned = false, RespawnClaim.KindAdHoc,
      None, None, None, None)

  "describe(ClaimOutcome)" should {

    "report a successful claim with what was granted" in {
      val result = RespawnActions.describe(ClaimOutcome.Claimed(spawn, claim(120)))
      result.ok shouldBe true
      result.message should include("415 — Cult Orcs")
      result.message should include("2h")
    }

    // Being queued is a good answer to a well-formed request, not a refusal —
    // the page uses `ok` only to pick a tone.
    "treat being queued as a success, and say the position" in {
      val result = RespawnActions.describe(ClaimOutcome.Queued(spawn, claim(), 3))
      result.ok shouldBe true
      result.message should include("number 3")
    }

    "explain a shortened claim and that the shortfall is not charged" in {
      val result = RespawnActions.describe(
        ClaimOutcome.Shortened(spawn, claim(60), requested = 180, reservedFrom = Some(now.plusHours(1))))
      result.ok shouldBe true
      result.message should include("1h")
      result.message should include("3h")
      result.message.toLowerCase should include("shorter")
    }

    "say plainly when somebody else got there first" in {
      val result = RespawnActions.describe(ClaimOutcome.JustTaken(spawn))
      result.ok shouldBe false
      result.message.toLowerCase should include("moment before")
    }

    "explain a refusal caused by a booking rather than by the caller" in {
      val result = RespawnActions.describe(ClaimOutcome.Reserved(spawn, now.plusMinutes(20)))
      result.ok shouldBe false
      result.message.toLowerCase should include("booked")
    }

    "say what is left when stamina is the blocker, and when it returns" in {
      val stamina = Stamina("u1", usedMinutes = 200, budgetMinutes = 240, resetAt = now.plusHours(6))
      val result = RespawnActions.describe(ClaimOutcome.NoStamina(spawn, 120, stamina, now.plusHours(6)))
      result.ok shouldBe false
      result.message should include("2h")   // asked for
      result.message should include("40m")  // left
      result.message.toLowerCase should include("resets")
    }

    "quote back an unknown code rather than saying something generic" in {
      RespawnActions.describe(ClaimOutcome.UnknownSpawn("9zz")).message should include("9zz")
    }

    "name the limit when a duration is too long" in {
      val result = RespawnActions.describe(ClaimOutcome.BadDuration(requested = 300, max = 240))
      result.ok shouldBe false
      result.message should include("4h")
    }

    "cover every claim outcome without falling through" in {
      val stamina = Stamina("u1", 0, 240, now)
      val all: List[ClaimOutcome] = List(
        ClaimOutcome.Claimed(spawn, claim()), ClaimOutcome.Queued(spawn, claim(), 1),
        ClaimOutcome.Shortened(spawn, claim(), 180, None), ClaimOutcome.JustTaken(spawn),
        ClaimOutcome.Reserved(spawn, now), ClaimOutcome.AlreadyHolding(spawn, claim()),
        ClaimOutcome.QueueFull(spawn, 5), ClaimOutcome.NoStamina(spawn, 60, stamina, now),
        ClaimOutcome.UnknownSpawn("x"), ClaimOutcome.BadDuration(300, 240), ClaimOutcome.NotConfigured)
      all.foreach { outcome =>
        val result = RespawnActions.describe(outcome)
        withClue(s"$outcome: ")(result.message.trim should not be empty)
      }
    }
  }

  "describe(ReleaseOutcome)" should {

    "confirm a release, the refund, and who it went to" in {
      val next = claim(who = "Kharsek")
      val result = RespawnActions.describe(ReleaseOutcome.Released(spawn, refundedMinutes = 45, offered = Some(next)))
      result.ok shouldBe true
      result.message should include("45m")
      result.message should include("Kharsek")
    }

    // Releasing a claim that ran its full length refunds nothing, and saying
    // "0m returned" would read as a bug.
    "say nothing about a refund when there was none" in {
      val result = RespawnActions.describe(ReleaseOutcome.Released(spawn, refundedMinutes = 0, offered = None))
      result.ok shouldBe true
      result.message.toLowerCase should not include "stamina"
    }

    "distinguish leaving a queue from giving up a hunt" in {
      RespawnActions.describe(ReleaseOutcome.LeftQueue(spawn)).message.toLowerCase should include("queue")
    }

    "cover every release outcome without falling through" in {
      val all: List[ReleaseOutcome] = List(
        ReleaseOutcome.Released(spawn, 0, None), ReleaseOutcome.LeftQueue(spawn),
        ReleaseOutcome.AlreadyHandingOver("415 — Cult Orcs"), ReleaseOutcome.NothingHeld,
        ReleaseOutcome.NotConfigured)
      all.foreach(outcome => withClue(s"$outcome: ")(
        RespawnActions.describe(outcome).message.trim should not be empty))
    }
  }

  "Unavailable" should {
    // Not a permission failure: the visitor did nothing wrong and retrying will
    // not help, so it must not read as "you can't".
    "point at Discord rather than implying the visitor lacks access" in {
      val message = RespawnActionPort.Unavailable.message.toLowerCase
      RespawnActionPort.Unavailable.ok shouldBe false
      message should include("discord")
      message should not include "permission"
      message should not include "forbidden"
    }
  }
}
