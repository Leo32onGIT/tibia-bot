package com.tibiabot.persistence

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.sql.Connection

/** Verifies SchemaInitializer creates the expected databases/tables against a
 *  real Postgres (cancels without PGHOST). */
class SchemaInitializerIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  test("initCache ensures bot_cache with deaths/levels/list/satchel tables") {
    val provider = pgOrCancel()
    new SchemaInitializer(provider).initCache()
    val conn = provider.cache()
    try {
      Seq("deaths", "levels", "list", "satchel")
      .foreach { t =>
        withClue(s"table=$t ") {
          hasTable(conn, t) shouldBe true
        }
      }
    } finally conn.close()
  }

  test("initGuild creates a guild database with all config tables") {
    val provider = pgOrCancel()
    val init = new SchemaInitializer(provider)
    val guildId = "333000333000333000"

    init.initGuild(guildId, "TestGuild")
    init.guildDatabaseExists(guildId) shouldBe true

    val conn = provider.guild(guildId)
    try {
      Seq("discord_info", "hunted_players", "hunted_guilds", "allied_players", "allied_guilds", "worlds")
        .foreach(t => hasTable(conn, t) shouldBe true)
    } finally conn.close()
  }

  private def hasTable(conn: Connection, name: String): Boolean = {
    val rs = conn.createStatement().executeQuery(
      s"SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = '$name'")
    rs.next()
  }
}
