package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcGalthenRepository
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime

/** Round-trips GalthenRepository against a real Postgres (cancels without PGHOST). */
class GalthenRepositoryIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val user = "itest_galthen_user"
  private val when = ZonedDateTime.parse("2026-05-30T10:00:00Z")

  test("add / get / del / delAll round-trip on the satchel table") {
    val provider = pgOrCancel()
    ensureCacheDatabase(provider)
    val repo = new JdbcGalthenRepository(provider)

    repo.getStamps(user) // creates the table on first use
    repo.delAll(user)    // start from a clean slate
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

  /** Create the bot_cache database the repository connects to, if absent. */
  private def ensureCacheDatabase(provider: JdbcConnectionProvider): Unit = {
    val conn = provider.admin()
    try {
      val rs = conn.createStatement()
        .executeQuery("SELECT datname FROM pg_database WHERE datname = 'bot_cache'")

      if (!rs.next()) {
        conn.createStatement()
          .executeUpdate("CREATE DATABASE bot_cache")
      }
    } catch {
      case _ : Throwable => //
    } finally {
      conn.close()
    }
  }
}
