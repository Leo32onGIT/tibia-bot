package com.tibiabot.persistence

import com.tibiabot.domain.CooldownKind
import com.tibiabot.persistence.jdbc.JdbcCooldownRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** Round-trips CooldownRepository against a real Postgres (cancels without PGHOST). */
class CooldownRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val user = "itest_cooldown_user"
  private val satchel = CooldownKind.Satchel
  private val dragon = CooldownKind.DragonHead
  private val when = ZonedDateTime.parse("2026-05-30T10:00:00Z")
  private val now = ZonedDateTime.parse("2026-08-30T10:00:00Z")

  test("add / get / del / delAll round-trip on the satchel table") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcCooldownRepository(provider)

    repo.delAll(user, satchel) // start from a clean slate
    repo.getStamps(user, satchel).getOrElse(Nil) shouldBe empty

    repo.add(user, satchel, when, "boots")
    repo.add(user, satchel, when, "ring")
    val tags = repo.getStamps(user, satchel).getOrElse(Nil).map(_.tag)
    tags should contain allOf ("boots", "ring")

    repo.del(user, satchel, "boots")
    repo.getStamps(user, satchel).getOrElse(Nil).map(_.tag) should (contain("ring") and not contain "boots")

    repo.delAll(user, satchel)
    repo.getStamps(user, satchel).getOrElse(Nil) shouldBe empty
  }

  private val botA = "itest_bot_a"
  private val botB = "itest_bot_b"

  test("a bot sweeps the stamps it owns and the unclaimed ones, never another bot's") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcCooldownRepository(provider)

    repo.delAll(user, satchel)
    repo.forget(user, botA)
    repo.forget(user, botB)

    repo.add(user, satchel, when, "unclaimed")
    repo.expiredStamps(satchel, now, botA).map(_.tag) should contain("unclaimed")

    repo.claim(user, botB)
    repo.expiredStamps(satchel, now, botA).map(_.tag) should not contain "unclaimed"
    repo.expiredStamps(satchel, now, botB).map(_.tag) should contain("unclaimed")

    // ...and clearing only reaches as far as the sweep did.
    repo.deleteExpired(satchel, now, botA)
    repo.getStamps(user, satchel).getOrElse(Nil).map(_.tag) should contain("unclaimed")
    repo.deleteExpired(satchel, now, botB)
    repo.getStamps(user, satchel).getOrElse(Nil) shouldBe empty

    repo.delAll(user, satchel)
  }

  test("failures accrue per user, and give up only on the stamps that bot owns") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcCooldownRepository(provider)

    repo.delAll(user, satchel)
    repo.forget(user, botA)
    repo.forget(user, botB)

    repo.add(user, satchel, when, "boots")
    repo.add(user, satchel, when, "ring")
    repo.claim(user, botA)

    // Counted per user, not per stamp: the row an expiry DM was sent for is
    // deleted in the same sweep, so a per-row count could never reach three.
    repo.recordDeliveryFailure(user, botA) shouldBe 1
    repo.recordDeliveryFailure(user, botA) shouldBe 2
    repo.recordDeliveryFailure(user, botA) shouldBe 3

    // Another bot failing at the same user counts for nothing — it owns none of
    // their stamps, so the failure says only that it isn't the bot in reach.
    repo.recordDeliveryFailure(user, botB) shouldBe 0
    repo.forget(user, botB)
    repo.getStamps(user, satchel).getOrElse(Nil).map(_.tag) should contain allOf ("boots", "ring")

    repo.forget(user, botA)
    repo.getStamps(user, satchel).getOrElse(Nil) shouldBe empty

    // Giving up took the count with it, so a user who comes back starts clean.
    repo.add(user, satchel, when, "boots")
    repo.claim(user, botA)
    repo.recordDeliveryFailure(user, botA) shouldBe 1

    repo.delAll(user, satchel)
    repo.forget(user, botA)
  }

  test("a delivered DM claims the user and wipes the failures behind them") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcCooldownRepository(provider)

    repo.delAll(user, satchel)
    repo.forget(user, botA)

    repo.add(user, satchel, when, "boots")
    repo.claim(user, botA)
    repo.recordDeliveryFailure(user, botA) shouldBe 1
    repo.recordDeliveryFailure(user, botA) shouldBe 2

    repo.claim(user, botA)
    repo.recordDeliveryFailure(user, botA) shouldBe 1

    repo.delAll(user, satchel)
    repo.forget(user, botA)
  }

  test("the two kinds are separate trackers over the one table") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcCooldownRepository(provider)

    repo.delAll(user, satchel)
    repo.delAll(user, dragon)

    repo.add(user, satchel, when, "boots")
    repo.add(user, dragon, when, "boots")

    // The same tag under both kinds is two rows, each read back as its own kind.
    repo.getStamps(user, satchel).getOrElse(Nil).map(_.kind) shouldBe List(satchel)
    repo.getStamps(user, dragon).getOrElse(Nil).map(_.kind) shouldBe List(dragon)

    // Clearing one leaves the other standing — Clear All is per tracker.
    repo.delAll(user, satchel)
    repo.getStamps(user, satchel).getOrElse(Nil) shouldBe empty
    repo.getStamps(user, dragon).getOrElse(Nil).map(_.tag) shouldBe List("boots")

    // And a sweep only ever collects the kind it was asked for, which matters
    // because each kind's cutoff is now minus its own duration.
    repo.expiredStamps(satchel, now, botA).map(_.tag) should not contain "boots"
    repo.expiredStamps(dragon, now, botA).map(_.tag) should contain("boots")

    repo.delAll(user, dragon)
  }
}
