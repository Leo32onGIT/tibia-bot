package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcBoostedRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Round-trips BoostedRepository against a real Postgres (cancels without PGHOST). */
class BoostedRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val user = "itest_boosted_user"

  test("subscribe / forUser / all / unsubscribe / unsubscribeAll round-trip") {
    val provider = pgOrCancel()
    val repo = new JdbcBoostedRepository(provider)

    repo.unsubscribeAll(user) // clean slate (also creates the table)

    repo.subscribe(user, "Rotworm", "creature")
    repo.subscribe(user, "Ferumbras", "boss")

    val mine = repo.forUser(user)
    mine.map(_.boostedName) should contain allOf ("Rotworm", "Ferumbras")
    mine.find(_.boostedName == "Rotworm").map(_.boostedType) shouldBe Some("creature")
    repo.all().map(_.user) should contain(user)

    // ON CONFLICT (userid, name) DO NOTHING — duplicate subscribe is a no-op
    repo.subscribe(user, "Rotworm", "creature")
    repo.forUser(user).count(_.boostedName == "Rotworm") shouldBe 1

    repo.unsubscribe(user, "rotworm") // case-insensitive
    repo.forUser(user).map(_.boostedName) should (contain("Ferumbras") and not contain "Rotworm")

    repo.unsubscribeAll(user)
    repo.forUser(user) shouldBe empty
  }
}
