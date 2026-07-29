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

    val initial = repo.getBoosted() // creates table + default row on first use
    initial should not be empty

    repo.updateBoosted("Some Boss", "Some Creature", "111", "222")
    val updated = repo.getBoosted()
    updated.head.boss shouldBe "Some Boss"
    updated.head.creature shouldBe "Some Creature"
    updated.head.bossChanged shouldBe "111"
    updated.head.creatureChanged shouldBe "222"

    // empty-string args leave fields unchanged
    repo.updateBoosted("", "Another Creature", "", "")
    val partial = repo.getBoosted()
    partial.head.boss shouldBe "Some Boss"
    partial.head.creature shouldBe "Another Creature"
  }
}
