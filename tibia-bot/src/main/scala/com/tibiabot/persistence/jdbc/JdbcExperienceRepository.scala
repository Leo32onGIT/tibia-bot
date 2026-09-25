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

  def readingTimes(world: String, from: Instant, to: Instant): List[Instant] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      // DISTINCT over a thousand rows an hour rather than a table of instants
      // kept beside this one. A second table would be one more thing the sweep
      // has to keep true, where this cannot disagree with the readings because
      // it is the readings.
      val statement = conn.prepareStatement(
        """SELECT DISTINCT observed
          |FROM experience_reading
          |WHERE world = ? AND observed >= ? AND observed <= ?
          |ORDER BY observed;""".stripMargin)
      statement.setString(1, world)
      statement.setTimestamp(2, Timestamp.from(from))
      statement.setTimestamp(3, Timestamp.from(to))
      val result = statement.executeQuery()
      val times = new ListBuffer[Instant]()
      while (result.next()) times += result.getTimestamp("observed").toInstant
      statement.close()
      times.toList
    }

  def gainsBetween(world: String, from: Instant, to: Instant, limit: Int): List[ExperienceDelta] =
    between(world, from, to, ">", "DESC", limit)

  def lossesBetween(world: String, from: Instant, to: Instant, limit: Int): List[ExperienceDelta] =
    between(world, from, to, "<", "ASC", limit)

  /** Two readings of the same world joined on the character: every
   *  character's movement between two exact hours.
   *
   *  Both ends are an equality on `observed`, so each end is a primary key
   *  lookup — `(world, name, observed)` leads with exactly what is bound here —
   *  and every character is measured over the same span rather than over
   *  whatever reading happened to sit nearest their own.
   *
   *  The display name and vocation come from the rollup, because a reading
   *  carries neither: they are the same for every reading of a character and
   *  storing them twenty-four times a day was half of what made this table
   *  expensive. The lateral runs after the limit, so it costs ten lookups and
   *  not one per mover on the world. Its `ORDER BY save_day DESC` takes the
   *  most recent spelling rather than a named day's, which is what keeps a name
   *  from going missing in the first hours of a save day, before any sweep has
   *  written a rollup row for it.
   *
   *  `comparison` and `direction` are literals chosen here, never user input.
   *  A character who stood still is excluded in the query for the same reason
   *  [[movers]] excludes them: printed under a heading that says gained or
   *  lost, a figure of nought is a plain untruth. */
  private def between(world: String, from: Instant, to: Instant, comparison: String,
                      direction: String, limit: Int): List[ExperienceDelta] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        s"""WITH moved AS (
           |  SELECT later.name,
           |         later.char_level,
           |         later.experience,
           |         earlier.char_level AS previous_level,
           |         later.experience - earlier.experience AS gained
           |  FROM experience_reading later
           |  JOIN experience_reading earlier
           |    ON earlier.world = later.world
           |   AND earlier.name = later.name
           |   AND earlier.observed = ?
           |  WHERE later.world = ? AND later.observed = ?
           |    AND later.experience $comparison earlier.experience
           |  ORDER BY gained $direction
           |  LIMIT ?
           |)
           |SELECT moved.name,
           |       COALESCE(named.display_name, moved.name) AS display_name,
           |       COALESCE(named.vocation, '') AS vocation,
           |       moved.char_level,
           |       moved.experience,
           |       moved.previous_level,
           |       moved.gained
           |FROM moved
           |LEFT JOIN LATERAL (
           |  SELECT display_name, vocation
           |  FROM experience_daily
           |  WHERE world = ? AND name = moved.name
           |  ORDER BY save_day DESC
           |  LIMIT 1
           |) named ON true
           |ORDER BY moved.gained $direction;""".stripMargin)
      statement.setTimestamp(1, Timestamp.from(from))
      statement.setString(2, world)
      statement.setTimestamp(3, Timestamp.from(to))
      statement.setInt(4, limit)
      statement.setString(5, world)
      val rows = readDeltas(statement)
      statement.close()
      rows
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
