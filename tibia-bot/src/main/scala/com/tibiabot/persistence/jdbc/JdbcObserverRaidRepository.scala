package com.tibiabot.persistence.jdbc

import com.tibiabot.persistence.{ConnectionProvider, ObserverRaidRepository}

import java.sql.Timestamp
import java.time.Instant
import scala.collection.mutable.ListBuffer

/** JDBC implementation of [[ObserverRaidRepository]] over `bot_cache`. Tables are
 *  created by SchemaInitializer.initCache. */
final class JdbcObserverRaidRepository(connectionProvider: ConnectionProvider) extends ObserverRaidRepository {

  def setChannel(guildId: String, world: String, channelId: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        """INSERT INTO observer_raid_channels (guildid, world, channelid)
          |VALUES (?, ?, ?)
          |ON CONFLICT (guildid, world) DO UPDATE SET channelid = EXCLUDED.channelid;""".stripMargin)
      try { statement.setString(1, guildId); statement.setString(2, world); statement.setString(3, channelId); statement.executeUpdate() }
      finally statement.close()
    }

  def clearChannel(guildId: String, world: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement("DELETE FROM observer_raid_channels WHERE guildid = ? AND world = ?")
      try { statement.setString(1, guildId); statement.setString(2, world); statement.executeUpdate() }
      finally statement.close()
    }

  def clearGuild(guildId: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement("DELETE FROM observer_raid_channels WHERE guildid = ?")
      try { statement.setString(1, guildId); statement.executeUpdate() }
      finally statement.close()
    }

  def channelFor(guildId: String, world: String): Option[String] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement("SELECT channelid FROM observer_raid_channels WHERE guildid = ? AND world = ?")
      try {
        statement.setString(1, guildId)
        statement.setString(2, world)
        val result = statement.executeQuery()
        if (result.next()) Option(result.getString("channelid")) else None
      } finally statement.close()
    }

  def channelsForWorld(world: String): List[(String, String)] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement("SELECT guildid, channelid FROM observer_raid_channels WHERE world = ?")
      try {
        statement.setString(1, world)
        val result = statement.executeQuery()
        val rows = new ListBuffer[(String, String)]
        while (result.next()) rows += ((result.getString("guildid"), result.getString("channelid")))
        rows.toList
      } finally statement.close()
    }

  def markPostedIfNew(guildId: String, raidId: String, category: String): Boolean =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        """INSERT INTO observer_posted_raids (guildid, raid_id, category)
          |VALUES (?, ?, ?) ON CONFLICT DO NOTHING;""".stripMargin)
      try {
        statement.setString(1, guildId)
        statement.setString(2, raidId)
        statement.setString(3, category)
        statement.executeUpdate() == 1 // 1 = inserted (new), 0 = already there
      } finally statement.close()
    }

  def prunePostedOlderThan(cutoff: Instant): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement("DELETE FROM observer_posted_raids WHERE posted_at < ?")
      try { statement.setTimestamp(1, Timestamp.from(cutoff)); statement.executeUpdate() }
      finally statement.close()
    }
}
