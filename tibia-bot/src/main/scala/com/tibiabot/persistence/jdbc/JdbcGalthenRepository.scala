package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.SatchelStamp
import com.tibiabot.persistence.{ConnectionProvider, GalthenRepository}

import java.sql.Timestamp
import java.time.{Instant, ZoneOffset, ZonedDateTime}
import scala.collection.mutable.ListBuffer
import scala.util.Try

/** JDBC implementation of GalthenRepository, routed through
 *  JdbcSupport.withConnection so the connection is always released. */
final class JdbcGalthenRepository(connectionProvider: ConnectionProvider) extends GalthenRepository {

  private def ensureTable(statement: java.sql.Statement): Unit = {
    val tableExistsQuery =
      statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'satchel'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    if (!tableExists) {
      val createListTable =
        s"""CREATE TABLE satchel (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |userid VARCHAR(255) NOT NULL,
           |time VARCHAR(255) NOT NULL,
           |tag VARCHAR(255),
           |bot_id VARCHAR(255) NOT NULL DEFAULT ''
           |);""".stripMargin

      statement.executeUpdate(createListTable)
    }

    val columnQuery = statement.executeQuery(
      "SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'satchel' AND COLUMN_NAME = 'bot_id'")
    val botIdExists = columnQuery.next()
    columnQuery.close()

    // Which bot delivers this user's expiry DM. Stamps predating the column
    // start unclaimed ('') and are claimed by whichever bot first gets a DM
    // through to them — the same handover boosted_notifications uses.
    if (!botIdExists) {
      statement.execute("ALTER TABLE satchel ADD COLUMN bot_id VARCHAR(255) NOT NULL DEFAULT ''")
    }

    // Undeliverable expiry DMs, counted per user instead of per stamp: the row
    // a DM was sent for is deleted in the same sweep, so a count kept on it
    // would reset to zero every time and never reach the giving-up threshold.
    val createFailuresTable =
      s"""CREATE TABLE IF NOT EXISTS satchel_dm_failures (
         |userid VARCHAR(255) NOT NULL,
         |bot_id VARCHAR(255) NOT NULL DEFAULT '',
         |failures INT NOT NULL DEFAULT 0,
         |CONSTRAINT unique_satchel_failures_constraint UNIQUE (userid, bot_id)
         |);""".stripMargin
    statement.executeUpdate(createFailuresTable)
  }

  private def readStamp(result: java.sql.ResultSet, userId: String): SatchelStamp = {
    val updatedTimeTemporal =
      Try(Option(result.getTimestamp("time").toInstant).getOrElse(Instant.parse("2022-01-01T01:00:00Z")))
        .getOrElse(Instant.parse("2022-01-01T01:00:00Z"))
    val updatedTime = updatedTimeTemporal.atZone(ZoneOffset.UTC)
    val tag = Option(result.getString("tag")).getOrElse("")

    SatchelStamp(userId, updatedTime, tag)
  }

  def getStamps(userId: String): Option[List[SatchelStamp]] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.createStatement()
      ensureTable(statement)

      val result = statement.executeQuery(s"SELECT time,tag FROM satchel WHERE userid = '$userId';")

      val satchelStampList: ListBuffer[SatchelStamp] = ListBuffer()

      while (result.next()) {
        satchelStampList += readStamp(result, userId)
      }

