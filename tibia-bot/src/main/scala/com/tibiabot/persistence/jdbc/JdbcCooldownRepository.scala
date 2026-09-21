package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.{CooldownKind, CooldownStamp}
import com.tibiabot.persistence.{ConnectionProvider, CooldownRepository}

import java.sql.Timestamp
import java.time.{Instant, ZoneOffset, ZonedDateTime}
import scala.collection.mutable.ListBuffer
import scala.util.Try

/** JDBC implementation of CooldownRepository, routed through
 *  JdbcSupport.withConnection so the connection is always released. */
final class JdbcCooldownRepository(connectionProvider: ConnectionProvider) extends CooldownRepository {

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
           |bot_id VARCHAR(255) NOT NULL DEFAULT '',
           |kind VARCHAR(32) NOT NULL DEFAULT '${CooldownKind.Satchel.id}'
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

    val kindQuery = statement.executeQuery(
      "SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'satchel' AND COLUMN_NAME = 'kind'")
    val kindExists = kindQuery.next()
    kindQuery.close()

    // Which collectible a row is for. Rows predating the column are satchels,
    // because that was the only thing tracked then — and the default keeps an
    // older bot sharing this table writing correct satchel rows rather than
    // failing the insert on a column it doesn't know about.
    if (!kindExists) {
      statement.execute(
        s"ALTER TABLE satchel ADD COLUMN kind VARCHAR(32) NOT NULL DEFAULT '${CooldownKind.Satchel.id}'")
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

  private def readStamp(result: java.sql.ResultSet, userId: String, kind: CooldownKind): CooldownStamp = {
    val updatedTimeTemporal =
      Try(Option(result.getTimestamp("time").toInstant).getOrElse(Instant.parse("2022-01-01T01:00:00Z")))
        .getOrElse(Instant.parse("2022-01-01T01:00:00Z"))
    val updatedTime = updatedTimeTemporal.atZone(ZoneOffset.UTC)
    val tag = Option(result.getString("tag")).getOrElse("")

    CooldownStamp(userId, kind, updatedTime, tag)
  }

  def getStamps(userId: String, kind: CooldownKind): Option[List[CooldownStamp]] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val ensure = conn.createStatement(); ensureTable(ensure); ensure.close()

      val statement = conn.prepareStatement("SELECT time,tag FROM satchel WHERE userid = ? AND kind = ?;")
      statement.setString(1, userId)
      statement.setString(2, kind.id)
      val result = statement.executeQuery()

      val stamps: ListBuffer[CooldownStamp] = ListBuffer()

      while (result.next()) {
        stamps += readStamp(result, userId, kind)
      }

      statement.close()
      Some(stamps.toList)
    }

  def del(user: String, kind: CooldownKind, tag: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val deleteStatement =
        conn.prepareStatement("DELETE FROM satchel WHERE userid = ? AND COALESCE(tag, '') = ? AND kind = ?;")
      deleteStatement.setString(1, user)
      deleteStatement.setString(2, tag)
      deleteStatement.setString(3, kind.id)
      deleteStatement.executeUpdate()

      deleteStatement.close()
    }

  def delAll(user: String, kind: CooldownKind): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val deleteStatement = conn.prepareStatement("DELETE FROM satchel WHERE userid = ? AND kind = ?;")
      deleteStatement.setString(1, user)
      deleteStatement.setString(2, kind.id)
      deleteStatement.executeUpdate()

      deleteStatement.close()
    }

  def add(user: String, kind: CooldownKind, when: ZonedDateTime, tag: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val ensure = conn.createStatement(); ensureTable(ensure); ensure.close()

      val selectStatement =
        conn.prepareStatement("SELECT time FROM satchel WHERE userid = ? AND tag = ? AND kind = ?;")
      selectStatement.setString(1, user)
      selectStatement.setString(2, tag)
      selectStatement.setString(3, kind.id)
      val resultSet = selectStatement.executeQuery()

      if (resultSet.next()) {
        val updateStatement = conn.prepareStatement(
          s"""
             |UPDATE satchel
             |SET time = ?
             |WHERE userid = ? AND tag = ? AND kind = ?;
             |""".stripMargin
        )
        updateStatement.setTimestamp(1, Timestamp.from(when.toInstant))
        updateStatement.setString(2, user)
        updateStatement.setString(3, tag)
        updateStatement.setString(4, kind.id)
        updateStatement.executeUpdate()
        updateStatement.close()
      } else {
        val insertStatement = conn.prepareStatement(
          s"""
             |INSERT INTO satchel(userid, time, tag, kind)
             |VALUES (?,?,?,?);
             |""".stripMargin
        )
        insertStatement.setString(1, user)
        insertStatement.setTimestamp(2, Timestamp.from(when.toInstant))
        insertStatement.setString(3, tag)
        insertStatement.setString(4, kind.id)
        insertStatement.executeUpdate()
        insertStatement.close()
      }

      selectStatement.close()
    }

  def expiredStamps(kind: CooldownKind, before: ZonedDateTime, botId: String): List[CooldownStamp] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val ensure = conn.createStatement(); ensureTable(ensure); ensure.close()

      val statement = conn.prepareStatement(
        "SELECT userid,time,tag FROM satchel WHERE kind = ? AND time < ? AND (bot_id = ? OR bot_id = '');")
      statement.setString(1, kind.id)
      statement.setTimestamp(2, Timestamp.from(before.toInstant))
      statement.setString(3, botId)
      val result = statement.executeQuery()

      val stamps: ListBuffer[CooldownStamp] = ListBuffer()
      while (result.next()) {
        stamps += readStamp(result, Option(result.getString("userid")).getOrElse(""), kind)
      }

      statement.close()
      stamps.toList
    }

  def deleteExpired(kind: CooldownKind, before: ZonedDateTime, botId: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val ensure = conn.createStatement(); ensureTable(ensure); ensure.close()

      val statement = conn.prepareStatement(
        "DELETE FROM satchel WHERE kind = ? AND time < ? AND (bot_id = ? OR bot_id = '');")
      statement.setString(1, kind.id)
      statement.setTimestamp(2, Timestamp.from(before.toInstant))
      statement.setString(3, botId)
      statement.executeUpdate()
      statement.close()
    }

  def claim(userId: String, botId: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val ensure = conn.createStatement(); ensureTable(ensure); ensure.close()

      // Every stamp for the user, not just the unclaimed ones: ownership follows
      // whichever bot most recently reached them, so someone who moves between
      // servers starts getting their cooldown DMs from the bot that's actually there.
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
