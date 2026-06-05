package com.tibiabot.persistence

import org.scalatest.funsuite.AnyFunSuite
import com.tibiabot.Config

/** Mix-in for Postgres integration tests.
 *
 *  Integration specs need a real database. When `PGHOST` is not set (e.g. a
 *  plain local `sbt test` with no DB), `pgOrCancel()` cancels the test instead
 *  of failing it — so the default test run stays green everywhere. CI sets
 *  `PGHOST`/`PGPASSWORD` (via a postgres service) so the same tests run for real.
 */
trait PostgresSupport { self: AnyFunSuite =>

  protected def pgConfigured: Boolean = sys.env.get("PGHOST").exists(_.nonEmpty)

  protected def pgOrCancel(): JdbcConnectionProvider = {
    val host = sys.env.getOrElse("PGHOST", "")
    if (host.isEmpty) cancel("PGHOST not set; skipping Postgres integration test")
    new JdbcConnectionProvider(host, sys.env.getOrElse("PGPASSWORD", "postgres"))
  }
}
