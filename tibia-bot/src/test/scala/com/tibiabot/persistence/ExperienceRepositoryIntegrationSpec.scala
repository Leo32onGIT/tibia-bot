package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcExperienceRepository
import com.tibiabot.tibiadata.response.HighscoreEntry
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

/** Round-trips ExperienceRepository against a real Postgres (cancels without PGHOST).
 *
 *  Everything is read back through `dailyGains`, `dailyLosses` and `lossesAmong`,
 *  which are the only three queries production runs against this table. There
 *  used to be a plain `daily` reader used here and nowhere else; reading through
 *  the real ones instead means these tests also cover the self-join that decides
 *  who counts as a mover, which is the part with something to get wrong.
 */
class ExperienceRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val world = "ExperienceSpecWorld"
  private val day = LocalDate.parse("2026-09-01")
  private val before = day.minusDays(1)

  private def entry(name: String, experience: Long, level: Int = 400) =
    HighscoreEntry(rank = 1, name = name, vocation = "Elite Knight", world = world, level = level, value = experience)

  private def freshRepo(): ExperienceRepository = {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcExperienceRepository(provider)
    repo.removeExpiredDaily(day.plusYears(10))
    repo
  }

  /** A day's gain needs the day before it to measure against. */
  private def baseline(repo: ExperienceRepository, name: String, experience: Long, on: LocalDate = before): Unit =
    repo.recordDaily(world, List(entry(name, experience)), on)

  test("the rollup keeps one row per character per save day, last write winning") {
    val repo = freshRepo()
    baseline(repo, "Bubble", 1000L)

    repo.recordDaily(world, List(entry("Bubble", 1400L)), day)
    repo.recordDaily(world, List(entry("Bubble", 1500L, level = 401)), day)

    val movers = repo.dailyGains(world, day, 10)
    movers should have size 1
    // 500, not 900: the second write replaced the first rather than adding to it.
    movers.head.gained shouldBe 500L
    movers.head.experience shouldBe 1500L
    movers.head.level shouldBe 401
    movers.head.previousLevel shouldBe 400
    movers.head.displayName shouldBe "Bubble"
  }

  test("successive save days each measure against the one before") {
    val repo = freshRepo()

    repo.recordDaily(world, List(entry("Bubble", 1000L)), day)
    repo.recordDaily(world, List(entry("Bubble", 2500L)), day.plusDays(1))
    repo.recordDaily(world, List(entry("Bubble", 4000L)), day.plusDays(2))

    repo.dailyGains(world, day.plusDays(1), 10).map(_.gained) shouldBe List(1500L)
    repo.dailyGains(world, day.plusDays(2), 10).map(_.gained) shouldBe List(1500L)
    // The first day has no day before it, so it has no gain at all — entering
    // the board is not a day's experience.
    repo.dailyGains(world, day, 10) shouldBe empty
  }

  test("names are keyed case-insensitively across the join") {
    val repo = freshRepo()

    repo.recordDaily(world, List(entry("Bubble", 1000L)), before)
    repo.recordDaily(world, List(entry("bubble", 1200L)), day)

    // One character, not two, and the differing casing still joins.
    val movers = repo.dailyGains(world, day, 10)
    movers should have size 1
    movers.head.gained shouldBe 200L
  }

  test("the movers are ordered by what they gained, largest first") {
    val repo = freshRepo()
    List(("Bubble", 1000L), ("Arieswar", 1000L), ("Kharsek", 1000L))
      .foreach { case (n, e) => baseline(repo, n, e) }

    repo.recordDaily(world, List(
      entry("Bubble", 1500L), entry("Arieswar", 9000L), entry("Kharsek", 1100L)), day)

    repo.dailyGains(world, day, 10).map(_.displayName) shouldBe List("Arieswar", "Bubble", "Kharsek")
    repo.dailyGains(world, day, 2).map(_.displayName) shouldBe List("Arieswar", "Bubble")
  }

  test("the day's worst loss is the other end of the same ordering") {
    val repo = freshRepo()
    baseline(repo, "Bubble", 1000L)
    baseline(repo, "Unlucky", 9000L)

    repo.recordDaily(world, List(entry("Bubble", 1500L), entry("Unlucky", 4000L)), day)

    repo.dailyLosses(world, day, 5).map(_.displayName) shouldBe List("Unlucky")
    repo.dailyLosses(world, day, 5).map(_.gained) shouldBe List(-5000L)
  }

  test("somebody who ended the day down is never listed under gains") {
    val repo = freshRepo()
    baseline(repo, "Bubble", 1000L)
    baseline(repo, "Unlucky", 9000L)
    baseline(repo, "Idle", 5000L)

    repo.recordDaily(world, List(
      entry("Bubble", 1500L), entry("Unlucky", 4000L), entry("Idle", 5000L)), day)

    // On a quiet world a loser reaches well inside the top ten of an ordering by
    // the delta, and so does somebody who did not move at all. Under a heading
    // that says "top experience gained" either would be a plain untruth, so the
    // query excludes them rather than the caller.
    repo.dailyGains(world, day, 10).map(_.displayName) shouldBe List("Bubble")
  }

  test("a day nobody gained anything on is empty rather than padded") {
    val repo = freshRepo()
    baseline(repo, "Unlucky", 9000L)
    repo.recordDaily(world, List(entry("Unlucky", 4000L)), day)

    repo.dailyGains(world, day, 10) shouldBe Nil
  }

  test("a day nobody ended down has no loss to report") {
    val repo = freshRepo()
    baseline(repo, "Bubble", 1000L)
    repo.recordDaily(world, List(entry("Bubble", 1500L)), day)

    repo.dailyLosses(world, day, 5) shouldBe Nil
  }

  test("losses among a named set ignore everybody else") {
    val repo = freshRepo()
    baseline(repo, "Grimjaw", 9000L)
    baseline(repo, "Bubble", 9000L)

    repo.recordDaily(world, List(entry("Grimjaw", 4000L), entry("Bubble", 1000L)), day)

    // Bubble lost more, and is not on the list being asked about.
    val losses = repo.lossesAmong(world, day, Set("grimjaw"), 5)
    losses.map(_.displayName) shouldBe List("Grimjaw")
    losses.map(_.gained) shouldBe List(-5000L)
  }

  test("the prune drops by age") {
    val repo = freshRepo()

    baseline(repo, "Bubble", 1000L)
    repo.recordDaily(world, List(entry("Bubble", 2000L)), day)

    repo.dailyGains(world, day, 10) should have size 1

    // Dropping the day before takes the baseline with it, so the join finds
    // nothing even though today's row is still there.
    repo.removeExpiredDaily(day)
    repo.dailyGains(world, day, 10) shouldBe empty
  }

  test("a page-set carrying the same character twice takes the last reading") {
    val repo = freshRepo()
    baseline(repo, "Bubble", 1000L)

    repo.recordDaily(world, List(entry("Bubble", 1050L), entry("Bubble", 1100L)), day)

    repo.dailyGains(world, day, 10).map(_.gained) shouldBe List(100L)
  }
}
