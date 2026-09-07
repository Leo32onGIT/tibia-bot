package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.CustomSort
import com.tibiabot.persistence.{ConnectionProvider, CustomSortRepository}

import scala.collection.mutable.ListBuffer

/** JDBC implementation of CustomSortRepository, routed through
 *  JdbcSupport.withConnection so the connection is always released. */
final class JdbcCustomSortRepository(connectionProvider: ConnectionProvider) extends CustomSortRepository {

  def getAll(guildId: String): List[CustomSort] =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
      val statement = conn.createStatement()

      val tableExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'online_list_categories'")
      val tableExists = tableExistsQuery.next()
      tableExistsQuery.close()

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
      results.toList
    }
}
