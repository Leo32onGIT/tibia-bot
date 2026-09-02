package com.tibiabot.tracking

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Pins the online-list reconciliation: what the bot believes is posted in a
 *  channel, and the sends/edits/deletes needed to bring Discord in line with a
 *  freshly rendered list — the logic that lets the steady-state refresh avoid
 *  reading channel history at all. */
class OnlineListStateSpec extends AnyFunSuite with Matchers {

  private val channel = "chan-1"

  /** A message already posted, carrying one embed. */
  private def posted(id: String, text: String) = OnlineListMessage(Some(id), List(text))

  /** A rendered list of single-embed messages, one per string — the shape most
   *  of these cases care about, since the diff is per message. */
  private def single(texts: String*): List[List[String]] = texts.map(List(_)).toList

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

  test("an empty channel sends every message") {
    val state = new OnlineListState()
    state.seed(channel, Nil)
    state.plan(channel, single("a", "b")) shouldBe List(
      SendOnlineListMessage(0, List("a")),
      SendOnlineListMessage(1, List("b"))
    )
  }

  test("an unchanged list produces no actions at all") {
    val state = new OnlineListState()
    state.seed(channel, List(posted("m1", "a"), posted("m2", "b")))
    state.plan(channel, single("a", "b")) shouldBe empty
  }

  test("only the changed message is edited") {
    val state = new OnlineListState()
    state.seed(channel, List(posted("m1", "a"), posted("m2", "b")))
    state.plan(channel, single("a", "b-changed")) shouldBe List(
      EditOnlineListMessage(1, "m2", List("b-changed"))
    )
  }

  test("a list that grew edits nothing and sends the new tail") {
    val state = new OnlineListState()
    state.seed(channel, List(posted("m1", "a")))
    state.plan(channel, single("a", "b")) shouldBe List(SendOnlineListMessage(1, List("b")))
  }

  test("a list that shrank deletes the leftovers by id") {
    val state = new OnlineListState()
    state.seed(channel, List(posted("m1", "a"), posted("m2", "b"), posted("m3", "c")))
    state.plan(channel, single("a")) shouldBe List(DeleteOnlineListMessages(List("m2", "m3")))
    // and they are gone from the believed state, so the next cycle is a no-op
    state.plan(channel, single("a")) shouldBe empty
  }

  test("a changed message's new text is committed, so replanning is a no-op") {
    val state = new OnlineListState()
    state.seed(channel, List(posted("m1", "a")))
    state.plan(channel, single("a2")) shouldBe List(EditOnlineListMessage(0, "m1", List("a2")))
    state.plan(channel, single("a2")) shouldBe empty
  }

  test("only the duration ticking up does not count as a change") {
    val state = new OnlineListState()
    state.seed(channel, List(posted("m1", "Bubble `5min` :zap:")))
    state.plan(channel, single("Bubble `1hr 12min` :zap:")) shouldBe empty
  }

  test("a real change alongside a ticking duration is still an edit") {
    val state = new OnlineListState()
    state.seed(channel, List(posted("m1", "Bubble `5min`")))
    state.plan(channel, single("Bubble `6min` :zap:")) shouldBe List(
      EditOnlineListMessage(0, "m1", List("Bubble `6min` :zap:"))
    )
  }

  // --- messages carrying several embeds ---

  test("a change in any of a message's embeds rewrites the whole message") {
    val state = new OnlineListState()
    state.seed(channel, List(OnlineListMessage(Some("m1"), List("a", "b"))))
    state.plan(channel, List(List("a", "b-changed"))) shouldBe List(
      EditOnlineListMessage(0, "m1", List("a", "b-changed"))
    )
  }

  test("a multi-embed message with nothing changed produces no actions") {
    val state = new OnlineListState()
    state.seed(channel, List(OnlineListMessage(Some("m1"), List("a", "Bubble `5min`"))))
    state.plan(channel, List(List("a", "Bubble `2hr 1min`"))) shouldBe empty
  }

  test("a message that gains or loses an embed is edited, even with its text unchanged") {
    val state = new OnlineListState()
    state.seed(channel, List(OnlineListMessage(Some("m1"), List("a", "b"))))
    state.plan(channel, List(List("a"))) shouldBe List(EditOnlineListMessage(0, "m1", List("a")))
    state.plan(channel, List(List("a", "b"))) shouldBe List(EditOnlineListMessage(0, "m1", List("a", "b")))
  }

  test("repacking the same lines into fewer messages edits the first and deletes the rest") {
    val state = new OnlineListState()
    state.seed(channel, List(posted("m1", "a"), posted("m2", "b")))
    state.plan(channel, List(List("a", "b"))) shouldBe List(
      EditOnlineListMessage(0, "m1", List("a", "b")),
      DeleteOnlineListMessages(List("m2"))
    )
  }

  // --- reposting instead of editing ---

  private val cooldownMs = 900000L
  private val congested = 400

  /** A state that will repost, with a clock the test drives by hand. */
  private class Reposting {
    var nowMs: Long = 1000000L
    val state = new OnlineListState(
      policy = OnlineListRepostPolicy(List(congested -> cooldownMs), dirtyFraction = 0.6),
      now = () => nowMs
    )
    def seedThree(): Unit = state.seed(channel, List(posted("m1", "a"), posted("m2", "b"), posted("m3", "c")))
    def elapse(ms: Long): Unit = nowMs += ms
  }

