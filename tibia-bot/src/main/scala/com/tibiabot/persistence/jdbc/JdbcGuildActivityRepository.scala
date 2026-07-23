package com.tibiabot.persistence.jdbc

import com.tibiabot.persistence.{ConnectionProvider, GuildActivityRepository}

import java.sql.{Connection, Timestamp}
import java.time.{ZoneOffset, ZonedDateTime}

/** JDBC implementation of GuildActivityRepository, routed through
 *  JdbcSupport.withConnection so the connection is always released. Lives in
 *  the shared `bot_cache` database (`connectionProvider.cache`), not a
 *  guild's own database. */
final class JdbcGuildActivityRepository(connectionProvider: ConnectionProvider) extends GuildActivityRepository {

  private def ensureTable(conn: Connection): Unit = {
    val statement = conn.createStatement()
    val tableExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'guild_activity'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    if (!tableExists) {
      statement.executeUpdate(
        """CREATE TABLE guild_activity (
          |guild_id VARCHAR(255) PRIMARY KEY,
          |last_command_at TIMESTAMP,
          |worldless_since TIMESTAMP
          |);""".stripMargin)
    }
    statement.close()
  }

  def recordCommandRun(guildId: String, at: ZonedDateTime): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val statement = conn.prepareStatement(
        "INSERT INTO guild_activity (guild_id, last_command_at) VALUES (?, ?) " +
        "ON CONFLICT (guild_id) DO UPDATE SET last_command_at = EXCLUDED.last_command_at;"
      )
      statement.setString(1, guildId)
      statement.setTimestamp(2, Timestamp.from(at.toInstant))
      statement.executeUpdate()
      statement.close()
    }

  def lastCommandAt(guildId: String): Option[ZonedDateTime] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val statement = conn.prepareStatement("SELECT last_command_at FROM guild_activity WHERE guild_id = ?")
      statement.setString(1, guildId)
      val result = statement.executeQuery()
      val at = if (result.next()) Option(result.getTimestamp("last_command_at")) else None
      statement.close()
      at.map(ts => ZonedDateTime.ofInstant(ts.toInstant, ZoneOffset.UTC))
    }

  def markWorldlessIfUnset(guildId: String, now: ZonedDateTime): ZonedDateTime =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val selectStatement = conn.prepareStatement("SELECT worldless_since FROM guild_activity WHERE guild_id = ?")
      selectStatement.setString(1, guildId)
      val result = selectStatement.executeQuery()
      val existing = if (result.next()) Option(result.getTimestamp("worldless_since")) else None
      selectStatement.close()

      existing match {
        case Some(ts) => ZonedDateTime.ofInstant(ts.toInstant, ZoneOffset.UTC)
        case None =>
          val upsertStatement = conn.prepareStatement(
            "INSERT INTO guild_activity (guild_id, worldless_since) VALUES (?, ?) " +
            "ON CONFLICT (guild_id) DO UPDATE SET worldless_since = EXCLUDED.worldless_since;"
          )
          upsertStatement.setString(1, guildId)
          upsertStatement.setTimestamp(2, Timestamp.from(now.toInstant))
          upsertStatement.executeUpdate()
          upsertStatement.close()
          now
      }
    }

  def clearWorldless(guildId: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val statement = conn.prepareStatement(
        "INSERT INTO guild_activity (guild_id, worldless_since) VALUES (?, NULL) " +
        "ON CONFLICT (guild_id) DO UPDATE SET worldless_since = NULL;"
      )
      statement.setString(1, guildId)
      statement.executeUpdate()
      statement.close()
    }
}
