package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.PlayerCache
import com.tibiabot.persistence.{ActivityRepository, ConnectionProvider}
import org.postgresql.util.PSQLException

import java.sql.Timestamp
import java.time.{Instant, ZoneOffset, ZonedDateTime}
import scala.collection.mutable.ListBuffer

/** JDBC implementation of ActivityRepository. Bodies moved verbatim from BotApp's
 *  activityConfig/addActivityToDatabase/updateActivityToDatabase/
 *  removePlayerActivityfromDatabase/removeGuildActivityfromDatabase, with the
 *  Guild parameter reduced to guildId. */
final class JdbcActivityRepository(connectionProvider: ConnectionProvider) extends ActivityRepository {

  def getActivity(guildId: String): List[PlayerCache] =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    val statement = conn.createStatement()

    // Check if the table already exists in bot_configuration
    val tableExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'tracked_activity'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    // Create the table if it doesn't exist
    if (!tableExists) {
      val createActivityTable =
        s"""CREATE TABLE tracked_activity (
           |name VARCHAR(255) NOT NULL,
           |former_names VARCHAR(255) NOT NULL,
           |guild_name VARCHAR(255) NOT NULL,
           |updated TIMESTAMP NOT NULL,
           |PRIMARY KEY (name)
           |);""".stripMargin

      statement.executeUpdate(createActivityTable)
    }

    val result = statement.executeQuery(s"SELECT name,former_names,guild_name,updated FROM tracked_activity")

    val results = new ListBuffer[PlayerCache]()
    while (result.next()) {
      val name = Option(result.getString("name")).getOrElse("")
      val formerNames = Option(result.getString("former_names")).getOrElse("")
      val guildName = Option(result.getString("guild_name")).getOrElse("")
      val formerNamesList = formerNames.split(",").toList
      val updatedTimeTemporal = Option(result.getTimestamp("updated").toInstant).getOrElse(Instant.parse("2022-01-01T01:00:00Z"))
      val updatedTime = updatedTimeTemporal.atZone(ZoneOffset.UTC)

      results += PlayerCache(name, formerNamesList, guildName, updatedTime)
    }

    statement.close()
    results.toList
  }

  def add(guildId: String, name: String, formerNames: List[String], guildName: String, updatedTime: ZonedDateTime): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    val statement = conn.prepareStatement(
      s"""
         |INSERT INTO tracked_activity(name, former_names, guild_name, updated)
         |VALUES (?,?,?,?)
         |ON CONFLICT (name)
         |DO UPDATE SET
         |  former_names = excluded.former_names,
         |  guild_name = excluded.guild_name,
         |  updated = excluded.updated;
         |""".stripMargin
    )
    statement.setString(1, name)
    statement.setString(2, formerNames.mkString(","))
    statement.setString(3, guildName)
    statement.setTimestamp(4, Timestamp.from(updatedTime.toInstant))
    statement.executeUpdate()

    statement.close()
  }

  def update(guildId: String, name: String, formerNames: List[String], guildName: String, updatedTime: ZonedDateTime, newName: String): Unit = {
    val conn = connectionProvider.guild(guildId)
    val statement = conn.prepareStatement("UPDATE tracked_activity SET name = ?, former_names = ?, guild_name = ?, updated = ? WHERE LOWER(name) = LOWER(?);")
    statement.setString(1, newName)
    statement.setString(2, formerNames.mkString(","))
    statement.setString(3, guildName)
    statement.setTimestamp(4, Timestamp.from(updatedTime.toInstant))
    statement.setString(5, name)

    try {
      statement.executeUpdate()
    } catch {
      case e: PSQLException if e.getMessage.contains("duplicate key value") =>
        val deleteStatement = conn.prepareStatement("DELETE FROM tracked_activity WHERE LOWER(name) = LOWER(?);")
        deleteStatement.setString(1, newName)
        deleteStatement.executeUpdate()
        deleteStatement.close()

        // Retry the update
        val retryStatement = conn.prepareStatement("UPDATE tracked_activity SET name = ?, former_names = ?, guild_name = ?, updated = ? WHERE LOWER(name) = LOWER(?);")
        retryStatement.setString(1, newName)
        retryStatement.setString(2, formerNames.mkString(","))
        retryStatement.setString(3, guildName)
        retryStatement.setTimestamp(4, Timestamp.from(updatedTime.toInstant))
        retryStatement.setString(5, name)
        retryStatement.executeUpdate()
        retryStatement.close()
    } finally {
      statement.close()
      conn.close()
    }
  }

  def removeByGuild(guildId: String, guildName: String): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    val statement = conn.prepareStatement(s"DELETE FROM tracked_activity WHERE LOWER(guild_name) = LOWER(?);")
    statement.setString(1, guildName)
    statement.executeUpdate()

    statement.close()
  }

  def removeByName(guildId: String, name: String): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    val statement = conn.prepareStatement(s"DELETE FROM tracked_activity WHERE LOWER(name) = LOWER(?);")
    statement.setString(1, name)
    statement.executeUpdate()

    statement.close()
  }
}