      statement.close()
      Some(satchelStampList.toList)
    }

  def del(user: String, tag: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val deleteStatement = conn.prepareStatement("DELETE FROM satchel WHERE userid = ? AND COALESCE(tag, '') = ?;")
      deleteStatement.setString(1, user)
      deleteStatement.setString(2, tag)
      deleteStatement.executeUpdate()

      deleteStatement.close()
    }

  def delAll(user: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val deleteStatement = conn.prepareStatement("DELETE FROM satchel WHERE userid = ?;")
      deleteStatement.setString(1, user)
      deleteStatement.executeUpdate()

      deleteStatement.close()
    }

  def add(user: String, when: ZonedDateTime, tag: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val selectStatement = conn.prepareStatement("SELECT time FROM satchel WHERE userid = ? AND tag = ?;")
      selectStatement.setString(1, user)
      selectStatement.setString(2, tag)
      val resultSet = selectStatement.executeQuery()

      if (resultSet.next()) {
        val updateStatement = conn.prepareStatement(
          s"""
             |UPDATE satchel
             |SET time = ?
             |WHERE userid = ? AND tag = ?;
             |""".stripMargin
        )
        updateStatement.setTimestamp(1, Timestamp.from(when.toInstant))
        updateStatement.setString(2, user)
        updateStatement.setString(3, tag)
        updateStatement.executeUpdate()
        updateStatement.close()
      } else {
        val insertStatement = conn.prepareStatement(
          s"""
             |INSERT INTO satchel(userid, time, tag)
             |VALUES (?,?,?);
             |""".stripMargin
        )
        insertStatement.setString(1, user)
        insertStatement.setTimestamp(2, Timestamp.from(when.toInstant))
        insertStatement.setString(3, tag)
        insertStatement.executeUpdate()
        insertStatement.close()
      }

      selectStatement.close()
    }

  def expiredStamps(before: ZonedDateTime, botId: String): List[SatchelStamp] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val ensure = conn.createStatement(); ensureTable(ensure); ensure.close()

      val statement = conn.prepareStatement(
        "SELECT userid,time,tag FROM satchel WHERE time < ? AND (bot_id = ? OR bot_id = '');")
      statement.setTimestamp(1, Timestamp.from(before.toInstant))
      statement.setString(2, botId)
      val result = statement.executeQuery()

      val satchelStampList: ListBuffer[SatchelStamp] = ListBuffer()
      while (result.next()) {
        satchelStampList += readStamp(result, Option(result.getString("userid")).getOrElse(""))
      }

      statement.close()
      satchelStampList.toList
    }

  def deleteExpired(before: ZonedDateTime, botId: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val ensure = conn.createStatement(); ensureTable(ensure); ensure.close()

      val statement = conn.prepareStatement("DELETE FROM satchel WHERE time < ? AND (bot_id = ? OR bot_id = '');")
      statement.setTimestamp(1, Timestamp.from(before.toInstant))
      statement.setString(2, botId)
      statement.executeUpdate()
      statement.close()
    }

  def claim(userId: String, botId: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val ensure = conn.createStatement(); ensureTable(ensure); ensure.close()

      // Every stamp for the user, not just the unclaimed ones: ownership follows
      // whichever bot most recently reached them, so someone who moves between
      // servers starts getting their satchel DMs from the bot that's actually there.
      val statement = conn.prepareStatement("UPDATE satchel SET bot_id = ? WHERE userid = ?")
      statement.setString(1, botId)
      statement.setString(2, userId)
      statement.executeUpdate()
      statement.close()

      val clear = conn.prepareStatement("DELETE FROM satchel_dm_failures WHERE userid = ? AND bot_id = ?")
      clear.setString(1, userId)
      clear.setString(2, botId)
      clear.executeUpdate()
      clear.close()
    }

  def recordDeliveryFailure(userId: String, botId: String): Int =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val ensure = conn.createStatement(); ensureTable(ensure); ensure.close()

      // Nothing of this bot's to give up on: the failure means only that it
      // isn't the bot sharing a guild with them, so it isn't counted.
      val owned = conn.prepareStatement("SELECT 1 FROM satchel WHERE userid = ? AND bot_id = ? LIMIT 1")
      owned.setString(1, userId)
      owned.setString(2, botId)
      val ownedResult = owned.executeQuery()
      val ownsAny = ownedResult.next()
      owned.close()

      if (!ownsAny) 0
      else {
        val statement = conn.prepareStatement(
          """INSERT INTO satchel_dm_failures(userid, bot_id, failures) VALUES (?, ?, 1)
            |ON CONFLICT (userid, bot_id) DO UPDATE SET failures = satchel_dm_failures.failures + 1
            |RETURNING failures""".stripMargin)
        statement.setString(1, userId)
        statement.setString(2, botId)
        val result = statement.executeQuery()
        val count = if (result.next()) result.getInt("failures") else 0
        statement.close()
        count
      }
    }

  def forget(userId: String, botId: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val ensure = conn.createStatement(); ensureTable(ensure); ensure.close()

      val statement = conn.prepareStatement("DELETE FROM satchel WHERE userid = ? AND bot_id = ?")
      statement.setString(1, userId)
      statement.setString(2, botId)
      statement.executeUpdate()
      statement.close()

      val clear = conn.prepareStatement("DELETE FROM satchel_dm_failures WHERE userid = ? AND bot_id = ?")
      clear.setString(1, userId)
      clear.setString(2, botId)
      clear.executeUpdate()
      clear.close()
    }
}
