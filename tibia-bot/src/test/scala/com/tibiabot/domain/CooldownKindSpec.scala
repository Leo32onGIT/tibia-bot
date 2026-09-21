package com.tibiabot.domain

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

class CooldownKindSpec extends AnyFunSuite with Matchers {

  test("each kind keeps its own cooldown length") {
    CooldownKind.Satchel.durationDays shouldBe 30L
    CooldownKind.DragonHead.durationDays shouldBe 14L
  }

  test("expiresAtEpoch is the epoch-second a kind's duration after the start") {
    val start = ZonedDateTime.parse("2026-01-01T00:00:00Z")
    CooldownKind.all.foreach { kind =>
      kind.expiresAtEpoch(start) shouldBe start.plusDays(kind.durationDays).toEpochSecond.toString
    }
  }

  test("expiry is exactly that many days of seconds after the start") {
    val start = ZonedDateTime.parse("2026-05-31T12:00:00Z")
    CooldownKind.all.foreach { kind =>
      kind.expiresAtEpoch(start).toLong - start.toEpochSecond shouldBe kind.durationDays * 24 * 60 * 60
    }
  }

  /** The id is what the `kind` column stores and what a component id carries,
   *  so a row or a button written by an older build has to keep parsing. */
  test("kind ids round-trip and are stable") {
    CooldownKind.all.foreach(k => CooldownKind.parse(k.id) shouldBe Some(k))
    CooldownKind.Satchel.id shouldBe "satchel"
    CooldownKind.DragonHead.id shouldBe "dragonhead"
    CooldownKind.parse("nosuch") shouldBe None
  }
}
