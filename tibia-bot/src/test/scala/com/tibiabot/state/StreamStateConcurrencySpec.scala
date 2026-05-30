package com.tibiabot.state

import com.tibiabot.domain.{PlayerCache, Players}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

/**
 * Simulates many world-streams + command threads mutating the shared state at
 * once. These assertions only hold because every read-modify-write goes through
 * the synchronized modify* methods; against a plain `var Map` they fail with lost
 * updates (which is the bug the hardening fixed).
 */
class StreamStateConcurrencySpec extends AnyFunSuite with Matchers {

  private val when = ZonedDateTime.parse("2026-01-01T00:00:00Z")
  private def cache(name: String) = PlayerCache(name, Nil, "guild", when)
  private def player(name: String) = Players(name, "false", "test", "0")

  /** Run `body(threadIndex)` on `threads` threads at once, started together. */
  private def race(threads: Int)(body: Int => Unit): Unit = {
    val pool = Executors.newFixedThreadPool(threads)
    val start = new CountDownLatch(1)
    val done = new CountDownLatch(threads)
    (0 until threads).foreach { t =>
      pool.submit(new Runnable {
        def run(): Unit = { start.await(); try body(t) finally done.countDown() }
      })
    }
    start.countDown() // release all threads simultaneously for maximum contention
    done.await(30, TimeUnit.SECONDS) shouldBe true
    pool.shutdown()
  }

  test("concurrent inserts for distinct guilds never lose an entry") {
    val state = new StreamState
    val threads = 16
    val perThread = 250 // 16 * 250 = 4000 distinct guild keys
    race(threads) { t =>
      (0 until perThread).foreach { k =>
        state.modifyActivityData(_ + (s"guild-$t-$k" -> List(cache(s"c$t$k"))))
      }
    }
    state.activityData.size shouldBe threads * perThread
  }

  test("concurrent appends to the SAME guild keep every entry (atomic read-modify-write)") {
    val state = new StreamState
    val threads = 16
    val perThread = 250
    val guildId = "shared-guild"
    race(threads) { t =>
      (0 until perThread).foreach { i =>
        state.modifyHuntedPlayersData { m =>
          m.updated(guildId, player(s"$t-$i") :: m.getOrElse(guildId, Nil))
        }
      }
    }
    state.huntedPlayersData(guildId).size shouldBe threads * perThread
    state.huntedPlayersData(guildId).map(_.name).toSet.size shouldBe threads * perThread // no duplicates/drops
  }

  test("interleaved writes across all three maps stay consistent") {
    val state = new StreamState
    val threads = 12
    val perThread = 200
    race(threads) { t =>
      (0 until perThread).foreach { i =>
        state.modifyActivityData(_ + (s"a-$t-$i" -> List(cache("x"))))
        state.modifyHuntedPlayersData(_ + (s"h-$t-$i" -> List(player("x"))))
        state.modifyAlliedPlayersData(_ + (s"y-$t-$i" -> List(player("x"))))
      }
    }
    state.activityData.size shouldBe threads * perThread
    state.huntedPlayersData.size shouldBe threads * perThread
    state.alliedPlayersData.size shouldBe threads * perThread
  }
}
