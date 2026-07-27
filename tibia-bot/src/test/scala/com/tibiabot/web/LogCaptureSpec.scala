package com.tibiabot.web

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class LogCaptureSpec extends AnyFunSuite with Matchers {

  test("a fresh log is empty") {
    val log = new LogCapture()
    log.recentErrors() shouldBe empty
    log.recentWarnings() shouldBe empty
  }

  test("errors and warnings are kept separately") {
    val log = new LogCapture()
    log.record("WARN", "com.example.Foo", "a warning")
    log.record("ERROR", "com.example.Bar", "an error")

    log.recentErrors().map(_.message) shouldBe List("an error")
    log.recentWarnings().map(_.message) shouldBe List("a warning")
  }

  test("recentErrors/recentWarnings return most-recent-first within their own level") {
    val log = new LogCapture()
    log.record("ERROR", "com.example.Foo", "first")
    log.record("ERROR", "com.example.Foo", "second")

    log.recentErrors().map(_.message) shouldBe List("second", "first")
  }

  test("recording preserves level, logger and message, count starts at 1") {
    val log = new LogCapture()
    log.record("ERROR", "com.example.Baz", "boom")
    val event = log.recentErrors().head
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

    val recent = log.recentWarnings()
    recent should have size 1
    recent.head.count shouldBe 3
  }

  test("a different message in between does not collapse into the earlier run") {
    val log = new LogCapture()
    log.record("WARN", "com.example.Spammy", "429 again")
    log.record("WARN", "com.example.Other", "unrelated")
    log.record("WARN", "com.example.Spammy", "429 again")

    val recent = log.recentWarnings()
    recent should have size 3
    recent.forall(_.count == 1) shouldBe true
  }

  test("reading does not remove anything") {
    val log = new LogCapture()
    log.record("WARN", "com.example.Qux", "still here")
    log.recentWarnings()
    log.recentWarnings() should have size 1
  }

  test("stays bounded at capacity, dropping the oldest entries first") {
    val log = new LogCapture(capacity = 3)
    // Distinct wording, not just a distinct number — normalize() blanks out
    // digits, so numeric-only messages would all collapse into one repeat
    // instead of testing eviction at all.
    List("alpha", "beta", "gamma", "delta", "epsilon").foreach(msg => log.record("WARN", "com.example.Loop", msg))
    log.recentWarnings().map(_.message) shouldBe List("epsilon", "delta", "gamma")
  }

  test("a burst of warnings cannot evict errors from their own buffer") {
    val log = new LogCapture(capacity = 3)
    log.record("ERROR", "com.example.Rare", "the one that matters")
    List("alpha", "beta", "gamma", "delta", "epsilon").foreach(msg => log.record("WARN", "com.example.Loop", msg))

    log.recentErrors().map(_.message) shouldBe List("the one that matters")
  }

  test("messages that only differ by a quoted value collapse as the same shape") {
    val log = new LogCapture()
    log.record("WARN", "com.example.TibiaDataClient", "Failed to get character: 'Exorcit' with status: '503 Service Unavailable'")
    log.record("WARN", "com.example.TibiaDataClient", "Failed to get character: 'Someone Else' with status: '503 Service Unavailable'")

    val recent = log.recentWarnings()
    recent should have size 1
    recent.head.count shouldBe 2
    // Shows the latest occurrence's actual message, not a generic placeholder
    recent.head.message shouldBe "Failed to get character: 'Someone Else' with status: '503 Service Unavailable'"
  }

  test("messages that only differ by an embedded number collapse as the same shape") {
    val log = new LogCapture()
    log.record("WARN", "com.example.RestRateLimiter", "Encountered 429 on route PATCH, channel_id=1346131642275594344 Retry-After: 5000 ms")
    log.record("WARN", "com.example.RestRateLimiter", "Encountered 429 on route PATCH, channel_id=1429505784471097456 Retry-After: 6000 ms")

    val recent = log.recentWarnings()
    recent should have size 1
    recent.head.count shouldBe 2
  }

  // Regression coverage for TibiaDataClient.requestWithRetry's retry-warning
  // shape, whose per-request URI (including the character/guild/world name)
  // is now quoted at the log call site specifically so it collapses here —
  // previously unquoted, a burst of per-character 503s each got its own row.
  test("TibiaDataClient retry warnings for different characters collapse as the same shape") {
    val log = new LogCapture()
    log.record("WARN", "com.tibiabot.tibiadata.TibiaDataClient",
      "Got 503 Service Unavailable from 'https://api.tibiadata.com/v4/character/Tpan%20Alius' (attempt 2/3), retrying in 500ms")
    log.record("WARN", "com.tibiabot.tibiadata.TibiaDataClient",
      "Got 503 Service Unavailable from 'https://api.tibiadata.com/v4/character/Vuniro%20Invacion' (attempt 2/3), retrying in 500ms")

    val recent = log.recentWarnings()
    recent should have size 1
    recent.head.count shouldBe 2
  }

  // A character name embedding its own apostrophe (e.g. "Sir'Locke") must
  // not prematurely close the quoted-value match on that inner apostrophe.
  test("a quoted value containing its own apostrophe still collapses as the same shape") {
    val log = new LogCapture()
    log.record("WARN", "com.tibiabot.tibiadata.TibiaDataClient",
      "Got 503 Service Unavailable from 'https://api.tibiadata.com/v4/character/Sir'Locke' (attempt 1/3), retrying in 250ms")
    log.record("WARN", "com.tibiabot.tibiadata.TibiaDataClient",
      "Got 503 Service Unavailable from 'https://api.tibiadata.com/v4/character/O'Brien' (attempt 1/3), retrying in 250ms")

    val recent = log.recentWarnings()
    recent should have size 1
    recent.head.count shouldBe 2
  }

  // A genuine closing quote followed directly by punctuation (not a letter)
  // must still be recognized as the delimiter, not swallowed as content.
  test("a quoted value immediately followed by punctuation still closes correctly") {
    val log = new LogCapture()
    log.record("WARN", "com.example.Foo", "Failed for 'Alpha': boom")
    log.record("WARN", "com.example.Foo", "Failed for 'Beta': boom")

    val recent = log.recentWarnings()
    recent should have size 1
    recent.head.count shouldBe 2
  }

  // Regression coverage for TibiaBot.getKillerLevel's exception-warning shape.
  test("TibiaBot killer-fallback timeout warnings for different characters collapse as the same shape") {
    val log = new LogCapture()
    log.record("WARN", "com.tibiabot.TibiaBot", "Exception when calling TibiaData API for 'Baber Kedde': Future timed out after [10 seconds]")
    log.record("WARN", "com.tibiabot.TibiaBot", "Exception when calling TibiaData API for 'Someone Else': Future timed out after [10 seconds]")

    val recent = log.recentWarnings()
    recent should have size 1
    recent.head.count shouldBe 2
  }
}
