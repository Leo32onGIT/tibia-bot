package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcWorldConfigRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Round-trips WorldConfigRepository against a real Postgres (cancels without PGHOST). */
class WorldConfigRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val guildId = "555000555000555000" // numeric-only fake guild id
  private val world = "Itestworld"

  test("worlds round-trip: create, retrieve, list and remove") {
    val provider = pgOrCancel()
    ensureGuildDatabase(provider, guildId)
    ensureWorldsTable(provider)
    val repo = new JdbcWorldConfigRepository(provider, mergedWorlds = List.empty)

    repo.removeWorld(guildId, world) // clean slate
    repo.createWorld(guildId, world, "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "99")

    val cfg = repo.retrieveWorld(guildId, world)
    cfg("name") shouldBe "Itestworld"
    cfg("allies_channel") shouldBe "1"
    cfg("activity_channel") shouldBe "99"
    cfg("fullbless_level") shouldBe "250" // default applied by createWorld

    repo.listWorlds(guildId).map(_.name) should contain("Itestworld")

    // string + int field updates
    repo.updateWorldString(guildId, "Itestworld", "detect_hunteds", "off")
    repo.updateWorldInt(guildId, "Itestworld", "fullbless_level", 400)
    val updated = repo.retrieveWorld(guildId, world)
    updated("detect_hunteds") shouldBe "off"
    updated("fullbless_level") shouldBe "400"

    repo.removeWorld(guildId, world)
    repo.listWorlds(guildId).map(_.name) should not contain "Itestworld"
  }

  private def ensureGuildDatabase(provider: JdbcConnectionProvider, guildId: String): Unit = {
    val conn = provider.admin()
    try {
      val rs = conn.createStatement().executeQuery(s"SELECT datname FROM pg_database WHERE datname = '_$guildId'")
      if (!rs.next()) conn.createStatement().executeUpdate(s"CREATE DATABASE _$guildId")
    } finally conn.close()
  }

  private def ensureWorldsTable(provider: JdbcConnectionProvider): Unit = {
    val conn = provider.guild(guildId)
    try {
      conn.createStatement().execute(
        """CREATE TABLE IF NOT EXISTS worlds (
          |name VARCHAR(255) NOT NULL,
          |allies_channel VARCHAR(255) NOT NULL,
          |enemies_channel VARCHAR(255) NOT NULL,
          |neutrals_channel VARCHAR(255) NOT NULL,
          |levels_channel VARCHAR(255) NOT NULL,
          |deaths_channel VARCHAR(255) NOT NULL,
          |category VARCHAR(255) NOT NULL,
          |fullbless_role VARCHAR(255) NOT NULL,
          |nemesis_role VARCHAR(255) NOT NULL,
          |allypk_role VARCHAR(255) NOT NULL,
          |masslog_role VARCHAR(255) NOT NULL,
          |fullbless_channel VARCHAR(255) NOT NULL,
          |nemesis_channel VARCHAR(255) NOT NULL,
          |fullbless_level INT NOT NULL,
          |show_neutral_levels VARCHAR(255) NOT NULL,
          |show_neutral_deaths VARCHAR(255) NOT NULL,
          |show_allies_levels VARCHAR(255) NOT NULL,
          |show_allies_deaths VARCHAR(255) NOT NULL,
          |show_enemies_levels VARCHAR(255) NOT NULL,
          |show_enemies_deaths VARCHAR(255) NOT NULL,
          |detect_hunteds VARCHAR(255) NOT NULL,
          |levels_min INT NOT NULL,
          |deaths_min INT NOT NULL,
          |exiva_list VARCHAR(255) NOT NULL,
          |activity_channel VARCHAR(255) NOT NULL,
          |online_combined VARCHAR(255) NOT NULL,
          |PRIMARY KEY (name)
          |)""".stripMargin)
    } finally conn.close()
  }
}
