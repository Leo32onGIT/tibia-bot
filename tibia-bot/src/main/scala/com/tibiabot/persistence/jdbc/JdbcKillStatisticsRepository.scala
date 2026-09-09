package com.tibiabot.persistence.jdbc

import com.tibiabot.persistence.{ConnectionProvider, KillStatisticsRepository}
import com.tibiabot.statistics.{BossKills, DayKillSummary}

import java.sql.{Date => SqlDate}
import java.time.LocalDate
import scala.collection.mutable.ListBuffer

/** JDBC implementation of KillStatisticsRepository against the shared bot_cache
 *  database. Both tables are created up front by SchemaInitializer.initCache. */
final class JdbcKillStatisticsRepository(connectionProvider: ConnectionProvider) extends KillStatisticsRepository {

  def recordBossKills(rows: List[BossKills]): Unit =
    if (rows.nonEmpty) JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      // Upsert rather than insert-or-ignore: a re-run means the first attempt
      // did not finish, and the later reading is the better one.
      val statement = conn.prepareStatement(
        s"""
           |INSERT INTO kill_statistics_boss(world, save_day, race, killed, players_killed)
           |VALUES (?,?,?,?,?)
           |ON CONFLICT (world, save_day, race)
           |DO UPDATE SET killed = excluded.killed, players_killed = excluded.players_killed;
           |""".stripMargin
      )
      // A boss can only appear once per world per day; the catalogue is keyed by
      // race, but a hand-edited file could still repeat one, and Postgres
      // refuses to touch a row twice in one statement.
      rows.groupBy(row => (row.world, row.saveDay, row.race.toLowerCase)).values.map(_.last).foreach { row =>
        statement.setString(1, row.world)
        statement.setDate(2, SqlDate.valueOf(row.saveDay))
        statement.setString(3, row.race)
        statement.setInt(4, row.killed)
        statement.setInt(5, row.playersKilled)
        statement.addBatch()
      }
      statement.executeBatch()
      statement.close()
    }

  def recordSummary(summary: DayKillSummary): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        s"""
           |INSERT INTO kill_statistics_summary(world, save_day, most_killed_race, most_killed,
           |  deadliest_race, deadliest_kills, player_deaths, total_killed, total_players_killed)
           |VALUES (?,?,?,?,?,?,?,?,?)
           |ON CONFLICT (world, save_day)
           |DO UPDATE SET
           |  most_killed_race = excluded.most_killed_race,
           |  most_killed = excluded.most_killed,
           |  deadliest_race = excluded.deadliest_race,
           |  deadliest_kills = excluded.deadliest_kills,
           |  player_deaths = excluded.player_deaths,
           |  total_killed = excluded.total_killed,
           |  total_players_killed = excluded.total_players_killed;
           |""".stripMargin
      )
      statement.setString(1, summary.world)
      statement.setDate(2, SqlDate.valueOf(summary.saveDay))
      // "" and 0 for a day that had no such creature — the column pair is read
      // back as None on the empty name, so absence survives the round trip.
      statement.setString(3, summary.mostKilled.map(_._1).getOrElse(""))
      statement.setInt(4, summary.mostKilled.map(_._2).getOrElse(0))
      statement.setString(5, summary.deadliest.map(_._1).getOrElse(""))
      statement.setInt(6, summary.deadliest.map(_._2).getOrElse(0))
      statement.setInt(7, summary.playerDeaths)
      statement.setLong(8, summary.totalKilled)
      statement.setInt(9, summary.totalPlayersKilled)
      statement.executeUpdate()
      statement.close()
    }

  def hasDay(world: String, saveDay: LocalDate): Boolean =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        "SELECT 1 FROM kill_statistics_summary WHERE world = ? AND save_day = ?;")
      statement.setString(1, world)
      statement.setDate(2, SqlDate.valueOf(saveDay))
      val result = statement.executeQuery()
      val found = result.next()
      statement.close()
      found
    }

  def bossHistory(world: String, race: String, from: LocalDate): List[BossKills] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        s"""
           |SELECT race,save_day,killed,players_killed
           |FROM kill_statistics_boss
           |WHERE world = ? AND LOWER(race) = LOWER(?) AND save_day >= ?
           |ORDER BY save_day ASC;
           |""".stripMargin
      )
      statement.setString(1, world)
      statement.setString(2, race)
      statement.setDate(3, SqlDate.valueOf(from))
      val result = statement.executeQuery()

      val rows = new ListBuffer[BossKills]()
      while (result.next()) {
        rows += BossKills(
          world = world,
          saveDay = result.getDate("save_day").toLocalDate,
          race = Option(result.getString("race")).getOrElse(race),
          killed = result.getInt("killed"),
          playersKilled = result.getInt("players_killed")
        )
      }

      statement.close()
      rows.toList
    }

  def summary(world: String, saveDay: LocalDate): Option[DayKillSummary] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        s"""
           |SELECT most_killed_race,most_killed,deadliest_race,deadliest_kills,
           |       player_deaths,total_killed,total_players_killed
           |FROM kill_statistics_summary
           |WHERE world = ? AND save_day = ?;
           |""".stripMargin
      )
      statement.setString(1, world)
      statement.setDate(2, SqlDate.valueOf(saveDay))
      val result = statement.executeQuery()

      val row = if (result.next()) Some(DayKillSummary(
        world = world,
        saveDay = saveDay,
        mostKilled = named(result.getString("most_killed_race"), result.getInt("most_killed")),
        deadliest = named(result.getString("deadliest_race"), result.getInt("deadliest_kills")),
        playerDeaths = result.getInt("player_deaths"),
        totalKilled = result.getLong("total_killed"),
        totalPlayersKilled = result.getInt("total_players_killed")
      )) else None

      statement.close()
      row
    }

  def removeExpired(before: LocalDate): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val day = SqlDate.valueOf(before)
      val bossRows = conn.prepareStatement("DELETE FROM kill_statistics_boss WHERE save_day < ?;")
      bossRows.setDate(1, day)
      bossRows.executeUpdate()
      bossRows.close()
      val summaryRows = conn.prepareStatement("DELETE FROM kill_statistics_summary WHERE save_day < ?;")
      summaryRows.setDate(1, day)
      summaryRows.executeUpdate()
      summaryRows.close()
    }

  /** The stored empty name is what "there was no such creature that day" looks
   *  like, so it must not come back as a creature called "". */
  private def named(race: String, count: Int): Option[(String, Int)] =
    Option(race).map(_.trim).filter(_.nonEmpty).map(name => (name, count))
}
