package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.{JdbcLegacyWorldTransferMigration, JdbcWorldTransferRepository}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.sql.Timestamp
import java.time.ZonedDateTime

/** The one-shot move of announced transfers from guild scope to world scope
 *  (cancels without PGHOST). */
class LegacyWorldTransferMigrationIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val t = ZonedDateTime.parse("2026-05-30T10:00:00Z")

  test("a single-world guild's records are carried onto that world, and the legacy table retired") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val guildId = "888000888000888401"
    val world = "MigrationSpecOne"
    val repo = new JdbcWorldTransferRepository(provider)
    repo.removeExpired(world, t.plusYears(10))
    givenLegacyTable(provider, guildId, Seq(("charone", "Antica", t), ("chartwo", "Bona,Nefera", t)))

    new JdbcLegacyWorldTransferMigration(provider).migrate(guildId, List(world))

    val rows = repo.getTransfers(world)
    rows.map(_.name) should contain allOf ("charone", "chartwo")
    rows.find(_.name == "chartwo").map(_.formerWorlds) shouldBe Some(List("Bona", "Nefera"))
    // Retired, not dropped: the rows are still there under the new name.
    tableExists(provider, guildId, "world_transfers") shouldBe false
    tableExists(provider, guildId, "world_transfers_legacy") shouldBe true

    repo.removeExpired(world, t.plusYears(10))
  }

  test("a multi-world guild is skipped rather than guessed at, but still retired") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val guildId = "888000888000888402"
    val (first, second) = ("MigrationSpecTwoA", "MigrationSpecTwoB")
    val repo = new JdbcWorldTransferRepository(provider)
    repo.removeExpired(first, t.plusYears(10))
    repo.removeExpired(second, t.plusYears(10))
    givenLegacyTable(provider, guildId, Seq(("ambiguous", "Antica", t)))

    new JdbcLegacyWorldTransferMigration(provider).migrate(guildId, List(first, second))

    repo.getTransfers(first) shouldBe empty
    repo.getTransfers(second) shouldBe empty
    // Retired all the same, so a later drop to one world can't migrate rows that
    // may well have belonged to the world that went away.
    tableExists(provider, guildId, "world_transfers") shouldBe false
  }

  test("a re-run changes nothing, and never overwrites a newer record") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val guildId = "888000888000888403"
    val world = "MigrationSpecThree"
    val repo = new JdbcWorldTransferRepository(provider)
    repo.removeExpired(world, t.plusYears(10))
    givenLegacyTable(provider, guildId, Seq(("returning", "Antica", t)))
    val migration = new JdbcLegacyWorldTransferMigration(provider)

    migration.migrate(guildId, List(world))
    // A second transfer spotted by the live bot after the migration ran.
    repo.record(world, "Returning", List("Antica", "Bona"), t.plusDays(3))
    // Whatever re-runs it — a rollback and redeploy, a restored database — must
    // not walk the newer sighting back to the one the legacy table held.
    givenLegacyTable(provider, guildId, Seq(("returning", "Antica", t)))
    migration.migrate(guildId, List(world))

    val rows = repo.getTransfers(world)
    rows.count(_.name == "returning") shouldBe 1
    rows.find(_.name == "returning").map(_.formerWorlds) shouldBe Some(List("Antica", "Bona"))

    repo.removeExpired(world, t.plusYears(10))
  }

  test("a guild with no legacy table at all is a no-op") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val guildId = "888000888000888404"
    val world = "MigrationSpecFour"
    ensureGuildDatabase(provider, guildId)
    dropTables(provider, guildId)

    new JdbcLegacyWorldTransferMigration(provider).migrate(guildId, List(world))

    new JdbcWorldTransferRepository(provider).getTransfers(world) shouldBe empty
  }

  /** A guild database holding the pre-migration per-guild table, exactly as the
   *  old JdbcWorldTransferRepository created it. */
  private def givenLegacyTable(provider: JdbcConnectionProvider, guildId: String, rows: Seq[(String, String, ZonedDateTime)]): Unit = {
    ensureGuildDatabase(provider, guildId)
    dropTables(provider, guildId)
    val conn = provider.guild(guildId)
    try {
      conn.createStatement().executeUpdate(
        """CREATE TABLE world_transfers (
          |name VARCHAR(255) NOT NULL,
          |former_worlds VARCHAR(255) NOT NULL,
          |detected TIMESTAMP NOT NULL,
          |PRIMARY KEY (name)
          |);""".stripMargin)
      val insert = conn.prepareStatement("INSERT INTO world_transfers(name, former_worlds, detected) VALUES (?,?,?);")
      rows.foreach { case (name, formerWorlds, detected) =>
        insert.setString(1, name)
        insert.setString(2, formerWorlds)
        insert.setTimestamp(3, Timestamp.from(detected.toInstant))
        insert.executeUpdate()
      }
      insert.close()
    } finally conn.close()
  }

  private def dropTables(provider: JdbcConnectionProvider, guildId: String): Unit = {
    val conn = provider.guild(guildId)
    try {
      val statement = conn.createStatement()
      statement.executeUpdate("DROP TABLE IF EXISTS world_transfers;")
      statement.executeUpdate("DROP TABLE IF EXISTS world_transfers_legacy;")
      statement.close()
    } finally conn.close()
  }

  private def tableExists(provider: JdbcConnectionProvider, guildId: String, table: String): Boolean = {
    val conn = provider.guild(guildId)
    try {
      val statement = conn.prepareStatement("SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?")
      statement.setString(1, table)
      val result = statement.executeQuery()
      val exists = result.next()
      result.close()
      statement.close()
      exists
    } finally conn.close()
  }

  private def ensureGuildDatabase(provider: JdbcConnectionProvider, guildId: String): Unit = {
    val conn = provider.admin()
    try {
      val rs = conn.createStatement().executeQuery(s"SELECT datname FROM pg_database WHERE datname = '_$guildId'")
      if (!rs.next()) conn.createStatement().executeUpdate(s"CREATE DATABASE _$guildId")
    } finally conn.close()
  }
}
