package com.tibiabot.persistence

/** Pure builders for the four JDBC URL shapes the bot uses, extracted so URL
 *  construction is unit-testable without a database. Strings reproduce the
 *  originals in BotApp verbatim. */
object JdbcUrls {
  private def base(host: String, port: Int): String = s"jdbc:postgresql://$host:$port"

  /** Per-guild database, named `_<guildId>`. */
  def guild(host: String, guildId: String, port: Int = 5432): String = s"${base(host, port)}/_$guildId"

  /** Shared cache database (`bot_cache`). */
  def cache(host: String, port: Int = 5432): String = s"${base(host, port)}/bot_cache"

  /** Maintenance connection (the default `postgres` database). */
  def admin(host: String, port: Int = 5432): String = s"${base(host, port)}/postgres"

  /** Premium database (PLANNED Patreon/premium tier — see
   *  SchemaInitializer.initPremium; not wired into runtime yet, kept on purpose). */
  def premium(host: String, port: Int = 5432): String = s"${base(host, port)}/premium"
}
