package com.tibiabot.interactions

import com.tibiabot.respawn.RespawnButtonId
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Guards the one decision BotListener makes on JDA's event thread: whether a
 *  button press can be acknowledged before it is queued for a worker.
 *
 *  Both directions cost something real. Deferring a press that goes on to call
 *  `replyModal` breaks it outright, since a modal has to be an interaction's
 *  first response. Failing to defer one that doesn't hands the acknowledgement
 *  back to a pool shared with `/setup`, which is what made presses time out as
 *  "Violent Bot did not respond" in the first place.
 */
class RespawnButtonAckSpec extends AnyFunSuite with Matchers {

  // Handover-offer ids are built inline by RespawnThreads.offerButtons rather
  // than through a named constructor, so they are spelled out in the form
  // RespawnButtonId.parse accepts.
  private val acceptOffer = s"${RespawnButtonId.Prefix}accept:1:2"
  private val declineOffer = s"${RespawnButtonId.Prefix}decline:1:2"

  test("board buttons that open a modal are not deferred") {
    RespawnButtonId.opensModal(RespawnButtonId.boardClaim) shouldBe true
    // Config decides between a modal and a deferred panel only after a role
    // lookup, so it has to keep its own first response free.
    RespawnButtonId.opensModal(RespawnButtonId.boardConfig) shouldBe true
  }

  test("spawn buttons that open a modal are not deferred") {
    RespawnButtonId.opensModal(RespawnButtonId.claim(7L)) shouldBe true
    RespawnButtonId.opensModal(RespawnButtonId.spawnConfig(7L)) shouldBe true
    RespawnButtonId.opensModal(RespawnButtonId.spawnSchedule(7L)) shouldBe true
  }

  test("spawn buttons that answer with a message are deferred up front") {
    RespawnButtonId.opensModal(RespawnButtonId.leave(7L)) shouldBe false
    RespawnButtonId.opensModal(RespawnButtonId.next(7L)) shouldBe false
    RespawnButtonId.opensModal(RespawnButtonId.release(7L)) shouldBe false
  }

  test("DM buttons are deferred — none of them open a modal") {
    RespawnButtonId.opensModal(acceptOffer) shouldBe false
    RespawnButtonId.opensModal(declineOffer) shouldBe false
    RespawnButtonId.opensModal(RespawnButtonId.keepSlot("1", 2L)) shouldBe false
    RespawnButtonId.opensModal(RespawnButtonId.passSlot("1", 2L)) shouldBe false
  }

  test("an unparseable id is deferred, so its out-of-date reply goes through the hook") {
    RespawnButtonId.opensModal(s"${RespawnButtonId.Prefix}nonsense") shouldBe false
    RespawnButtonId.opensModal(s"${RespawnButtonId.Prefix}claim:not-a-number") shouldBe false
  }

  test("every press the listener acknowledges is also one it routes") {
    // A press BotListener deferred but never handed to RespawnButtons would
    // hang until Discord gave up on it, so the two predicates have to agree on
    // what counts as ours.
    val ids = List(
      RespawnButtonId.boardClaim, RespawnButtonId.boardConfig,
      RespawnButtonId.claim(1L), RespawnButtonId.leave(1L), RespawnButtonId.next(1L),
      RespawnButtonId.release(1L), RespawnButtonId.spawnConfig(1L), RespawnButtonId.spawnSchedule(1L),
      acceptOffer, RespawnButtonId.keepSlot("1", 2L))
    ids.foreach(id => withClue(s"$id: ")(RespawnButtonId.handles(id) shouldBe true))
  }
}
