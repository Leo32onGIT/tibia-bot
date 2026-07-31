package com.tibiabot.persistence

import com.tibiabot.domain.PatreonGrace
import com.tibiabot.persistence.jdbc.JdbcPatreonGraceRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** Round-trips PatreonGraceRepository against a real Postgres (cancels without PGHOST). */
class PatreonGraceRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val started = ZonedDateTime.parse("2026-08-01T10:00:00Z")

  test("beginGrace round-trips a timer, and a world with none reads back nothing") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcPatreonGraceRepository(provider)
    clearGrace(provider)

    repo.allGrace() shouldBe empty
    repo.beginGrace("guild-1", "Antica", started)
    repo.allGrace() shouldBe List(PatreonGrace("guild-1", "Antica", started, notified = false))
  }

  test("beginGrace again leaves the original start time alone — the deadline can't drift forwards") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcPatreonGraceRepository(provider)
    clearGrace(provider)

    repo.beginGrace("guild-1", "Antica", started)
    repo.beginGrace("guild-1", "Antica", started.plusDays(5))

    repo.allGrace() shouldBe List(PatreonGrace("guild-1", "Antica", started, notified = false))
  }

  test("beginGrace again does not clear an already-sent notice") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcPatreonGraceRepository(provider)
    clearGrace(provider)

    repo.beginGrace("guild-1", "Antica", started)
    repo.markNotified("guild-1", "Antica")
    repo.beginGrace("guild-1", "Antica", started.plusDays(1))

    repo.allGrace().map(_.notified) shouldBe List(true)
  }

  test("clearGrace removes only the world it names, and is a no-op for one with no timer") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcPatreonGraceRepository(provider)
    clearGrace(provider)

    repo.beginGrace("guild-1", "Antica", started)
    repo.beginGrace("guild-1", "Secura", started)
    repo.clearGrace("guild-1", "Antica")
    repo.clearGrace("guild-1", "Nowhere")

    repo.allGrace().map(_.world) shouldBe List("Secura")
  }

  test("the same world in two guilds keeps two independent timers") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcPatreonGraceRepository(provider)
    clearGrace(provider)

    repo.beginGrace("guild-1", "Antica", started)
    repo.beginGrace("guild-2", "Antica", started.plusDays(2))
    repo.markNotified("guild-1", "Antica")

    repo.allGrace().toSet shouldBe Set(
      PatreonGrace("guild-1", "Antica", started, notified = true),
      PatreonGrace("guild-2", "Antica", started.plusDays(2), notified = false)
    )
  }

  private def clearGrace(provider: JdbcConnectionProvider): Unit = {
    val conn = provider.cache()
    try {
      val exists = conn.createStatement()
        .executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'patreon_grace'")
      if (exists.next()) conn.createStatement().executeUpdate("DELETE FROM patreon_grace")
    } finally conn.close()
  }
}
