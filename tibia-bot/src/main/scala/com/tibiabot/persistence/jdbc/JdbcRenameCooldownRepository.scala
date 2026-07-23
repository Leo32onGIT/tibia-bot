package com.tibiabot.persistence.jdbc

import com.tibiabot.persistence.{ConnectionProvider, RenameCooldownRepository}

import java.sql.{Connection, Timestamp}
import java.time.{ZoneOffset, ZonedDateTime}
import scala.collection.mutable

/** JDBC implementation of RenameCooldownRepository, routed through
 *  JdbcSupport.withConnection so the connection is always released. Lives in
 *  the shared `bot_cache` database (`connectionProvider.cache`), not a
 *  guild's own database — channel/category snowflake ids are globally unique,
 *  so `world` is stored purely to scope the bulk load query per TibiaBot
 *  instance. */
final class JdbcRenameCooldownRepository(connectionProvider: ConnectionProvider) extends RenameCooldownRepository {

  private def ensureTable(conn: Connection): Unit = {
    val statement = conn.createStatement()
    val tableExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'rename_cooldowns'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    if (!tableExists) {
      statement.executeUpdate(
        """CREATE TABLE rename_cooldowns (
          |channel_id VARCHAR(255) PRIMARY KEY,
          |world VARCHAR(255) NOT NULL,
          |last_rename TIMESTAMP NOT NULL
          |);""".stripMargin)
    }
    statement.close()
  }

  def recordRename(world: String, channelOrCategoryId: String, at: ZonedDateTime): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val statement = conn.prepareStatement(
        "INSERT INTO rename_cooldowns (channel_id, world, last_rename) VALUES (?, ?, ?) " +
        "ON CONFLICT (channel_id) DO UPDATE SET world = EXCLUDED.world, last_rename = EXCLUDED.last_rename;"
      )
      statement.setString(1, channelOrCategoryId)
      statement.setString(2, world)
      statement.setTimestamp(3, Timestamp.from(at.toInstant))
      statement.executeUpdate()
      statement.close()
    }

  def loadForWorld(world: String): Map[String, ZonedDateTime] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val statement = conn.prepareStatement("SELECT channel_id, last_rename FROM rename_cooldowns WHERE world = ?")
      statement.setString(1, world)
      val result = statement.executeQuery()
      val results = mutable.Map.empty[String, ZonedDateTime]
      while (result.next()) {
        val channelId = result.getString("channel_id")
        val lastRename = ZonedDateTime.ofInstant(result.getTimestamp("last_rename").toInstant, ZoneOffset.UTC)
        results += channelId -> lastRename
      }
      statement.close()
      results.toMap
    }
}
