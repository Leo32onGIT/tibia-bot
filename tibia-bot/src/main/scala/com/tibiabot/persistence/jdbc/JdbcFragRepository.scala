package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.{FragEvent, FragSide, FragTally, Fragger, Repeat, TopKill}
import com.tibiabot.persistence.{ConnectionProvider, FragRepository}

import java.sql.{Connection, Date => SqlDate, Timestamp}
import java.time.{Instant, LocalDate}
import scala.collection.mutable.ListBuffer

/** JDBC implementation of FragRepository against a guild's own database.
 *
 *  The table is created here on first use rather than in SchemaInitializer's
 *  guild block, so the several hundred guilds that ran `/setup` long before this
 *  existed get it without a migration pass of their own — the same approach
 *  `JdbcActivityRepository` and `JdbcGalthenRepository` take. */
final class JdbcFragRepository(connectionProvider: ConnectionProvider) extends FragRepository {

  private def ensureTable(conn: Connection): Unit = {
    val statement = conn.createStatement()
    statement.executeUpdate(
      """CREATE TABLE IF NOT EXISTS frag_event (
        |world VARCHAR(255) NOT NULL,
        |save_day DATE NOT NULL,
        |killer VARCHAR(255) NOT NULL,
        |victim VARCHAR(255) NOT NULL,
        |victim_level INT NOT NULL DEFAULT 0,
        |victim_side VARCHAR(16) NOT NULL,
        |occurred_at TIMESTAMP NOT NULL,
        |death_message_id VARCHAR(64) NOT NULL DEFAULT '',
        |PRIMARY KEY (world, killer, victim, occurred_at)
        |);""".stripMargin)
    // The tally reads one world's day; the primary key leads with world but puts
    // the names before the date, so it cannot serve that on its own.
    statement.executeUpdate(
      "CREATE INDEX IF NOT EXISTS frag_event_world_day ON frag_event (world, save_day);")
    statement.close()
    // For a guild that picked the table up before these two columns existed.
    addColumn(conn, "victim_level", "INT NOT NULL DEFAULT 0")
    addColumn(conn, "death_message_id", "VARCHAR(64) NOT NULL DEFAULT ''")
  }

  private def addColumn(conn: Connection, column: String, definition: String): Unit = {
    val check = conn.createStatement()
    val result = check.executeQuery(
      s"SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'frag_event' AND COLUMN_NAME = '$column'")
    val exists = result.next()
    result.close()
    if (!exists) check.execute(s"ALTER TABLE frag_event ADD COLUMN $column $definition")
    check.close()
  }

