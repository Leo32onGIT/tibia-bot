package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcWorldTransferRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** Round-trips WorldTransferRepository against a real Postgres (cancels without PGHOST). */
class WorldTransferRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val world = "TransferSpecWorld"
  private val t = ZonedDateTime.parse("2026-05-30T10:00:00Z")

  test("world_transfers round-trip: record, re-record the same character, expire") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcWorldTransferRepository(provider)

    repo.removeExpired(world, t.plusYears(10)) // clean slate

    repo.record(world, "TransferChar", List("Nefera"), t)
    val rows = repo.getTransfers(world)
    rows.map(_.name) should contain("transferchar") // stored lowercased
    rows.find(_.name == "transferchar").map(_.formerWorlds) shouldBe Some(List("Nefera"))

    // A second transfer replaces the record rather than adding a row, and differing
    // capitalisation from the API must not slip past the primary key as a new one.
    repo.record(world, "transferchar", List("Nefera", "Bona"), t.plusDays(1))
    val updated = repo.getTransfers(world)
    updated.count(_.name == "transferchar") shouldBe 1
    updated.find(_.name == "transferchar").map(_.formerWorlds) shouldBe Some(List("Nefera", "Bona"))

    repo.removeExpired(world, t.plusDays(2))
    repo.getTransfers(world).map(_.name) should not contain "transferchar"
  }

  test("world_transfers rename: the record moves onto the new name and the old key goes") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcWorldTransferRepository(provider)

    repo.removeExpired(world, t.plusYears(10)) // clean slate

    repo.record(world, "Rodzeraah", List("Unebra"), t)
    // What BotApp.rekeyWorldTransfer does: write under the new key, then drop the old.
    repo.record(world, "Chris Rpbombita", List("Unebra"), t)
    repo.remove(world, "Rodzeraah")

    val rows = repo.getTransfers(world)
    rows.map(_.name) should contain("chris rpbombita")
    rows.map(_.name) should not contain "rodzeraah"
    rows.find(_.name == "chris rpbombita").map(_.formerWorlds) shouldBe Some(List("Unebra"))

    // Capitalisation from the API must not leave the old key behind, and removing a
    // key that was never there is a no-op rather than a failure.
    repo.record(world, "Rodzeraah", List("Unebra"), t)
    repo.remove(world, "RODZERAAH")
    repo.remove(world, "Nobodyatall")
    repo.getTransfers(world).map(_.name) should not contain "rodzeraah"

    repo.removeExpired(world, t.plusDays(2))
  }

  test("a record on one world is invisible to another") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcWorldTransferRepository(provider)

    val otherWorld = s"${world}Two"
    repo.removeExpired(world, t.plusYears(10))
    repo.removeExpired(otherWorld, t.plusYears(10))

    // The same character name transferring into two different worlds is two
    // arrivals, and the primary key has to hold both.
    repo.record(world, "Wanderer", List("Antica"), t)
    repo.record(otherWorld, "Wanderer", List("Bona"), t)

    repo.getTransfers(world).find(_.name == "wanderer").map(_.formerWorlds) shouldBe Some(List("Antica"))
    repo.getTransfers(otherWorld).find(_.name == "wanderer").map(_.formerWorlds) shouldBe Some(List("Bona"))

    // Pruning one world leaves the other's record alone.
    repo.removeExpired(world, t.plusDays(1))
    repo.getTransfers(world).map(_.name) should not contain "wanderer"
    repo.getTransfers(otherWorld).map(_.name) should contain("wanderer")

    repo.removeExpired(otherWorld, t.plusDays(1))
  }
}
