package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.BoostedStamp
import com.tibiabot.persistence.{BoostedRepository, ConnectionProvider}

import scala.collection.mutable.ListBuffer

/** JDBC implementation of BoostedRepository. Every method goes through
 *  JdbcSupport.withConnection so the connection is released even if a
 *  statement throws, and ensures the backing table exists on first use. */
final class JdbcBoostedRepository(connectionProvider: ConnectionProvider) extends BoostedRepository {

  private def ensureTable(statement: java.sql.Statement): Unit = {
    val tableExistsQuery =
      statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'boosted_notifications'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()
    if (!tableExists) {
      val createListTable =
        s"""CREATE TABLE boosted_notifications (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |userid VARCHAR(255) NOT NULL,
           |name VARCHAR(255) NOT NULL,
           |type VARCHAR(255),
           |bot_id VARCHAR(255) NOT NULL DEFAULT '',
           |dm_failures INT NOT NULL DEFAULT 0,
           |CONSTRAINT unique_user_name_constraint UNIQUE (userid, name)
           |);""".stripMargin
      statement.executeUpdate(createListTable)
    }

    def columnExists(name: String): Boolean = {
      val query = statement.executeQuery(
        s"SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'boosted_notifications' AND COLUMN_NAME = '$name'")
      val exists = query.next()
      query.close()
      exists
    }

    // Which bot delivers this user's DM, and how many saves in a row it has
    // failed to. Rows predating these columns start unclaimed (bot_id '') and
    // are claimed by whichever bot first gets a DM through — see BoostedStamp.
    if (!columnExists("bot_id")) {
      statement.execute("ALTER TABLE boosted_notifications ADD COLUMN bot_id VARCHAR(255) NOT NULL DEFAULT ''")
    }
    if (!columnExists("dm_failures")) {
      statement.execute("ALTER TABLE boosted_notifications ADD COLUMN dm_failures INT NOT NULL DEFAULT 0")
    }
  }

  def all(): List[BoostedStamp] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.createStatement()
      ensureTable(statement)

      val result = statement.executeQuery(s"SELECT userid,name,type,bot_id,dm_failures FROM boosted_notifications;")
      val boostedStampList: ListBuffer[BoostedStamp] = ListBuffer()

      while (result.next()) {
        val boostedUserSql = Option(result.getString("userid")).getOrElse("")
        val boostedNameSql = Option(result.getString("name")).getOrElse("")
        val boostedTypeSql = Option(result.getString("type")).getOrElse("")
        val botIdSql = Option(result.getString("bot_id")).getOrElse("")
        val failuresSql = result.getInt("dm_failures")

        val boostedStamp = BoostedStamp(boostedUserSql, boostedTypeSql, boostedNameSql, botIdSql, failuresSql)
        boostedStampList += boostedStamp
      }

      statement.close()
      boostedStampList.toList
    }

  def forUser(userId: String): List[BoostedStamp] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.createStatement()
      ensureTable(statement)

      val result = statement.executeQuery(s"SELECT name,type,bot_id,dm_failures FROM boosted_notifications WHERE userid = '$userId';")
      val boostedStampList: ListBuffer[BoostedStamp] = ListBuffer()

      while (result.next()) {
        val boostedNameSql = Option(result.getString("name")).getOrElse("")
        val boostedTypeSql = Option(result.getString("type")).getOrElse("")
        val botIdSql = Option(result.getString("bot_id")).getOrElse("")
        val failuresSql = result.getInt("dm_failures")

        val boostedStamp = BoostedStamp(userId, boostedTypeSql, boostedNameSql, botIdSql, failuresSql)
        boostedStampList += boostedStamp
      }

      statement.close()
      boostedStampList.toList
    }

  def subscribe(userId: String, name: String, boostedType: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val ensure = conn.createStatement(); ensureTable(ensure); ensure.close()
      val statement = conn.prepareStatement(
        "INSERT INTO boosted_notifications (userid, name, type) VALUES (?, ?, ?) ON CONFLICT (userid, name) DO NOTHING")
      statement.setString(1, userId)
      statement.setString(2, name)
      statement.setString(3, boostedType)
      statement.executeUpdate()
      statement.close()
    }

  def unsubscribe(userId: String, name: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val ensure = conn.createStatement(); ensureTable(ensure); ensure.close()
      val statement = conn.prepareStatement("DELETE FROM boosted_notifications WHERE userid = ? AND LOWER(name) = LOWER(?)")
      statement.setString(1, userId)
      statement.setString(2, name)
      statement.executeUpdate()
      statement.close()
    }

  def unsubscribeAll(userId: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val ensure = conn.createStatement(); ensureTable(ensure); ensure.close()
      val statement = conn.prepareStatement("DELETE FROM boosted_notifications WHERE userid = ?")
      statement.setString(1, userId)
      statement.executeUpdate()
      statement.close()
    }

  def claim(userId: String, botId: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val ensure = conn.createStatement(); ensureTable(ensure); ensure.close()
      // Every row for the user, not just the unclaimed ones: ownership follows
      // whichever bot most recently reached them, so someone who moves between
      // servers starts getting their DMs from the bot that's actually there.
      val statement = conn.prepareStatement("UPDATE boosted_notifications SET bot_id = ?, dm_failures = 0 WHERE userid = ?")
      statement.setString(1, botId)
      statement.setString(2, userId)
      statement.executeUpdate()
      statement.close()
    }

  def recordDeliveryFailure(userId: String, botId: String): Int =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val ensure = conn.createStatement(); ensureTable(ensure); ensure.close()
      val statement = conn.prepareStatement(
        "UPDATE boosted_notifications SET dm_failures = dm_failures + 1 WHERE userid = ? AND bot_id = ? RETURNING dm_failures")
      statement.setString(1, userId)
      statement.setString(2, botId)
      val result = statement.executeQuery()
      var highest = 0
      while (result.next()) {
        val count = result.getInt("dm_failures")
        if (count > highest) highest = count
      }
      statement.close()
      highest
    }

  def unsubscribeAllFor(userId: String, botId: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val ensure = conn.createStatement(); ensureTable(ensure); ensure.close()
      val statement = conn.prepareStatement("DELETE FROM boosted_notifications WHERE userid = ? AND bot_id = ?")
      statement.setString(1, userId)
      statement.setString(2, botId)
      statement.executeUpdate()
      statement.close()
    }
}
