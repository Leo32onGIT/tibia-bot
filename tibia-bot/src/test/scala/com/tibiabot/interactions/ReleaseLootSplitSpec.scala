package com.tibiabot.interactions

import com.tibiabot.domain.{Respawn, RespawnClaim}
import com.tibiabot.respawn.ReleaseOutcome
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.ZonedDateTime

/** Which Leave presses come back carrying the Loot Split form.
 *
 *  The form rides on the reply because leaving a spawn is the moment a party has
 *  a hunt to split. That is only true when a hunt actually ended, so this pins
 *  the one outcome that offers it — the rest of the family must not, and a new
 *  member of it defaults to not offering rather than to offering wrongly. */
class ReleaseLootSplitSpec extends AnyWordSpec with Matchers {

  private val respawn =
    Respawn(1, "420b", "Monster Graveyard West", "Dragon", "Edron", "", "", "", Respawn.SourceSeed, "seed")

  private val nextInLine =
    RespawnClaim(2, 1, "u2", "user2", "", RespawnClaim.StatusQueued, 1,
      ZonedDateTime.parse("2026-09-06T09:00:00Z"), None, None, 60,
      warned = false, RespawnClaim.KindAdHoc, None, None, None, None)

  "a Leave press" should {

    "offer the split when a hunt actually ended" in {
      RespawnButtons.lootSplitRowFor(
        ReleaseOutcome.Released(respawn, refundedMinutes = 59, offered = None)) shouldBe defined
    }

    "still offer it when the spawn is being handed to somebody waiting" in {
      // The hunt is over for the person leaving either way — whether anyone was
      // queued behind them decides who gets the spawn, not whether they have
      // loot to split.
      RespawnButtons.lootSplitRowFor(
        ReleaseOutcome.Released(respawn, refundedMinutes = 0, offered = Some(nextInLine))) shouldBe defined
    }

    "not offer it for giving up a place in the queue" in {
      // Nothing was hunted, so there is nothing to split.
      RespawnButtons.lootSplitRowFor(ReleaseOutcome.LeftQueue(respawn)) shouldBe empty
    }

    "not offer it on any refusal" in {
      RespawnButtons.lootSplitRowFor(ReleaseOutcome.AlreadyHandingOver("Monster Graveyard West")) shouldBe empty
      RespawnButtons.lootSplitRowFor(ReleaseOutcome.NothingHeld) shouldBe empty
      RespawnButtons.lootSplitRowFor(ReleaseOutcome.NotConfigured) shouldBe empty
    }
  }
}
