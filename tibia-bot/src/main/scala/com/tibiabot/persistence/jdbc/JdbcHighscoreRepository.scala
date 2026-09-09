package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.{FiledEvent, HighscoreEvent, HighscoreRecord}
import com.tibiabot.highscores.HighscoreDiff
import com.tibiabot.persistence.{ConnectionProvider, HighscoreRepository}
import com.tibiabot.tibiadata.response.HighscoreEntry

import java.sql.Timestamp
import java.time.Instant
import scala.collection.mutable.ListBuffer

/** JDBC implementation of HighscoreRepository against the shared bot_cache
 *  database. The tables are created up front by SchemaInitializer.initCache,
 *  which runs before any world stream starts.
 *
 *  `score` and `char_level` rather than `value` and `level`: both of the
 *  natural names are Postgres keywords that work unquoted today, and neither is
 *  worth a migration if that ever changes. */
final class JdbcHighscoreRepository(connectionProvider: ConnectionProvider) extends HighscoreRepository {

  /** How many rows go to the server per round trip. A full list is 1000 rows,
   *  and sending it as one batch is a single round trip per list per world —
   *  816 of them per snapshot across the fleet, rather than 816,000. */
  private val batchSize = 500

  def load(world: String, category: String): Map[String, HighscoreRecord] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        "SELECT name,display_name,vocation,char_level,score,last_seen FROM highscore_value WHERE world = ? AND category = ?;")
      statement.setString(1, world)
      statement.setString(2, category)
      val result = statement.executeQuery()

      val records = Map.newBuilder[String, HighscoreRecord]
      while (result.next()) {
        val name = Option(result.getString("name")).getOrElse("")
        records += name -> HighscoreRecord(
          name = name,
          displayName = Option(result.getString("display_name")).getOrElse(name),
          vocation = Option(result.getString("vocation")).getOrElse(""),
          level = result.getInt("char_level"),
          score = result.getLong("score"),
          lastSeen = result.getTimestamp("last_seen").toInstant
        )
      }

      statement.close()
      records.result()
    }

  def upsertAll(world: String, category: String, entries: List[HighscoreEntry], snapshotAt: Instant): Unit =
    if (entries.nonEmpty) JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        s"""
           |INSERT INTO highscore_value(world, category, name, display_name, vocation, char_level, score, last_seen)
           |VALUES (?,?,?,?,?,?,?,?)
           |ON CONFLICT (world, category, name)
           |DO UPDATE SET
           |  display_name = excluded.display_name,
           |  vocation = excluded.vocation,
           |  char_level = excluded.char_level,
           |  score = excluded.score,
           |  last_seen = excluded.last_seen;
           |""".stripMargin
      )
      val seenAt = Timestamp.from(snapshotAt)
      // A page-set can carry the same character twice if tibia.com reshuffles
      // between two page fetches. Postgres refuses to update the same row twice
      // in one statement ("ON CONFLICT DO UPDATE command cannot affect row a
      // second time"), which would fail the whole batch, so the last reading of
      // a name wins here instead.
      val deduped = entries.groupBy(entry => HighscoreDiff.key(entry.name)).map { case (key, rows) => key -> rows.last }

      var pending = 0
      deduped.foreach { case (key, entry) =>
        statement.setString(1, world)
        statement.setString(2, category)
        statement.setString(3, key)
        statement.setString(4, entry.name)
        statement.setString(5, entry.vocation)
        statement.setInt(6, entry.level)
        statement.setLong(7, entry.value)
        statement.setTimestamp(8, seenAt)
        statement.addBatch()
        pending += 1
        if (pending == batchSize) {
          statement.executeBatch()
          pending = 0
        }
      }
      if (pending > 0) statement.executeBatch()

      statement.close()
    }

  def recordEvents(events: List[HighscoreEvent]): Unit =
    if (events.nonEmpty) JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        s"""
           |INSERT INTO highscore_events(world, category, name, display_name, vocation, char_level, previous_score, score, observed)
           |VALUES (?,?,?,?,?,?,?,?,?);
           |""".stripMargin
      )
      events.foreach { event =>
        statement.setString(1, event.world)
        statement.setString(2, event.category)
        statement.setString(3, event.name)
        statement.setString(4, event.displayName)
        statement.setString(5, event.vocation)
        statement.setInt(6, event.level)
        statement.setLong(7, event.previousScore)
        statement.setLong(8, event.score)
        statement.setTimestamp(9, Timestamp.from(event.observed))
        statement.addBatch()
      }
      statement.executeBatch()

      statement.close()
    }

  def events(world: String, since: Instant): List[HighscoreEvent] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        s"""
           |SELECT category,name,display_name,vocation,char_level,previous_score,score,observed
           |FROM highscore_events
           |WHERE world = ? AND observed >= ?
           |ORDER BY observed DESC, id DESC;
           |""".stripMargin
      )
      statement.setString(1, world)
      statement.setTimestamp(2, Timestamp.from(since))
      val result = statement.executeQuery()

      val rows = new ListBuffer[HighscoreEvent]()
      while (result.next()) {
        rows += HighscoreEvent(
          world = world,
          category = Option(result.getString("category")).getOrElse(""),
          name = Option(result.getString("name")).getOrElse(""),
          displayName = Option(result.getString("display_name")).getOrElse(""),
          vocation = Option(result.getString("vocation")).getOrElse(""),
          level = result.getInt("char_level"),
          previousScore = result.getLong("previous_score"),
          score = result.getLong("score"),
          observed = result.getTimestamp("observed").toInstant
        )
      }

      statement.close()
      rows.toList
    }

  def eventsAfter(afterId: Long, limit: Int): List[FiledEvent] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        s"""
           |SELECT id,world,category,name,display_name,vocation,char_level,previous_score,score,observed
           |FROM highscore_events
           |WHERE id > ?
           |ORDER BY id ASC
           |LIMIT ?;
           |""".stripMargin
      )
      statement.setLong(1, afterId)
      statement.setInt(2, limit)
      val result = statement.executeQuery()

      val rows = new ListBuffer[FiledEvent]()
      while (result.next()) {
        rows += FiledEvent(result.getLong("id"), HighscoreEvent(
          world = Option(result.getString("world")).getOrElse(""),
          category = Option(result.getString("category")).getOrElse(""),
          name = Option(result.getString("name")).getOrElse(""),
          displayName = Option(result.getString("display_name")).getOrElse(""),
          vocation = Option(result.getString("vocation")).getOrElse(""),
          level = result.getInt("char_level"),
          previousScore = result.getLong("previous_score"),
          score = result.getLong("score"),
          observed = result.getTimestamp("observed").toInstant
        ))
      }

      statement.close()
      rows.toList
    }

  def maxEventId(): Long =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement("SELECT COALESCE(MAX(id), 0) AS max_id FROM highscore_events;")
      val result = statement.executeQuery()
      val id = if (result.next()) result.getLong("max_id") else 0L
      statement.close()
      id
    }

  def feedCursor(botId: String): Option[Long] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement("SELECT last_event_id FROM highscore_feed_cursor WHERE bot_id = ?;")
      statement.setString(1, botId)
      val result = statement.executeQuery()
      val cursor = if (result.next()) Some(result.getLong("last_event_id")) else None
      statement.close()
      cursor
    }

  def setFeedCursor(botId: String, eventId: Long): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        s"""
           |INSERT INTO highscore_feed_cursor(bot_id, last_event_id)
           |VALUES (?,?)
           |ON CONFLICT (bot_id)
           |DO UPDATE SET last_event_id = excluded.last_event_id;
           |""".stripMargin
      )
      statement.setString(1, botId)
      statement.setLong(2, eventId)
      statement.executeUpdate()
      statement.close()
    }

  def removeStale(world: String, before: Instant): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement("DELETE FROM highscore_value WHERE world = ? AND last_seen < ?;")
      statement.setString(1, world)
      statement.setTimestamp(2, Timestamp.from(before))
      statement.executeUpdate()

      statement.close()
    }

  def removeExpiredEvents(before: Instant): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement("DELETE FROM highscore_events WHERE observed < ?;")
      statement.setTimestamp(1, Timestamp.from(before))
      statement.executeUpdate()

      statement.close()
    }
}
