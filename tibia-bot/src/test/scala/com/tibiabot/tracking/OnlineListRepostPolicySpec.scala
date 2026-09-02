package com.tibiabot.tracking

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Pins when the online list is wiped and reposted rather than edited in place —
 *  the trade of a scarce shared-bucket PATCH for a per-channel POST, paid for in
 *  a channel that goes unread. */
class OnlineListRepostPolicySpec extends AnyFunSuite with Matchers {

  private val fifteenMinutes = 900000L
  private val fiveMinutes = 300000L
  private val policy = OnlineListRepostPolicy(List(400 -> fifteenMinutes, 800 -> fiveMinutes))

  /** Every condition satisfied; each test below spoils exactly one. */
  private def shouldRepost(
    messageCount: Int = 5,
    editCount: Int = 5,
    allIdsKnown: Boolean = true,
    queueDepth: Int = 400,
    msSinceLastRepost: Long = fifteenMinutes
  ): Boolean = policy.shouldRepost(messageCount, editCount, allIdsKnown, queueDepth, msSinceLastRepost)

  test("a healthy lane never reposts, however dirty the list") {
    policy.cooldownMs(0) shouldBe None
    policy.cooldownMs(399) shouldBe None
    shouldRepost(queueDepth = 399) shouldBe false
  }

  test("the cooldown tightens as the lane worsens") {
    policy.cooldownMs(400) shouldBe Some(fifteenMinutes)
    policy.cooldownMs(799) shouldBe Some(fifteenMinutes)
    policy.cooldownMs(800) shouldBe Some(fiveMinutes)
    policy.cooldownMs(5000) shouldBe Some(fiveMinutes)
  }

  test("tiers given out of order still read deepest-first") {
    OnlineListRepostPolicy(List(800 -> fiveMinutes, 400 -> fifteenMinutes)).cooldownMs(800) shouldBe Some(fiveMinutes)
  }

  test("a congested lane with a fully dirty list reposts") {
    shouldRepost() shouldBe true
  }

  test("nothing is reposted before its cooldown has run") {
    shouldRepost(msSinceLastRepost = fifteenMinutes - 1) shouldBe false
    shouldRepost(msSinceLastRepost = fifteenMinutes) shouldBe true
  }

  test("a deeper lane reposts on the shorter cooldown the shallower one would refuse") {
    shouldRepost(queueDepth = 400, msSinceLastRepost = fiveMinutes) shouldBe false
    shouldRepost(queueDepth = 800, msSinceLastRepost = fiveMinutes) shouldBe true
  }

  test("a short list is not worth the swap") {
    shouldRepost(messageCount = 2, editCount = 2) shouldBe false
    shouldRepost(messageCount = 3, editCount = 3) shouldBe true
  }

  test("a list that is only partly dirty is edited, not reposted") {
    // 80% of 5 is 4; of 3 it is 3, since a repost rewrites whole messages.
    shouldRepost(messageCount = 5, editCount = 3) shouldBe false
    shouldRepost(messageCount = 5, editCount = 4) shouldBe true
    shouldRepost(messageCount = 3, editCount = 2) shouldBe false
  }

  test("a send still in flight has no id to delete by, so nothing is reposted") {
    shouldRepost(allIdsKnown = false) shouldBe false
  }

  test("the disabled policy reposts nothing at any depth") {
    OnlineListRepostPolicy.disabled.cooldownMs(100000) shouldBe None
    OnlineListRepostPolicy.disabled.shouldRepost(10, 10, allIdsKnown = true, 100000, Long.MaxValue) shouldBe false
  }

  test("tiered is the disabled policy when reposting is switched off") {
    OnlineListRepostPolicy.tiered(enabled = false, 400 -> fifteenMinutes) shouldBe OnlineListRepostPolicy.disabled
    OnlineListRepostPolicy.tiered(enabled = true, 400 -> fifteenMinutes).cooldownMs(400) shouldBe Some(fifteenMinutes)
  }
}
