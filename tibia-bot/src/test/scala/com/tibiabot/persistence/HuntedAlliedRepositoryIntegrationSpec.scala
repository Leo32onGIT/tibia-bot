package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcHuntedAlliedRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Round-trips HuntedAlliedRepository against a real Postgres (cancels without PGHOST). */
class HuntedAlliedRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val guildId = "777000777000777000" // numeric-only fake guild id

  test("hunted players: add, read, rename and remove") {
    val provider = pgOrCancel()
    ensureGuildDatabase(provider, guildId)
    ensureTable(provider, "hunted_players")
    val repo = new JdbcHuntedAlliedRepository(provider)

    repo.removeHunted(guildId, "player", "Enemy")     // clean slate
    repo.removeHunted(guildId, "player", "EnemyTwo")

    repo.addHunted(guildId, "player", "Enemy", "manual", "added by test", "tester")
    val players = repo.getPlayers(guildId, "hunted_players")
    players.map(_.name) should contain("Enemy")
    players.find(_.name == "Enemy").map(_.reason) shouldBe Some("manual")

    repo.rename(guildId, "hunted", "Enemy", "EnemyTwo")
    repo.getPlayers(guildId, "hunted_players").map(_.name) should
      (contain("EnemyTwo") and not contain "Enemy")

    repo.removeHunted(guildId, "player", "EnemyTwo")
    repo.getPlayers(guildId, "hunted_players").map(_.name) should not contain "EnemyTwo"
  }

  test("hunted guilds: add, read and remove") {
    val provider = pgOrCancel()
    ensureGuildDatabase(provider, guildId)
    ensureTable(provider, "hunted_guilds")
    val repo = new JdbcHuntedAlliedRepository(provider)

    repo.removeHunted(guildId, "guild", "Some Guild")
    repo.addHunted(guildId, "guild", "Some Guild", "manual", "added by test", "tester")
    repo.getGuilds(guildId, "hunted_guilds").map(_.name) should contain("Some Guild")
    repo.removeHunted(guildId, "guild", "Some Guild")
    repo.getGuilds(guildId, "hunted_guilds").map(_.name) should not contain "Some Guild"
  }

  private def ensureGuildDatabase(provider: JdbcConnectionProvider, guildId: String): Unit = {
    val conn = provider.admin()
    try {
      val rs = conn.createStatement().executeQuery(s"SELECT datname FROM pg_database WHERE datname = '_$guildId'")
      if (!rs.next()) conn.createStatement().executeUpdate(s"CREATE DATABASE _$guildId")
    } finally conn.close()
  }

  private def ensureTable(provider: JdbcConnectionProvider, table: String): Unit = {
    val conn = provider.guild(guildId)
    try {
      conn.createStatement().execute(
        s"""CREATE TABLE IF NOT EXISTS $table (
           |name VARCHAR(255) NOT NULL,
           |reason VARCHAR(255) NOT NULL,
           |reason_text VARCHAR(255) NOT NULL,
           |added_by VARCHAR(255) NOT NULL,
           |PRIMARY KEY (name)
           |)""".stripMargin)
    } finally conn.close()
  }
}
