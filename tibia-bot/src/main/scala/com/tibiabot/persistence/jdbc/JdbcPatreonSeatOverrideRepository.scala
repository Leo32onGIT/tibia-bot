package com.tibiabot.persistence.jdbc

import com.tibiabot.persistence.{ConnectionProvider, PatreonSeatOverrideRepository}

import java.sql.{Connection, Timestamp}
import java.time.ZonedDateTime
import scala.collection.mutable

/** JDBC implementation of PatreonSeatOverrideRepository, routed through
 *  JdbcSupport.withConnection so the connection is always released. Lives in
 *  the shared `bot_cache` database (`connectionProvider.cache`), alongside
 *  `patreon_seats` — an adjustment isn't tied to one guild or seat. */
final class JdbcPatreonSeatOverrideRepository(connectionProvider: ConnectionProvider) extends PatreonSeatOverrideRepository {

  private def ensureTable(conn: Connection): Unit = {
    val statement = conn.createStatement()
    val tableExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'patreon_seat_overrides'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    if (!tableExists) {
      statement.executeUpdate(
        """CREATE TABLE patreon_seat_overrides (
          |user_id VARCHAR(255) PRIMARY KEY,
          |extra_seats INT NOT NULL,
          |updated TIMESTAMP NOT NULL
          |);""".stripMargin)
    }

    statement.close()
  }

  def extraSeatsFor(userId: String): Int =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val statement = conn.prepareStatement("SELECT extra_seats FROM patreon_seat_overrides WHERE user_id = ?")
      statement.setString(1, userId)
      val result = statement.executeQuery()
      val extraSeats = if (result.next()) result.getInt("extra_seats") else 0
      statement.close()
      extraSeats
    }

  def setExtraSeats(userId: String, extraSeats: Int, updated: ZonedDateTime): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val statement = conn.prepareStatement(
        "INSERT INTO patreon_seat_overrides (user_id, extra_seats, updated) VALUES (?, ?, ?) " +
        "ON CONFLICT (user_id) DO UPDATE SET extra_seats = EXCLUDED.extra_seats, updated = EXCLUDED.updated;"
      )
      statement.setString(1, userId)
      statement.setInt(2, extraSeats)
      statement.setTimestamp(3, Timestamp.from(updated.toInstant))
      statement.executeUpdate()
      statement.close()
    }

  def allExtraSeats(): Map[String, Int] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val statement = conn.createStatement()
      val result = statement.executeQuery("SELECT user_id, extra_seats FROM patreon_seat_overrides")
      val overrides = mutable.Map.empty[String, Int]
      while (result.next()) overrides += result.getString("user_id") -> result.getInt("extra_seats")
      statement.close()
      overrides.toMap
    }
}
