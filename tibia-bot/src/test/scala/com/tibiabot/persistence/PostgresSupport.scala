package com.tibiabot.persistence

import org.scalatest.funsuite.AnyFunSuite

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

  /** Creates the shared `bot_cache` database and its tables, exactly once per
   *  test JVM. Every spec that touches the cache database must go through this
   *  rather than running its own CREATE: sbt runs suites in parallel inside one
   *  JVM, and against a brand-new Postgres two threads would otherwise both find
   *  the database (or a table) missing and both try to create it — failing with
   *  `database "bot_cache" does not exist` or a duplicate `pg_type` key. */
  protected def ensureCacheSchema(provider: JdbcConnectionProvider): Unit =
    PostgresSupport.ensureCacheSchema(provider)
}

private object PostgresSupport {

  private var initialised = false

  /** Deliberately implemented via `SchemaInitializer.initCache` — that is the
   *  production path SchemaInitializerIntegrationSpec asserts on, so the specs
   *  set up their database the same way the bot does. */
  def ensureCacheSchema(provider: JdbcConnectionProvider): Unit = synchronized {
    if (!initialised) {
      new SchemaInitializer(provider).initCache()
      initialised = true
    }
  }
}
