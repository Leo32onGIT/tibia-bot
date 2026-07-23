package com.tibiabot.persistence

import com.tibiabot.domain.PatreonMember
import com.tibiabot.persistence.jdbc.JdbcPatreonMemberRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** Round-trips PatreonMemberRepository against a real Postgres (cancels without PGHOST). */
class PatreonMemberRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val syncedAt = ZonedDateTime.parse("2026-07-24T00:00:00Z")

  test("replaceSnapshot round-trips members, including nullable patron_status/discord_user_id") {
    val provider = pgOrCancel()
    ensureCacheDatabase(provider)
    val repo = new JdbcPatreonMemberRepository(provider)
    clearMembers(provider)

    val members = List(
      PatreonMember("m1", "Alice", Some("active_patron"), 500, Some("111")),
      PatreonMember("m2", "Bob", None, 0, None)
    )
    repo.replaceSnapshot(members, syncedAt)

    repo.snapshot().toSet shouldBe members.toSet
  }

  test("a later replaceSnapshot prunes members no longer present, without a window where the table is empty") {
    val provider = pgOrCancel()
    ensureCacheDatabase(provider)
    val repo = new JdbcPatreonMemberRepository(provider)
    clearMembers(provider)

    repo.replaceSnapshot(List(
      PatreonMember("m1", "Alice", Some("active_patron"), 500, Some("111")),
      PatreonMember("m2", "Bob", Some("active_patron"), 300, None)
    ), syncedAt)

    // m2 dropped from Patreon's response, m1's pledge changed
    repo.replaceSnapshot(List(
      PatreonMember("m1", "Alice", Some("active_patron"), 1000, Some("111"))
    ), syncedAt.plusMinutes(30))

    repo.snapshot() shouldBe List(PatreonMember("m1", "Alice", Some("active_patron"), 1000, Some("111")))
  }

  test("replaceSnapshot with an empty list clears the whole table") {
    val provider = pgOrCancel()
    ensureCacheDatabase(provider)
    val repo = new JdbcPatreonMemberRepository(provider)
    clearMembers(provider)

    repo.replaceSnapshot(List(PatreonMember("m1", "Alice", Some("active_patron"), 500, Some("111"))), syncedAt)
    repo.replaceSnapshot(Nil, syncedAt.plusMinutes(30))

    repo.snapshot() shouldBe empty
  }

  private def clearMembers(provider: JdbcConnectionProvider): Unit = {
    val conn = provider.cache()
    try {
      val exists = conn.createStatement()
        .executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'patreon_members'")
      if (exists.next()) conn.createStatement().executeUpdate("DELETE FROM patreon_members")
    } finally conn.close()
  }

  private def ensureCacheDatabase(provider: JdbcConnectionProvider): Unit = {
    val conn = provider.admin()
    try {
      val rs = conn.createStatement()
        .executeQuery("SELECT datname FROM pg_database WHERE datname = 'bot_cache'")

      if (!rs.next()) {
        conn.createStatement()
          .executeUpdate("CREATE DATABASE bot_cache")
      }
    } catch {
      case _ : Throwable => //
    } finally {
      conn.close()
    }
  }
}
