package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.CustomSort
import com.tibiabot.persistence.{ConnectionProvider, CustomSortRepository}

import java.time.ZonedDateTime
import scala.collection.mutable.ListBuffer

/** JDBC implementation of CustomSortRepository. Bodies moved verbatim from
 *  BotApp's customSortConfig and the *OnlineListCategory*ToDatabase methods,
 *  with the Guild parameter reduced to guildId. */
final class JdbcCustomSortRepository(connectionProvider: ConnectionProvider) extends CustomSortRepository {

  def getAll(guildId: String): List[CustomSort] = {
    val conn = connectionProvider.guild(guildId)
    val statement = conn.createStatement()

    // Check if the table already exists in bot_configuration
    val tableExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'online_list_categories'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    // Create the table if it doesn't exist
    if (!tableExists) {
      val createCustomSortTable =
        s"""CREATE TABLE online_list_categories (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |entity VARCHAR(255) NOT NULL,
           |name VARCHAR(255) NOT NULL,
           |label VARCHAR(255) NOT NULL,
           |emoji VARCHAR(255) NOT NULL,
           |added VARCHAR(255) NOT NULL
           |);""".stripMargin

      statement.executeUpdate(createCustomSortTable)
    }

    val result = statement.executeQuery(s"SELECT entity,name,label,emoji FROM online_list_categories")

    val results = new ListBuffer[CustomSort]()
    while (result.next()) {
      val entity = Option(result.getString("entity")).getOrElse("")
      val name = Option(result.getString("name")).getOrElse("")
      val label = Option(result.getString("label")).getOrElse("")
      val emoji = Option(result.getString("emoji")).getOrElse("")

      results += CustomSort(entity, name, label, emoji)
    }

    statement.close()
    conn.close()
    results.toList
  }

  def add(guildId: String, entity: String, name: String, label: String, emoji: String): Unit = {
    val conn = connectionProvider.guild(guildId)
    val query = "INSERT INTO online_list_categories(entity, name, label, emoji, added) VALUES (?, ?, ?, ?, ?);"
    val statement = conn.prepareStatement(query)
    statement.setString(1, entity)
    statement.setString(2, name)
    statement.setString(3, label)
    statement.setString(4, emoji)
    statement.setString(5, ZonedDateTime.now().toEpochSecond().toString)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  def removeByNameEntity(guildId: String, entity: String, name: String): Unit = {
    val conn = connectionProvider.guild(guildId)
    val statement = conn.prepareStatement(s"DELETE FROM online_list_categories WHERE name = ? AND entity = ?;")
    statement.setString(1, name)
    statement.setString(2, entity)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  def removeByLabel(guildId: String, label: String): Unit = {
    val conn = connectionProvider.guild(guildId)
    val statement = conn.prepareStatement(s"DELETE FROM online_list_categories WHERE LOWER(label) = LOWER(?);")
    statement.setString(1, label)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }
}
