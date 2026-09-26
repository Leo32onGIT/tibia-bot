package com.tibiabot.setup

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** When `/repair` leaves a notifications channel alone and when it posts the lot
 *  again: the tracker, then every world's role card, then the boosted message. */
class NotificationsOrderSpec extends AnyFunSuite with Matchers {

  private def inOrder(posted: String*)(cards: String*): Boolean =
    ChannelService.notificationsInOrder(posted.toList, tracker = "t", cards = cards.toList, boosted = "b")

  test("the tracker, the cards and the boosted message at the bottom are in order") {
    inOrder("t", "c1", "c2", "b")("c1", "c2") shouldBe true
  }

  test("the cards may sit in any order among themselves") {
    inOrder("t", "c2", "c1", "b")("c1", "c2") shouldBe true
  }

  test("a missing boosted message leaves the channel in order, since it is posted at the bottom anyway") {
    inOrder("t", "c1")("c1") shouldBe true
  }

  test("a missing card is out of order") {
    inOrder("t", "c1", "b")("c1", "c2") shouldBe false
  }

  test("a missing tracker is out of order") {
    inOrder("c1", "b")("c1") shouldBe false
  }

  test("a card posted below the boosted message is out of order, as /setup leaves a second world's") {
    inOrder("t", "c1", "b", "c2")("c1", "c2") shouldBe false
  }

  test("a tracker posted below the cards is out of order") {
    inOrder("c1", "t", "b")("c1") shouldBe false
  }

  test("anything else of the bot's in the channel is out of order") {
    inOrder("t", "old", "c1", "b")("c1") shouldBe false
  }

  test("a card from before ids were kept is out of order, so it is replaced") {
    inOrder("t", "legacy", "b")("0") shouldBe false
  }

  test("an empty channel is out of order") {
    inOrder()("c1") shouldBe false
  }
}
