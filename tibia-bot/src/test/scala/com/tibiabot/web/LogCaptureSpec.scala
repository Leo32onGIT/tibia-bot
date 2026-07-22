package com.tibiabot.web

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class LogCaptureSpec extends AnyFunSuite with Matchers {

  test("a fresh log is empty") {
    new LogCapture().recent() shouldBe empty
  }

  test("recent() returns most-recent-first") {
    val log = new LogCapture()
    log.record("WARN", "com.example.Foo", "first")
    log.record("ERROR", "com.example.Bar", "second")

    log.recent().map(_.message) shouldBe List("second", "first")
  }

  test("recording preserves level, logger and message, count starts at 1") {
    val log = new LogCapture()
    log.record("ERROR", "com.example.Baz", "boom")
    val event = log.recent().head
    event.level shouldBe "ERROR"
    event.logger shouldBe "com.example.Baz"
    event.message shouldBe "boom"
    event.count shouldBe 1
  }

  test("a repeat of the same level/logger/message bumps count instead of adding a new row") {
    val log = new LogCapture()
    log.record("WARN", "com.example.Spammy", "429 again")
    log.record("WARN", "com.example.Spammy", "429 again")
    log.record("WARN", "com.example.Spammy", "429 again")

    val recent = log.recent()
    recent should have size 1
    recent.head.count shouldBe 3
  }

  test("a different message in between does not collapse into the earlier run") {
    val log = new LogCapture()
    log.record("WARN", "com.example.Spammy", "429 again")
    log.record("ERROR", "com.example.Other", "unrelated")
    log.record("WARN", "com.example.Spammy", "429 again")

    val recent = log.recent()
    recent should have size 3
    recent.forall(_.count == 1) shouldBe true
  }

  test("reading does not remove anything") {
    val log = new LogCapture()
    log.record("WARN", "com.example.Qux", "still here")
    log.recent()
    log.recent() should have size 1
  }

  test("stays bounded at capacity, dropping the oldest entries first") {
    val log = new LogCapture(capacity = 3)
    (1 to 5).foreach(i => log.record("WARN", "com.example.Loop", i.toString))
    log.recent().map(_.message) shouldBe List("5", "4", "3")
  }
}
