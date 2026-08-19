package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.WorldTransfer
import com.tibiabot.persistence.{ConnectionProvider, WorldTransferRepository}

import java.sql.Timestamp
import java.time.{Instant, ZoneOffset, ZonedDateTime}
import scala.collection.mutable.ListBuffer

/** JDBC implementation of WorldTransferRepository, following the lazy
 *  create-on-first-read shape the other per-guild tables use. */
final class JdbcWorldTransferRepository(connectionProvider: ConnectionProvider) extends WorldTransferRepository {

  def getTransfers(guildId: String): List[WorldTransfer] =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
      val statement = conn.createStatement()

      val tableExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'world_transfers'")
      val tableExists = tableExistsQuery.next()
      tableExistsQuery.close()

      if (!tableExists) {
        val createTransfersTable =
          s"""CREATE TABLE world_transfers (
             |name VARCHAR(255) NOT NULL,
             |former_worlds VARCHAR(255) NOT NULL,
             |detected TIMESTAMP NOT NULL,
             |PRIMARY KEY (name)
             |);""".stripMargin

        statement.executeUpdate(createTransfersTable)
      }

      val result = statement.executeQuery("SELECT name,former_worlds,detected FROM world_transfers")

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

  def record(guildId: String, name: String, formerWorlds: List[String], detectedAt: ZonedDateTime): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
      val statement = conn.prepareStatement(
        s"""
           |INSERT INTO world_transfers(name, former_worlds, detected)
           |VALUES (?,?,?)
           |ON CONFLICT (name)
           |DO UPDATE SET
           |  former_worlds = excluded.former_worlds,
           |  detected = excluded.detected;
           |""".stripMargin
      )
      // Lowercased on the way in so the primary key can't hold "Bob" and "bob" as
      // two records of the same character's transfer.
      statement.setString(1, name.toLowerCase)
      statement.setString(2, formerWorlds.mkString(","))
      statement.setTimestamp(3, Timestamp.from(detectedAt.toInstant))
      statement.executeUpdate()

      statement.close()
    }

  def remove(guildId: String, name: String): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
      val statement = conn.prepareStatement("DELETE FROM world_transfers WHERE name = ?;")
      // Lowercased to match how `record` writes the key.
      statement.setString(1, name.toLowerCase)
      statement.executeUpdate()

      statement.close()
    }

  def removeExpired(guildId: String, before: ZonedDateTime): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
      val statement = conn.prepareStatement("DELETE FROM world_transfers WHERE detected < ?;")
      statement.setTimestamp(1, Timestamp.from(before.toInstant))
      statement.executeUpdate()

      statement.close()
    }
}
