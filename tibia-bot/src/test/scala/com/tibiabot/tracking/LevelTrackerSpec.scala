package com.tibiabot.tracking

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** Characterization tests for the level-up dedup gate (TibiaBot 625-659).
 *  These pin the exact "post this level-up or not?" decision and must stay
 *  green after recentLevels is optimized to a keyed Map. */
class LevelTrackerSpec extends AnyFunSuite with Matchers {

  private val login1 = ZonedDateTime.parse("2026-05-30T08:00:00Z")
  private val login2 = ZonedDateTime.parse("2026-05-30T20:00:00Z") // a later session
  private val now    = ZonedDateTime.parse("2026-05-30T21:00:00Z")

  private def rec(name: String, level: Int, lastLogin: ZonedDateTime, time: ZonedDateTime = now) =
    LevelRecord(name, level, "Knight", lastLogin, time)

  test("first time reaching a level: should record") {
    val lt = new LevelTracker
    lt.shouldRecord("Hero", 100, login1) shouldBe true
  }

  test("same level, same login session, already recorded: suppressed (dedup)") {
    val lt = new LevelTracker
    lt.record(rec("Hero", 100, login1))
    lt.shouldRecord("Hero", 100, login1) shouldBe false
  }

  test("same level reached in a LATER login session: should record again") {
    val lt = new LevelTracker
    lt.record(rec("Hero", 100, login1))
    lt.shouldRecord("Hero", 100, login2) shouldBe true
  }

  test("a recorded later session suppresses an earlier sheet login") {
    val lt = new LevelTracker
    lt.record(rec("Hero", 100, login2))
    lt.shouldRecord("Hero", 100, login1) shouldBe false
  }

  test("different level is independent") {
    val lt = new LevelTracker
    lt.record(rec("Hero", 100, login1))
    lt.shouldRecord("Hero", 101, login1) shouldBe true
  }

  test("different name is independent") {
    val lt = new LevelTracker
    lt.record(rec("Hero", 100, login1))
    lt.shouldRecord("Other", 100, login1) shouldBe true
  }

  test("multiple records for the same (name, level): newest lastLogin wins the decision") {
    val lt = new LevelTracker
    lt.record(rec("Hero", 100, login1))
    lt.record(rec("Hero", 100, login2)) // baseline Set keeps both
    // there exists a record with lastLogin >= login1, so suppressed
    lt.shouldRecord("Hero", 100, login1) shouldBe false
    // nothing newer than login2, so a strictly-later session would record
    lt.shouldRecord("Hero", 100, login2.plusHours(1)) shouldBe true
  }

  test("prune drops records older than the expiry window by recorded time") {
    val lt = new LevelTracker
    lt.record(rec("Old", 10, login1, time = now.minusHours(30)))
    lt.record(rec("Fresh", 20, login1, time = now.minusMinutes(5)))
    lt.size shouldBe 2

    lt.prune(now, 25 * 60 * 60) // 25h, matching recentLevelExpiry
    lt.snapshot.map(_.name) shouldBe Set("Fresh")
  }
}
