package com.tibiabot.respawn

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class RespawnOwnershipSpec extends AnyFunSuite with Matchers {

  private val blue = "1193678088165404807"
  private val red = "1438767287447584893"

  private def ownership(selfUserId: String) = new RespawnOwnership(selfUserId)

  test("the bot that created the board owns the guild's sweep") {
    ownership(blue).ownedBy(Some(blue), blue) shouldBe true
  }

  test("another bot sharing the guild does not sweep it") {
    // The reported bug: a spawn claimed through Blue was answered by Red,
    // because Red swept the same shared database and won the race.
    ownership(red).ownedBy(Some(blue), red) shouldBe false
  }

  test("two identities never both own the same guild") {
    val owner = Some(blue)
    List(blue, red).count(self => ownership(self).ownedBy(owner, self)) shouldBe 1
  }

  test("an unidentifiable board leaves the guild running rather than going silent") {
    // Nothing could say who owns it — a deleted or deeply archived board post.
    // Stopping a working guild's hunts from ever ending is worse than the
    // duplicate nudge this guards against, so it stays as it was before.
    ownership(blue).ownedBy(None, blue) shouldBe true
    ownership(red).ownedBy(None, red) shouldBe true
  }
}
