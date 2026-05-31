package com.tibiabot.persistence

import java.sql.Connection

/** Port for obtaining JDBC connections, isolating the database-per-guild URL
 *  juggling from the many call sites that open connections. Lets repositories
 *  be pointed at a Dockerized Postgres in tests, and is the single seam where
 *  the injection-prone SQL gets fixed later without touching callers. */
trait ConnectionProvider {
  /** Connection to a guild's own database (`_<guildId>`). */
  def guild(guildId: String): Connection
  /** Connection to the shared `bot_cache` database. */
  def cache(): Connection
  /** Maintenance connection to the default `postgres` database. */
  def admin(): Connection
  /** Connection to the `premium` database. PLANNED — only used by
   *  SchemaInitializer.initPremium (the not-yet-wired Patreon/premium tier);
   *  kept intentionally, not dead code. */
  def premium(): Connection
}
