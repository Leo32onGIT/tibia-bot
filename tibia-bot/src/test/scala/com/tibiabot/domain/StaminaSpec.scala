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

  test("in-thread respawn button ids round-trip to a spawn action") {
    RespawnButtonId.parse(RespawnButtonId.next(415L)) shouldBe Some(RespawnButtonId.SpawnButton("next", 415L))
    RespawnButtonId.parse(RespawnButtonId.claim(1L)) shouldBe Some(RespawnButtonId.SpawnButton("claim", 1L))
    RespawnButtonId.parse(RespawnButtonId.leave(99L)) shouldBe Some(RespawnButtonId.SpawnButton("leave", 99L))
    RespawnButtonId.parse(RespawnButtonId.release(7L)) shouldBe Some(RespawnButtonId.SpawnButton("release", 7L))
  }

  test("offer button ids carry the guild, because a DM interaction has no guild of its own") {
    RespawnButtonId.parse(RespawnButtonId.accept("1082484147492237515", 42L)) shouldBe
      Some(RespawnButtonId.OfferButton(accept = true, "1082484147492237515", 42L))
    RespawnButtonId.parse(RespawnButtonId.decline("1082484147492237515", 42L)) shouldBe
      Some(RespawnButtonId.OfferButton(accept = false, "1082484147492237515", 42L))
  }

  test("a DM Leave button carries its guild, since a DM interaction has none") {
    RespawnButtonId.parse(RespawnButtonId.dmLeave("1082484147492237515", 415L)) shouldBe
      Some(RespawnButtonId.DmSpawnButton("leave", "1082484147492237515", 415L))
    // Must not be confused with the in-thread Leave, which has no guild in it.
    RespawnButtonId.parse(RespawnButtonId.leave(415L)) shouldBe
      Some(RespawnButtonId.SpawnButton("leave", 415L))
  }

  test("offer button ids stay inside Discord's 100-character component-id limit") {
    // Real snowflakes are 18-19 digits and claim ids grow without bound; an id
    // over the limit is rejected by Discord when the message is sent, so the
    // whole handover DM would fail rather than just the button.
    RespawnButtonId.accept("1082484147492237515", Long.MaxValue).length should be < 100
    RespawnButtonId.decline("1082484147492237515", Long.MaxValue).length should be < 100
  }

  test("the two id shapes never parse as each other") {
    // The trailing number is a respawn id for one and a claim id for the other,
    // so confusing them would act on the wrong row entirely.
    RespawnButtonId.parse(RespawnButtonId.claim(5L)) shouldBe Some(RespawnButtonId.SpawnButton("claim", 5L))
    RespawnButtonId.parse(RespawnButtonId.accept("9", 5L)) shouldBe
      Some(RespawnButtonId.OfferButton(accept = true, "9", 5L))
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

  test("leaving a queue does not advance a handover, but declining an offer does") {
    // The Leave bug: both were treated alike, so a queued member leaving made the
    // service advance the handover — which closes out whoever it is replacing. The
    // holder's live hunt was therefore ended by a third party abandoning the
    // queue, or offered away mid-hunt when somebody else was still waiting.
    val base = RespawnClaim(1L, 1L, "7", "n", "", RespawnClaim.StatusQueued, 1, now, None, None,
      120, warned = false, kind = RespawnClaim.KindAdHoc, limboUntil = None,
      offerExpiresAt = None, outcome = None, endedAt = None, scheduleId = None)

    base.leavingAdvancesHandover shouldBe false
    base.copy(status = RespawnClaim.StatusOffered).leavingAdvancesHandover shouldBe true
    // An active holder pressing Leave takes the release path, not this one.
    base.copy(status = RespawnClaim.StatusActive).leavingAdvancesHandover shouldBe false
  }

  test("only a claim already on its way out may be finished by a handover") {
    val holder = RespawnClaim(1L, 1L, "holder", "H", "", RespawnClaim.StatusActive, 0, now, Some(now),
      Some(now.plusHours(2)), 120, warned = false, kind = RespawnClaim.KindAdHoc,
      limboUntil = None, offerExpiresAt = None, outcome = None, endedAt = None, scheduleId = None)

    // Mid-hunt, an hour still to run: a handover must not touch it.
    holder.eligibleForHandover shouldBe false
    // Time up, next person deciding: this is the one a handover closes out.
    holder.copy(limboUntil = Some(now.plusMinutes(10))).eligibleForHandover shouldBe true
  }

  test("a claim knows whether it is being handed over") {
    val base = RespawnClaim(1L, 1L, "7", "n", "", RespawnClaim.StatusActive, 0, now, Some(now),
      Some(now), 120, warned = false, kind = RespawnClaim.KindAdHoc,
      limboUntil = None, offerExpiresAt = None, outcome = None, endedAt = None, scheduleId = None)
    base.inLimbo(now) shouldBe false
    base.copy(limboUntil = Some(now.plusMinutes(10))).inLimbo(now) shouldBe true
    // The window having passed is exactly when the claim stops being the holder.
    base.copy(limboUntil = Some(now.minusMinutes(1))).inLimbo(now) shouldBe false
    base.copy(limboUntil = Some(now)).inLimbo(now) shouldBe false
  }

  test("an offered claim is neither active nor merely queued") {
    val offered = RespawnClaim(1L, 1L, "7", "n", "", RespawnClaim.StatusOffered, 1, now, None, None,
      120, warned = false, kind = RespawnClaim.KindAdHoc, limboUntil = None,
      offerExpiresAt = Some(now.plusMinutes(10)), outcome = None, endedAt = None, scheduleId = None)
    offered.isOffered shouldBe true
    offered.isActive shouldBe false
    offered.isQueued shouldBe false
  }

  test("a claim knows which state it is in") {
    val base = RespawnClaim(1L, 1L, "7", "n", "", RespawnClaim.StatusActive, 0, now, Some(now),
      Some(now.plusHours(2)), 120, warned = false, kind = RespawnClaim.KindAdHoc,
      limboUntil = None, offerExpiresAt = None, outcome = None, endedAt = None, scheduleId = None)
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
