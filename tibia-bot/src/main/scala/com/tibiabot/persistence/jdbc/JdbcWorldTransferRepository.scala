package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.WorldTransfer
import com.tibiabot.persistence.{ConnectionProvider, WorldTransferRepository}

import java.sql.Timestamp
import java.time.{Instant, ZoneOffset, ZonedDateTime}
import scala.collection.mutable.ListBuffer

/** JDBC implementation of WorldTransferRepository against the shared bot_cache
 *  database, following the same shape as the deaths and levels caches it now
 *  sits beside. The table is created up front by SchemaInitializer.initCache,
 *  which runs before any world stream starts. */
final class JdbcWorldTransferRepository(connectionProvider: ConnectionProvider) extends WorldTransferRepository {

  def getTransfers(world: String): List[WorldTransfer] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement("SELECT name,former_worlds,detected FROM world_transfers WHERE world = ?;")
      statement.setString(1, world)
      val result = statement.executeQuery()

      val results = new ListBuffer[WorldTransfer]()
      while (result.next()) {
        val name = Option(result.getString("name")).getOrElse("")
        val formerWorlds = Option(result.getString("former_worlds")).getOrElse("")
        val formerWorldsList = formerWorlds.split(",").toList.filter(_.nonEmpty)
        val detectedTemporal = Option(result.getTimestamp("detected").toInstant).getOrElse(Instant.parse("2022-01-01T01:00:00Z"))

        results += WorldTransfer(name, formerWorldsList, detectedTemporal.atZone(ZoneOffset.UTC))
      }

      statement.close()
      results.toList
    }

  def record(world: String, name: String, formerWorlds: List[String], detectedAt: ZonedDateTime): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        s"""
           |INSERT INTO world_transfers(world, name, former_worlds, detected)
           |VALUES (?,?,?,?)
           |ON CONFLICT (world, name)
           |DO UPDATE SET
           |  former_worlds = excluded.former_worlds,
           |  detected = excluded.detected;
           |""".stripMargin
      )
      statement.setString(1, world)
      // Lowercased on the way in so the primary key can't hold "Bob" and "bob" as
      // two records of the same character's transfer.
      statement.setString(2, name.toLowerCase)
      statement.setString(3, formerWorlds.mkString(","))
      statement.setTimestamp(4, Timestamp.from(detectedAt.toInstant))
      statement.executeUpdate()

      statement.close()
    }

  def remove(world: String, name: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement("DELETE FROM world_transfers WHERE world = ? AND name = ?;")
      statement.setString(1, world)
      // Lowercased to match how `record` writes the key.
      statement.setString(2, name.toLowerCase)
      statement.executeUpdate()

      statement.close()
    }

  def removeExpired(world: String, before: ZonedDateTime): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement("DELETE FROM world_transfers WHERE world = ? AND detected < ?;")
      statement.setString(1, world)
      statement.setTimestamp(2, Timestamp.from(before.toInstant))
      statement.executeUpdate()

      statement.close()
    }
}
