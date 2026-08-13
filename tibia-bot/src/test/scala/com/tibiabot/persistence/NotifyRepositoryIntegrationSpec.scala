package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcNotifyRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Covers the parts of the notification subscriptions that only a real Postgres
 *  can answer: the two upserts, which both lean on constraints the DDL declares
 *  — a plain unique key for mass log, and a case-insensitive expression index
 *  for bounties, whose `ON CONFLICT` target has to match it exactly. */
class NotifyRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val guildId = "555000555000555111"
  private val world = "Itestworld"

  test("a mass-log subscription is created once and adjusted thereafter") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcNotifyRepository(provider)
    val userId = "user-masslog-1"
    repo.deleteGuild(guildId)

    val created = repo.upsertMasslog(guildId, world, userId, 8)
    created.threshold shouldBe 8
    created.enabled shouldBe true
    created.mutedUntil shouldBe None
    created.lastNotified shouldBe None

    val readjusted = repo.upsertMasslog(guildId, world, userId, 12)
    readjusted.id shouldBe created.id // adjusted, not a second subscription
    readjusted.threshold shouldBe 12
    repo.allMasslog().count(_.guildId == guildId) shouldBe 1

    repo.deleteGuild(guildId)
  }

  test("re-subscribing clears an off switch and a running mute") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcNotifyRepository(provider)
    val userId = "user-masslog-2"
    repo.deleteGuild(guildId)

    val sub = repo.upsertMasslog(guildId, world, userId, 8)
    repo.setMasslogEnabled(sub.id, enabled = false)
    repo.muteMasslog(sub.id, Instant.now().plus(1, ChronoUnit.HOURS))
    repo.masslogById(sub.id).map(_.enabled) shouldBe Some(false)

    val again = repo.upsertMasslog(guildId, world, userId, 9)
    again.enabled shouldBe true
    again.mutedUntil shouldBe None

    repo.deleteGuild(guildId)
  }

  test("enabling a subscription again lifts whatever mute was on it") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcNotifyRepository(provider)
    val userId = "user-masslog-3"
    repo.deleteGuild(guildId)

    val sub = repo.upsertMasslog(guildId, world, userId, 8)
    repo.muteMasslog(sub.id, Instant.now().plus(1, ChronoUnit.HOURS))
    repo.setMasslogEnabled(sub.id, enabled = false)
    repo.setMasslogEnabled(sub.id, enabled = true)

    val reread = repo.masslogById(sub.id)
    reread.map(_.enabled) shouldBe Some(true)
    reread.flatMap(_.mutedUntil) shouldBe None

    repo.deleteGuild(guildId)
  }

  test("a bounty on a name already watched is the same row, whatever the casing") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcNotifyRepository(provider)
    val userId = "user-bounty-1"
    repo.deleteGuild(guildId)

    val first = repo.upsertBounty(guildId, world, userId, "Bubble", 10)
    val second = repo.upsertBounty(guildId, world, userId, "bUBBLE", 30)
    second.id shouldBe first.id
    second.cooldownMinutes shouldBe 30
    second.character shouldBe "bUBBLE" // the spelling last entered wins
    repo.allBounty().count(_.guildId == guildId) shouldBe 1

    repo.deleteGuild(guildId)
  }

  test("different characters, users and worlds each hold their own bounty") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcNotifyRepository(provider)
    repo.deleteGuild(guildId)

    repo.upsertBounty(guildId, world, "user-a", "Bubble", 10)
    repo.upsertBounty(guildId, world, "user-a", "Eternal Oblivion", 10)
    repo.upsertBounty(guildId, world, "user-b", "Bubble", 10)
    repo.upsertBounty(guildId, "Itestworld2", "user-a", "Bubble", 10)
    repo.allBounty().count(_.guildId == guildId) shouldBe 4

    // Removing one world leaves the other world's alone.
    repo.deleteWorld(guildId, world)
    repo.allBounty().count(_.guildId == guildId) shouldBe 1

    repo.deleteGuild(guildId)
    repo.allBounty().count(_.guildId == guildId) shouldBe 0
  }

  test("notification stamps survive the round trip to the second") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcNotifyRepository(provider)
    repo.deleteGuild(guildId)

    val at = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    val masslog = repo.upsertMasslog(guildId, world, "user-stamp", 8)
    repo.markMasslogNotified(masslog.id, at)
    repo.masslogById(masslog.id).flatMap(_.lastNotified) shouldBe Some(at)

    val bounty = repo.upsertBounty(guildId, world, "user-stamp", "Bubble", 10)
    repo.markBountyNotified(bounty.id, at)
    repo.bountyById(bounty.id).flatMap(_.lastNotified) shouldBe Some(at)

    repo.deleteGuild(guildId)
  }
}
