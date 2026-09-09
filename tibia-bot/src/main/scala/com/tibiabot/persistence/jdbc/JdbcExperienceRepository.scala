package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.ExperiencePoint
import com.tibiabot.highscores.HighscoreDiff
import com.tibiabot.persistence.{ConnectionProvider, ExperienceRepository}
import com.tibiabot.tibiadata.response.HighscoreEntry

import java.sql.{Date => SqlDate, Timestamp}
import java.time.{Instant, LocalDate}
import scala.collection.mutable.ListBuffer

/** JDBC implementation of ExperienceRepository against the shared bot_cache
 *  database. Both tables are created up front by SchemaInitializer.initCache. */
final class JdbcExperienceRepository(connectionProvider: ConnectionProvider) extends ExperienceRepository {

  private val batchSize = 500

  def recordReadings(world: String, entries: List[HighscoreEntry], observed: Instant): Unit =
    if (entries.nonEmpty) JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      // ON CONFLICT DO NOTHING rather than an update: the key already carries the
      // snapshot, so a second write of the same one is a re-run of work already
      // done, not a correction.
      val statement = conn.prepareStatement(
        s"""
           |INSERT INTO experience_reading(world, name, observed, char_level, experience)
           |VALUES (?,?,?,?,?)
           |ON CONFLICT (world, name, observed) DO NOTHING;
           |""".stripMargin
      )
      val at = Timestamp.from(observed)
      write(statement, dedupe(entries)) { case (key, entry) =>
        statement.setString(1, world)
        statement.setString(2, key)
        statement.setTimestamp(3, at)
        statement.setInt(4, entry.level)
        statement.setLong(5, entry.value)
      }
      statement.close()
    }

  def recordDaily(world: String, entries: List[HighscoreEntry], saveDay: LocalDate): Unit =
    if (entries.nonEmpty) JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        s"""
           |INSERT INTO experience_daily(world, name, save_day, display_name, vocation, char_level, experience)
           |VALUES (?,?,?,?,?,?,?)
           |ON CONFLICT (world, name, save_day)
           |DO UPDATE SET
           |  display_name = excluded.display_name,
           |  vocation = excluded.vocation,
           |  char_level = excluded.char_level,
           |  experience = excluded.experience;
           |""".stripMargin
      )
      val day = SqlDate.valueOf(saveDay)
      write(statement, dedupe(entries)) { case (key, entry) =>
        statement.setString(1, world)
        statement.setString(2, key)
        statement.setDate(3, day)
        statement.setString(4, entry.name)
        statement.setString(5, entry.vocation)
        statement.setInt(6, entry.level)
        statement.setLong(7, entry.value)
      }
      statement.close()
    }

  def daily(world: String, name: String, from: LocalDate): List[ExperiencePoint] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        s"""
           |SELECT name,display_name,vocation,char_level,experience,save_day
           |FROM experience_daily
           |WHERE world = ? AND name = ? AND save_day >= ?
           |ORDER BY save_day ASC;
           |""".stripMargin
      )
      statement.setString(1, world)
      statement.setString(2, HighscoreDiff.key(name))
      statement.setDate(3, SqlDate.valueOf(from))
      val result = statement.executeQuery()

      val points = new ListBuffer[ExperiencePoint]()
      while (result.next()) {
        val key = Option(result.getString("name")).getOrElse("")
        points += ExperiencePoint(
          name = key,
          displayName = Option(result.getString("display_name")).getOrElse(key),
          vocation = Option(result.getString("vocation")).getOrElse(""),
          level = result.getInt("char_level"),
          experience = result.getLong("experience"),
          saveDay = result.getDate("save_day").toLocalDate
        )
      }

      statement.close()
      points.toList
    }

  def removeExpiredReadings(before: Instant): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement("DELETE FROM experience_reading WHERE observed < ?;")
      statement.setTimestamp(1, Timestamp.from(before))
      statement.executeUpdate()
      statement.close()
    }

  def removeExpiredDaily(before: LocalDate): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement("DELETE FROM experience_daily WHERE save_day < ?;")
      statement.setDate(1, SqlDate.valueOf(before))
      statement.executeUpdate()
      statement.close()
    }

  /** Last reading of a name wins — tibia.com can hand the same character back on
   *  two pages if it reshuffles mid-fetch, and Postgres refuses to touch one row
   *  twice in a single statement. */
  private def dedupe(entries: List[HighscoreEntry]): Map[String, HighscoreEntry] =
    entries.groupBy(entry => HighscoreDiff.key(entry.name)).map { case (key, rows) => key -> rows.last }

  private def write(statement: java.sql.PreparedStatement, rows: Map[String, HighscoreEntry])
                   (bind: ((String, HighscoreEntry)) => Unit): Unit = {
    var pending = 0
    rows.foreach { row =>
      bind(row)
      statement.addBatch()
      pending += 1
      if (pending == batchSize) {
        statement.executeBatch()
        pending = 0
      }
    }
    if (pending > 0) statement.executeBatch()
  }
}
