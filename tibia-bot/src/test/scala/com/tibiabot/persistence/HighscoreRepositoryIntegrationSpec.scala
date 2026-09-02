package com.tibiabot.persistence

import com.tibiabot.domain.HighscoreEvent
import com.tibiabot.highscores.HighscoreDiff
import com.tibiabot.persistence.jdbc.JdbcHighscoreRepository
import com.tibiabot.tibiadata.response.HighscoreEntry
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, Instant}

/** Round-trips HighscoreRepository against a real Postgres (cancels without PGHOST). */
class HighscoreRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val world = "HighscoreSpecWorld"
  private val category = "swordfighting"
  private val snapshot = Instant.parse("2026-09-02T05:40:00Z")

  private def entry(name: String, value: Long, level: Int = 400) =
    HighscoreEntry(rank = 1, name = name, vocation = "Elite Knight", world = world, level = level, value = value)

  private def freshRepo(): HighscoreRepository = {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcHighscoreRepository(provider)
    repo.removeStale(world, snapshot.plus(Duration.ofDays(3650)))
    repo.removeExpiredEvents(snapshot.plus(Duration.ofDays(3650)))
    repo
  }

  test("scores round-trip, keyed case-insensitively, keeping the displayed casing") {
    val repo = freshRepo()

    repo.upsertAll(world, category, List(entry("Bubble", 115), entry("Xerxess", 114)), snapshot)
    val loaded = repo.load(world, category)

    loaded should have size 2
    loaded.keys should contain allOf ("bubble", "xerxess")
    loaded("bubble").displayName shouldBe "Bubble"
    loaded("bubble").score shouldBe 115L
    loaded("bubble").level shouldBe 400
    loaded("bubble").lastSeen shouldBe snapshot
  }

  test("a second snapshot updates in place rather than adding rows") {
    val repo = freshRepo()
    val later = snapshot.plus(Duration.ofHours(1))

    repo.upsertAll(world, category, List(entry("Bubble", 115)), snapshot)
    // Differing capitalisation from the endpoint must not slip past the key as a
    // second character.
    repo.upsertAll(world, category, List(entry("bubble", 116, level = 401)), later)

    val loaded = repo.load(world, category)
    loaded should have size 1
    loaded("bubble").score shouldBe 116L
    loaded("bubble").level shouldBe 401
    loaded("bubble").displayName shouldBe "bubble"
    loaded("bubble").lastSeen shouldBe later
  }

  test("an unchanged score still moves last_seen, so a later advance is not read as stale") {
    val repo = freshRepo()
    val later = snapshot.plus(Duration.ofDays(5))

    repo.upsertAll(world, category, List(entry("Bubble", 115)), snapshot)
    repo.upsertAll(world, category, List(entry("Bubble", 115)), later)

    // Five days of standing still, then an advance. Had last_seen been written
    // only on change, the baseline would now be stale and the advance silently
    // re-baselined instead of announced.
    val previous = repo.load(world, category)
    HighscoreDiff.classify(previous.get("bubble"), entry("Bubble", 116), later.plus(Duration.ofHours(1)))
      .isAdvance shouldBe true
  }

  test("lists are stored per category, so one does not read another's scores") {
    val repo = freshRepo()

    repo.upsertAll(world, category, List(entry("Bubble", 115)), snapshot)
    repo.upsertAll(world, "axefighting", List(entry("Bubble", 90)), snapshot)

    repo.load(world, category)("bubble").score shouldBe 115L
    repo.load(world, "axefighting")("bubble").score shouldBe 90L
  }

  test("a page-set carrying the same character twice takes the last reading") {
    val repo = freshRepo()

    // tibia.com reshuffling between two page fetches can hand us a name twice.
    // Postgres refuses to update one row twice in a statement, so this would
    // fail the whole batch if the duplicates were not collapsed first.
    repo.upsertAll(world, category, List(entry("Bubble", 115), entry("Bubble", 116)), snapshot)

    val loaded = repo.load(world, category)
    loaded should have size 1
    loaded("bubble").score shouldBe 116L
  }

  test("events file and read back newest first, and prune by age") {
    val repo = freshRepo()
    val older = HighscoreEvent(world, category, "bubble", "Bubble", "Elite Knight", 400, 114, 115, snapshot)
    val newer = older.copy(previousScore = 115, score = 116, observed = snapshot.plus(Duration.ofHours(1)))

    repo.recordEvents(List(older, newer))
    repo.events(world, snapshot).map(_.score) shouldBe List(116, 115)
    repo.events(world, snapshot.plus(Duration.ofMinutes(30))).map(_.score) shouldBe List(116)

    repo.removeExpiredEvents(snapshot.plus(Duration.ofMinutes(30)))
    repo.events(world, snapshot).map(_.score) shouldBe List(116)
  }

  test("stale scores prune by world without touching a world still being tracked") {
    val repo = freshRepo()
    val other = s"${world}Two"
    repo.removeStale(other, snapshot.plus(Duration.ofDays(3650)))

    repo.upsertAll(world, category, List(entry("Bubble", 115)), snapshot)
    repo.upsertAll(other, category, List(entry("Bubble", 115)), snapshot)

    repo.removeStale(world, snapshot.plus(Duration.ofDays(30)))
    repo.load(world, category) shouldBe empty
    repo.load(other, category) should have size 1

    repo.removeStale(other, snapshot.plus(Duration.ofDays(3650)))
  }
}
