package com.tibiabot.persistence

import java.sql.Connection

/** Port for obtaining JDBC connections, isolating the database-per-guild URL
 *  juggling from the many call sites that open connections. Lets repositories
 *  be pointed at a Dockerized Postgres in tests, and is the single seam where
 *  the injection-prone SQL gets fixed later without touching callers. */
trait ConnectionProvider {
  /** Connection to a guild's own database (`_<guildId>`). */
  def guild(guildId: String): Connection

  /** As [[guild]], but guaranteed not to come from a shared pool.
   *
   *  For the one caller that holds a connection open while its own body asks
   *  for more — [[com.tibiabot.persistence.jdbc.JdbcRespawnRepository.withRespawnLock]],
   *  which takes a row lock and then does ordinary reads inside it. Drawn from a
   *  pool that is also serving those reads, enough simultaneous lock holders
   *  would each be waiting for a connection the others are holding, and none of
   *  them could finish. Keeping the outer connection outside the pool is what
   *  makes that impossible rather than merely unlikely.
   *
   *  Defaults to [[guild]], which is the whole answer for a provider that does
   *  not pool in the first place.
   */
  def guildUnpooled(guildId: String): Connection = guild(guildId)

  /** Connection to the shared `bot_cache` database. */
  def cache(): Connection
  /** Maintenance connection to the default `postgres` database. */
  def admin(): Connection
  /** Connection to the `premium` database. PLANNED — only used by
   *  SchemaInitializer.initPremium (the not-yet-wired Patreon/premium tier);
   *  kept intentionally, not dead code. */
  def premium(): Connection

  /** Let go of everything held open against a guild's database.
   *
   *  Called before the database is dropped: Postgres refuses to drop a database
   *  anything is still connected to, so a pool holding an idle connection to a
   *  guild the bot has just left would keep that guild's database alive for as
   *  long as the process ran.
   *
   *  A no-op where nothing is held — see [[JdbcConnectionProvider]].
   */
  def evictGuild(guildId: String): Unit = ()
}
