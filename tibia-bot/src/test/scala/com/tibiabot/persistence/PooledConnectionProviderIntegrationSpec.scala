package com.tibiabot.persistence

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.sql.Connection

/** What pooling has to be true for, against a real Postgres (cancels without
 *  PGHOST).
 *
 *  Every assertion here is about the backend process on the server rather than
 *  about the Java object: `pg_backend_pid()` is the same number for as long as
 *  one connection is genuinely being reused, and a different one the moment a
 *  fresh one has been opened. That is the only thing that distinguishes a pool
 *  that works from one that quietly opens a connection per call anyway.
 */
class PooledConnectionProviderIntegrationSpec extends AnyFunSuite with Matchers with PostgresSupport {

  private val guildId = "888000888000888501"

  private def backendPid(conn: Connection): Int = {
    val statement = conn.createStatement()
    try {
      val result = statement.executeQuery("SELECT pg_backend_pid();")
      result.next()
      result.getInt(1)
    } finally statement.close()
  }

  private def pidOf(open: () => Connection): Int = {
    val conn = open()
    try backendPid(conn) finally conn.close()
  }

  private def pooled(direct: JdbcConnectionProvider, poolIdleMillis: Long = PooledConnectionProvider.PoolIdle) =
    new PooledConnectionProvider(
      sys.env.getOrElse("PGHOST", ""), sys.env.getOrElse("PGPASSWORD", "postgres"),
      poolIdleMillis = poolIdleMillis, unpooled = direct)

  private def withGuildDatabase(direct: JdbcConnectionProvider)(body: => Unit): Unit = {
    new SchemaInitializer(direct).initGuild(guildId, "pool-spec")
    try body finally new SchemaInitializer(direct).dropGuild(guildId)
  }

  test("a closed connection goes back to the pool rather than to the server") {
    val direct = pgOrCancel()
    withGuildDatabase(direct) {
      val provider = pooled(direct)
      try {
        val first = pidOf(() => provider.guild(guildId))
        val second = pidOf(() => provider.guild(guildId))
        // The point of the whole exercise: the second query did not pay for a
        // handshake, because it is talking to the backend the first one left.
        second shouldBe first
      } finally provider.close()
    }
  }

  test("guildUnpooled never hands out the pool's connection") {
    val direct = pgOrCancel()
    withGuildDatabase(direct) {
      val provider = pooled(direct)
      try {
        val pooledPid = pidOf(() => provider.guild(guildId))
        // Held open, so the pool could only satisfy the second ask by opening
        // something new — which is exactly what must not be shared with it.
        val held = provider.guildUnpooled(guildId)
        try {
          backendPid(held) should not be pooledPid
          // And the pool is untouched by it: still the same backend as before.
          pidOf(() => provider.guild(guildId)) shouldBe pooledPid
        } finally held.close()
      } finally provider.close()
    }
  }

  test("a swept pool is replaced rather than reused") {
    val direct = pgOrCancel()
    withGuildDatabase(direct) {
      // Nothing survives a sweep, so the second ask has to start a pool of its own.
      val provider = pooled(direct, poolIdleMillis = 0L)
      try {
        val first = pidOf(() => provider.guild(guildId))
        provider.sweep()
        provider.size shouldBe 0
        pidOf(() => provider.guild(guildId)) should not be first
      } finally provider.close()
    }
  }

  test("dropping a guild's database is not blocked by the pool holding it open") {
    val direct = pgOrCancel()
    val initializer = new SchemaInitializer(direct)
    initializer.initGuild(guildId, "pool-spec")
    val provider = pooled(direct)
    try {
      // Leaves an idle connection to that database in the pool, which is what
      // Postgres refuses to drop a database out from under.
      pidOf(() => provider.guild(guildId))
      new SchemaInitializer(provider).dropGuild(guildId)
      initializer.guildDatabaseExists(guildId) shouldBe false
    } finally {
      provider.close()
      if (initializer.guildDatabaseExists(guildId)) initializer.dropGuild(guildId)
    }
  }
}
