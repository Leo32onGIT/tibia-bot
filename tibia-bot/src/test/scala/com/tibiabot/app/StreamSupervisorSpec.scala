package com.tibiabot.app

import com.tibiabot.domain.Discords
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class StreamSupervisorSpec extends AnyFunSuite with Matchers {

  private class FakeCancellable extends akka.actor.Cancellable {
    var cancelled = false
    def cancel(): Boolean = { cancelled = true; true }
    def isCancelled: Boolean = cancelled
  }

  private def discord(id: String) = Discords(id, "0", "0", "0")

  test("put then get/contains tracks a world's stream") {
    val sup = new StreamSupervisor
    val s = new FakeCancellable
    sup.put("Antica", s, List(discord("g1")))
    sup.contains("Antica") shouldBe true
    sup.get("Antica").map(_.usedBy) shouldBe Some(List(discord("g1")))
    sup.activeWorlds shouldBe Set("Antica")
  }

  test("removeGuild keeps streams still in use and cancels+drops unused ones") {
    val sup = new StreamSupervisor
    val sA = new FakeCancellable
    val sB = new FakeCancellable
    sup.put("Antica", sA, List(discord("g1"), discord("g2")))
    sup.put("Bona", sB, List(discord("g1")))

    sup.removeGuild("g1")

    sup.get("Antica").map(_.usedBy) shouldBe Some(List(discord("g2"))) // g1 dropped, kept
    sA.cancelled shouldBe false
    sup.contains("Bona") shouldBe false                                // last user gone -> removed
    sB.cancelled shouldBe true
  }

  test("removeGuildFromWorld cancels only when the world becomes unused") {
    val sup = new StreamSupervisor
    val sA = new FakeCancellable
    sup.put("Antica", sA, List(discord("g1"), discord("g2")))

    sup.removeGuildFromWorld("Antica", "g1")
    sup.get("Antica").map(_.usedBy) shouldBe Some(List(discord("g2")))
    sA.cancelled shouldBe false

    sup.removeGuildFromWorld("Antica", "g2")
    sup.contains("Antica") shouldBe false
    sA.cancelled shouldBe true
  }

  test("removeGuildFromWorld is a no-op for an unknown world") {
    val sup = new StreamSupervisor
    noException should be thrownBy sup.removeGuildFromWorld("Nowhere", "g1")
  }
}
