package com.tibiabot.tracking

import org.scalatest.funsuite.AnyFunSuite

import java.time.ZonedDateTime

/** Lightweight benchmarks (not JMH) to record BASELINE cost of the hot paths
 *  before optimization, and to demonstrate the speedup after. Timings are
 *  printed, not asserted (wall-clock assertions are flaky); the asserts only
 *  guard that the work actually happened. Run with:  sbt "testOnly *TrackingBenchmarkSpec"
 */
class TrackingBenchmarkSpec extends AnyFunSuite {

  private val t0 = ZonedDateTime.parse("2026-05-30T10:00:00Z")

  private def time[A](label: String)(body: => A): A = {
    val start = System.nanoTime()
    val r = body
    val ms = (System.nanoTime() - start) / 1e6
    println(f"[bench] $label%-52s ${ms}%9.2f ms")
    r
  }

  test("OnlineTracker: full-cycle merge over a large online list") {
    val N = 2000          // ~ a busy world's online count
    val cycles = 20       // ~20 minutes of 60s cycles
    val players = (1 to N).map(i => (s"Player$i", 100 + (i % 500), "Knight")).toVector

    val tr = new OnlineTracker
    time(s"merge N=$N x $cycles cycles (current: O(n^2)/cycle)") {
      var c = 0
      while (c < cycles) {
        tr.updateFromOnline(players, t0.plusSeconds(c.toLong * 60))
        c += 1
      }
    }
    assert(tr.size == N)
  }

  test("OnlineTracker: per-character find storm (one find per online char)") {
    val N = 2000
    val players = (1 to N).map(i => (s"Player$i", 100, "Knight")).toVector
    val tr = new OnlineTracker
    tr.updateFromOnline(players, t0)

    time(s"$N finds over N=$N state (current: O(n) each)") {
      var hits = 0
      players.foreach { case (name, _, _) => if (tr.find(name).isDefined) hits += 1 }
      assert(hits == N)
    }
  }

  test("LevelTracker: shouldRecord scan over a 25h backlog") {
    val K = 20000         // level-ups retained over 25h on a busy world
    val M = 5000          // shouldRecord calls in a cycle
    val login = ZonedDateTime.parse("2026-05-30T08:00:00Z")
    val lt = new LevelTracker
    lt.load((1 to K).map(i => LevelRecord(s"P${i % 4000}", 100 + (i % 800), "Knight", login, t0)))

    time(s"$M shouldRecord over K=$K backlog (current: O(k) each)") {
      var trues = 0
      var i = 0
      while (i < M) {
        if (lt.shouldRecord(s"P${i % 4000}", 2000 + i, login)) trues += 1
        i += 1
      }
      assert(trues == M) // all are brand-new (name,level) pairs
    }
  }
}
