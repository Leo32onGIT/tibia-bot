package com.tibiabot.tracking

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class RecentEventsSpec extends AnyFunSuite with Matchers {

  test("a fresh log is empty") {
    new RecentEvents().recent() shouldBe empty
  }

  test("recent() returns most-recent-first") {
    val log = new RecentEvents()
    log.record("death", "a")
    log.record("level-up", "b")
    log.record("rename", "c")

    val texts = log.recent().map(_.text)
    texts shouldBe List("c", "b", "a")
  }

  test("recording preserves tag alongside the text") {
    val log = new RecentEvents()
    log.record("death", "Violent Beams died")
    val event = log.recent().head
    event.tag shouldBe "death"
    event.text shouldBe "Violent Beams died"
  }

  test("stays bounded at capacity, dropping the oldest entries first") {
    val log = new RecentEvents(capacity = 3)
    (1 to 5).foreach(i => log.record("tag", i.toString))
    log.recent().map(_.text) shouldBe List("5", "4", "3")
  }

  test("reading does not remove anything") {
    val log = new RecentEvents()
    log.record("death", "a")
    log.recent()
    log.recent() should have size 1
  }
}
