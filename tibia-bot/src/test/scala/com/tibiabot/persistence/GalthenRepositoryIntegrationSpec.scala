package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcGalthenRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** Round-trips GalthenRepository against a real Postgres (cancels without PGHOST). */
class GalthenRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val user = "itest_galthen_user"
  private val when = ZonedDateTime.parse("2026-05-30T10:00:00Z")
  private val now = ZonedDateTime.parse("2026-08-30T10:00:00Z")

  test("add / get / del / delAll round-trip on the satchel table") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcGalthenRepository(provider)

    repo.delAll(user) // start from a clean slate
    repo.getStamps(user).getOrElse(Nil) shouldBe empty

    repo.add(user, when, "boots")
    repo.add(user, when, "ring")
    val tags = repo.getStamps(user).getOrElse(Nil).map(_.tag)
    tags should contain allOf ("boots", "ring")

    repo.del(user, "boots")
    repo.getStamps(user).getOrElse(Nil).map(_.tag) should (contain("ring") and not contain "boots")

    repo.delAll(user)
    repo.getStamps(user).getOrElse(Nil) shouldBe empty
  }

  private val botA = "itest_bot_a"
  private val botB = "itest_bot_b"

  test("a bot sweeps the stamps it owns and the unclaimed ones, never another bot's") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcGalthenRepository(provider)

    repo.delAll(user)
    repo.forget(user, botA)
    repo.forget(user, botB)

    repo.add(user, when, "unclaimed")
    repo.expiredStamps(now, botA).map(_.tag) should contain("unclaimed")

    repo.claim(user, botB)
    repo.expiredStamps(now, botA).map(_.tag) should not contain "unclaimed"
    repo.expiredStamps(now, botB).map(_.tag) should contain("unclaimed")

    // ...and clearing only reaches as far as the sweep did.
    repo.deleteExpired(now, botA)
    repo.getStamps(user).getOrElse(Nil).map(_.tag) should contain("unclaimed")
    repo.deleteExpired(now, botB)
    repo.getStamps(user).getOrElse(Nil) shouldBe empty

    repo.delAll(user)
  }

  test("failures accrue per user, and give up only on the stamps that bot owns") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcGalthenRepository(provider)

    repo.delAll(user)
    repo.forget(user, botA)
    repo.forget(user, botB)

    repo.add(user, when, "boots")
    repo.add(user, when, "ring")
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
    repo.getStamps(user).getOrElse(Nil).map(_.tag) should contain allOf ("boots", "ring")

    repo.forget(user, botA)
    repo.getStamps(user).getOrElse(Nil) shouldBe empty

    // Giving up took the count with it, so a user who comes back starts clean.
    repo.add(user, when, "boots")
    repo.claim(user, botA)
    repo.recordDeliveryFailure(user, botA) shouldBe 1

    repo.delAll(user)
    repo.forget(user, botA)
  }

  test("a delivered DM claims the user and wipes the failures behind them") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcGalthenRepository(provider)

    repo.delAll(user)
    repo.forget(user, botA)

    repo.add(user, when, "boots")
    repo.claim(user, botA)
    repo.recordDeliveryFailure(user, botA) shouldBe 1
    repo.recordDeliveryFailure(user, botA) shouldBe 2

    repo.claim(user, botA)
    repo.recordDeliveryFailure(user, botA) shouldBe 1

    repo.delAll(user)
    repo.forget(user, botA)
  }
}
