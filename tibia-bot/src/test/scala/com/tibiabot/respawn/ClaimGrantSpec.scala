package com.tibiabot.respawn

import com.tibiabot.domain.Stamina
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.ZonedDateTime

/** How long a claim runs for when it cannot have everything it asked for. */
class ClaimGrantSpec extends AnyWordSpec with Matchers {

  private val resetAt = ZonedDateTime.parse("2026-08-09T08:00:00Z")
  private def tank(used: Int, budget: Int = 240) = Stamina("u1", used, budget, resetAt)
  private val unlimited = Stamina("u1", 0, 0, resetAt)

  "a granted claim" should {

    "be the whole hunt when nothing is in the way" in {
      RespawnService.grantedMinutes(120, tank(used = 0)) shouldBe 120
    }

    // The change: a tank that cannot cover the hunt shortens it rather than
    // refusing it. Being told to come back after server save leaves the spawn
    // empty and the person hunting nothing.
    "be whatever is left in the tank when that is less than the hunt" in {
      RespawnService.grantedMinutes(120, tank(used = 200)) shouldBe 40
    }

    "be the remainder even when it is under half an hour" in {
      RespawnService.grantedMinutes(120, tank(used = 222)) shouldBe 18
    }

    "be the booking's limit when the booking is the tighter of the two" in {
      RespawnService.grantedMinutes(45, tank(used = 0)) shouldBe 45
    }

    "take the tighter limit when both bind" in {
      RespawnService.grantedMinutes(45, tank(used = 210)) shouldBe 30
      RespawnService.grantedMinutes(20, tank(used = 210)) shouldBe 20
    }

    // Zero is not a claim, and the caller refuses it against the floor rather
    // than starting a hunt that is already over.
    "be nothing at all when the tank is empty" in {
      RespawnService.grantedMinutes(120, tank(used = 240)) shouldBe 0
      RespawnService.grantedMinutes(120, tank(used = 900)) shouldBe 0
    }

    // A guild with stamina switched off has an unlimited tank, whose remaining
    // minutes are Int.MaxValue — taking the smaller of the two would work by
    // accident here, but only until something adds to the requested minutes.
    "ignore the tank entirely when the guild has stamina switched off" in {
      RespawnService.grantedMinutes(120, unlimited) shouldBe 120
      RespawnService.grantedMinutes(1440, unlimited) shouldBe 1440
    }
  }
}
