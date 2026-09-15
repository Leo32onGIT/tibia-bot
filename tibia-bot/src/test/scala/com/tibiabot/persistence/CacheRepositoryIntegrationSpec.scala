package com.tibiabot.persistence

import com.tibiabot.domain.SheetCache
import com.tibiabot.persistence.JdbcConnectionProvider
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

  test("character sheet cache: record, get, re-record and expiry") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcCacheRepository(provider)
    val seen = ZonedDateTime.parse("2026-05-30T10:00:00Z")

    repo.recordSheets(List(
      SheetCache(world, "char c", "Char C", "Bones", "Elite Knight", 302, seen),
      SheetCache(world, "char d", "Char D", "", "Master Sorcerer", 180, seen)))

    val sheets = repo.getSheets(world)
    sheets("char c").guild shouldBe "Bones"
    sheets("char c").displayName shouldBe "Char C"
    sheets("char c").vocation shouldBe "Elite Knight"
    // Somebody in no guild is a row like any other, not an absent one: "we
    // looked and they have no guild" is an answer the post wants.
    sheets("char d").guild shouldBe ""

    // A second reading of the same name replaces the first rather than doubling
    // it — a guild swap is the case this table exists to notice.
    repo.recordSheets(List(
      SheetCache(world, "char c", "Char C", "Red Rose", "Elite Knight", 303, seen.plusHours(2))))
    val swapped = repo.getSheets(world)
    swapped should have size 2
    swapped("char c").guild shouldBe "Red Rose"
    swapped("char c").level shouldBe 303

    // Past the 25-hour window measured from the *last* reading, not the first.
    repo.removeExpiredSheets(seen.plusHours(26))
    repo.getSheets(world).keySet shouldBe Set("char c")
    repo.removeExpiredSheets(seen.plusHours(28))
    repo.getSheets(world) shouldBe empty
  }

  /** Every name in the shared cache, whichever world it belongs to.
   *
   *  `getList` filters by world, which is not enough here: pruning is defined
   *  against *all* listed players, so a test that prunes has to know every name
   *  already in the table or it deletes rows belonging to whatever else uses
   *  this database. Which is exactly what an earlier version of this spec did.
   */
  private def allCachedNames(provider: JdbcConnectionProvider): Set[String] = {
    val conn = provider.cache()
    try {
      val statement = conn.createStatement()
      val result = statement.executeQuery("SELECT name FROM list")
      val names = scala.collection.mutable.Set.empty[String]
      while (result.next()) names += result.getString("name").toLowerCase
      result.close()
      statement.close()
      names.toSet
    } finally conn.close()
  }

  test("list cache: add (upsert), get, and orphan-only pruning") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcCacheRepository(provider)
    val listWorld = "Itestlistx"
    val otherWorld = "Itestlistz"

    repo.getList(listWorld)
    repo.addToList("ListChar", List("OldName"), listWorld, List("OldWorld"),
      "SomeGuild", "200", "Knight", "2026-05-30T09:00:00Z", ZonedDateTime.parse("2026-05-30T10:00:00Z"))
    // Somebody else's row, which nothing here ever orphans.
    //
    // It is what keeps the keep-set below from coming out empty, and without it
    // this test only passed on a database that happened to be holding rows from
    // somewhere else. On a clean one "ListChar" was the only name in the table,
    // so the keep-set was empty — and an empty keep-set prunes nothing at all,
    // which is the guard the very next test exists to assert. The two
    // contradicted each other and the shared table decided which won.
    //
    // It also makes the "orphan-only" in this test's name mean something: that
    // a name still listed survives the same prune that drops an orphan.
    repo.addToList("KeptChar", Nil, otherWorld, Nil, "OtherGuild", "300", "Druid",
      "2026-05-30T09:00:00Z", ZonedDateTime.parse("2026-05-30T10:00:00Z"))

    val rows = repo.getList(listWorld)
    rows.map(_.name) should contain("ListChar")
    rows.find(_.name == "ListChar").map(_.guild) shouldBe Some("SomeGuild")

    // Age no longer decides anything: a sheet does not go stale sitting still,
    // since a character's level cannot change while they are offline. However old
    // the row, it survives as long as somebody still lists that player.
    val everyone = allCachedNames(provider)
    repo.pruneList(everyone)
    repo.getList(listWorld).map(_.name) should contain("ListChar")

    // Dropped only once no list references them — and only this one: everything
    // else in the shared table is named in the keep set and must survive.
    repo.pruneList(everyone - "listchar")
    repo.getList(listWorld).map(_.name) should not contain "ListChar"
    repo.getList(otherWorld).map(_.name) should contain("KeptChar")
    allCachedNames(provider) shouldBe (everyone - "listchar")
  }

  /** An empty keep-set is indistinguishable from "the lists have not loaded
   *  yet", so it must not be read as "nobody is listed, drop everything". */
  test("list cache: an empty keep-set prunes nothing at all") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcCacheRepository(provider)
    val listWorld = "Itestlisty"

    repo.getList(listWorld)
    repo.addToList("SafeChar", Nil, listWorld, Nil, "G", "100", "Druid",
      "2026-05-30T09:00:00Z", ZonedDateTime.parse("2026-05-30T10:00:00Z"))

    repo.pruneList(Set.empty) shouldBe 0
    repo.getList(listWorld).map(_.name) should contain("SafeChar")
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
    // Put both back to a known state first. These rows outlive the run, and the
    // last thing this test does is leave red's flags up — so without this it
    // passes exactly once per database and fails on every run after that.
    repo.updateBoosted(blue, "", "", "0", "0")
    repo.updateBoosted(red, "", "", "0", "0")

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
