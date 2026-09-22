package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.{ObserverStatus, ObserverToken}
import com.tibiabot.persistence.{ConnectionProvider, ObserverRepository}

import java.sql.ResultSet

/** JDBC implementation of [[ObserverRepository]] over the shared `bot_cache`
 *  database. The table is created by SchemaInitializer.initCache. */
final class JdbcObserverRepository(connectionProvider: ConnectionProvider) extends ObserverRepository {

  private def read(result: ResultSet): ObserverToken =
    ObserverToken(
      result.getLong("id"),
      result.getString("guildid"),
      result.getString("userid"),
      Option(result.getString("world")),
      Option(result.getString("account_label")),
      ObserverStatus.fromCode(result.getString("status")),
      result.getTimestamp("created_at").toInstant,
      result.getTimestamp("updated_at").toInstant
    )

  def all(): List[ObserverToken] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.createStatement()
      try {
        val result = statement.executeQuery("SELECT * FROM observer_tokens")
        val rows = scala.collection.mutable.ListBuffer.empty[ObserverToken]
        while (result.next()) rows += read(result)
        rows.toList
      } finally statement.close()
    }

  def forUser(guildId: String, userId: String): Option[ObserverToken] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        "SELECT * FROM observer_tokens WHERE guildid = ? AND userid = ?")
      try {
        statement.setString(1, guildId)
        statement.setString(2, userId)
        val result = statement.executeQuery()
        if (result.next()) Some(read(result)) else None
      } finally statement.close()
    }

  def upsert(guildId: String, userId: String, tokenEnc: String, status: ObserverStatus,
             accountLabel: Option[String], world: Option[String]): ObserverToken =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        """INSERT INTO observer_tokens (guildid, userid, token_enc, status, account_label, world, updated_at)
          |VALUES (?, ?, ?, ?, ?, ?, NOW())
          |ON CONFLICT (guildid, userid)
          |DO UPDATE SET token_enc = EXCLUDED.token_enc, status = EXCLUDED.status,
          |              account_label = EXCLUDED.account_label, world = EXCLUDED.world, updated_at = NOW()
          |RETURNING *;""".stripMargin)
      try {
        statement.setString(1, guildId)
        statement.setString(2, userId)
        statement.setString(3, tokenEnc)
        statement.setString(4, status.code)
        statement.setString(5, accountLabel.orNull)
        statement.setString(6, world.orNull)
        val result = statement.executeQuery()
        result.next()
        read(result)
      } finally statement.close()
    }

  def tokenEncFor(guildId: String, userId: String): Option[String] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        "SELECT token_enc FROM observer_tokens WHERE guildid = ? AND userid = ?")
      try {
        statement.setString(1, guildId)
        statement.setString(2, userId)
        val result = statement.executeQuery()
        if (result.next()) Option(result.getString("token_enc")) else None
      } finally statement.close()
    }

  def setStatus(id: Long, status: ObserverStatus, world: Option[String]): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        "UPDATE observer_tokens SET status = ?, world = ?, updated_at = NOW() WHERE id = ?")
      try {
        statement.setString(1, status.code)
        statement.setString(2, world.orNull)
        statement.setLong(3, id)
        statement.executeUpdate()
      } finally statement.close()
    }

  def delete(guildId: String, userId: String): Boolean =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        "DELETE FROM observer_tokens WHERE guildid = ? AND userid = ?")
      try {
        statement.setString(1, guildId)
        statement.setString(2, userId)
        statement.executeUpdate() > 0
      } finally statement.close()
    }

  def deleteGuild(guildId: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement("DELETE FROM observer_tokens WHERE guildid = ?")
      try { statement.setString(1, guildId); statement.executeUpdate() }
      finally statement.close()
    }

  def deleteUser(guildId: String, userId: String): Unit = { delete(guildId, userId); () }
}
