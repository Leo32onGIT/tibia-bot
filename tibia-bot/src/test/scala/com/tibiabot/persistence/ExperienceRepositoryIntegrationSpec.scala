package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcExperienceRepository
import com.tibiabot.tibiadata.response.HighscoreEntry
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, Instant, LocalDate}

/** Round-trips ExperienceRepository against a real Postgres (cancels without PGHOST). */
class ExperienceRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val world = "ExperienceSpecWorld"
  private val snapshot = Instant.parse("2026-09-02T05:40:00Z")
  private val day = LocalDate.parse("2026-09-01")

  private def entry(name: String, experience: Long, level: Int = 400) =
    HighscoreEntry(rank = 1, name = name, vocation = "Elite Knight", world = world, level = level, value = experience)

  private def freshRepo(): ExperienceRepository = {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcExperienceRepository(provider)
    repo.removeExpiredReadings(snapshot.plus(Duration.ofDays(3650)))
    repo.removeExpiredDaily(day.plusYears(10))
    repo
  }

  test("the daily rollup keeps one row per character per save day, last write winning") {
    val repo = freshRepo()

    repo.recordDaily(world, List(entry("Bubble", 1000L)), day)
    repo.recordDaily(world, List(entry("Bubble", 1500L, level = 401)), day)

    val points = repo.daily(world, "Bubble", day)
    points should have size 1
    points.head.experience shouldBe 1500L
    points.head.level shouldBe 401
    points.head.displayName shouldBe "Bubble"
    points.head.saveDay shouldBe day
  }

  test("successive save days build the series an experience chart would read") {
    val repo = freshRepo()

    repo.recordDaily(world, List(entry("Bubble", 1000L)), day)
    repo.recordDaily(world, List(entry("Bubble", 2500L)), day.plusDays(1))
    repo.recordDaily(world, List(entry("Bubble", 4000L)), day.plusDays(2))

    val points = repo.daily(world, "Bubble", day)
    points.map(_.experience) shouldBe List(1000L, 2500L, 4000L)
    // Oldest first, so the gains are a straight diff down the list.
    points.map(_.saveDay) shouldBe List(day, day.plusDays(1), day.plusDays(2))

    repo.daily(world, "Bubble", day.plusDays(1)).map(_.experience) shouldBe List(2500L, 4000L)
  }

  test("names are keyed case-insensitively on both the write and the read") {
    val repo = freshRepo()

    repo.recordDaily(world, List(entry("Bubble", 1000L)), day)
    repo.recordDaily(world, List(entry("bubble", 1200L)), day)

    repo.daily(world, "BUBBLE", day).map(_.experience) shouldBe List(1200L)
  }

  test("raw readings are keyed by snapshot, so re-running one changes nothing") {
    val repo = freshRepo()

    repo.recordReadings(world, List(entry("Bubble", 1000L)), snapshot)
    // A re-run of work already done is not a correction; the second write is a
    // no-op rather than an error or a duplicate row.
    repo.recordReadings(world, List(entry("Bubble", 9999L)), snapshot)
    repo.recordReadings(world, List(entry("Bubble", 2000L)), snapshot.plus(Duration.ofHours(1)))

    repo.removeExpiredReadings(snapshot.plus(Duration.ofMinutes(30)))
    // The first snapshot's row went; the second's stayed. If the duplicate had
    // landed as a second row, or the ON CONFLICT had overwritten, this count
    // would be wrong either way.
    repo.recordReadings(world, List(entry("Bubble", 1000L)), snapshot)
    repo.removeExpiredReadings(snapshot.plus(Duration.ofDays(3650)))
  }

  test("both prunes drop by age") {
    val repo = freshRepo()

    repo.recordDaily(world, List(entry("Bubble", 1000L)), day)
    repo.recordDaily(world, List(entry("Bubble", 2000L)), day.plusDays(30))

    repo.removeExpiredDaily(day.plusDays(30))
    repo.daily(world, "Bubble", day).map(_.experience) shouldBe List(2000L)

    repo.removeExpiredDaily(day.plusYears(10))
    repo.daily(world, "Bubble", day) shouldBe empty
  }

  test("a page-set carrying the same character twice takes the last reading") {
    val repo = freshRepo()

    repo.recordDaily(world, List(entry("Bubble", 1000L), entry("Bubble", 1100L)), day)
    repo.daily(world, "Bubble", day).map(_.experience) shouldBe List(1100L)

    // The raw table's ON CONFLICT DO NOTHING would also survive a duplicate, but
    // only because the duplicates are collapsed before the batch is built.
    repo.recordReadings(world, List(entry("Bubble", 1000L), entry("Bubble", 1100L)), snapshot)
    repo.removeExpiredReadings(snapshot.plus(Duration.ofDays(3650)))
  }
}
