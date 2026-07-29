package com.tibiabot.domain

import com.tibiabot.respawn.RespawnButtonId
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

class StaminaSpec extends AnyFunSuite with Matchers {

  private val now = ZonedDateTime.parse("2026-07-30T10:00:00Z")
  private def tank(used: Int, budget: Int = 240) = Stamina("7", used, budget, now)

  test("remaining is the tank minus what's reserved") {
    tank(90).remainingMinutes shouldBe 150
  }

  test("a claim that exactly empties the tank is allowed") {
    // The design's headline case: two 2h claims against a 4h tank.
    tank(120).canAfford(120) shouldBe true
    tank(120).remainingMinutes shouldBe 120
  }

  test("a claim one minute past the tank is refused") {
    tank(120).canAfford(121) shouldBe false
  }

  test("a zero budget means stamina is disabled, not that nothing is affordable") {
    tank(0, budget = 0).unlimited shouldBe true
    tank(999, budget = 0).canAfford(10000) shouldBe true
  }

  test("over-reserved never reports negative time left") {
    // Reachable if an admin lowers the guild's budget while claims are running.
    tank(300, budget = 240).remainingMinutes shouldBe 0
    tank(300, budget = 240).canAfford(1) shouldBe false
  }

  test("respawn button ids round-trip") {
    RespawnButtonId.parse(RespawnButtonId.next(415L)) shouldBe Some(("next", 415L))
    RespawnButtonId.parse(RespawnButtonId.claim(1L)) shouldBe Some(("claim", 1L))
    RespawnButtonId.parse(RespawnButtonId.leave(99L)) shouldBe Some(("leave", 99L))
    RespawnButtonId.parse(RespawnButtonId.release(7L)) shouldBe Some(("release", 7L))
  }

  test("the respawn prefix claims its own ids and nothing else") {
    RespawnButtonId.handles(RespawnButtonId.next(1L)) shouldBe true
    // Must not swallow the existing button families in ButtonHandler.
    RespawnButtonId.handles("galthenSet") shouldBe false
    RespawnButtonId.handles("boosted list") shouldBe false
    RespawnButtonId.handles("paywall_reassign_yes_Antica") shouldBe false
  }

  test("a malformed id parses to None rather than throwing") {
    // Buttons live on long-lived forum posts, so ids from an older deploy will
    // be clicked; they must degrade, not crash the handler.
    RespawnButtonId.parse("respawn:next") shouldBe None
    RespawnButtonId.parse("respawn:next:notanumber") shouldBe None
    RespawnButtonId.parse("respawn:") shouldBe None
  }

  test("a claim knows which state it is in") {
    val base = RespawnClaim(1L, 1L, "7", "n", "", RespawnClaim.StatusActive, 0, now, Some(now),
      Some(now.plusHours(2)), 120, warned = false, kind = RespawnClaim.KindAdHoc)
    base.isActive shouldBe true
    base.isQueued shouldBe false
    base.copy(status = RespawnClaim.StatusQueued).isQueued shouldBe true
    base.copy(status = RespawnClaim.StatusFinished).isActive shouldBe false
  }

  test("a respawn's display name is the code and name, as the forum post title") {
    Respawn(1L, "415", "Cult Orcs", "", "Edron", "", "", "0", Respawn.SourceSeed, "seed")
      .displayName shouldBe "415 — Cult Orcs"
  }
}
