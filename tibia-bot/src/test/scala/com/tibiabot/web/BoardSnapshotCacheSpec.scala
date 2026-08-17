package com.tibiabot.web

import com.tibiabot.domain.Respawn
import com.tibiabot.respawn.RespawnBoardEntry
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicInteger

class BoardSnapshotCacheSpec extends AnyWordSpec with Matchers {

  private def spawn(code: String) =
    Respawn(1L, code, "Cult Orcs", "Orc Cult Fanatic", "Edron", "", "", "0", Respawn.SourceSeed, "seed")

  private def board(code: String) =
    List(RespawnBoardEntry(spawn(code), None, Nil, Nil, None))

  /** A clock the test moves itself, so nothing here waits on real seconds. */
  private final class Clock(var at: Instant = Instant.parse("2026-08-12T09:00:00Z")) {
    def now(): Instant = at
    def pass(seconds: Long): Unit = at = at.plusSeconds(seconds)
  }

  "a board held for a few seconds" should {

    "answer every reader from one read" in {
      val reads = new AtomicInteger()
      val clock = new Clock()
      val cache = new BoardSnapshotCache(
        read = _ => { reads.incrementAndGet(); board("415") },
        ttl = Duration.ofSeconds(3), now = () => clock.now())

      // Ten tabs watching the same guild, as ten polls landing together.
      (1 to 10).foreach(_ => cache.board("g1") shouldBe board("415"))
      reads.get() shouldBe 1
    }

    "read again once it has gone stale" in {
      val reads = new AtomicInteger()
      val clock = new Clock()
      val cache = new BoardSnapshotCache(
        read = _ => { reads.incrementAndGet(); board("415") },
        ttl = Duration.ofSeconds(3), now = () => clock.now())

      cache.board("g1")
      clock.pass(2)
      cache.board("g1")
      reads.get() shouldBe 1
      clock.pass(2)              // now past the three seconds
      cache.board("g1")
      reads.get() shouldBe 2
    }

    "keep one guild's board out of another's" in {
      val seen = scala.collection.mutable.ListBuffer.empty[String]
      val clock = new Clock()
      val cache = new BoardSnapshotCache(
        read = guildId => { seen += guildId; board(guildId) }, now = () => clock.now())

      cache.board("g1") shouldBe board("g1")
      cache.board("g2") shouldBe board("g2")
      cache.board("g1") shouldBe board("g1")
      seen.toList shouldBe List("g1", "g2")
    }

    "show a write to whoever made it, without waiting out the hold" in {
      var current = board("415")
      val clock = new Clock()
      val cache = new BoardSnapshotCache(read = _ => current, now = () => clock.now())

      cache.board("g1") shouldBe board("415")
      current = board("419")
      // Still the held copy — nobody has said anything changed.
      cache.board("g1") shouldBe board("415")
      // A write says so, and the next read is the truth rather than the wait.
      cache.invalidate("g1")
      cache.board("g1") shouldBe board("419")
    }

    "forget only the guild that changed" in {
      val reads = new AtomicInteger()
      val clock = new Clock()
      val cache = new BoardSnapshotCache(
        read = _ => { reads.incrementAndGet(); board("415") }, now = () => clock.now())

      cache.board("g1"); cache.board("g2")
      reads.get() shouldBe 2
      cache.invalidate("g1")
      cache.board("g2")          // untouched, still held
      reads.get() shouldBe 2
      cache.board("g1")          // forgotten, so read again
      reads.get() shouldBe 3
    }

    "not grow without bound" in {
      val clock = new Clock()
      val cache = new BoardSnapshotCache(
        read = guildId => board(guildId), ttl = Duration.ofSeconds(3),
        maxEntries = 10, now = () => clock.now())

      (1 to 10).foreach(n => cache.board(s"g$n"))
      cache.size shouldBe 10
      // Everything held has expired by now, so the sweep reclaims it rather
      // than the map growing past its bound.
      clock.pass(10)
      cache.board("g99")
      cache.size should be <= 10
    }

    "survive a guild whose board cannot be read, without holding the failure" in {
      val clock = new Clock()
      var fail = true
      val cache = new BoardSnapshotCache(
        read = _ => if (fail) throw new RuntimeException("database away") else board("415"),
        now = () => clock.now())

      // The failure travels to the caller rather than being swallowed into an
      // empty board, which would read as "this guild has no spawns".
      a[RuntimeException] should be thrownBy cache.board("g1")
      fail = false
      cache.board("g1") shouldBe board("415")
    }
  }
}
