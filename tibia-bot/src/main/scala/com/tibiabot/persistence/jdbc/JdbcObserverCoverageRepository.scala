package com.tibiabot.persistence.jdbc

import com.tibiabot.persistence.{ConnectionProvider, CoveredArea, ObserverCoverageRepository}

import scala.collection.mutable.ListBuffer

/** JDBC implementation of [[ObserverCoverageRepository]] over `bot_cache`. Tables
 *  are created by SchemaInitializer.initCache. */
final class JdbcObserverCoverageRepository(connectionProvider: ConnectionProvider) extends ObserverCoverageRepository {

  def setAreas(guildId: String, userId: String, areas: Map[String, List[Int]]): Unit =
    JdbcSupport.withTransaction(connectionProvider.cache) { conn =>
      val delete = conn.prepareStatement("DELETE FROM observer_link_areas WHERE guildid = ? AND userid = ?")
      try { delete.setString(1, guildId); delete.setString(2, userId); delete.executeUpdate() }
      finally delete.close()
      val insert = conn.prepareStatement(
        """INSERT INTO observer_link_areas (guildid, userid, world, area_id) VALUES (?, ?, ?, ?)
          |ON CONFLICT DO NOTHING""".stripMargin)
      try {
        for ((world, ids) <- areas; id <- ids.distinct) {
          insert.setString(1, guildId)
          insert.setString(2, userId)
          insert.setString(3, world)
          insert.setInt(4, id)
          insert.addBatch()
        }
        insert.executeBatch()
      } finally insert.close()
    }

  def clearLink(guildId: String, userId: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement("DELETE FROM observer_link_areas WHERE guildid = ? AND userid = ?")
      try { statement.setString(1, guildId); statement.setString(2, userId); statement.executeUpdate() }
      finally statement.close()
    }

  def clearGuild(guildId: String): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement("DELETE FROM observer_link_areas WHERE guildid = ?")
      try { statement.setString(1, guildId); statement.executeUpdate() }
      finally statement.close()
    }

  // Only links that work: one marked for relinking, or gone, covers nothing.
  def liveAreas(worlds: List[String]): List[CoveredArea] =
    if (worlds.isEmpty) Nil
    else JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        """SELECT a.guildid, a.userid, a.world, a.area_id
          |FROM observer_link_areas a
          |JOIN observer_tokens t ON t.guildid = a.guildid AND t.userid = a.userid
          |WHERE t.status = 'linked' AND LOWER(a.world) = ANY (?)""".stripMargin)
      try {
        statement.setArray(1, conn.createArrayOf("varchar", worlds.map(_.toLowerCase).distinct.toArray[AnyRef]))
        val result = statement.executeQuery()
        val rows = new ListBuffer[CoveredArea]
        while (result.next())
          rows += CoveredArea(result.getString("guildid"), result.getString("userid"),
            result.getString("world"), result.getInt("area_id"))
        rows.toList
      } finally statement.close()
    }

  def setNames(names: Map[Int, String]): Unit =
    if (names.nonEmpty) JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        "INSERT INTO observer_area_names (area_id, name) VALUES (?, ?) ON CONFLICT (area_id) DO UPDATE SET name = EXCLUDED.name")
      try {
        names.foreach { case (id, name) =>
          statement.setInt(1, id)
          statement.setString(2, name)
          statement.addBatch()
        }
        statement.executeBatch()
      } finally statement.close()
    }

  def names(): Map[Int, String] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.createStatement()
      try {
        val result = statement.executeQuery("SELECT area_id, name FROM observer_area_names")
        val rows = Map.newBuilder[Int, String]
        while (result.next()) rows += result.getInt("area_id") -> result.getString("name")
        rows.result()
      } finally statement.close()
    }
}
