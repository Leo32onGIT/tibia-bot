package com.tibiabot.persistence

import java.sql.{Connection, DriverManager}

/** DriverManager-backed ConnectionProvider. Reproduces today's exact URL
 *  shapes, username and password — no behaviour change. */
final class JdbcConnectionProvider(host: String, password: String, user: String = "postgres", port: Int = 5432)
    extends ConnectionProvider {

  def guild(guildId: String): Connection = open(JdbcUrls.guild(host, guildId, port))
  def cache(): Connection = open(JdbcUrls.cache(host, port))
  def admin(): Connection = open(JdbcUrls.admin(host, port))
  def premium(): Connection = open(JdbcUrls.premium(host, port))

  private def open(url: String): Connection = DriverManager.getConnection(url, user, password)
}
