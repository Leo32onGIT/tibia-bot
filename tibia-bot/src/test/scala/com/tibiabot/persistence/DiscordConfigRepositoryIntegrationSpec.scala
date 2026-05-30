package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcDiscordConfigRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** Round-trips DiscordConfigRepository against a real Postgres (cancels without PGHOST). */
class DiscordConfigRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val guildId = "444000444000444000" // numeric-only fake guild id
  private val created = ZonedDateTime.parse("2026-05-30T10:00:00Z")

  test("discord_info round-trip: create, retrieve and conditional update") {
    val provider = pgOrCancel()
    ensureGuildDatabase(provider, guildId)
    ensureDiscordInfoTable(provider)
    val repo = new JdbcDiscordConfigRepository(provider)

    repo.getConfig(guildId) // migrates last_world column
    clearDiscordInfo(provider) // idempotency: a prior run leaves last_world='Antica'

    repo.create(guildId, "MyGuild", "owner1", "cat1", "achan", "bchan", "msg1", created)
    val cfg = repo.getConfig(guildId)
    cfg("guild_name") shouldBe "MyGuild"
    cfg("admin_channel") shouldBe "achan"
    cfg("last_world") shouldBe "0" // default

    // only the non-empty fields are updated
    repo.update(guildId, adminCategory = "", adminChannel = "newadmin", boostedChannel = "",
      boostedMessage = "", lastWorld = "Antica")
    val updated = repo.getConfig(guildId)
    updated("admin_channel") shouldBe "newadmin"
    updated("last_world") shouldBe "Antica"
    updated("admin_category") shouldBe "cat1" // unchanged (empty arg)
  }

  private def clearDiscordInfo(provider: JdbcConnectionProvider): Unit = {
    val conn = provider.guild(guildId)
    try conn.createStatement().executeUpdate("DELETE FROM discord_info") finally conn.close()
  }

  private def ensureGuildDatabase(provider: JdbcConnectionProvider, guildId: String): Unit = {
    val conn = provider.admin()
    try {
      val rs = conn.createStatement().executeQuery(s"SELECT datname FROM pg_database WHERE datname = '_$guildId'")
      if (!rs.next()) conn.createStatement().executeUpdate(s"CREATE DATABASE _$guildId")
    } finally conn.close()
  }

  private def ensureDiscordInfoTable(provider: JdbcConnectionProvider): Unit = {
    val conn = provider.guild(guildId)
    try {
      conn.createStatement().execute(
        """CREATE TABLE IF NOT EXISTS discord_info (
          |guild_name VARCHAR(255) NOT NULL,
          |guild_owner VARCHAR(255) NOT NULL,
          |admin_category VARCHAR(255) NOT NULL,
          |admin_channel VARCHAR(255) NOT NULL,
          |boosted_channel VARCHAR(255) NOT NULL,
          |boosted_messageid VARCHAR(255) NOT NULL,
          |flags VARCHAR(255) NOT NULL,
          |created TIMESTAMP NOT NULL,
          |PRIMARY KEY (guild_name)
          |)""".stripMargin)
    } finally conn.close()
  }
}
