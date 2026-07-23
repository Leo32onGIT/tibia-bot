package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.PatreonSeat
import com.tibiabot.persistence.{ConnectionProvider, PatreonSeatRepository}

import java.sql.{Connection, ResultSet, Timestamp}
import java.time.{ZoneOffset, ZonedDateTime}
import scala.collection.mutable.ListBuffer

/** JDBC implementation of PatreonSeatRepository, routed through
 *  JdbcSupport.withConnection so the connection is always released. Lives in
 *  the shared `bot_cache` database (`connectionProvider.cache`), not a
 *  guild's own database — a seat isn't tied to one discord. */
final class JdbcPatreonSeatRepository(connectionProvider: ConnectionProvider) extends PatreonSeatRepository {

  private def ensureTable(conn: Connection): Unit = {
    val statement = conn.createStatement()
    val tableExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'patreon_seats'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    if (!tableExists) {
      statement.executeUpdate(
        """CREATE TABLE patreon_seats (
          |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
          |user_id VARCHAR(255) NOT NULL,
          |guild_id VARCHAR(255) NOT NULL,
          |world VARCHAR(255) NOT NULL,
          |created TIMESTAMP NOT NULL,
          |CONSTRAINT unique_guild_world UNIQUE (guild_id, world)
          |);""".stripMargin)
    }
    statement.close()
  }

  private def toSeat(rs: ResultSet): PatreonSeat =
    PatreonSeat(
      rs.getString("user_id"),
      rs.getString("guild_id"),
      rs.getString("world"),
      ZonedDateTime.ofInstant(rs.getTimestamp("created").toInstant, ZoneOffset.UTC)
    )

  def seatsForUser(userId: String): List[PatreonSeat] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val statement = conn.prepareStatement("SELECT user_id, guild_id, world, created FROM patreon_seats WHERE user_id = ?")
      statement.setString(1, userId)
      val result = statement.executeQuery()
      val seats = new ListBuffer[PatreonSeat]()
      while (result.next()) seats += toSeat(result)
      statement.close()
      seats.toList
    }

  def seatFor(guildId: String, world: String): Option[PatreonSeat] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val statement = conn.prepareStatement("SELECT user_id, guild_id, world, created FROM patreon_seats WHERE guild_id = ? AND world = ?")
      statement.setString(1, guildId)
      statement.setString(2, world)
      val result = statement.executeQuery()
      val seat = if (result.next()) Some(toSeat(result)) else None
      statement.close()
      seat
    }

  def assignSeat(userId: String, guildId: String, world: String, created: ZonedDateTime): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val statement = conn.prepareStatement(
        "INSERT INTO patreon_seats (user_id, guild_id, world, created) VALUES (?, ?, ?, ?) " +
        "ON CONFLICT (guild_id, world) DO UPDATE SET user_id = EXCLUDED.user_id, created = EXCLUDED.created;"
      )
      statement.setString(1, userId)
      statement.setString(2, guildId)
      statement.setString(3, world)
      statement.setTimestamp(4, Timestamp.from(created.toInstant))
      statement.executeUpdate()
      statement.close()
    }

  def releaseSeat(guildId: String, world: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val statement = conn.prepareStatement("DELETE FROM patreon_seats WHERE guild_id = ? AND world = ?")
      statement.setString(1, guildId)
      statement.setString(2, world)
      statement.executeUpdate()
      statement.close()
    }

  def allSeats(): List[PatreonSeat] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val statement = conn.createStatement()
      val result = statement.executeQuery("SELECT user_id, guild_id, world, created FROM patreon_seats")
      val seats = new ListBuffer[PatreonSeat]()
      while (result.next()) seats += toSeat(result)
      statement.close()
      seats.toList
    }
}
