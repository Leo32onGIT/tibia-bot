package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.{Guilds, Players}
import com.tibiabot.persistence.{ConnectionProvider, HuntedAlliedRepository}
import org.postgresql.util.PSQLException

import scala.collection.mutable.ListBuffer

/** JDBC implementation of HuntedAlliedRepository. Bodies moved verbatim from
 *  BotApp's playerConfig/guildConfig/addHuntedToDatabase/addAllyToDatabase/
 *  removeHuntedFromDatabase/removeAllyFromDatabase/updateHuntedOrAllyNameToDatabase,
 *  with the Guild parameter reduced to guildId. Table names are selected by the
 *  same option logic as before (kept verbatim; not user input). */
final class JdbcHuntedAlliedRepository(connectionProvider: ConnectionProvider) extends HuntedAlliedRepository {

  def getPlayers(guildId: String, query: String): List[Players] =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT name,reason,reason_text,added_by FROM $query")

    val results = new ListBuffer[Players]()
    while (result.next()) {
      val name = Option(result.getString("name")).getOrElse("")
      val reason = Option(result.getString("reason")).getOrElse("")
      val reasonText = Option(result.getString("reason_text")).getOrElse("")
      val addedBy = Option(result.getString("added_by")).getOrElse("")
      results += Players(name, reason, reasonText, addedBy)
    }

    statement.close()
    results.toList
  }

  def getGuilds(guildId: String, query: String): List[Guilds] =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT name,reason,reason_text,added_by FROM $query")

    val results = new ListBuffer[Guilds]()
    while (result.next()) {
      val name = Option(result.getString("name")).getOrElse("")
      val reason = Option(result.getString("reason")).getOrElse("")
      val reasonText = Option(result.getString("reason_text")).getOrElse("")
      val addedBy = Option(result.getString("added_by")).getOrElse("")
      results += Guilds(name, reason, reasonText, addedBy)
    }

    statement.close()
    results.toList
  }

  def addHunted(guildId: String, option: String, name: String, reason: String, reasonText: String, addedBy: String): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    val table = (if (option == "guild") "hunted_guilds" else if (option == "player") "hunted_players").toString
    val statement = conn.prepareStatement(s"INSERT INTO $table(name, reason, reason_text, added_by) VALUES (?,?,?,?) ON CONFLICT (name) DO NOTHING;")
    statement.setString(1, name)
    statement.setString(2, reason)
    statement.setString(3, reasonText)
    statement.setString(4, addedBy)
    statement.executeUpdate()

    statement.close()
  }

  def addAllied(guildId: String, option: String, name: String, reason: String, reasonText: String, addedBy: String): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    val table = (if (option == "guild") "allied_guilds" else if (option == "player") "allied_players").toString
    val statement = conn.prepareStatement(s"INSERT INTO $table(name, reason, reason_text, added_by) VALUES (?,?,?,?) ON CONFLICT (name) DO NOTHING;")
    statement.setString(1, name)
    statement.setString(2, reason)
    statement.setString(3, reasonText)
    statement.setString(4, addedBy)
    statement.executeUpdate()

    statement.close()
  }

  def removeHunted(guildId: String, option: String, name: String): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    val table = (if (option == "guild") "hunted_guilds" else if (option == "player") "hunted_players").toString
    val statement = conn.prepareStatement(s"DELETE FROM $table WHERE LOWER(name) = LOWER(?);")
    statement.setString(1, name)
    statement.executeUpdate()

    statement.close()
  }

  def removeAllied(guildId: String, option: String, name: String): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    val table = (if (option == "guild") "allied_guilds" else if (option == "player") "allied_players").toString
    val statement = conn.prepareStatement(s"DELETE FROM $table WHERE LOWER(name) = LOWER(?);")
    statement.setString(1, name)
    statement.executeUpdate()

    statement.close()
  }

  def rename(guildId: String, option: String, oldName: String, newName: String): Unit = {
    val conn = connectionProvider.guild(guildId)
    val table = if (option == "hunted") "hunted_players" else if (option == "allied") "allied_players"

    val statement = conn.prepareStatement(s"UPDATE $table SET name = ? WHERE LOWER(name) = LOWER(?);")
    statement.setString(1, newName)
    statement.setString(2, oldName)

    try {
      statement.executeUpdate()
    } catch {
      case e: PSQLException if e.getMessage.contains("duplicate key value") =>
        // Handle duplicate key error
        val deleteStatement = conn.prepareStatement(s"DELETE FROM $table WHERE LOWER(name) = LOWER(?);")
        deleteStatement.setString(1, newName)
        deleteStatement.executeUpdate()
        deleteStatement.close()

        // Retry the update within the same transaction
        val retryStatement = conn.prepareStatement(s"UPDATE $table SET name = ? WHERE LOWER(name) = LOWER(?);")
        retryStatement.setString(1, newName)
        retryStatement.setString(2, oldName)
        retryStatement.executeUpdate()
        retryStatement.close()
    } finally {
      statement.close()
      conn.close()
    }
  }
}
