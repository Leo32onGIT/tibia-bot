package com.tibiabot.state

import com.tibiabot.domain.{PlayerCache, Players, Guilds, CustomSort, Discords, Worlds}
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
  private def guildEntry(name: String) = Guilds(name, "false", "test", "0")
  private def sortEntry(name: String) = CustomSort("guild", name, "label", ":x:")
  private def discord(id: String) = Discords(id, "admin", "boosted", "msg")

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

  test("concurrent guild-list appends to the SAME guild keep every entry") {
    val state = new StreamState
    val threads = 16
    val perThread = 250
    val guildId = "shared-guild"
    race(threads) { t =>
      (0 until perThread).foreach { i =>
        state.modifyHuntedGuildsData { m =>
          m.updated(guildId, guildEntry(s"$t-$i") :: m.getOrElse(guildId, Nil))
        }
      }
    }
    state.huntedGuildsData(guildId).size shouldBe threads * perThread
    state.alliedGuildsData shouldBe empty // independent map untouched
  }

  test("concurrent worldsData inserts never lose an entry") {
    val state = new StreamState
    val threads = 16
    val perThread = 250
    race(threads) { t =>
      (0 until perThread).foreach { k =>
        state.modifyWorldsData(_ + (s"guild-$t-$k" -> List.empty[Worlds]))
      }
    }
    state.worldsData.size shouldBe threads * perThread
  }

  test("concurrent discordsData inserts never lose an entry") {
    val state = new StreamState
    val threads = 16
    val perThread = 250
    race(threads) { t =>
      (0 until perThread).foreach { k =>
        state.modifyDiscordsData(_ + (s"world-$t-$k" -> List(discord(s"d$t$k"))))
      }
    }
    state.discordsData.size shouldBe threads * perThread
  }

  test("concurrent customSort inserts never lose an entry") {
    val state = new StreamState
    val threads = 16
    val perThread = 250
    race(threads) { t =>
      (0 until perThread).foreach { k =>
        state.modifyCustomSortData(_ + (s"sort-$t-$k" -> List(sortEntry(s"s$t$k"))))
      }
    }
    state.customSortData.size shouldBe threads * perThread
  }

  test("concurrent activityCommandBlocker inserts never lose an entry") {
    val state = new StreamState
    val threads = 16
    val perThread = 250
    race(threads) { t =>
      (0 until perThread).foreach { k =>
        state.modifyActivityCommandBlocker(_ + (s"guild-$t-$k" -> true))
      }
    }
    state.activityCommandBlocker.size shouldBe threads * perThread
  }

  test("concurrent characterCache inserts never lose an entry") {
    val state = new StreamState
    val threads = 16
    val perThread = 250 // 16 * 250 = 4000 distinct character names
    race(threads) { t =>
      (0 until perThread).foreach { k =>
        state.modifyCharacterCache(_ + (s"char-$t-$k" -> when))
      }
    }
    state.characterCache.size shouldBe threads * perThread
  }

  test("interleaved writes across all ten maps stay consistent") {
    val state = new StreamState
    val threads = 12
    val perThread = 200
    race(threads) { t =>
      (0 until perThread).foreach { i =>
        state.modifyActivityData(_ + (s"a-$t-$i" -> List(cache("x"))))
        state.modifyHuntedPlayersData(_ + (s"hp-$t-$i" -> List(player("x"))))
        state.modifyAlliedPlayersData(_ + (s"ap-$t-$i" -> List(player("x"))))
        state.modifyHuntedGuildsData(_ + (s"hg-$t-$i" -> List(guildEntry("x"))))
        state.modifyAlliedGuildsData(_ + (s"ag-$t-$i" -> List(guildEntry("x"))))
        state.modifyCustomSortData(_ + (s"cs-$t-$i" -> List(sortEntry("x"))))
        state.modifyDiscordsData(_ + (s"d-$t-$i" -> List(discord("x"))))
        state.modifyWorldsData(_ + (s"w-$t-$i" -> List.empty[Worlds]))
        state.modifyActivityCommandBlocker(_ + (s"b-$t-$i" -> true))
        state.modifyCharacterCache(_ + (s"c-$t-$i" -> when))
      }
    }
    val expected = threads * perThread
    state.activityData.size shouldBe expected
    state.huntedPlayersData.size shouldBe expected
    state.alliedPlayersData.size shouldBe expected
    state.huntedGuildsData.size shouldBe expected
    state.alliedGuildsData.size shouldBe expected
    state.customSortData.size shouldBe expected
    state.discordsData.size shouldBe expected
    state.worldsData.size shouldBe expected
    state.activityCommandBlocker.size shouldBe expected
    state.characterCache.size shouldBe expected
  }
}
