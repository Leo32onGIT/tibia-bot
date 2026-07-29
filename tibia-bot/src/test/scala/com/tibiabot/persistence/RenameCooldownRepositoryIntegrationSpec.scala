package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcRenameCooldownRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Round-trips RenameCooldownRepository against a real Postgres (cancels without PGHOST). */
class RenameCooldownRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val now = ZonedDateTime.parse("2026-07-24T00:00:00Z")

  test("recordRename and loadForWorld round-trip, scoped per world, overwriting on repeated calls") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcRenameCooldownRepository(provider)
    clearCooldowns(provider)

    repo.recordRename("Antica", "channel-1", now)
    repo.recordRename("Antica", "channel-2", now)
    repo.recordRename("Secura", "channel-3", now)

    repo.loadForWorld("Antica").keySet shouldBe Set("channel-1", "channel-2")
    repo.loadForWorld("Secura").keySet shouldBe Set("channel-3")
    repo.loadForWorld("Nowhere") shouldBe empty

    val later = now.plusMinutes(10)
    repo.recordRename("Antica", "channel-1", later)
    repo.loadForWorld("Antica")("channel-1").truncatedTo(ChronoUnit.SECONDS) shouldBe later.truncatedTo(ChronoUnit.SECONDS)
  }

  private def clearCooldowns(provider: JdbcConnectionProvider): Unit = {
    val conn = provider.cache()
    try {
      val exists = conn.createStatement()
        .executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'rename_cooldowns'")
      if (exists.next()) conn.createStatement().executeUpdate("DELETE FROM rename_cooldowns")
    } finally conn.close()
  }
}
