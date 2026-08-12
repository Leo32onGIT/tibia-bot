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
