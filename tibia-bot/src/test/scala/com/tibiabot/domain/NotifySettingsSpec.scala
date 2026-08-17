package com.tibiabot.domain

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class NotifySettingsSpec extends AnyFunSuite with Matchers {

  test("a mass-log threshold below the floor is refused, not clamped") {
    NotifySettings.parseThreshold("4").isLeft shouldBe true
    NotifySettings.parseThreshold("5") shouldBe Right(5)
    NotifySettings.parseThreshold("100") shouldBe Right(100)
    NotifySettings.parseThreshold("101").isLeft shouldBe true
  }

  test("thresholds tolerate surrounding whitespace but not other text") {
    NotifySettings.parseThreshold("  12 ") shouldBe Right(12)
    NotifySettings.parseThreshold("twelve").isLeft shouldBe true
    NotifySettings.parseThreshold("").isLeft shouldBe true
  }

  test("cooldowns run from one minute to a day") {
    NotifySettings.parseCooldown("0").isLeft shouldBe true
    NotifySettings.parseCooldown("1") shouldBe Right(1)
    NotifySettings.parseCooldown("1440") shouldBe Right(1440)
    NotifySettings.parseCooldown("1441").isLeft shouldBe true
  }

  test("character names keep their spelling but lose stray spacing") {
    NotifySettings.parseCharacter("  Bubble  ") shouldBe Right("Bubble")
    NotifySettings.parseCharacter("Sir  Valiant") shouldBe Right("Sir Valiant")
    NotifySettings.parseCharacter("Mooh'Tah Da-Silva") shouldBe Right("Mooh'Tah Da-Silva")
  }

  test("a name that couldn't be a character is refused rather than stored unfireable") {
    NotifySettings.parseCharacter("").isLeft shouldBe true
    NotifySettings.parseCharacter("Bubble123").isLeft shouldBe true
    NotifySettings.parseCharacter("a" * 30).isLeft shouldBe true
    NotifySettings.parseCharacter("a" * 29) shouldBe Right("a" * 29)
  }
}

class MuteScaleSpec extends AnyFunSuite with Matchers {

  test("only the offered lengths, and the unmute value, come back") {
    MuteScale.parse("60") shouldBe Some(60)
    MuteScale.parse("0") shouldBe Some(MuteScale.Unmute)
    MuteScale.parse("45") shouldBe None
    MuteScale.parse("banana") shouldBe None
  }

  test("the picker runs shortest first") {
    MuteScale.options.map(_._1) shouldBe List(15, 30, 60, 120, 720, 1440)
  }

  test("a length knows what it is called") {
    MuteScale.label(1440) shouldBe "24 hours"
  }
}

class NotifyDecisionSpec extends AnyFunSuite with Matchers {

  private val now = Instant.parse("2026-01-01T12:00:00Z")

  test("a fresh subscription fires") {
    NotifyDecision.due(enabled = true, None, None, 10, now) shouldBe true
  }

  test("switched off beats everything else") {
    NotifyDecision.due(enabled = false, None, None, 10, now) shouldBe false
  }

  test("a running mute silences it; an expired one doesn't") {
    NotifyDecision.due(true, Some(now.plusSeconds(60)), None, 10, now) shouldBe false
    NotifyDecision.due(true, Some(now.minusSeconds(1)), None, 10, now) shouldBe true
  }

  test("the cooldown is measured from the last notification, exclusive of its own end") {
    NotifyDecision.due(true, None, Some(now.minusSeconds(599)), 10, now) shouldBe false
    NotifyDecision.due(true, None, Some(now.minusSeconds(600)), 10, now) shouldBe true
  }
}
