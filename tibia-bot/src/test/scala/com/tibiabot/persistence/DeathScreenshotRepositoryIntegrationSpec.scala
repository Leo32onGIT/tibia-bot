package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcDeathScreenshotRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Round-trips DeathScreenshotRepository against a real Postgres (cancels without PGHOST). */
class DeathScreenshotRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val guildId = "999000999000999000" // numeric-only fake guild id
  private val world = "Antica"
  private val char = "Test Char"
  private val dt = 1234L
  private val url = "https://example.com/shot1.png"

  test("store / get / deleteIfPermitted round-trip on death_screenshots") {
    val provider = pgOrCancel()
    ensureGuildDatabase(provider, guildId)
    val repo = new JdbcDeathScreenshotRepository(provider)

    repo.store(guildId, world, char, dt, url, "user1", "User One", "msg1")

    val stored = repo.get(guildId, world, char, dt)
    stored.map(_.screenshotUrl) should contain(url)
    stored.find(_.screenshotUrl == url).map(_.addedBy) shouldBe Some("user1")

    // permission predicate denies -> nothing deleted
    repo.deleteIfPermitted(guildId, char, dt, url)(_ => false) shouldBe false
    repo.get(guildId, world, char, dt) should not be empty

    // permission predicate allows -> deleted
    repo.deleteIfPermitted(guildId, char, dt, url)(_ => true) shouldBe true
    repo.get(guildId, world, char, dt) shouldBe empty
  }

  private def ensureGuildDatabase(provider: JdbcConnectionProvider, guildId: String): Unit = {
    val conn = provider.admin()
    try {
      val rs = conn.createStatement().executeQuery(s"SELECT datname FROM pg_database WHERE datname = '_$guildId'")
      if (!rs.next()) conn.createStatement().executeUpdate(s"CREATE DATABASE _$guildId")
    } finally conn.close()
  }
}
