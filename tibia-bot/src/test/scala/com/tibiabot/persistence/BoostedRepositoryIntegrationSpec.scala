package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcBoostedRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Round-trips boosted notification subscriptions, and the bot-ownership
 *  routing that decides which of several bots sharing this table DMs a given
 *  subscriber (cancels without PGHOST). */
class BoostedRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val blue = "ITestDmBotBlue"
  private val red = "ITestDmBotRed"

  test("a new subscription starts unclaimed and reads back") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcBoostedRepository(provider)
    val user = "ITestDmUserNew"
    repo.unsubscribeAll(user)

    repo.subscribe(user, "grim reaper", "creature")

    val rows = repo.forUser(user)
    rows.map(_.boostedName) should contain("grim reaper")
    rows.head.botId shouldBe ""
    rows.head.dmFailures shouldBe 0
  }

  test("claim takes ownership of every row for the user and clears failures") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcBoostedRepository(provider)
    val user = "ITestDmUserClaim"
    repo.unsubscribeAll(user)

    repo.subscribe(user, "all", "all")
    repo.subscribe(user, "yirkas blue", "boss")
    repo.claim(user, blue)
    repo.recordDeliveryFailure(user, blue) shouldBe 1

    // a later delivery (or /boosted command) resets the run of failures
    repo.claim(user, blue)
    repo.forUser(user).map(_.botId).distinct shouldBe List(blue)
    repo.forUser(user).map(_.dmFailures).distinct shouldBe List(0)
  }

  test("a failure against rows another bot owns counts for nothing") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcBoostedRepository(provider)
    val user = "ITestDmUserWrongBot"
    repo.unsubscribeAll(user)

    repo.subscribe(user, "all", "all")
    repo.claim(user, blue)

    // Red can't reach a user blue owns, and must never be able to spend down
    // their subscription — this is the path that used to delete the whole list.
    repo.recordDeliveryFailure(user, red) shouldBe 0
    repo.recordDeliveryFailure(user, red) shouldBe 0
    repo.recordDeliveryFailure(user, red) shouldBe 0
    repo.forUser(user).map(_.dmFailures).distinct shouldBe List(0)
    repo.forUser(user) should not be empty
  }

  test("an unclaimed row's failure counts for nothing either") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcBoostedRepository(provider)
    val user = "ITestDmUserUnclaimed"
    repo.unsubscribeAll(user)

    repo.subscribe(user, "all", "all")

    repo.recordDeliveryFailure(user, blue) shouldBe 0
    repo.forUser(user) should not be empty
    repo.forUser(user).head.botId shouldBe ""
  }

  test("consecutive failures accumulate for the owning bot") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcBoostedRepository(provider)
    val user = "ITestDmUserFailRun"
    repo.unsubscribeAll(user)

    repo.subscribe(user, "all", "all")
    repo.claim(user, blue)

    repo.recordDeliveryFailure(user, blue) shouldBe 1
    repo.recordDeliveryFailure(user, blue) shouldBe 2
    repo.recordDeliveryFailure(user, blue) shouldBe 3
  }

  test("unsubscribeAllFor drops only the calling bot's rows") {
    val provider = pgOrCancel()
    ensureCacheSchema(provider)
    val repo = new JdbcBoostedRepository(provider)
    val blueUser = "ITestDmUserBlueOwned"
    val redUser = "ITestDmUserRedOwned"
    repo.unsubscribeAll(blueUser)
    repo.unsubscribeAll(redUser)

    repo.subscribe(blueUser, "all", "all")
    repo.claim(blueUser, blue)
    repo.subscribe(redUser, "all", "all")
    repo.claim(redUser, red)

    repo.unsubscribeAllFor(blueUser, blue)

    repo.forUser(blueUser) shouldBe empty
    repo.forUser(redUser) should not be empty
    // and blue giving up on its own user leaves red's untouched
    repo.all().filter(_.user == redUser).map(_.botId).distinct shouldBe List(red)
  }
}
