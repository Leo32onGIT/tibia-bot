package com.tibiabot.interactions

import com.tibiabot.respawn.{LogScope, RespawnButtonId}
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
    RespawnButtonId.opensModal(RespawnButtonId.boardBook) shouldBe true
    // Config decides between a modal and a deferred panel only after a role
    // lookup, so it has to keep its own first response free.
    RespawnButtonId.opensModal(RespawnButtonId.boardConfig) shouldBe true
  }

  test("the autoclaim toggle rewrites the panel it is drawn on") {
    // It sits in the same row as Claim rules, which opens a modal instead — and
    // the two are told apart by nothing but their id, so this is worth pinning
    // twice over. Replying rather than editing would leave the pressed panel
    // showing the label and the field it had before the press, with a corrected
    // copy stacked under it.
    RespawnButtonId.ackFor(RespawnButtonId.boardAutoClaim) shouldBe
      RespawnButtonId.Ack.EditsMessage
    RespawnButtonId.opensModal(RespawnButtonId.boardAutoClaim) shouldBe false
    RespawnButtonId.parse(RespawnButtonId.boardAutoClaim) shouldBe
      Some(RespawnButtonId.BoardButton("autoclaim"))
  }

  test("Config itself still opens rather than edits, being pressed from the board post") {
    // The panel is a new ephemeral every time it is opened; only the toggle
    // inside it edits. Deferring an edit here would try to rewrite the board
    // post everyone can see.
    RespawnButtonId.ackFor(RespawnButtonId.boardConfig) shouldBe RespawnButtonId.Ack.OpensModal
    RespawnButtonId.ackFor(RespawnButtonId.boardMySettings) shouldBe RespawnButtonId.Ack.OpensModal
    RespawnButtonId.ackFor(RespawnButtonId.boardClaimRules) shouldBe RespawnButtonId.Ack.OpensModal
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

  test("log pages edit the message they were pressed on, rather than replying") {
    // Deferring these as a reply would stack a fresh ephemeral log on every
    // click instead of turning the page.
    RespawnButtonId.ackFor(RespawnButtonId.logPage(LogScope.Everything, 0)) shouldBe
      RespawnButtonId.Ack.EditsMessage
    RespawnButtonId.ackFor(RespawnButtonId.logPage(LogScope.Spawn(415L), 3)) shouldBe
      RespawnButtonId.Ack.EditsMessage
    RespawnButtonId.ackFor(RespawnButtonId.logPage(LogScope.Member("1082484147492237515"), 1)) shouldBe
      RespawnButtonId.Ack.EditsMessage
  }

  test("Find sits on a log message but must not be deferred, since it opens a modal") {
    RespawnButtonId.ackFor(RespawnButtonId.logFind) shouldBe RespawnButtonId.Ack.OpensModal
    RespawnButtonId.parse(RespawnButtonId.logFind) shouldBe Some(RespawnButtonId.LogFindButton)
  }

  test("a log id round-trips through the parser, for the guild, one spawn and one member") {
    RespawnButtonId.parse(RespawnButtonId.logPage(LogScope.Everything, 2)) shouldBe
      Some(RespawnButtonId.LogButton(LogScope.Everything, 2))
    RespawnButtonId.parse(RespawnButtonId.logPage(LogScope.Spawn(415L), 0)) shouldBe
      Some(RespawnButtonId.LogButton(LogScope.Spawn(415L), 0))
    RespawnButtonId.parse(RespawnButtonId.logPage(LogScope.Member("1082484147492237515"), 4)) shouldBe
      Some(RespawnButtonId.LogButton(LogScope.Member("1082484147492237515"), 4))
  }

  test("a spawn id and a member id are told apart, since both are bare digits") {
    // Without the `u` prefix on the member form, one would parse as the other and
    // the log would quietly read the wrong thing.
    LogScope.Spawn(415L).token should not be LogScope.Member("415").token
    LogScope.fromToken(LogScope.Member("415").token) shouldBe Some(LogScope.Member("415"))
    LogScope.fromToken(LogScope.Spawn(415L).token) shouldBe Some(LogScope.Spawn(415L))
  }

  test("a member token that isn't a snowflake is refused rather than passed to a query") {
    LogScope.fromToken("u") shouldBe None
    LogScope.fromToken("u12'; DROP TABLE respawn_claims;--") shouldBe None
    LogScope.fromToken("nonsense") shouldBe None
  }

  test("the three acknowledgement kinds stay distinct") {
    RespawnButtonId.ackFor(RespawnButtonId.boardClaim) shouldBe RespawnButtonId.Ack.OpensModal
    RespawnButtonId.ackFor(RespawnButtonId.logPage(LogScope.Everything, 0)) shouldBe
      RespawnButtonId.Ack.EditsMessage
    RespawnButtonId.ackFor(RespawnButtonId.leave(1L)) shouldBe RespawnButtonId.Ack.Replies
  }

  test("every press the listener acknowledges is also one it routes") {
    // A press BotListener deferred but never handed to RespawnButtons would
    // hang until Discord gave up on it, so the two predicates have to agree on
    // what counts as ours.
    val ids = List(
      RespawnButtonId.boardClaim, RespawnButtonId.boardBook, RespawnButtonId.boardConfig,
      RespawnButtonId.claim(1L), RespawnButtonId.leave(1L), RespawnButtonId.next(1L),
      RespawnButtonId.release(1L), RespawnButtonId.spawnConfig(1L), RespawnButtonId.spawnSchedule(1L),
      acceptOffer, RespawnButtonId.keepSlot("1", 2L))
    ids.foreach(id => withClue(s"$id: ")(RespawnButtonId.handles(id) shouldBe true))
  }
}
