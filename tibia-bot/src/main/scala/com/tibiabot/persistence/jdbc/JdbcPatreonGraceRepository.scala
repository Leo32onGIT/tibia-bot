package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.PatreonGrace
import com.tibiabot.persistence.{ConnectionProvider, PatreonGraceRepository}

import java.sql.{Connection, Timestamp}
import java.time.{ZoneOffset, ZonedDateTime}
import scala.collection.mutable.ListBuffer

/** JDBC implementation of PatreonGraceRepository, routed through
 *  JdbcSupport.withConnection so the connection is always released. Lives in
 *  the shared `bot_cache` database (`connectionProvider.cache`), alongside
 *  `patreon_seats`. */
final class JdbcPatreonGraceRepository(connectionProvider: ConnectionProvider) extends PatreonGraceRepository {

  private def ensureTable(conn: Connection): Unit = {
    val statement = conn.createStatement()
    val tableExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'patreon_grace'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    if (!tableExists) {
      statement.executeUpdate(
        """CREATE TABLE patreon_grace (
          |guild_id VARCHAR(255) NOT NULL,
          |world VARCHAR(255) NOT NULL,
          |started TIMESTAMP NOT NULL,
          |notified BOOLEAN NOT NULL DEFAULT FALSE,
          |CONSTRAINT unique_grace_guild_world UNIQUE (guild_id, world)
          |);""".stripMargin)
    }

    statement.close()
  }

  def beginGrace(guildId: String, world: String, started: ZonedDateTime): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val statement = conn.prepareStatement(
        "INSERT INTO patreon_grace (guild_id, world, started, notified) VALUES (?, ?, ?, FALSE) " +
        "ON CONFLICT (guild_id, world) DO NOTHING;"
      )
      statement.setString(1, guildId)
      statement.setString(2, world)
      statement.setTimestamp(3, Timestamp.from(started.toInstant))
      statement.executeUpdate()
      statement.close()
    }

  def markNotified(guildId: String, world: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val statement = conn.prepareStatement("UPDATE patreon_grace SET notified = TRUE WHERE guild_id = ? AND world = ?")
      statement.setString(1, guildId)
      statement.setString(2, world)
      statement.executeUpdate()
      statement.close()
    }

  def clearGrace(guildId: String, world: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val statement = conn.prepareStatement("DELETE FROM patreon_grace WHERE guild_id = ? AND world = ?")
      statement.setString(1, guildId)
      statement.setString(2, world)
      statement.executeUpdate()
      statement.close()
    }

  def allGrace(): List[PatreonGrace] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val statement = conn.createStatement()
      val result = statement.executeQuery("SELECT guild_id, world, started, notified FROM patreon_grace")
      val timers = new ListBuffer[PatreonGrace]()
      while (result.next()) {
        timers += PatreonGrace(
          result.getString("guild_id"),
          result.getString("world"),
          ZonedDateTime.ofInstant(result.getTimestamp("started").toInstant, ZoneOffset.UTC),
          result.getBoolean("notified")
        )
      }
      statement.close()
      timers.toList
    }
}
