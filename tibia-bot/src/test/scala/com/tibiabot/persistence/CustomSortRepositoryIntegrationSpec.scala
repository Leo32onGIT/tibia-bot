package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcCustomSortRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Round-trips CustomSortRepository against a real Postgres (cancels without PGHOST). */
class CustomSortRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val guildId = "666000666000666000" // numeric-only fake guild id

  test("custom sort round-trip: add, read, remove by name/entity and by label") {
    val provider = pgOrCancel()
    ensureGuildDatabase(provider, guildId)
    val repo = new JdbcCustomSortRepository(provider)

    repo.getAll(guildId) // creates the table on first use
    repo.removeByNameEntity(guildId, "guild", "TestGuild") // clean slate
    repo.removeByLabel(guildId, "LabelX")

    repo.add(guildId, "guild", "TestGuild", "MyLabel", ":emoji:")
    val rows = repo.getAll(guildId)
    rows.map(_.name) should contain("TestGuild")
    rows.find(_.name == "TestGuild").map(_.label) shouldBe Some("MyLabel")

    repo.removeByNameEntity(guildId, "guild", "TestGuild")
    repo.getAll(guildId).map(_.name) should not contain "TestGuild"

    repo.add(guildId, "player", "TestPlayer", "LabelX", ":e:")
    repo.removeByLabel(guildId, "LabelX")
    repo.getAll(guildId).map(_.name) should not contain "TestPlayer"
  }

  private def ensureGuildDatabase(provider: JdbcConnectionProvider, guildId: String): Unit = {
    val conn = provider.admin()
    try {
      val rs = conn.createStatement().executeQuery(s"SELECT datname FROM pg_database WHERE datname = '_$guildId'")
      if (!rs.next()) conn.createStatement().executeUpdate(s"CREATE DATABASE _$guildId")
    } finally conn.close()
  }
}
