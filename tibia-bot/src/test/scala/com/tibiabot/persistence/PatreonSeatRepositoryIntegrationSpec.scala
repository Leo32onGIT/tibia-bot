package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcPatreonSeatRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** Round-trips PatreonSeatRepository against a real Postgres (cancels without PGHOST). */
class PatreonSeatRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val created = ZonedDateTime.parse("2026-05-30T10:00:00Z")

  test("patreon_seats: assign, retrieve by user/guild-world, and release") {
    val provider = pgOrCancel()
    ensureCacheDatabase(provider)
    val repo = new JdbcPatreonSeatRepository(provider)
    clearSeats(provider)

    repo.assignSeat("user-1", "User One", "guild-1", "Antica", created)
    repo.assignSeat("user-1", "User One", "guild-2", "Secura", created)

    repo.seatsForUser("user-1").map(_.world).toSet shouldBe Set("Antica", "Secura")
    repo.seatFor("guild-1", "Antica").map(_.userId) shouldBe Some("user-1")
    repo.seatFor("guild-1", "Antica").map(_.userName) shouldBe Some("User One")
    repo.seatFor("guild-3", "Nowhere") shouldBe None

    // reassigning the same (guild, world) to the same user is idempotent, not a second row
    repo.assignSeat("user-1", "User One", "guild-1", "Antica", created)
    repo.seatsForUser("user-1") should have size 2

    repo.releaseSeat("guild-1", "Antica")
    repo.seatFor("guild-1", "Antica") shouldBe None
    repo.seatsForUser("user-1").map(_.world) shouldBe List("Secura")
  }

  test("allSeats returns every seat regardless of owner") {
    val provider = pgOrCancel()
    ensureCacheDatabase(provider)
    val repo = new JdbcPatreonSeatRepository(provider)
    clearSeats(provider)

    repo.assignSeat("user-1", "User One", "guild-1", "Antica", created)
    repo.assignSeat("user-2", "User Two", "guild-2", "Secura", created)

    repo.allSeats().map(s => s.userId -> s.world).toSet shouldBe Set("user-1" -> "Antica", "user-2" -> "Secura")
  }

  private def clearSeats(provider: JdbcConnectionProvider): Unit = {
    val conn = provider.cache()
    try {
      val exists = conn.createStatement()
        .executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'patreon_seats'")
      if (exists.next()) conn.createStatement().executeUpdate("DELETE FROM patreon_seats")
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
