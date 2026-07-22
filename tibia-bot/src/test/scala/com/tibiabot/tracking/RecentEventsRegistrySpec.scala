package com.tibiabot.tracking

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class RecentEventsRegistrySpec extends AnyFunSuite with Matchers {

  test("forWorld creates a log on first access and returns the same instance thereafter") {
    val registry = new RecentEventsRegistry
    val first = registry.forWorld("Antica")
    first.record("death", "a")
    val second = registry.forWorld("Antica")
    second.recent() should have size 1 // same underlying instance
  }

  test("different worlds get independent logs — a busy world can't push out a quiet world's events") {
    val registry = new RecentEventsRegistry
    registry.forWorld("Antica").record("death", "a")
    registry.forWorld("Secura").record("level-up", "b")

    registry.forWorld("Antica").recent().map(_.text) shouldBe List("a")
    registry.forWorld("Secura").recent().map(_.text) shouldBe List("b")
  }
}
