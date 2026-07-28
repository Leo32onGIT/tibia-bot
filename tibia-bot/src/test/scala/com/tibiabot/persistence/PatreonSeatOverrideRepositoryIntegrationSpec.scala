package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcPatreonSeatOverrideRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** Round-trips PatreonSeatOverrideRepository against a real Postgres (cancels without PGHOST). */
class PatreonSeatOverrideRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val updated = ZonedDateTime.parse("2026-05-30T10:00:00Z")

  test("a user with no override reads back 0") {
    val provider = pgOrCancel()
    ensureCacheDatabase(provider)
    val repo = new JdbcPatreonSeatOverrideRepository(provider)
    clearOverrides(provider)

    repo.extraSeatsFor("user-1") shouldBe 0
  }

  test("setExtraSeats round-trips a positive and a negative adjustment") {
    val provider = pgOrCancel()
    ensureCacheDatabase(provider)
    val repo = new JdbcPatreonSeatOverrideRepository(provider)
    clearOverrides(provider)

    repo.setExtraSeats("user-1", 3, updated)
    repo.extraSeatsFor("user-1") shouldBe 3

    repo.setExtraSeats("user-2", -2, updated)
    repo.extraSeatsFor("user-2") shouldBe -2
  }

  test("setExtraSeats for the same user again replaces the prior value, not a second row") {
    val provider = pgOrCancel()
    ensureCacheDatabase(provider)
    val repo = new JdbcPatreonSeatOverrideRepository(provider)
    clearOverrides(provider)

    repo.setExtraSeats("user-1", 3, updated)
    repo.setExtraSeats("user-1", 5, updated)

    repo.extraSeatsFor("user-1") shouldBe 5
    repo.allExtraSeats() shouldBe Map("user-1" -> 5)
  }

  test("allExtraSeats returns every user with a non-default adjustment") {
    val provider = pgOrCancel()
    ensureCacheDatabase(provider)
    val repo = new JdbcPatreonSeatOverrideRepository(provider)
    clearOverrides(provider)

    repo.setExtraSeats("user-1", 2, updated)
    repo.setExtraSeats("user-2", -1, updated)

    repo.allExtraSeats() shouldBe Map("user-1" -> 2, "user-2" -> -1)
  }

  private def clearOverrides(provider: JdbcConnectionProvider): Unit = {
    val conn = provider.cache()
    try {
      val exists = conn.createStatement()
        .executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'patreon_seat_overrides'")
      if (exists.next()) conn.createStatement().executeUpdate("DELETE FROM patreon_seat_overrides")
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
