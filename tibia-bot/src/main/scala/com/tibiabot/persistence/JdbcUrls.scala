package com.tibiabot.persistence

/** Pure builders for the four JDBC URL shapes the bot uses, extracted so URL
 *  construction is unit-testable without a database. Strings reproduce the
 *  originals in BotApp verbatim. */
object JdbcUrls {
  private def base(host: String): String = s"jdbc:postgresql://$host:5432"

  /** Per-guild database, named `_<guildId>`. */
  def guild(host: String, guildId: String): String = s"${base(host)}/_$guildId"

  /** Shared cache database (`bot_cache`). */
  def cache(host: String): String = s"${base(host)}/bot_cache"

  /** Maintenance connection (the default `postgres` database). */
  def admin(host: String): String = s"${base(host)}/postgres"

  /** Premium database. */
  def premium(host: String): String = s"${base(host)}/premium"
}
