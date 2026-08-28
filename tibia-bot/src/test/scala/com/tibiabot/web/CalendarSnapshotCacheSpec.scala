package com.tibiabot.web

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Duration, ZonedDateTime}
import java.util.concurrent.atomic.AtomicInteger

/** One read of a guild's calendar rows, shared by every panel open on it. */
class CalendarSnapshotCacheSpec extends AnyWordSpec with Matchers {

  private val start = ZonedDateTime.parse("2026-08-12T09:00:00Z")

  /** A clock the test moves itself, so nothing here waits on real seconds. */
  private final class Clock(var at: ZonedDateTime = start) {
    def now(): ZonedDateTime = at
    def pass(seconds: Long): Unit = at = at.plusSeconds(seconds)
  }

  private def rows(from: ZonedDateTime, to: ZonedDateTime) =
    CalendarRows(Map.empty, Map.empty, Map.empty, Map.empty, from, to)

  private def cache(reads: AtomicInteger, clock: Clock,
                    seen: scala.collection.mutable.ListBuffer[(ZonedDateTime, ZonedDateTime)] = null) =
    new CalendarSnapshotCache(
      read = (_, from, to) => {
        reads.incrementAndGet()
        if (seen ne null) seen += ((from, to))
        rows(from, to)
      },
      ttl = Duration.ofSeconds(3), now = () => clock.now())

  // The week the panel opens on, well inside any sane horizon.
  private def thisWeek = (start.minusDays(1), start.plusDays(6))

  "a guild's calendar rows" should {

    "answer every panel from one read" in {
      val reads = new AtomicInteger()
      val clock = new Clock()
      val c = cache(reads, clock)
      val (from, to) = thisWeek
      // Ten spawns opened across a guild, each wanting its own week.
      (1 to 10).foreach(_ => c.rows("g1", from, to))
      reads.get() shouldBe 1
    }

    "read once for a horizon wider than the week it was asked for" in {
      val reads = new AtomicInteger()
      val clock = new Clock()
      val seen = scala.collection.mutable.ListBuffer.empty[(ZonedDateTime, ZonedDateTime)]
      val c = cache(reads, clock, seen)
      val (from, to) = thisWeek
      c.rows("g1", from, to)
      val (readFrom, readTo) = seen.head
      readFrom.isBefore(from) shouldBe true
      readTo.isAfter(to) shouldBe true
    }

    "read again once the rows have gone stale" in {
      val reads = new AtomicInteger()
      val clock = new Clock()
      val c = cache(reads, clock)
      val (from, to) = thisWeek
      c.rows("g1", from, to)
      clock.pass(4)
      c.rows("g1", from, to)
      reads.get() shouldBe 2
    }

    "forget a guild that has just been written to" in {
      val reads = new AtomicInteger()
      val clock = new Clock()
      val c = cache(reads, clock)
      val (from, to) = thisWeek
      c.rows("g1", from, to)
      c.invalidate("g1")
      c.rows("g1", from, to)
      reads.get() shouldBe 2
    }

    "keep one guild's rows out of another's" in {
      val reads = new AtomicInteger()
      val clock = new Clock()
      val c = cache(reads, clock)
      val (from, to) = thisWeek
      c.rows("g1", from, to)
      c.rows("g2", from, to)
      reads.get() shouldBe 2
      c.size shouldBe 2
    }

    // Somebody who has scrolled two months out. Serving them would mean either
    // a horizon wide enough to make every read expensive, or an entry per shape
    // of window — so they get a read of their own and the snapshot stays as it
    // was for everybody else.
    "read directly for a window past the horizon, without disturbing the snapshot" in {
      val reads = new AtomicInteger()
      val clock = new Clock()
      val c = cache(reads, clock)
      val (from, to) = thisWeek
      c.rows("g1", from, to)
      reads.get() shouldBe 1

      val far = c.rows("g1", start.plusDays(120), start.plusDays(127))
      reads.get() shouldBe 2
      far.from shouldBe start.plusDays(120)

      // The ordinary week is still answered from the snapshot that was there.
      c.rows("g1", from, to)
      reads.get() shouldBe 2
    }

    "cover a window inside the horizon at either end" in {
      val reads = new AtomicInteger()
      val clock = new Clock()
      val c = cache(reads, clock)
      c.rows("g1", start.minusDays(1), start.plusDays(6))
      // A week behind and a month ahead both sit inside what was read.
      c.rows("g1", start.minusDays(6), start.plusDays(1))
      c.rows("g1", start.plusDays(30), start.plusDays(37))
      reads.get() shouldBe 1
    }

    "stay bounded" in {
      val reads = new AtomicInteger()
      val clock = new Clock()
      val c = new CalendarSnapshotCache(
        read = (_, from, to) => { reads.incrementAndGet(); rows(from, to) },
        ttl = Duration.ofSeconds(3), maxEntries = 4, now = () => clock.now())
      val (from, to) = thisWeek
      (1 to 12).foreach(g => c.rows(s"g$g", from, to))
      c.size should be <= 4
    }
  }
}
