package com.tibiabot.persistence.jdbc

import com.tibiabot.persistence.{ConnectionProvider, WorldOnlineAverage, WorldOnlineRepository}

import java.sql.{Date => SqlDate}
import java.time.LocalDate

/** JDBC implementation of WorldOnlineRepository against the shared bot_cache
 *  database. The table is created up front by SchemaInitializer.initCache. */
final class JdbcWorldOnlineRepository(connectionProvider: ConnectionProvider) extends WorldOnlineRepository {

  /** One upsert that reads nothing back, so a poll never waits on a round trip
   *  it has no use for. The row accumulates rather than being replaced, which is
   *  what makes several bots on the same world safe to add together. */
  def recordSample(world: String, saveDay: LocalDate, online: Int, levelTotal: Long): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        s"""
           |INSERT INTO world_online_daily(world, save_day, samples, total, level_total)
           |VALUES (?,?,1,?,?)
           |ON CONFLICT (world, save_day)
           |DO UPDATE SET samples = world_online_daily.samples + 1,
           |              total = world_online_daily.total + excluded.total,
           |              level_total = world_online_daily.level_total + excluded.level_total;
           |""".stripMargin
      )
      statement.setString(1, world)
      statement.setDate(2, SqlDate.valueOf(saveDay))
      statement.setLong(3, math.max(0, online).toLong)
      statement.setLong(4, math.max(0L, levelTotal))
      statement.executeUpdate()
      statement.close()
    }

  /** None where the day was never sampled, and also where it holds no samples
   *  or nobody at all — dividing by either would be a crash rather than a
   *  figure. A world that was empty every time it was looked at has no average
   *  level to report, which is the honest answer. */
  def averages(world: String, saveDay: LocalDate): Option[WorldOnlineAverage] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        "SELECT samples, total, level_total FROM world_online_daily WHERE world = ? AND save_day = ?;")
      statement.setString(1, world)
      statement.setDate(2, SqlDate.valueOf(saveDay))
      val result = statement.executeQuery()
      val average =
        if (!result.next()) None
        else {
          val samples = result.getInt("samples")
          val total = result.getLong("total")
          if (samples <= 0 || total <= 0) None
          else Some(WorldOnlineAverage(
            online = total.toDouble / samples,
            level = result.getLong("level_total").toDouble / total))
        }
      statement.close()
      average
    }

  def removeExpired(before: LocalDate): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement("DELETE FROM world_online_daily WHERE save_day < ?;")
      statement.setDate(1, SqlDate.valueOf(before))
      statement.executeUpdate()
      statement.close()
    }
}