  def record(guildId: String, events: List[FragEvent]): Unit =
    if (events.nonEmpty) JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
      ensureTable(conn)
      // DO NOTHING rather than an update: the key already carries the instant, so
      // a second write of the same row is a death being reprocessed, not a
      // correction.
      val statement = conn.prepareStatement(
        """INSERT INTO frag_event(world, save_day, killer, victim, victim_level, victim_side, occurred_at, death_message_id)
          |VALUES (?,?,?,?,?,?,?,?)
          |ON CONFLICT (world, killer, victim, occurred_at) DO NOTHING;""".stripMargin)
      // One killer can appear twice on a death list — as themselves and behind a
      // summon — and Postgres refuses to touch a row twice in one statement.
      events.groupBy(e => (e.world, e.killer.toLowerCase, e.victim.toLowerCase, e.occurredAt))
        .values.map(_.head).foreach { event =>
          statement.setString(1, event.world)
          statement.setDate(2, SqlDate.valueOf(event.saveDay))
          statement.setString(3, event.killer)
          statement.setString(4, event.victim)
          statement.setInt(5, event.victimLevel)
          statement.setString(6, event.side.stored)
          statement.setTimestamp(7, Timestamp.from(event.occurredAt))
          statement.setString(8, event.deathMessageId)
          statement.addBatch()
        }
      statement.executeBatch()
      statement.close()
    }

  def attachDeathMessage(guildId: String, world: String, victim: String,
                         occurredAt: Instant, messageId: String): Unit =
    if (messageId.nonEmpty) JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
      ensureTable(conn)
      // Every killer of that death gets the same message, so this is keyed on the
      // victim and the instant rather than on one row. Only rows still holding
      // the empty string are touched, so a re-post cannot overwrite a good id.
      val statement = conn.prepareStatement(
        """UPDATE frag_event SET death_message_id = ?
          |WHERE world = ? AND LOWER(victim) = LOWER(?) AND occurred_at = ? AND death_message_id = '';""".stripMargin)
      statement.setString(1, messageId)
      statement.setString(2, world)
      statement.setString(3, victim)
      statement.setTimestamp(4, Timestamp.from(occurredAt))
      statement.executeUpdate()
      statement.close()
    }

  def tally(guildId: String, world: String, saveDay: LocalDate,
            topFraggers: Int, topRepeats: Int): FragTally =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
      ensureTable(conn)
      val counts = sideCounts(conn, world, saveDay)
      // Five a side taken separately and then merged, so a one-sided day cannot
      // crowd the other side out of its own post.
      val ours = fraggers(conn, world, saveDay, FragSide.Enemy, topFraggers)
      val theirs = fraggers(conn, world, saveDay, FragSide.Ally, topFraggers)
      FragTally(
        enemiesKilled = counts.getOrElse(FragSide.Enemy.stored, (0, 0L))._1,
        alliesKilled = counts.getOrElse(FragSide.Ally.stored, (0, 0L))._1,
        enemyLevels = counts.getOrElse(FragSide.Enemy.stored, (0, 0L))._2,
        allyLevels = counts.getOrElse(FragSide.Ally.stored, (0, 0L))._2,
        fraggers = (ours ++ theirs).sortBy(row => (-row.kills, row.name.toLowerCase)),
        mostWanted = repeats(conn, world, saveDay, topRepeats),
        topEnemyKilled = topKill(conn, world, saveDay, FragSide.Enemy),
        topAllyKilled = topKill(conn, world, saveDay, FragSide.Ally)
      )
    }

  /** How many players died on each side, and what their levels added up to.
   *
   *  Counted over a DISTINCT subquery rather than COUNT(DISTINCT (a, b)). The
   *  row-constructor form works in Postgres, but it is exotic enough not to want
   *  to depend on in a query no test can exercise without a live database. */
  private def sideCounts(conn: Connection, world: String, saveDay: LocalDate): Map[String, (Int, Long)] = {
    val statement = conn.prepareStatement(
      """SELECT victim_side, COUNT(*) AS deaths, COALESCE(SUM(victim_level), 0) AS levels FROM (
        |  SELECT DISTINCT victim_side, victim, victim_level, occurred_at
        |  FROM frag_event
        |  WHERE world = ? AND save_day = ?
        |) deaths
        |GROUP BY victim_side;""".stripMargin)
    statement.setString(1, world)
    statement.setDate(2, SqlDate.valueOf(saveDay))
    val result = statement.executeQuery()
    var counts = Map.empty[String, (Int, Long)]
    while (result.next())
      counts += (result.getString("victim_side") -> ((result.getInt("deaths"), result.getLong("levels"))))
    statement.close()
    counts
  }

  /** Who killed the most players on the named side, most first.
   *
   *  Grouped case-insensitively and rendered from one of the stored spellings,
   *  the same split every other name in this bot keeps between the key and what
   *  a post shows. */
  private def fraggers(conn: Connection, world: String, saveDay: LocalDate,
                       side: FragSide, limit: Int): List[Fragger] = {
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
    val rows = new ListBuffer[Fragger]()
    // The killers of an enemy are on our side, and the other way round — a row's
    // side is the opposite of the victim's.
    val killerSide = if (side == FragSide.Enemy) FragSide.Ally else FragSide.Enemy
    while (result.next()) rows += Fragger(result.getString("name"), killerSide, result.getInt("frags"))
    statement.close()
    rows.toList
  }

  /** Enemies who died more than anyone else. Deaths, not frags, so being killed
   *  by six people at once still counts once. */
  private def repeats(conn: Connection, world: String, saveDay: LocalDate, limit: Int): List[Repeat] = {
    val statement = conn.prepareStatement(
      """SELECT MIN(victim) AS name, MAX(victim_level) AS level, COUNT(*) AS deaths FROM (
        |  SELECT DISTINCT victim, victim_level, occurred_at
        |  FROM frag_event
        |  WHERE world = ? AND save_day = ? AND victim_side = ?
        |) deaths
        |GROUP BY LOWER(victim)
        |ORDER BY deaths DESC, name ASC
        |LIMIT ?;""".stripMargin)
    statement.setString(1, world)
    statement.setDate(2, SqlDate.valueOf(saveDay))
    statement.setString(3, FragSide.Enemy.stored)
    statement.setInt(4, limit)
    val result = statement.executeQuery()
    val rows = new ListBuffer[Repeat]()
    while (result.next()) rows += Repeat(result.getString("name"), result.getInt("level"), result.getInt("deaths"))
    statement.close()
    rows.toList
  }

  /** The highest-level victim on one side, and the death that can be linked to.
   *
   *  A level of 0 is a row filed before the column existed, and is skipped rather
   *  than reported as the day's biggest kill at level zero. */
  private def topKill(conn: Connection, world: String, saveDay: LocalDate, side: FragSide): Option[TopKill] = {
    val statement = conn.prepareStatement(
      """SELECT victim, victim_level, death_message_id
        |FROM frag_event
        |WHERE world = ? AND save_day = ? AND victim_side = ? AND victim_level > 0
        |ORDER BY victim_level DESC, occurred_at ASC
        |LIMIT 1;""".stripMargin)
    statement.setString(1, world)
    statement.setDate(2, SqlDate.valueOf(saveDay))
    statement.setString(3, side.stored)
    val result = statement.executeQuery()
    val row =
      if (result.next())
        Some(TopKill(result.getString("victim"), result.getInt("victim_level"), side,
          Option(result.getString("death_message_id")).getOrElse("")))
      else None
    statement.close()
    row
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
