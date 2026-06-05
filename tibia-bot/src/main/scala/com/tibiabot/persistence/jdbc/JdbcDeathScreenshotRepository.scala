package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.DeathScreenshot
import com.tibiabot.persistence.{ConnectionProvider, DeathScreenshotRepository}
import com.typesafe.scalalogging.StrictLogging

import java.sql.Timestamp
import java.time.{Instant, ZoneOffset, ZonedDateTime}
import scala.collection.mutable.ListBuffer

/** JDBC implementation of DeathScreenshotRepository. store/get bodies moved
 *  verbatim from BotApp; deleteIfPermitted is BotApp.deleteDeathScreenshot's DB
 *  logic with the JDA permission check replaced by the `permitted` predicate. */
final class JdbcDeathScreenshotRepository(connectionProvider: ConnectionProvider)
    extends DeathScreenshotRepository with StrictLogging {

  def store(guildId: String, world: String, characterName: String, deathTime: Long,
            screenshotUrl: String, addedBy: String, addedName: String, messageId: String): Unit = {
    val conn = connectionProvider.guild(guildId)
    try {
      // Create table if it doesn't exist
      val createTableStatement = conn.createStatement()
      createTableStatement.execute(
        s"""CREATE TABLE IF NOT EXISTS death_screenshots (
           |    guild_id VARCHAR(100) NOT NULL,
           |    world VARCHAR(50) NOT NULL,
           |    character_name VARCHAR(255) NOT NULL,
           |    death_time BIGINT NOT NULL,
           |    screenshot_url TEXT NOT NULL,
           |    added_by VARCHAR(100) NOT NULL,
           |    added_name VARCHAR(100) NOT NULL,
           |    added_at TIMESTAMP NOT NULL,
           |    message_id VARCHAR(100) NOT NULL,
           |    PRIMARY KEY (guild_id, world, character_name, death_time, screenshot_url)
           |)""".stripMargin)
      createTableStatement.close()

      // Insert screenshot
      val insertStatement = conn.prepareStatement(
        "INSERT INTO death_screenshots (guild_id, world, character_name, death_time, screenshot_url, added_by, added_name, added_at, message_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
      )
      insertStatement.setString(1, guildId)
      insertStatement.setString(2, world)
      insertStatement.setString(3, characterName)
      insertStatement.setLong(4, deathTime)
      insertStatement.setString(5, screenshotUrl)
      insertStatement.setString(6, addedBy)
      insertStatement.setString(7, addedName)
      insertStatement.setTimestamp(8, Timestamp.from(Instant.now()))
      insertStatement.setString(9, messageId)
      insertStatement.executeUpdate()
      insertStatement.close()
    } catch {
      case ex: Exception => logger.error(s"Failed to store death screenshot: ${ex.getMessage}")
    } finally {
      conn.close()
    }
  }

  def get(guildId: String, world: String, characterName: String, deathTime: Long): List[DeathScreenshot] = {
    val conn = connectionProvider.guild(guildId)
    val screenshots = ListBuffer[DeathScreenshot]()
    try {
      val selectStatement = conn.prepareStatement(
        "SELECT * FROM death_screenshots WHERE guild_id = ? AND character_name = ? AND death_time = ? ORDER BY added_at ASC"
      )
      selectStatement.setString(1, guildId)
      selectStatement.setString(2, characterName)
      selectStatement.setLong(3, deathTime)
      val resultSet = selectStatement.executeQuery()

      while (resultSet.next()) {
        screenshots += DeathScreenshot(
          guildId = resultSet.getString("guild_id"),
          world = resultSet.getString("world"),
          characterName = resultSet.getString("character_name"),
          deathTime = resultSet.getLong("death_time"),
          screenshotUrl = resultSet.getString("screenshot_url"),
          addedBy = resultSet.getString("added_by"),
          addedName = resultSet.getString("added_name"),
          addedAt = ZonedDateTime.ofInstant(resultSet.getTimestamp("added_at").toInstant, ZoneOffset.UTC),
          messageId = resultSet.getString("message_id")
        )
      }
      resultSet.close()
      selectStatement.close()
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to get death screenshots: ${ex.getMessage}")
    } finally {
      conn.close()
    }
    screenshots.toList
  }

  def deleteIfPermitted(guildId: String, characterName: String, deathTime: Long, screenshotUrl: String)
                       (permitted: String => Boolean): Boolean = {
    val conn = connectionProvider.guild(guildId)
    var deleted = false
    try {
      // First check who added the screenshot, then let the caller's predicate decide
      val checkStatement = conn.prepareStatement(
        "SELECT added_by FROM death_screenshots WHERE guild_id = ? AND character_name = ? AND death_time = ? AND screenshot_url = ?"
      )
      checkStatement.setString(1, guildId)
      checkStatement.setString(2, characterName)
      checkStatement.setLong(3, deathTime)
      checkStatement.setString(4, screenshotUrl)
      val resultSet = checkStatement.executeQuery()

      if (resultSet.next()) {
        val addedBy = resultSet.getString("added_by")
        if (permitted(addedBy)) {
          val deleteStatement = conn.prepareStatement(
            "DELETE FROM death_screenshots WHERE guild_id = ? AND character_name = ? AND death_time = ? AND screenshot_url = ?"
          )
          deleteStatement.setString(1, guildId)
          deleteStatement.setString(2, characterName)
          deleteStatement.setLong(3, deathTime)
          deleteStatement.setString(4, screenshotUrl)
          val rowsDeleted = deleteStatement.executeUpdate()
          deleted = rowsDeleted > 0
          deleteStatement.close()
        }
      }
      resultSet.close()
      checkStatement.close()
    } catch {
      case ex: Exception => logger.error(s"Failed to delete death screenshot: ${ex.getMessage}")
    } finally {
      conn.close()
    }
    deleted
  }
}
