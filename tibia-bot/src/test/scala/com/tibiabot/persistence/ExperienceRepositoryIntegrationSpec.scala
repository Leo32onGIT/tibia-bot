package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcExperienceRepository
import com.tibiabot.tibiadata.response.HighscoreEntry
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, Instant, LocalDate}

/** Round-trips ExperienceRepository against a real Postgres (cancels without PGHOST).
 *
 *  The rollup is read back through `dailyGains`, `dailyLosses` and `lossesAmong`,
 *  which are the only three queries production runs against that table. There
 *  used to be a plain `daily` reader used here and nowhere else; reading through
 *  the real ones instead means these tests also cover the self-join that decides
 *  who counts as a mover, which is the part with something to get wrong.
 *
 *  The readings are read back through `gainsBetween` and `lossesBetween`, which
 *  are the queries the refreshed experience embed runs, and through
 *  `readingTimes`, which is how a caller learns the two instants those two will
 *  accept.
 */
class ExperienceRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val world = "ExperienceSpecWorld"
  private val snapshot = Instant.parse("2026-09-02T05:40:00Z")
  /** The reading a rolling window would anchor on: the same minute, a day back. */
  private val dayBefore = snapshot.minus(Duration.ofHours(24))
  private val day = LocalDate.parse("2026-09-01")
  private val before = day.minusDays(1)

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

  test("a snapshot carrying the same character twice keeps the last reading") {
    val repo = freshRepo()
    repo.recordReadings(world, List(entry("Bubble", 1000L)), dayBefore)

    repo.recordReadings(world, List(entry("Bubble", 1400L), entry("Bubble", 1900L)), snapshot)
    // And again at the same instant, which is what a re-run of a snapshot is.
    // ON CONFLICT DO NOTHING, so the stored reading is the first run's.
    repo.recordReadings(world, List(entry("Bubble", 9999L)), snapshot)

    repo.gainsBetween(world, dayBefore, snapshot, 10).map(_.gained) shouldBe List(900L)
  }

  test("the readings a world has are its snapshot instants, in order and without repeats") {
    val repo = freshRepo()
    repo.recordReadings(world, List(entry("Bubble", 1000L), entry("Statler", 5000L)), dayBefore)
    repo.recordReadings(world, List(entry("Bubble", 1500L)), snapshot)
    repo.recordReadings("SomewhereElse", List(entry("Bubble", 1500L)), snapshot.plusSeconds(60))

    // Two rows written at dayBefore come back as one instant, and the other
    // world's reading is not this world's.
    repo.readingTimes(world, dayBefore, snapshot) shouldBe List(dayBefore, snapshot)
    // Both ends are inclusive, and a range that excludes one excludes it.
    repo.readingTimes(world, dayBefore.plusSeconds(1), snapshot) shouldBe List(snapshot)
  }

  test("a window measures every character between the same two readings") {
    val repo = freshRepo()
    repo.recordReadings(world, List(
      entry("Bubble", 1000L, level = 400),
      entry("Statler", 5000L, level = 500),
      entry("Waldorf", 8000L, level = 600)), dayBefore)
    repo.recordReadings(world, List(
      entry("Bubble", 1900L, level = 401),
      entry("Statler", 5000L, level = 500),
      entry("Waldorf", 7000L, level = 598),
      entry("Newcomer", 77L, level = 100)), snapshot)

    val gains = repo.gainsBetween(world, dayBefore, snapshot, 10)

    // Bubble gained. Statler stood still, Waldorf lost, and Newcomer has no
    // reading at the far end — entering the top thousand is not a day's
    // experience, so the join drops them rather than crediting them with 77.
    gains.map(_.name) shouldBe List("bubble")
    gains.head.gained shouldBe 900L
    gains.head.experience shouldBe 1900L
    gains.head.level shouldBe 401
    gains.head.previousLevel shouldBe 400

    val losses = repo.lossesBetween(world, dayBefore, snapshot, 10)
    losses.map(_.name) shouldBe List("waldorf")
    losses.head.gained shouldBe -1000L
    losses.head.previousLevel shouldBe 600
  }

  test("a window orders by the size of the move and stops at the limit") {
    val repo = freshRepo()
    repo.recordReadings(world, List(
      entry("Small", 1000L), entry("Large", 1000L), entry("Middle", 1000L)), dayBefore)
    repo.recordReadings(world, List(
      entry("Small", 1100L), entry("Large", 9000L), entry("Middle", 3000L)), snapshot)

    repo.gainsBetween(world, dayBefore, snapshot, 10).map(_.name) shouldBe List("large", "middle", "small")
    repo.gainsBetween(world, dayBefore, snapshot, 2).map(_.name) shouldBe List("large", "middle")
  }

  test("a window names people from the rollup, and falls back to the key without one") {
    val repo = freshRepo()
    repo.recordReadings(world, List(entry("Bubble", 1000L), entry("NoRollup", 1000L)), dayBefore)
    repo.recordReadings(world, List(entry("Bubble", 1900L), entry("NoRollup", 1900L)), snapshot)
    // Only one of them has ever been folded into a day, which is the state a
    // character is in during the first hours of a save day.
    repo.recordDaily(world, List(entry("Bubble", 1900L)), day)

    val named = repo.gainsBetween(world, dayBefore, snapshot, 10).map(row => row.name -> row.displayName).toMap

    named("bubble") shouldBe "Bubble"
    named("norollup") shouldBe "norollup"
    repo.gainsBetween(world, dayBefore, snapshot, 10)
      .find(_.name == "bubble").map(_.vocation) shouldBe Some("Elite Knight")
  }

  /** The instants are matched on equality, which is the whole reason
   *  `readingTimes` exists — a caller that invents its own "24 hours ago" gets
   *  nothing rather than the nearest reading to it, and would otherwise report
   *  an empty day as a quiet one.
   */
  test("an instant no snapshot was taken at matches nothing") {
    val repo = freshRepo()
    repo.recordReadings(world, List(entry("Bubble", 1000L)), dayBefore)
    repo.recordReadings(world, List(entry("Bubble", 1900L)), snapshot)

    repo.gainsBetween(world, dayBefore.plusSeconds(1), snapshot, 10) shouldBe empty
    repo.gainsBetween(world, dayBefore, snapshot.minusSeconds(1), 10) shouldBe empty
  }
}
