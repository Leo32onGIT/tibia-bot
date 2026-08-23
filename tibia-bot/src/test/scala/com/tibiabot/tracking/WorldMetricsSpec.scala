package com.tibiabot.tracking

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class WorldMetricsSpec extends AnyFunSuite with Matchers {

  test("a fresh instance has zero counters and no poll timing") {
    val m = new WorldMetrics
    val snap = m.snapshot()
    snap.population shouldBe 0
    snap.lastPollAt shouldBe None
    snap.nextPollAt shouldBe None
    snap.deaths shouldBe 0
    snap.levels shouldBe 0
    snap.edits shouldBe 0
  }

  test("recordPoll overwrites population, poll timing, battleye and pvp type") {
    val m = new WorldMetrics
    val now = Instant.now()
    val next = now.plusSeconds(60)
    m.recordPoll(150, now, next, battleyeGreen_ = true, pvpType_ = "Open PvP")
    val snap = m.snapshot()
    snap.population shouldBe 150
    snap.lastPollAt shouldBe Some(now)
    snap.nextPollAt shouldBe Some(next)
    snap.battleyeGreen shouldBe true
    snap.pvpType shouldBe "Open PvP"

    // a later poll overwrites, it does not accumulate
    m.recordPoll(140, next, next.plusSeconds(60), battleyeGreen_ = false, pvpType_ = "Optional PvP")
    val snap2 = m.snapshot()
    snap2.population shouldBe 140
    snap2.battleyeGreen shouldBe false
    snap2.pvpType shouldBe "Optional PvP"
  }

  test("increments accumulate independently per counter") {
    val m = new WorldMetrics
    m.incrementDeaths()
    m.incrementDeaths()
    m.incrementLevels()
    m.incrementEdits()
    m.incrementEdits()
    m.incrementEdits()
    val snap = m.snapshot()
    snap.deaths shouldBe 2
    snap.levels shouldBe 1
    snap.edits shouldBe 3
  }

  test("resetCounters zeroes deaths/levels/edits but leaves population and poll timing alone") {
    val m = new WorldMetrics
    val now = Instant.now()
    m.recordPoll(200, now, now.plusSeconds(60), battleyeGreen_ = true, pvpType_ = "Open PvP")
    m.incrementDeaths()
    m.incrementLevels()
    m.incrementEdits()

    m.resetCounters()

    val snap = m.snapshot()
    snap.deaths shouldBe 0
    snap.levels shouldBe 0
    snap.edits shouldBe 0
    snap.population shouldBe 200
    snap.lastPollAt shouldBe Some(now)
  }

  test("death lag averages over detections, and keeps the worst one in the window") {
    val m = new WorldMetrics
    m.recordDeathDetected(100)
    m.recordDeathDetected(300)
    m.recordDeathDetected(200)
    val snap = m.snapshot()
    snap.deathDetections shouldBe 3
    snap.deathLagAvgSeconds shouldBe 200.0
    snap.deathLagMaxSeconds shouldBe 300
  }

  test("death lag is counted per detection, not per post — incrementDeaths does not move it") {
    val m = new WorldMetrics
    m.recordDeathDetected(120)
    // the same death posting to three discords
    m.incrementDeaths(); m.incrementDeaths(); m.incrementDeaths()
    val snap = m.snapshot()
    snap.deaths shouldBe 3
    snap.deathDetections shouldBe 1
    snap.deathLagAvgSeconds shouldBe 120.0
  }

  test("no detections reports a zero average rather than dividing by zero") {
    val snap = new WorldMetrics().snapshot()
    snap.deathDetections shouldBe 0
    snap.deathLagAvgSeconds shouldBe 0.0
    snap.deathLagMaxSeconds shouldBe 0
  }

  test("resetCounters clears the lag window, so a quiet window does not inherit the last one's worst") {
    val m = new WorldMetrics
    m.recordDeathDetected(900)
    m.resetCounters()
    val snap = m.snapshot()
    snap.deathDetections shouldBe 0
    snap.deathLagAvgSeconds shouldBe 0.0
    snap.deathLagMaxSeconds shouldBe 0
  }
}
