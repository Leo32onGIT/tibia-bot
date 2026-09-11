package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.ExperienceDelta
import com.tibiabot.highscores.HighscoreDiff
import com.tibiabot.persistence.{ConnectionProvider, ExperienceRepository}
import com.tibiabot.tibiadata.response.HighscoreEntry

import java.sql.{Date => SqlDate, Timestamp}
import java.time.LocalDate
import scala.collection.mutable.ListBuffer

/** JDBC implementation of ExperienceRepository against the shared bot_cache
 *  database. Both tables are created up front by SchemaInitializer.initCache. */
final class JdbcExperienceRepository(connectionProvider: ConnectionProvider) extends ExperienceRepository {

  private val batchSize = 500

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

  def dailyMovers(world: String, saveDay: LocalDate, limit: Int): List[ExperienceDelta] =
    movers(world, saveDay, "DESC", limit)

  def dailyLosses(world: String, saveDay: LocalDate, limit: Int): List[ExperienceDelta] =
    movers(world, saveDay, "ASC", limit).filter(_.gained < 0)

  /** Both ends of the day's ordering, which differ only in direction.
   *
   *  An inner join against the previous day is what drops the characters who
   *  have no baseline, so the exclusion is the join rather than a filter that
   *  could be forgotten. `direction` is a literal chosen here, never user input.
   */
  private def movers(world: String, saveDay: LocalDate, direction: String, limit: Int): List[ExperienceDelta] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        s"""
           |SELECT today.name,
           |       today.display_name,
           |       today.vocation,
           |       today.char_level,
           |       today.experience,
           |       before.char_level AS previous_level,
           |       today.experience - before.experience AS gained
           |FROM experience_daily today
           |JOIN experience_daily before
           |  ON before.world = today.world
           | AND before.name = today.name
           | AND before.save_day = ?
           |WHERE today.world = ? AND today.save_day = ?
           |ORDER BY gained $direction
           |LIMIT ?;
           |""".stripMargin
      )
      statement.setDate(1, SqlDate.valueOf(saveDay.minusDays(1)))
      statement.setString(2, world)
      statement.setDate(3, SqlDate.valueOf(saveDay))
      statement.setInt(4, limit)
      val result = statement.executeQuery()

      val deltas = new ListBuffer[ExperienceDelta]()
      while (result.next()) {
        val key = Option(result.getString("name")).getOrElse("")
        deltas += ExperienceDelta(
          name = key,
          displayName = Option(result.getString("display_name")).getOrElse(key),
          vocation = Option(result.getString("vocation")).getOrElse(""),
          level = result.getInt("char_level"),
          previousLevel = result.getInt("previous_level"),
          experience = result.getLong("experience"),
          gained = result.getLong("gained")
        )
      }

      statement.close()
      deltas.toList
    }

  def lossesAmong(world: String, saveDay: LocalDate, names: Set[String], limit: Int): List[ExperienceDelta] =
    if (names.isEmpty) Nil
    else JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      // The name set is built from the guild's own hunted list, never from user
      // input reaching here directly, but it is still bound rather than spliced.
      val placeholders = List.fill(names.size)("?").mkString(",")
      val statement = conn.prepareStatement(
        s"""
           |SELECT today.name,
           |       today.display_name,
           |       today.vocation,
           |       today.char_level,
           |       today.experience,
           |       before.char_level AS previous_level,
           |       today.experience - before.experience AS gained
           |FROM experience_daily today
           |JOIN experience_daily before
           |  ON before.world = today.world
           | AND before.name = today.name
           | AND before.save_day = ?
           |WHERE today.world = ? AND today.save_day = ?
           |  AND today.name IN ($placeholders)
           |  AND today.experience < before.experience
           |ORDER BY gained ASC
           |LIMIT ?;
           |""".stripMargin
      )
      statement.setDate(1, SqlDate.valueOf(saveDay.minusDays(1)))
      statement.setString(2, world)
      statement.setDate(3, SqlDate.valueOf(saveDay))
      val ordered = names.toList
      ordered.zipWithIndex.foreach { case (name, index) => statement.setString(4 + index, name.toLowerCase) }
      statement.setInt(4 + ordered.size, limit)
      val result = statement.executeQuery()

      val deltas = new ListBuffer[ExperienceDelta]()
      while (result.next()) {
        val key = Option(result.getString("name")).getOrElse("")
        deltas += ExperienceDelta(
          name = key,
          displayName = Option(result.getString("display_name")).getOrElse(key),
          vocation = Option(result.getString("vocation")).getOrElse(""),
          level = result.getInt("char_level"),
          previousLevel = result.getInt("previous_level"),
          experience = result.getLong("experience"),
          gained = result.getLong("gained")
        )
      }

      statement.close()
      deltas.toList
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
