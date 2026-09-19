package com.tibiabot.respawn

import com.tibiabot.domain.RespawnClaim
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** The Leave button on the DM that says a hunt has started.
 *
 *  It is the only respawn control that lives somewhere it can never be taken
 *  away from: a spawn's card is rewritten as the spawn changes hands, and an
 *  offer's buttons are stripped once answered, but this sits in an inbox with
 *  whatever it said the night it arrived. So the press has to be answerable
 *  weeks later, about a claim that is long finished, from somebody who may well
 *  be hunting that same spawn again right now — which is why the button names
 *  the claim and not the spawn, and why the rule that reads it is pinned here.
 */
class LeaveHuntDmSpec extends AnyFunSuite with Matchers {

  private val owner = "1082484147492237515"
  private val somebodyElse = "1193678088165404807"
  private val when = ZonedDateTime.parse("2026-09-20T21:00:00Z")

  private def claim(status: String, userId: String = owner) =
    RespawnClaim(7, 1, userId, "beams", "", status, 0, when, Some(when),
      Some(when.plusHours(3)), 180, warned = false, RespawnClaim.KindScheduled,
      None, None, None, None)

  test("a running hunt is the one thing it ends") {
    RespawnService.leaveTarget(Some(claim(RespawnClaim.StatusActive)), owner) shouldBe
      Right(claim(RespawnClaim.StatusActive))
  }

  test("a hunt that has already ended says so, rather than reaching for another") {
    // The reason the id carries a claim at all. Resolving by spawn would find
    // whatever this person holds there tonight and end that instead — something
    // real, to the right person, on the right spawn, and not what they meant.
    RespawnService.leaveTarget(Some(claim(RespawnClaim.Outcome.Completed)), owner) shouldBe
      Left(ReleaseOutcome.HuntOver)
    RespawnService.leaveTarget(None, owner) shouldBe Left(ReleaseOutcome.HuntOver)
  }

  test("a booking that has not started yet is not a hunt to leave") {
    // Cancelling a booking is a different act with its own buttons. A reserved
    // slot holds nothing: the spawn may be free, or somebody else's, right now.
    RespawnService.leaveTarget(Some(claim(RespawnClaim.StatusReserved)), owner) shouldBe
      Left(ReleaseOutcome.HuntOver)
  }

  test("a queue place is not one either, however it was reached") {
    RespawnService.leaveTarget(Some(claim(RespawnClaim.StatusQueued)), owner) shouldBe
      Left(ReleaseOutcome.HuntOver)
    RespawnService.leaveTarget(Some(claim(RespawnClaim.StatusOffered)), owner) shouldBe
      Left(ReleaseOutcome.HuntOver)
  }

  test("somebody else's hunt is refused, not ended") {
    // Only reachable by forging a component id, and answered rather than
    // ignored for the same reason every other DM button answers it.
    RespawnService.leaveTarget(Some(claim(RespawnClaim.StatusActive)), somebodyElse) shouldBe
      Left(ReleaseOutcome.NotYours)
  }

  test("a hunt already being handed over is passed through, not refused here") {
    // It is still active and still holding the spawn. Whether a handover is in
    // flight is the release path's answer to give — it knows not to refund the
    // same minutes twice — and this must not shadow it with a worse one.
    val handingOver = claim(RespawnClaim.StatusActive).copy(limboUntil = Some(when.plusMinutes(5)))
    RespawnService.leaveTarget(Some(handingOver), owner) shouldBe Right(handingOver)
  }

  test("the button names the claim, so the press carries which hunt it meant") {
    val row = RespawnThreads.leaveHuntButtons("99", 415L)
    val buttons = row.getButtons
    buttons.size shouldBe 1
    buttons.get(0).getLabel shouldBe "Leave"
    RespawnButtonId.parse(buttons.get(0).getCustomId) shouldBe
      Some(RespawnButtonId.LeaveClaimButton("99", 415L))
  }
}
