package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcWorldTransferRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** Round-trips WorldTransferRepository against a real Postgres (cancels without PGHOST). */
class WorldTransferRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val guildId = "888000888000888001" // numeric-only fake guild id
  private val t = ZonedDateTime.parse("2026-05-30T10:00:00Z")

  test("world_transfers round-trip: record, re-record the same character, expire") {
    val provider = pgOrCancel()
    ensureGuildDatabase(provider, guildId)
    val repo = new JdbcWorldTransferRepository(provider)

    repo.getTransfers(guildId) // creates the table on first use
    repo.removeExpired(guildId, t.plusYears(10)) // clean slate

    repo.record(guildId, "TransferChar", List("Nefera"), t)
    val rows = repo.getTransfers(guildId)
    rows.map(_.name) should contain("transferchar") // stored lowercased
    rows.find(_.name == "transferchar").map(_.formerWorlds) shouldBe Some(List("Nefera"))

    // A second transfer replaces the record rather than adding a row, and differing
    // capitalisation from the API must not slip past the primary key as a new one.
    repo.record(guildId, "transferchar", List("Nefera", "Bona"), t.plusDays(1))
    val updated = repo.getTransfers(guildId)
    updated.count(_.name == "transferchar") shouldBe 1
    updated.find(_.name == "transferchar").map(_.formerWorlds) shouldBe Some(List("Nefera", "Bona"))

    repo.removeExpired(guildId, t.plusDays(2))
    repo.getTransfers(guildId).map(_.name) should not contain "transferchar"
  }

  private def ensureGuildDatabase(provider: JdbcConnectionProvider, guildId: String): Unit = {
    val conn = provider.admin()
    try {
      val rs = conn.createStatement().executeQuery(s"SELECT datname FROM pg_database WHERE datname = '_$guildId'")
      if (!rs.next()) conn.createStatement().executeUpdate(s"CREATE DATABASE _$guildId")
    } finally conn.close()
  }
}