  test("a fully dirty list on a congested lane is reposted rather than edited") {
    val r = new Reposting
    r.seedThree()
    r.elapse(cooldownMs)
    r.state.plan(channel, single("a2", "b2", "c2"), congested, canDelete = true) shouldBe List(
      RepostOnlineList(List("m1", "m2", "m3"), single("a2", "b2", "c2"))
    )
  }

  test("a repost commits every message as awaiting an id, like a cold channel") {
    val r = new Reposting
    r.seedThree()
    r.elapse(cooldownMs)
    r.state.plan(channel, single("a2", "b2", "c2"), congested, canDelete = true)
    r.state.posted(channel).map(_.map(_.id)) shouldBe Some(List(None, None, None))
    // and nothing is planned again while those sends are in flight
    r.state.plan(channel, single("a3", "b3", "c3"), congested, canDelete = true) shouldBe empty
  }

  test("without permission to bulk delete the messages are edited as before") {
    val r = new Reposting
    r.seedThree()
    r.elapse(cooldownMs)
    r.state.plan(channel, single("a2", "b2", "c2"), congested, canDelete = false) should have size 3
  }

  test("a healthy lane keeps editing however dirty the list") {
    val r = new Reposting
    r.seedThree()
    r.elapse(cooldownMs)
    r.state.plan(channel, single("a2", "b2", "c2"), queueDepth = 0, canDelete = true) should have size 3
  }

  test("seeding starts the cooldown, so a channel is not reposted the moment it syncs") {
    val r = new Reposting
    r.seedThree()
    r.state.plan(channel, single("a2", "b2", "c2"), congested, canDelete = true) should have size 3
  }

  test("a repost restarts the cooldown, so the next cycle edits again") {
    val r = new Reposting
    r.seedThree()
    r.elapse(cooldownMs)
    r.state.plan(channel, single("a2", "b2", "c2"), congested, canDelete = true) should have size 1
    List("n1", "n2", "n3").zipWithIndex.foreach { case (id, i) => r.state.recordMessageId(channel, i, id) }
    r.elapse(cooldownMs - 1)
    r.state.plan(channel, single("a3", "b3", "c3"), congested, canDelete = true) shouldBe List(
      EditOnlineListMessage(0, "n1", List("a3")),
      EditOnlineListMessage(1, "n2", List("b3")),
      EditOnlineListMessage(2, "n3", List("c3"))
    )
  }

  test("invalidating a channel does not hand it a fresh cooldown") {
    val r = new Reposting
    r.seedThree()
    r.elapse(cooldownMs)
    r.state.plan(channel, single("a2", "b2", "c2"), congested, canDelete = true) should have size 1
    // A failed send drops the cache; the resync that follows reseeds it, and it
    // is the reseed — not the invalidate — that is allowed to restart the clock.
    r.state.invalidate(channel)
    r.state.seed(channel, List(posted("m1", "a"), posted("m2", "b"), posted("m3", "c")))
    r.state.plan(channel, single("a2", "b2", "c2"), congested, canDelete = true) should have size 3
  }

  test("the default state never reposts, whatever the lane is doing") {
    val state = new OnlineListState()
    state.seed(channel, List(posted("m1", "a"), posted("m2", "b"), posted("m3", "c")))
    state.plan(channel, single("a2", "b2", "c2"), queueDepth = 100000, canDelete = true) should have size 3
  }

  // --- in-flight sends ---

  test("a slot whose send is in flight is not sent again next cycle") {
    val state = new OnlineListState()
    state.seed(channel, Nil)
    state.plan(channel, single("a")) shouldBe List(SendOnlineListMessage(0, List("a")))
    // id has not come back yet, and the roster has moved on
    state.plan(channel, single("a-changed")) shouldBe empty
  }

  test("once a send reports its id, later changes become edits") {
    val state = new OnlineListState()
    state.seed(channel, Nil)
    state.plan(channel, single("a"))
    state.recordMessageId(channel, 0, "m1")
    state.plan(channel, single("a-changed")) shouldBe List(EditOnlineListMessage(0, "m1", List("a-changed")))
  }

  test("recording an id for an invalidated channel is a no-op, not a resurrection") {
    val state = new OnlineListState()
    state.seed(channel, Nil)
    state.plan(channel, single("a"))
    state.invalidate(channel)
    state.recordMessageId(channel, 0, "m1")
    state.isWarm(channel) shouldBe false
  }

  test("a late duplicate id callback does not overwrite the recorded one") {
    val state = new OnlineListState()
    state.seed(channel, Nil)
    state.plan(channel, single("a"))
    state.recordMessageId(channel, 0, "m1")
    state.recordMessageId(channel, 0, "m2")
    state.posted(channel).map(_.map(_.id)) shouldBe Some(List(Some("m1")))
  }

  test("planning a cold channel treats it as empty and sends everything") {
    val state = new OnlineListState()
    state.plan(channel, single("a")) shouldBe List(SendOnlineListMessage(0, List("a")))
  }

  test("channels are independent") {
    val state = new OnlineListState()
    state.seed("a", List(posted("m1", "x")))
    state.seed("b", Nil)
    state.plan("a", single("x")) shouldBe empty
    state.plan("b", single("x")) shouldBe List(SendOnlineListMessage(0, List("x")))
  }
}
