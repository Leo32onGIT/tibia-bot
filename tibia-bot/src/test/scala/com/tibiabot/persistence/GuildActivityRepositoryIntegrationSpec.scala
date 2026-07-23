package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcGuildActivityRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Round-trips GuildActivityRepository against a real Postgres (cancels without PGHOST). */
class GuildActivityRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val now = ZonedDateTime.parse("2026-07-24T00:00:00Z")

  test("recordCommandRun and lastCommandAt round-trip, overwriting on repeated calls") {
    val provider = pgOrCancel()
    ensureCacheDatabase(provider)
    val repo = new JdbcGuildActivityRepository(provider)
    clearActivity(provider)

    repo.lastCommandAt("guild-1") shouldBe None

    repo.recordCommandRun("guild-1", now)
    repo.lastCommandAt("guild-1").map(_.truncatedTo(ChronoUnit.SECONDS)) shouldBe Some(now.truncatedTo(ChronoUnit.SECONDS))

    val later = now.plusHours(1)
    repo.recordCommandRun("guild-1", later)
    repo.lastCommandAt("guild-1").map(_.truncatedTo(ChronoUnit.SECONDS)) shouldBe Some(later.truncatedTo(ChronoUnit.SECONDS))
  }

  test("markWorldlessIfUnset sets it once and returns the original value on later calls") {
    val provider = pgOrCancel()
    ensureCacheDatabase(provider)
    val repo = new JdbcGuildActivityRepository(provider)
    clearActivity(provider)

    val first = repo.markWorldlessIfUnset("guild-2", now)
    first.truncatedTo(ChronoUnit.SECONDS) shouldBe now.truncatedTo(ChronoUnit.SECONDS)

    val second = repo.markWorldlessIfUnset("guild-2", now.plusDays(5))
    second.truncatedTo(ChronoUnit.SECONDS) shouldBe now.truncatedTo(ChronoUnit.SECONDS)
  }

  test("clearWorldless resets worldless_since so a later markWorldlessIfUnset re-stamps it") {
    val provider = pgOrCancel()
    ensureCacheDatabase(provider)
    val repo = new JdbcGuildActivityRepository(provider)
    clearActivity(provider)

    repo.markWorldlessIfUnset("guild-3", now)
    repo.clearWorldless("guild-3")

    val restamped = repo.markWorldlessIfUnset("guild-3", now.plusDays(5))
    restamped.truncatedTo(ChronoUnit.SECONDS) shouldBe now.plusDays(5).truncatedTo(ChronoUnit.SECONDS)
  }

  private def clearActivity(provider: JdbcConnectionProvider): Unit = {
    val conn = provider.cache()
    try {
      val exists = conn.createStatement()
        .executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'guild_activity'")
      if (exists.next()) conn.createStatement().executeUpdate("DELETE FROM guild_activity")
    } finally conn.close()
  }

  private def ensureCacheDatabase(provider: JdbcConnectionProvider): Unit = {
    val conn = provider.admin()
    try {
      val rs = conn.createStatement()
        .executeQuery("SELECT datname FROM pg_database WHERE datname = 'bot_cache'")

      if (!rs.next()) {
        conn.createStatement()
          .executeUpdate("CREATE DATABASE bot_cache")
      }
    } catch {
      case _ : Throwable => //
    } finally {
      conn.close()
    }
  }
}
