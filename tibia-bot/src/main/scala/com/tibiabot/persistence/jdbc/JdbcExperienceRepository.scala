package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.ExperienceDelta
import com.tibiabot.highscores.HighscoreDiff
import com.tibiabot.persistence.{ConnectionProvider, ExperienceRepository}
import com.tibiabot.tibiadata.response.HighscoreEntry

import java.sql.{Date => SqlDate, PreparedStatement, ResultSet, Timestamp}
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

  def dailyGains(world: String, saveDay: LocalDate, limit: Int): List[ExperienceDelta] =
    movers(world, saveDay, ">", "DESC", limit)

  def dailyLosses(world: String, saveDay: LocalDate, limit: Int): List[ExperienceDelta] =
    movers(world, saveDay, "<", "ASC", limit)

  /** A day's rows joined to the day before, which is where every figure the
   *  statistics post reports comes from.
   *
   *  The inner join is what drops the characters who have no baseline — entering
   *  the world's top thousand is not a day's experience, and neither is dropping
   *  out of it — so the exclusion is the join rather than a filter somewhere
   *  else that could be forgotten.
   *
   *  Shared by all three readers below, which differ only in what they order by
   *  and what they add to this WHERE. */
  private val deltaSelect =
    """SELECT today.name,
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
      |WHERE today.world = ? AND today.save_day = ?""".stripMargin

  /** Either end of the day's ordering, which differ only in which way they face.
   *
   *  `comparison` and `direction` are literals chosen here, never user input.
   *
   *  Both ends exclude the middle in the query rather than afterwards.
   *  A mover who stood still is neither a gain nor a loss, and on a quiet world
   *  they reach well inside either top ten — printed under a heading that says
   *  gained or lost, they would be a plain untruth. Excluding them here is the
   *  same answer a filter on the results gives, since every loss sorts before
   *  every gain, and it means the caller has nothing left to remember. */
  private def movers(world: String, saveDay: LocalDate, comparison: String, direction: String,
                     limit: Int): List[ExperienceDelta] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        s"""$deltaSelect
           |  AND today.experience $comparison before.experience
           |ORDER BY gained $direction
           |LIMIT ?;""".stripMargin)
      statement.setInt(bindDay(statement, world, saveDay), limit)
      val rows = readDeltas(statement)
      statement.close()
      rows
    }

  def lossesAmong(world: String, saveDay: LocalDate, names: Set[String], limit: Int): List[ExperienceDelta] =
    if (names.isEmpty) Nil
    else JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      // The name set is built from the guild's own hunted list, never from user
      // input reaching here directly, but it is still bound rather than spliced.
      val placeholders = List.fill(names.size)("?").mkString(",")
      val statement = conn.prepareStatement(
        s"""$deltaSelect
           |  AND today.name IN ($placeholders)
           |  AND today.experience < before.experience
           |ORDER BY gained ASC
           |LIMIT ?;""".stripMargin)
      val next = bindDay(statement, world, saveDay)
      val ordered = names.toList
      ordered.zipWithIndex.foreach { case (name, index) => statement.setString(next + index, name.toLowerCase) }
      statement.setInt(next + ordered.size, limit)
      val rows = readDeltas(statement)
      statement.close()
      rows
    }

  /** The three parameters [[deltaSelect]] opens with — the baseline day, the
   *  world, and the day itself — bound in that order. Returns the next free
   *  index, so a caller's own parameters do not have to count them again. */
  private def bindDay(statement: PreparedStatement, world: String, saveDay: LocalDate): Int = {
    statement.setDate(1, SqlDate.valueOf(saveDay.minusDays(1)))
    statement.setString(2, world)
    statement.setDate(3, SqlDate.valueOf(saveDay))
    4
  }

  private def readDeltas(statement: PreparedStatement): List[ExperienceDelta] = {
    val result = statement.executeQuery()
    val deltas = new ListBuffer[ExperienceDelta]()
    while (result.next()) deltas += delta(result)
    deltas.toList
  }

  /** One row. The stored name is the lowercased key, so it stands in for a
   *  display name that was never recorded. */
  private def delta(result: ResultSet): ExperienceDelta = {
    val key = Option(result.getString("name")).getOrElse("")
    ExperienceDelta(
      name = key,
      displayName = Option(result.getString("display_name")).getOrElse(key),
      vocation = Option(result.getString("vocation")).getOrElse(""),
      level = result.getInt("char_level"),
      previousLevel = result.getInt("previous_level"),
      experience = result.getLong("experience"),
      gained = result.getLong("gained")
    )
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
