package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcActivityRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** Round-trips ActivityRepository against a real Postgres (cancels without PGHOST). */
class ActivityRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val guildId = "888000888000888000" // numeric-only fake guild id
  private val t = ZonedDateTime.parse("2026-05-30T10:00:00Z")

  test("tracked_activity round-trip: add, rename via update, remove") {
    val provider = pgOrCancel()
    ensureGuildDatabase(provider, guildId)
    val repo = new JdbcActivityRepository(provider)

    repo.getActivity(guildId) // creates the table on first use
    repo.removeByName(guildId, "ActChar")        // clean slate
    repo.removeByName(guildId, "ActCharRenamed")

    repo.add(guildId, "ActChar", List("Old A"), "Guild X", t)
    val rows = repo.getActivity(guildId)
    rows.map(_.name) should contain("ActChar")
    rows.find(_.name == "ActChar").map(_.guild) shouldBe Some("Guild X")

    repo.update(guildId, "ActChar", List("Old A"), "Guild X", t, "ActCharRenamed")
    val renamed = repo.getActivity(guildId)
    renamed.map(_.name) should (contain("ActCharRenamed") and not contain "ActChar")

    repo.removeByName(guildId, "ActCharRenamed")
    repo.getActivity(guildId).map(_.name) should not contain "ActCharRenamed"
  }

  private def ensureGuildDatabase(provider: JdbcConnectionProvider, guildId: String): Unit = {
    val conn = provider.admin()
    try {
      val rs = conn.createStatement().executeQuery(s"SELECT datname FROM pg_database WHERE datname = '_$guildId'")
      if (!rs.next()) conn.createStatement().executeUpdate(s"CREATE DATABASE _$guildId")
    } finally conn.close()
  }
}
