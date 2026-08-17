package com.tibiabot.tracking

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class BountyPresenceSpec extends AnyFunSuite with Matchers {

  private val watched = Set("bubble")

  test("the first pass only seeds — a restart must not read as everyone arriving") {
    val presence = new BountyPresence
    presence.logins(watched, Map("bubble" -> 4000L)) shouldBe empty
  }

  test("a target absent then present has logged in") {
    val presence = new BountyPresence
    presence.logins(watched, Map.empty) shouldBe empty
    presence.logins(watched, Map("bubble" -> 0L)) shouldBe Set("bubble")
  }

  test("staying online is not a second login, however long they stay") {
    val presence = new BountyPresence
    presence.logins(watched, Map.empty)
    presence.logins(watched, Map("bubble" -> 0L)) shouldBe Set("bubble")
    presence.logins(watched, Map("bubble" -> 300L)) shouldBe empty
    presence.logins(watched, Map("bubble" -> 7200L)) shouldBe empty
  }

  test("a relog inside one pass shows up as the duration going backwards") {
    val presence = new BountyPresence
    presence.logins(watched, Map("bubble" -> 5000L))
    presence.logins(watched, Map("bubble" -> 12L)) shouldBe Set("bubble")
  }

  test("logging off and back on again is a login") {
    val presence = new BountyPresence
    presence.logins(watched, Map("bubble" -> 5000L))
    presence.logins(watched, Map.empty) shouldBe empty
    presence.logins(watched, Map("bubble" -> 0L)) shouldBe Set("bubble")
  }

  test("a newly watched character already online is seeded, not announced") {
    val presence = new BountyPresence
    presence.logins(Set.empty, Map("bubble" -> 900L)) shouldBe empty
    presence.logins(watched, Map("bubble" -> 960L)) shouldBe empty
    presence.logins(watched, Map("bubble" -> 1020L)) shouldBe empty
  }

  test("dropping a bounty forgets it, so re-adding it starts over rather than firing") {
    val presence = new BountyPresence
    presence.logins(watched, Map.empty)
    presence.logins(watched, Map("bubble" -> 0L)) shouldBe Set("bubble")
    presence.logins(Set.empty, Map("bubble" -> 60L)) shouldBe empty
    // Re-added while still online: the first pass seeds again.
    presence.logins(watched, Map("bubble" -> 120L)) shouldBe empty
    presence.logins(watched, Map("bubble" -> 180L)) shouldBe empty
  }

  test("several targets are judged independently") {
    val presence = new BountyPresence
    val targets = Set("bubble", "eternal oblivion")
    presence.logins(targets, Map("bubble" -> 500L))
    presence.logins(targets, Map("bubble" -> 560L, "eternal oblivion" -> 0L)) shouldBe Set("eternal oblivion")
  }
}
