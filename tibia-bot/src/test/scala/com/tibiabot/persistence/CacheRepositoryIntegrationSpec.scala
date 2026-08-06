package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcCacheRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** Round-trips the deaths/levels caches against a real Postgres (cancels without PGHOST). */
class CacheRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val world = "ITestCacheWorld"

  test("deaths cache: add, get and expiry") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcCacheRepository(provider)

    repo.addDeath(world, "Char A", "2026-05-30T10:00:00Z")
    repo.getDeaths(world).map(_.name) should contain("Char A")

    // now is well past the 30-minute window -> the row is purged
    repo.removeExpiredDeaths(ZonedDateTime.parse("2026-05-31T00:00:00Z"))
    repo.getDeaths(world).map(_.name) should not contain "Char A"
  }

  test("levels cache: add, get and expiry") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcCacheRepository(provider)

    repo.addLevel(world, "Char B", "100", "Knight", "2026-05-30T09:00:00Z", "2026-05-30T10:00:00Z")
    repo.getLevels(world).map(_.name) should contain("Char B")

    // now is well past the 25-hour window -> the row is purged
    repo.removeExpiredLevels(ZonedDateTime.parse("2026-06-01T00:00:00Z"))
    repo.getLevels(world).map(_.name) should not contain "Char B"
  }

  test("list cache: add (upsert), get and expiry") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcCacheRepository(provider)
    val listWorld = "Itestlistx"

    repo.getList(listWorld)
    repo.addToList("ListChar", List("OldName"), listWorld, List("OldWorld"),
      "SomeGuild", "200", "Knight", "2026-05-30T09:00:00Z", ZonedDateTime.parse("2026-05-30T10:00:00Z"))

    val rows = repo.getList(listWorld)
    rows.map(_.name) should contain("ListChar")
    rows.find(_.name == "ListChar").map(_.guild) shouldBe Some("SomeGuild")

    // now is well past the 7-day window -> the row is purged
    repo.removeExpiredList(ZonedDateTime.parse("2026-06-30T00:00:00Z"))
    repo.getList(listWorld).map(_.name) should not contain "ListChar"
  }

  test("boosted_info: default row created, then updated and read back") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcCacheRepository(provider)
    val bot = "ITestBotDefault"

    val initial = repo.getBoosted(bot) // creates table + this bot's row on first use
    initial should not be empty

    repo.updateBoosted(bot, "Some Boss", "Some Creature", "111", "222")
    val updated = repo.getBoosted(bot)
    updated.head.boss shouldBe "Some Boss"
    updated.head.creature shouldBe "Some Creature"
    updated.head.bossChanged shouldBe "111"
    updated.head.creatureChanged shouldBe "222"

    // empty-string args leave fields unchanged
    repo.updateBoosted(bot, "", "Another Creature", "", "")
    val partial = repo.getBoosted(bot)
    partial.head.boss shouldBe "Some Boss"
    partial.head.creature shouldBe "Another Creature"
  }

  test("boosted_info: two bots keep separate rows and changed-flags") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcCacheRepository(provider)
    val blue = "ITestBotBlue"
    val red = "ITestBotRed"

    repo.getBoosted(blue)
    repo.getBoosted(red)

    // Blue works through a whole server-save cycle: names rotate, both flags go
    // up, then it posts and clears them.
    repo.updateBoosted(blue, "Blue Boss", "Blue Creature", "1", "1")
    repo.updateBoosted(blue, "", "", "0", "0")

    // Red's own state is untouched by any of it — before this was keyed by bot,
    // blue clearing the flags is exactly what stopped red ever posting.
    val redRow = repo.getBoosted(red).head
    redRow.boss should not be "Blue Boss"
    redRow.bossChanged shouldBe "0"

    repo.updateBoosted(red, "Red Boss", "Red Creature", "1", "1")
    val redChanged = repo.getBoosted(red).head
    redChanged.boss shouldBe "Red Boss"
    redChanged.bossChanged shouldBe "1"
    redChanged.creatureChanged shouldBe "1"

    // and blue still reads back what blue wrote
    val blueRow = repo.getBoosted(blue).head
    blueRow.boss shouldBe "Blue Boss"
    blueRow.bossChanged shouldBe "0"
  }

  test("boosted_info: a pre-migration shared row seeds each bot's first row") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcCacheRepository(provider)

    // Stand in for the old single-row table: bot_id '' is what the ALTER leaves
    // behind, and it must not be adopted by whichever bot reads first.
    repo.getBoosted("")
    repo.updateBoosted("", "Legacy Boss", "Legacy Creature", "1", "1")

    val seeded = repo.getBoosted("ITestBotFresh").head
    seeded.boss shouldBe "Legacy Boss"
    seeded.creature shouldBe "Legacy Creature"
    // flags start clear — this bot hasn't posted anything yet, whatever the
    // shared row was mid-way through when the migration landed
    seeded.bossChanged shouldBe "0"
    seeded.creatureChanged shouldBe "0"
  }
}
