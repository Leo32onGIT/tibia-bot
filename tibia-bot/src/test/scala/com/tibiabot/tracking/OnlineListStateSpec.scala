package com.tibiabot.tracking

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Pins the online-list reconciliation: what the bot believes is posted in a
 *  channel, and the sends/edits/deletes needed to bring Discord in line with a
 *  freshly rendered list — the logic that lets the steady-state refresh avoid
 *  reading channel history at all. */
class OnlineListStateSpec extends AnyFunSuite with Matchers {

  private val channel = "chan-1"
  private def posted(id: String, text: String) = OnlineListMessage(Some(id), text)

  test("a channel is cold until it is seeded") {
    val state = new OnlineListState()
    state.isWarm(channel) shouldBe false
    state.seed(channel, Nil)
    state.isWarm(channel) shouldBe true // seeded-but-empty is still synced
  }

  test("invalidate returns a channel to cold") {
    val state = new OnlineListState()
    state.seed(channel, List(posted("m1", "a")))
    state.invalidate(channel)
    state.isWarm(channel) shouldBe false
  }

  test("an empty channel sends every field") {
    val state = new OnlineListState()
    state.seed(channel, Nil)
    state.plan(channel, List("a", "b")) shouldBe List(
      SendOnlineListMessage(0, "a"),
      SendOnlineListMessage(1, "b")
    )
  }

  test("an unchanged list produces no actions at all") {
    val state = new OnlineListState()
    state.seed(channel, List(posted("m1", "a"), posted("m2", "b")))
    state.plan(channel, List("a", "b")) shouldBe empty
  }

  test("only the changed message is edited") {
    val state = new OnlineListState()
    state.seed(channel, List(posted("m1", "a"), posted("m2", "b")))
    state.plan(channel, List("a", "b-changed")) shouldBe List(
      EditOnlineListMessage(1, "m2", "b-changed")
    )
  }

  test("a list that grew edits nothing and sends the new tail") {
    val state = new OnlineListState()
    state.seed(channel, List(posted("m1", "a")))
    state.plan(channel, List("a", "b")) shouldBe List(SendOnlineListMessage(1, "b"))
  }

  test("a list that shrank deletes the leftovers by id") {
    val state = new OnlineListState()
    state.seed(channel, List(posted("m1", "a"), posted("m2", "b"), posted("m3", "c")))
    state.plan(channel, List("a")) shouldBe List(DeleteOnlineListMessages(List("m2", "m3")))
    // and they are gone from the believed state, so the next cycle is a no-op
    state.plan(channel, List("a")) shouldBe empty
  }

  test("a changed message's new text is committed, so replanning is a no-op") {
    val state = new OnlineListState()
    state.seed(channel, List(posted("m1", "a")))
    state.plan(channel, List("a2")) shouldBe List(EditOnlineListMessage(0, "m1", "a2"))
    state.plan(channel, List("a2")) shouldBe empty
  }

  test("only the duration ticking up does not count as a change") {
    val state = new OnlineListState()
    state.seed(channel, List(posted("m1", "Bubble `5min` :zap:")))
    state.plan(channel, List("Bubble `1hr 12min` :zap:")) shouldBe empty
  }

  test("a real change alongside a ticking duration is still an edit") {
    val state = new OnlineListState()
    state.seed(channel, List(posted("m1", "Bubble `5min`")))
    state.plan(channel, List("Bubble `6min` :zap:")) shouldBe List(
      EditOnlineListMessage(0, "m1", "Bubble `6min` :zap:")
    )
  }

  test("a slot whose send is in flight is not sent again next cycle") {
    val state = new OnlineListState()
    state.seed(channel, Nil)
    state.plan(channel, List("a")) shouldBe List(SendOnlineListMessage(0, "a"))
    // id has not come back yet, and the roster has moved on
    state.plan(channel, List("a-changed")) shouldBe empty
  }

  test("once a send reports its id, later changes become edits") {
    val state = new OnlineListState()
    state.seed(channel, Nil)
    state.plan(channel, List("a"))
    state.recordMessageId(channel, 0, "m1")
    state.plan(channel, List("a-changed")) shouldBe List(EditOnlineListMessage(0, "m1", "a-changed"))
  }

  test("recording an id for an invalidated channel is a no-op, not a resurrection") {
    val state = new OnlineListState()
    state.seed(channel, Nil)
    state.plan(channel, List("a"))
    state.invalidate(channel)
    state.recordMessageId(channel, 0, "m1")
    state.isWarm(channel) shouldBe false
  }

  test("a late duplicate id callback does not overwrite the recorded one") {
    val state = new OnlineListState()
    state.seed(channel, Nil)
    state.plan(channel, List("a"))
    state.recordMessageId(channel, 0, "m1")
    state.recordMessageId(channel, 0, "m2")
    state.posted(channel).map(_.map(_.id)) shouldBe Some(List(Some("m1")))
  }

  test("planning a cold channel treats it as empty and sends everything") {
    val state = new OnlineListState()
    state.plan(channel, List("a")) shouldBe List(SendOnlineListMessage(0, "a"))
  }

  test("channels are independent") {
    val state = new OnlineListState()
    state.seed("a", List(posted("m1", "x")))
    state.seed("b", Nil)
    state.plan("a", List("x")) shouldBe empty
    state.plan("b", List("x")) shouldBe List(SendOnlineListMessage(0, "x"))
  }
}
