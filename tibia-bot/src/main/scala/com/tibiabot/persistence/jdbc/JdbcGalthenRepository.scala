package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.SatchelStamp
import com.tibiabot.persistence.{ConnectionProvider, GalthenRepository}

import java.sql.Timestamp
import java.time.{Instant, ZoneOffset, ZonedDateTime}
import scala.collection.mutable.ListBuffer
import scala.util.Try

/** JDBC implementation of GalthenRepository. Bodies moved verbatim from BotApp's
 *  getGalthenTable/addGalthen/delGalthen/delAllGalthen — no behaviour change. */
final class JdbcGalthenRepository(connectionProvider: ConnectionProvider) extends GalthenRepository {

  def getStamps(userId: String): Option[List[SatchelStamp]] = {
    val conn = connectionProvider.cache()
    val statement = conn.createStatement()

    // Check if the table already exists in bot_configuration
    val tableExistsQuery =
      statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'satchel'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    // Create the table if it doesn't exist
    if (!tableExists) {
      val createListTable =
        s"""CREATE TABLE satchel (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |userid VARCHAR(255) NOT NULL,
           |time VARCHAR(255) NOT NULL,
           |tag VARCHAR(255)
           |);""".stripMargin

      statement.executeUpdate(createListTable)
    }

    val result = statement.executeQuery(s"SELECT time,tag FROM satchel WHERE userid = '$userId';")

    val satchelStampList: ListBuffer[SatchelStamp] = ListBuffer()

    while (result.next()) {
      val updatedTimeTemporal =
        Try(Option(result.getTimestamp("time").toInstant).getOrElse(Instant.parse("2022-01-01T01:00:00Z")))
          .getOrElse(Instant.parse("2022-01-01T01:00:00Z"))
      val updatedTime = updatedTimeTemporal.atZone(ZoneOffset.UTC)
      val tag = Option(result.getString("tag")).getOrElse("")

      val satchelStamp = SatchelStamp(userId, updatedTime, tag)
      satchelStampList += satchelStamp
    }

    statement.close()
    conn.close()
    Some(satchelStampList.toList)
  }

  def del(user: String, tag: String): Unit = {
    val conn = connectionProvider.cache()

    val deleteStatement = conn.prepareStatement("DELETE FROM satchel WHERE userid = ? AND COALESCE(tag, '') = ?;")
    deleteStatement.setString(1, user)
    deleteStatement.setString(2, tag)
    deleteStatement.executeUpdate()

    deleteStatement.close()
    conn.close()
  }

  def delAll(user: String): Unit = {
    val conn = connectionProvider.cache()

    val deleteStatement = conn.prepareStatement("DELETE FROM satchel WHERE userid = ?;")
    deleteStatement.setString(1, user)
    deleteStatement.executeUpdate()

    deleteStatement.close()
    conn.close()
  }

  def add(user: String, when: ZonedDateTime, tag: String): Unit = {
    val conn = connectionProvider.cache()
    val selectStatement = conn.prepareStatement("SELECT time FROM satchel WHERE userid = ? AND tag = ?;")
    selectStatement.setString(1, user)
    selectStatement.setString(2, tag)
    val resultSet = selectStatement.executeQuery()

    if (resultSet.next()) {
      // Update existing row
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
      // Insert new row
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
    conn.close()
  }
}
