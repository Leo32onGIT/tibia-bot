package com.tibiabot.persistence

import com.tibiabot.domain.Respawn
import com.tibiabot.persistence.jdbc.JdbcRespawnRepository
import com.tibiabot.respawn.RespawnService
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** Adding and removing spawns from the dashboard, against a real database
 *  (cancels without PGHOST).
 *
 *  The refusals are the point. Each of them is a rule about rows the service has
 *  to go and look at — a code already taken, a spawn somebody is standing in the
 *  queue for — so none of them can be checked without one.
 */
class CatalogueAdminIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val guildId = "888000888000888222" // numeric-only fake guild id
  private val now = ZonedDateTime.parse("2026-08-13T12:00:00Z")

  private def freshService(): (RespawnService, JdbcRespawnRepository, String) = {
    val provider = pgOrCancel()
    ensureGuildDatabase(provider, guildId)
    val repo = new JdbcRespawnRepository(provider)
    repo.listRespawns(guildId).foreach(r => repo.removeRespawn(guildId, r.id))
    (new RespawnService(repo), repo, guildId)
  }

  test("a spawn a guild adds is its own, not the bundled list's") {
    // Source is what keeps the boot-time seed sync off it. A custom row marked
    // `seed` would be retired the moment the bundled file did not mention it.
    val (service, _, g) = freshService()
    val added = service.addCustomSpawn(g, "mod-1", "9001", "Edron", "Deep Cave", "Orc Warlord")
    added.map(_.code) shouldBe Right("9001")
    added.map(_.source) shouldBe Right(Respawn.SourceCustom)
    added.map(_.name) shouldBe Right("Deep Cave")
    added.map(_.region) shouldBe Right("Edron")
  }

  test("a code already in the catalogue is refused, never overwritten") {
    // Overwriting would rename whatever people are already claiming under it.
    val (service, _, g) = freshService()
    service.addCustomSpawn(g, "mod-1", "9001", "Edron", "Deep Cave", "")
    val again = service.addCustomSpawn(g, "mod-1", "9001", "Thais", "Something Else", "")
    again.isLeft shouldBe true
    service.resolve(g, "9001").map(_.name) shouldBe Some("Deep Cave")
  }

  test("the same code in a different case is still the same code") {
    val (service, _, g) = freshService()
    service.addCustomSpawn(g, "mod-1", "9001a", "Edron", "Deep Cave", "")
    service.addCustomSpawn(g, "mod-1", "9001A", "Edron", "Deep Cave", "").isLeft shouldBe true
  }

  test("a spawn the guild added can be removed again") {
    val (service, _, g) = freshService()
    service.addCustomSpawn(g, "mod-1", "9001", "Edron", "Deep Cave", "")
    service.removeCustomSpawn(g, "9001").map(_.code) shouldBe Right("9001")
    service.resolve(g, "9001") shouldBe None
  }

  test("a bundled code cannot be removed, because it would only come back") {
    // The next boot syncs the seed file, so a deleted seed row reappears — which
    // reads as the button not working rather than as a rule.
    val (service, repo, g) = freshService()
    repo.addRespawn(g, "415", "Cult Orcs", "", "Edron", "", "", Respawn.SourceSeed, "seed")
    val refused = service.removeCustomSpawn(g, "415")
    refused.isLeft shouldBe true
    refused.left.map(_.toLowerCase) match {
      case Left(reason) => reason should include("bundled")
      case Right(_)     => fail("expected a refusal")
    }
    service.resolve(g, "415") should not be empty
  }

  test("a spawn somebody is hunting is not removed out from under them") {
    // removeRespawn takes the claims with it, so this refusal is what stands
    // between a tidy-up and somebody's evening.
    val (service, repo, g) = freshService()
    val spawn = service.addCustomSpawn(g, "mod-1", "9001", "Edron", "Deep Cave", "").toOption.get
    repo.insertActiveClaim(g, spawn.id, "u1", "violentbeams", "Beams", "Bubble",
      now, now.plusHours(2), 120, "adhoc")

    service.removeCustomSpawn(g, "9001").isLeft shouldBe true
    service.resolve(g, "9001") should not be empty
  }

  test("a spawn somebody is queued for is not removed either") {
    val (service, repo, g) = freshService()
    val spawn = service.addCustomSpawn(g, "mod-1", "9002", "Edron", "Deep Cave", "").toOption.get
    repo.enqueueClaim(g, spawn.id, "u2", "someone", "", "", 120, 20, "adhoc")
    service.removeCustomSpawn(g, "9002").isLeft shouldBe true
  }

  test("a spawn with a standing booking is not removed either") {
    // A schedule outlives any one slot, so checking only for written rows would
    // leave a weekly booking pointing at a spawn that no longer exists.
    val (service, repo, g) = freshService()
    val spawn = service.addCustomSpawn(g, "mod-1", "9003", "Edron", "Deep Cave", "").toOption.get
    repo.addSchedule(g, spawn.id, "u3", "someone", "", "", now.plusDays(1), 1440, 120)
    service.removeCustomSpawn(g, "9003").isLeft shouldBe true
  }

  test("removing the last thing standing in the way lets it go") {
    // The refusal has to be a state somebody can get out of, or it is just a
    // spawn that can never be removed.
    val (service, repo, g) = freshService()
    val spawn = service.addCustomSpawn(g, "mod-1", "9004", "Edron", "Deep Cave", "").toOption.get
    val queued = repo.enqueueClaim(g, spawn.id, "u2", "someone", "", "", 120, 20, "adhoc")
    service.removeCustomSpawn(g, "9004").isLeft shouldBe true
    repo.cancelClaim(g, queued.get.id, "left")
    service.removeCustomSpawn(g, "9004").map(_.code) shouldBe Right("9004")
  }

  // There is no member list to offer instead — the bot runs without the
  // privileged GUILD_MEMBERS intent — so the picker is built from whoever has
  // actually used the system here.
  test("everybody the system has seen can be picked from, once each") {
    val (service, repo, g) = freshService()
    val spawn = service.addCustomSpawn(g, "mod-1", "9101", "Edron", "Deep Cave", "").toOption.get
    repo.insertActiveClaim(g, spawn.id, "u1", "violentbeams", "Beams", "", now, now.plusHours(1), 60, "adhoc")
    repo.enqueueClaim(g, spawn.id, "u2", "someone", "Some One", "", 60, 20, "adhoc")
    // Somebody whose only involvement is a standing booking has no claim row at
    // all until it materialises, and leaving them out makes them unpickable.
    repo.addSchedule(g, spawn.id, "u3", "planner", "The Planner", "", now.plusDays(1), 1440, 60)

    val people = service.knownMembers(g)
    people.map(_.userId) should contain allOf ("u1", "u2", "u3")
    people.map(_.userId).distinct.size shouldBe people.size
    people.find(_.userId == "u1").map(_.nickname) shouldBe Some("Beams")
  }

  test("the newest spelling of a name is the one offered") {
    // People rename themselves, and the picker is searched by what they are
    // called now rather than by what they were called the first time. Two
    // spawns rather than two rows on one, since a second claim on a spawn
    // somebody already holds is refused.
    val (service, repo, g) = freshService()
    val first = service.addCustomSpawn(g, "mod-1", "9102", "Edron", "Deep Cave", "").toOption.get
    val second = service.addCustomSpawn(g, "mod-1", "9103", "Edron", "Deeper Cave", "").toOption.get
    repo.insertActiveClaim(g, first.id, "u1", "oldname", "Old Nick", "",
      now.minusDays(7), now.minusDays(7).plusHours(1), 60, "adhoc")
    repo.insertActiveClaim(g, second.id, "u1", "newname", "New Nick", "",
      now, now.plusHours(1), 60, "adhoc")

    val found = service.knownMembers(g).find(_.userId == "u1")
    found.map(_.userName) shouldBe Some("newname")
    found.map(_.nickname) shouldBe Some("New Nick")
  }

  test("a booking made after a claim is the newer of the two") {
    // The two tables have their own identity sequences, so ordering by id would
    // compare a schedule's 3 against a claim's 900 and call the claim newer
    // whatever the dates say.
    val (service, repo, g) = freshService()
    val spawn = service.addCustomSpawn(g, "mod-1", "9104", "Edron", "Deep Cave", "").toOption.get
    repo.insertActiveClaim(g, spawn.id, "u4", "oldname", "Old Nick", "",
      now.minusDays(30), now.minusDays(30).plusHours(1), 60, "adhoc")
    repo.addSchedule(g, spawn.id, "u4", "newname", "New Nick", "", now.plusDays(1), 1440, 60)

    service.knownMembers(g).find(_.userId == "u4").map(_.userName) shouldBe Some("newname")
  }

  test("removing something that was never there says so") {
    val (service, _, g) = freshService()
    service.removeCustomSpawn(g, "nope").isLeft shouldBe true
  }

  private def ensureGuildDatabase(provider: JdbcConnectionProvider, guildId: String): Unit = {
    val conn = provider.admin()
    try {
      val rs = conn.createStatement().executeQuery(s"SELECT datname FROM pg_database WHERE datname = '_$guildId'")
      if (!rs.next()) conn.createStatement().executeUpdate(s"CREATE DATABASE _$guildId")
    } finally conn.close()
  }
}
