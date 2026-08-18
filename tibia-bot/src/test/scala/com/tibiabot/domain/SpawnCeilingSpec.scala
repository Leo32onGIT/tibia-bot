package com.tibiabot.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** One spawn's own ceiling on claim length, and how it reconciles with the
 *  guild's.
 *
 *  Small, because the whole point of `maxFor` is that this is the *only* place
 *  the reconciliation happens: four checks in the service read it and none of
 *  them does its own arithmetic, so getting this table right is getting all four
 *  right.
 */
class SpawnCeilingSpec extends AnyWordSpec with Matchers {

  private val settings = RespawnSettings(
    forumChannel = "forum", boardThread = "board",
    defaultDurationMinutes = 120, maxDurationMinutes = 240,
    queueLimit = 3, staminaMinutes = 480, warnMinutes = 10, handoverMinutes = 10)

  private def spawn(max: Option[Int] = None) =
    Respawn(1L, "415", "Cult Orcs", "", "Edron", "", "", "0",
      Respawn.SourceSeed, "seed", max)

  "a spawn's ceiling" should {

    // The common case by a distance: almost no spawn is singled out, and a guild
    // retuning its own number has to move every one of them.
    "follow the guild when the spawn has none of its own" in {
      settings.maxFor(spawn()) shouldBe 240
    }

    "be the spawn's own when it has one" in {
      settings.maxFor(spawn(Some(60))) shouldBe 60
    }

    // The decision from the design pass: it replaces rather than caps. An admin
    // who types six hours on a raid spawn meant six hours, and silently clamping
    // to the server's four would be the setting lying about itself.
    "be allowed above the guild's, not clamped to it" in {
      settings.maxFor(spawn(Some(360))) shouldBe 360
    }

    "move with the guild only for the spawns that follow it" in {
      val stricter = settings.copy(maxDurationMinutes = 90)
      stricter.maxFor(spawn()) shouldBe 90
      stricter.maxFor(spawn(Some(360))) shouldBe 360
    }

    // Zero is not "no override" — that is what None is for. A stored zero would
    // mean a spawn nobody can claim, which the service refuses to write in the
    // first place; this only pins down that the resolver does not quietly treat
    // it as absent.
    "not read a stored zero as an absent override" in {
      settings.maxFor(spawn(Some(0))) shouldBe 0
    }
  }
}
