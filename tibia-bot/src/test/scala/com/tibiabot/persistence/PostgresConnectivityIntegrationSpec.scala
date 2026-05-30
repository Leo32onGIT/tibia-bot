package com.tibiabot.persistence

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Smoke test proving the integration-test pipeline works end to end:
 *  the JdbcConnectionProvider opens a real connection and runs a query.
 *  Cancels (does not fail) when no PGHOST is configured. */
class PostgresConnectivityIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  test("admin connection runs a trivial query against a real Postgres") {
    val provider = pgOrCancel()
    val conn = provider.admin()
    try {
      val rs = conn.createStatement().executeQuery("SELECT 1")
      rs.next() shouldBe true
      rs.getInt(1) shouldBe 1
    } finally conn.close()
  }
}
