package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.BoostedStamp
import com.tibiabot.persistence.{BoostedRepository, ConnectionProvider}

import scala.collection.mutable.ListBuffer

/** JDBC implementation of BoostedRepository. Read bodies moved verbatim from
 *  BotApp's boostedAll/boostedList; the subscribe/unsubscribe SQL matches the
 *  inline statements in BotApp.boosted. The table is created on first use, as
 *  the originals did. Every method goes through JdbcSupport.withConnection so
 *  the connection is released even if a statement throws. */
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
           |CONSTRAINT unique_user_name_constraint UNIQUE (userid, name)
           |);""".stripMargin
      statement.executeUpdate(createListTable)
    }
  }

  def all(): List[BoostedStamp] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.createStatement()
      ensureTable(statement)

      val result = statement.executeQuery(s"SELECT userid,name,type FROM boosted_notifications;")
      val boostedStampList: ListBuffer[BoostedStamp] = ListBuffer()

      while (result.next()) {
        val boostedUserSql = Option(result.getString("userid")).getOrElse("")
        val boostedNameSql = Option(result.getString("name")).getOrElse("")
        val boostedTypeSql = Option(result.getString("type")).getOrElse("")

        val boostedStamp = BoostedStamp(boostedUserSql, boostedTypeSql, boostedNameSql)
        boostedStampList += boostedStamp
      }

      statement.close()
      boostedStampList.toList
    }

  def forUser(userId: String): List[BoostedStamp] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.createStatement()
      ensureTable(statement)

      val result = statement.executeQuery(s"SELECT name,type FROM boosted_notifications WHERE userid = '$userId';")
      val boostedStampList: ListBuffer[BoostedStamp] = ListBuffer()

      while (result.next()) {
        val boostedNameSql = Option(result.getString("name")).getOrElse("")
        val boostedTypeSql = Option(result.getString("type")).getOrElse("")

        val boostedStamp = BoostedStamp(userId, boostedTypeSql, boostedNameSql)
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
}
