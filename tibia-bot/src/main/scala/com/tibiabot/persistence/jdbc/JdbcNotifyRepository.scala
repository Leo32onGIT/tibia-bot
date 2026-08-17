package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.{BountySub, MasslogSub}
import com.tibiabot.persistence.{ConnectionProvider, NotifyRepository}

import java.sql.{Connection, ResultSet, Timestamp}
import java.time.Instant
import scala.collection.mutable.ListBuffer

/** JDBC implementation of [[NotifyRepository]] over the shared `bot_cache`
 *  database. Tables are created by SchemaInitializer.initCache. */
final class JdbcNotifyRepository(connectionProvider: ConnectionProvider) extends NotifyRepository {

  private def instantOf(result: ResultSet, column: String): Option[Instant] =
    Option(result.getTimestamp(column)).map(_.toInstant)

  private def readMasslog(result: ResultSet): MasslogSub =
    MasslogSub(
      result.getLong("id"),
      result.getString("guildid"),
      result.getString("world"),
      result.getString("userid"),
      result.getInt("threshold"),
      result.getBoolean("enabled"),
      instantOf(result, "muted_until"),
      instantOf(result, "last_notified")
    )

  private def readBounty(result: ResultSet): BountySub =
    BountySub(
      result.getLong("id"),
      result.getString("guildid"),
      result.getString("world"),
      result.getString("userid"),
      result.getString("character_name"),
      result.getInt("cooldown_minutes"),
      result.getBoolean("enabled"),
      instantOf(result, "muted_until"),
      instantOf(result, "last_notified")
    )

  private def collect[A](conn: Connection, sql: String)(read: ResultSet => A): List[A] = {
    val statement = conn.createStatement()
    try {
      val result = statement.executeQuery(sql)
      val rows = new ListBuffer[A]()
      while (result.next()) rows += read(result)
      rows.toList
    } finally statement.close()
  }

  def allMasslog(): List[MasslogSub] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      collect(conn, "SELECT * FROM masslog_notifications")(readMasslog)
    }

  def allBounty(): List[BountySub] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      collect(conn, "SELECT * FROM bounty_notifications")(readBounty)
    }

  /** Re-pressing the button is how a threshold gets changed, so the insert
   *  updates on conflict — and clears `enabled`/`muted_until` back to on, since
   *  deliberately resubscribing plainly means "start telling me again". */
  def upsertMasslog(guildId: String, world: String, userId: String, threshold: Int): MasslogSub =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        """INSERT INTO masslog_notifications (guildid, world, userid, threshold, enabled)
          |VALUES (?, ?, ?, ?, TRUE)
          |ON CONFLICT (guildid, world, userid)
          |DO UPDATE SET threshold = EXCLUDED.threshold, enabled = TRUE, muted_until = NULL
          |RETURNING *;""".stripMargin)
      try {
        statement.setString(1, guildId)
        statement.setString(2, world)
        statement.setString(3, userId)
        statement.setInt(4, threshold)
        val result = statement.executeQuery()
        result.next()
        readMasslog(result)
      } finally statement.close()
    }

  def upsertBounty(guildId: String, world: String, userId: String, character: String, cooldownMinutes: Int): BountySub =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        """INSERT INTO bounty_notifications (guildid, world, userid, character_name, cooldown_minutes, enabled)
          |VALUES (?, ?, ?, ?, ?, TRUE)
          |ON CONFLICT (guildid, world, userid, LOWER(character_name))
          |DO UPDATE SET cooldown_minutes = EXCLUDED.cooldown_minutes, character_name = EXCLUDED.character_name,
          |              enabled = TRUE, muted_until = NULL
          |RETURNING *;""".stripMargin)
      try {
        statement.setString(1, guildId)
        statement.setString(2, world)
        statement.setString(3, userId)
        statement.setString(4, character)
        statement.setInt(5, cooldownMinutes)
        val result = statement.executeQuery()
        result.next()
        readBounty(result)
      } finally statement.close()
    }

  private def byId[A](table: String, id: Long)(read: ResultSet => A): Option[A] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(s"SELECT * FROM $table WHERE id = ?;")
      try {
        statement.setLong(1, id)
        val result = statement.executeQuery()
        if (result.next()) Some(read(result)) else None
      } finally statement.close()
    }

  def masslogById(id: Long): Option[MasslogSub] = byId("masslog_notifications", id)(readMasslog)
  def bountyById(id: Long): Option[BountySub] = byId("bounty_notifications", id)(readBounty)

  private def update(sql: String)(bind: java.sql.PreparedStatement => Unit): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(sql)
      try {
        bind(statement)
        statement.executeUpdate()
      } finally statement.close()
    }

  /** Switching a subscription back on clears any mute with it: a mute is a
   *  quieter form of the same "not now", and leaving one in place would make
   *  Enable look broken. */
  def setMasslogEnabled(id: Long, enabled: Boolean): Unit =
    update("UPDATE masslog_notifications SET enabled = ?, muted_until = NULL WHERE id = ?;") { s =>
      s.setBoolean(1, enabled); s.setLong(2, id)
    }

  def setBountyEnabled(id: Long, enabled: Boolean): Unit =
    update("UPDATE bounty_notifications SET enabled = ?, muted_until = NULL WHERE id = ?;") { s =>
      s.setBoolean(1, enabled); s.setLong(2, id)
    }

  def muteMasslog(id: Long, until: Instant): Unit =
    update("UPDATE masslog_notifications SET muted_until = ? WHERE id = ?;") { s =>
      s.setTimestamp(1, Timestamp.from(until)); s.setLong(2, id)
    }

  def muteBounty(id: Long, until: Instant): Unit =
    update("UPDATE bounty_notifications SET muted_until = ? WHERE id = ?;") { s =>
      s.setTimestamp(1, Timestamp.from(until)); s.setLong(2, id)
    }

  def setMasslogThreshold(id: Long, threshold: Int): Unit =
    update("UPDATE masslog_notifications SET threshold = ? WHERE id = ?;") { s =>
      s.setInt(1, threshold); s.setLong(2, id)
    }

  def markMasslogNotified(id: Long, at: Instant): Unit =
    update("UPDATE masslog_notifications SET last_notified = ? WHERE id = ?;") { s =>
      s.setTimestamp(1, Timestamp.from(at)); s.setLong(2, id)
    }

  def markBountyNotified(id: Long, at: Instant): Unit =
    update("UPDATE bounty_notifications SET last_notified = ? WHERE id = ?;") { s =>
      s.setTimestamp(1, Timestamp.from(at)); s.setLong(2, id)
    }

  def deleteGuild(guildId: String): Unit = {
    update("DELETE FROM masslog_notifications WHERE guildid = ?;")(_.setString(1, guildId))
    update("DELETE FROM bounty_notifications WHERE guildid = ?;")(_.setString(1, guildId))
  }

  def deleteWorld(guildId: String, world: String): Unit = {
    update("DELETE FROM masslog_notifications WHERE guildid = ? AND LOWER(world) = LOWER(?);") { s =>
      s.setString(1, guildId); s.setString(2, world)
    }
    update("DELETE FROM bounty_notifications WHERE guildid = ? AND LOWER(world) = LOWER(?);") { s =>
      s.setString(1, guildId); s.setString(2, world)
    }
  }
}
