package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.{FragEvent, FragSide, FragTally}
import com.tibiabot.persistence.{ConnectionProvider, FragRepository}

import java.sql.{Date => SqlDate, Timestamp}
import java.time.LocalDate
import scala.collection.mutable.ListBuffer

/** JDBC implementation of FragRepository against a guild's own database.
 *
 *  The table is created here on first use rather than in SchemaInitializer's
 *  guild block, so the several hundred guilds that ran `/setup` long before this
 *  existed get it without a migration pass of their own — the same approach
 *  `JdbcActivityRepository` and `JdbcGalthenRepository` take. */
final class JdbcFragRepository(connectionProvider: ConnectionProvider) extends FragRepository {

  private def ensureTable(conn: java.sql.Connection): Unit = {
    val statement = conn.createStatement()
    statement.executeUpdate(
      """CREATE TABLE IF NOT EXISTS frag_event (
        |world VARCHAR(255) NOT NULL,
        |save_day DATE NOT NULL,
        |killer VARCHAR(255) NOT NULL,
        |victim VARCHAR(255) NOT NULL,
        |victim_side VARCHAR(16) NOT NULL,
        |occurred_at TIMESTAMP NOT NULL,
        |PRIMARY KEY (world, killer, victim, occurred_at)
        |);""".stripMargin)
    // The tally reads one world's day; the primary key leads with world but puts
    // the names before the date, so it cannot serve that on its own.
    statement.executeUpdate(
      "CREATE INDEX IF NOT EXISTS frag_event_world_day ON frag_event (world, save_day);")
    statement.close()
  }

  def record(guildId: String, events: List[FragEvent]): Unit =
    if (events.nonEmpty) JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
      ensureTable(conn)
      // DO NOTHING rather than an update: the key already carries the instant, so
      // a second write of the same row is a death being reprocessed, not a
      // correction.
      val statement = conn.prepareStatement(
        """INSERT INTO frag_event(world, save_day, killer, victim, victim_side, occurred_at)
          |VALUES (?,?,?,?,?,?)
          |ON CONFLICT (world, killer, victim, occurred_at) DO NOTHING;""".stripMargin)
      // One killer can appear twice on a death list — as themselves and behind a
      // summon — and Postgres refuses to touch a row twice in one statement.
      events.groupBy(e => (e.world, e.killer.toLowerCase, e.victim.toLowerCase, e.occurredAt))
        .values.map(_.head).foreach { event =>
          statement.setString(1, event.world)
          statement.setDate(2, SqlDate.valueOf(event.saveDay))
          statement.setString(3, event.killer)
          statement.setString(4, event.victim)
          statement.setString(5, event.side.stored)
          statement.setTimestamp(6, Timestamp.from(event.occurredAt))
          statement.addBatch()
        }
      statement.executeBatch()
      statement.close()
    }

  def tally(guildId: String, world: String, saveDay: LocalDate, topFraggers: Int): FragTally =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
      ensureTable(conn)
      val counts = sideCounts(conn, world, saveDay)
      FragTally(
        enemiesKilled = counts.getOrElse(FragSide.Enemy.stored, 0),
        alliesKilled = counts.getOrElse(FragSide.Ally.stored, 0),
        topAllied = fraggers(conn, world, saveDay, FragSide.Enemy, topFraggers),
        topEnemy = fraggers(conn, world, saveDay, FragSide.Ally, topFraggers)
      )
    }

  /** How many players died on each side. One row per death, so this counts
   *  deaths rather than killers — a victim killed by eight people is one loss,
   *  not eight. */
  private def sideCounts(conn: java.sql.Connection, world: String, saveDay: LocalDate): Map[String, Int] = {
    // Counted over a DISTINCT subquery rather than COUNT(DISTINCT (a, b)). The
    // row-constructor form works in Postgres, but it is exotic enough not to
    // want to depend on in a query no test can exercise without a live database.
    val statement = conn.prepareStatement(
      """SELECT victim_side, COUNT(*) AS deaths FROM (
        |  SELECT DISTINCT victim_side, victim, occurred_at
        |  FROM frag_event
        |  WHERE world = ? AND save_day = ?
        |) deaths
        |GROUP BY victim_side;""".stripMargin)
    statement.setString(1, world)
    statement.setDate(2, SqlDate.valueOf(saveDay))
    val result = statement.executeQuery()
    var counts = Map.empty[String, Int]
    while (result.next()) counts += (result.getString("victim_side") -> result.getInt("deaths"))
    statement.close()
    counts
  }

  /** Who killed the most players on the named side, most first.
   *
   *  Grouped case-insensitively and rendered from one of the stored spellings,
   *  the same split every other name in this bot keeps between the key and what
   *  a post shows. */
  private def fraggers(conn: java.sql.Connection, world: String, saveDay: LocalDate,
                       side: FragSide, limit: Int): List[(String, Int)] = {
    val statement = conn.prepareStatement(
      """SELECT MIN(killer) AS name, COUNT(*) AS frags
        |FROM frag_event
        |WHERE world = ? AND save_day = ? AND victim_side = ?
        |GROUP BY LOWER(killer)
        |ORDER BY frags DESC, name ASC
        |LIMIT ?;""".stripMargin)
    statement.setString(1, world)
    statement.setDate(2, SqlDate.valueOf(saveDay))
    statement.setString(3, side.stored)
    statement.setInt(4, limit)
    val result = statement.executeQuery()
    val rows = new ListBuffer[(String, Int)]()
    while (result.next()) rows += ((result.getString("name"), result.getInt("frags")))
    statement.close()
    rows.toList
  }

  def removeExpired(guildId: String, before: LocalDate): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
      ensureTable(conn)
      val statement = conn.prepareStatement("DELETE FROM frag_event WHERE save_day < ?;")
      statement.setDate(1, SqlDate.valueOf(before))
      statement.executeUpdate()
      statement.close()
    }
}
