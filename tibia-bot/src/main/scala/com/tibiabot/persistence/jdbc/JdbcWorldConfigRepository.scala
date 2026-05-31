package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.{Worlds, WorldName}
import com.tibiabot.persistence.{ConnectionProvider, WorldConfigRepository}

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success, Try}

/** JDBC implementation of WorldConfigRepository. Bodies moved verbatim from
 *  BotApp's worldConfig/worldCreateConfig/worldRetrieveConfig/worldRemoveConfig,
 *  with the Guild parameter reduced to guildId. `mergedWorlds` (Config.mergedWorlds)
 *  is injected rather than read from Config so this stays decoupled from config
 *  loading and testable. */
final class JdbcWorldConfigRepository(connectionProvider: ConnectionProvider, mergedWorlds: List[String]) extends WorldConfigRepository {

  def listWorlds(guildId: String): List[Worlds] =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    val statement = conn.createStatement()

    // Check if the column already exists in the table
    val columnExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'worlds' AND COLUMN_NAME = 'exiva_list'")
    val columnExists = columnExistsQuery.next()
    columnExistsQuery.close()

    // Add the column if it doesn't exist
    if (!columnExists) {
      statement.execute("ALTER TABLE worlds ADD COLUMN exiva_list VARCHAR(255) DEFAULT 'false'")
    }

    // Check if the column already exists in the table
    val allyPkExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'worlds' AND COLUMN_NAME = 'allypk_role'")
    val allyPkExists = allyPkExistsQuery.next()
    allyPkExistsQuery.close()

    // Add the allyPk if it doesn't exist
    if (!allyPkExists) {
      statement.execute("ALTER TABLE worlds ADD COLUMN allypk_role VARCHAR(255) DEFAULT '0'")
    }

    // Check if the column already exists in the table
    val masslogExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'worlds' AND COLUMN_NAME = 'masslog_role'")
    val masslogExists = masslogExistsQuery.next()
    masslogExistsQuery.close()

    // Add the allyPk if it doesn't exist
    if (!masslogExists) {
      statement.execute("ALTER TABLE worlds ADD COLUMN masslog_role VARCHAR(255) DEFAULT '0'")
    }

    // Check if the column already exists in the table
    val activityExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'worlds' AND COLUMN_NAME = 'activity_channel'")
    val activityExists = activityExistsQuery.next()
    activityExistsQuery.close()

    // Add the column if it doesn't exist
    if (!activityExists) {
      statement.execute("ALTER TABLE worlds ADD COLUMN activity_channel VARCHAR(255) DEFAULT '0'")
    }

    // Check if the column already exists in the table
    val onlineCombinedExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'worlds' AND COLUMN_NAME = 'online_combined'")
    val onlineCombinedExists = onlineCombinedExistsQuery.next()
    onlineCombinedExistsQuery.close()

    // Add the column if it doesn't exist
    if (!onlineCombinedExists) {
      statement.execute("ALTER TABLE worlds ADD COLUMN online_combined VARCHAR(255) DEFAULT 'false'")
    }

    val result = statement.executeQuery(s"SELECT name,allies_channel,enemies_channel,neutrals_channel,levels_channel,deaths_channel,category,fullbless_role,nemesis_role,allypk_role,masslog_role,fullbless_channel,nemesis_channel,fullbless_level,show_neutral_levels,show_neutral_deaths,show_allies_levels,show_allies_deaths,show_enemies_levels,show_enemies_deaths,detect_hunteds,levels_min,deaths_min,exiva_list,activity_channel,online_combined FROM worlds")

    val results = new ListBuffer[Worlds]()
    while (result.next()) {
      val name = Option(result.getString("name")).getOrElse("")
      val alliesChannel = Option(result.getString("allies_channel")).getOrElse(null)
      val enemiesChannel = Option(result.getString("enemies_channel")).getOrElse(null)
      val neutralsChannel = Option(result.getString("neutrals_channel")).getOrElse(null)
      val levelsChannel = Option(result.getString("levels_channel")).getOrElse(null)
      val deathsChannel = Option(result.getString("deaths_channel")).getOrElse(null)
      val category = Option(result.getString("category")).getOrElse(null)
      val fullblessRole = Option(result.getString("fullbless_role")).getOrElse(null)
      val nemesisRole = Option(result.getString("nemesis_role")).getOrElse(null)
      val allyPkRole = Option(result.getString("allypk_role")).getOrElse(null)
      val masslogRole = Option(result.getString("masslog_role")).getOrElse(null)
      val fullblessChannel = Option(result.getString("fullbless_channel")).getOrElse(null)
      val nemesisChannel = Option(result.getString("nemesis_channel")).getOrElse(null)
      val fullblessLevel = Option(result.getInt("fullbless_level")).getOrElse(250)
      val showNeutralLevels = Option(result.getString("show_neutral_levels")).getOrElse("true")
      val showNeutralDeaths = Option(result.getString("show_neutral_deaths")).getOrElse("true")
      val showAlliesLevels = Option(result.getString("show_allies_levels")).getOrElse("true")
      val showAlliesDeaths = Option(result.getString("show_allies_deaths")).getOrElse("true")
      val showEnemiesLevels = Option(result.getString("show_enemies_levels")).getOrElse("true")
      val showEnemiesDeaths = Option(result.getString("show_enemies_deaths")).getOrElse("true")
      val detectHunteds = Option(result.getString("detect_hunteds")).getOrElse("on")
      val levelsMin = Option(result.getInt("levels_min")).getOrElse(8)
      val deathsMin = Option(result.getInt("deaths_min")).getOrElse(8)
      val exivaList = Option(result.getString("exiva_list")).getOrElse("false")
      val activityChannel = Option(result.getString("activity_channel")).getOrElse(null)
      val onlineCombined = Option(result.getString("online_combined")).getOrElse(null)

      // Ignore merged worlds (they are now effectively inactive and ignored but their data still exists in the db)
      if (!mergedWorlds.exists(_.equalsIgnoreCase(name))) {
        results += Worlds(name, alliesChannel, enemiesChannel, neutralsChannel, levelsChannel, deathsChannel, category, fullblessRole, nemesisRole, allyPkRole, masslogRole, fullblessChannel, nemesisChannel, fullblessLevel, showNeutralLevels, showNeutralDeaths, showAlliesLevels, showAlliesDeaths, showEnemiesLevels, showEnemiesDeaths, detectHunteds, levelsMin, deathsMin, exivaList, activityChannel, onlineCombined)
      }
    }

    statement.close()
    results.toList
  }

  def createWorld(guildId: String, world: String, alliesChannel: String, enemiesChannel: String,
                  neutralsChannels: String, levelsChannel: String, deathsChannel: String, category: String,
                  fullblessRole: String, nemesisRole: String, allyPkRole: String, masslogRole: String,
                  fullblessChannel: String, nemesisChannel: String, activityChannel: String): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    val statement = conn.prepareStatement("INSERT INTO worlds(name, allies_channel, enemies_channel, neutrals_channel, levels_channel, deaths_channel, category, fullbless_role, nemesis_role, allypk_role, masslog_role, fullbless_channel, nemesis_channel, fullbless_level, show_neutral_levels, show_neutral_deaths, show_allies_levels, show_allies_deaths, show_enemies_levels, show_enemies_deaths, detect_hunteds, levels_min, deaths_min, exiva_list, activity_channel, online_combined) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (name) DO UPDATE SET allies_channel = ?, enemies_channel = ?, neutrals_channel = ?, levels_channel = ?, deaths_channel = ?, category = ?, fullbless_role = ?, nemesis_role = ?, allypk_role = ?, masslog_role = ?, fullbless_channel = ?, nemesis_channel = ?, fullbless_level = ?, show_neutral_levels = ?, show_neutral_deaths = ?, show_allies_levels = ?, show_allies_deaths = ?, show_enemies_levels = ?, show_enemies_deaths = ?, detect_hunteds = ?, levels_min = ?, deaths_min = ?, exiva_list = ?, activity_channel = ?, online_combined = ?;")
    val formalQuery = WorldName.formal(world)
    statement.setString(1, formalQuery)
    statement.setString(2, alliesChannel)
    statement.setString(3, enemiesChannel)
    statement.setString(4, neutralsChannels)
    statement.setString(5, levelsChannel)
    statement.setString(6, deathsChannel)
    statement.setString(7, category)
    statement.setString(8, fullblessRole)
    statement.setString(9, nemesisRole)
    statement.setString(10, allyPkRole)
    statement.setString(11, masslogRole)
    statement.setString(12, fullblessChannel)
    statement.setString(13, nemesisChannel)
    statement.setInt(14, 250)
    statement.setString(15, "true")
    statement.setString(16, "true")
    statement.setString(17, "true")
    statement.setString(18, "true")
    statement.setString(19, "true")
    statement.setString(20, "true")
    statement.setString(21, "on")
    statement.setInt(22, 8)
    statement.setInt(23, 8)
    statement.setString(24, "false")
    statement.setString(25, activityChannel)
    statement.setString(26, "true")
    statement.setString(27, alliesChannel)
    statement.setString(28, enemiesChannel)
    statement.setString(29, neutralsChannels)
    statement.setString(30, levelsChannel)
    statement.setString(31, deathsChannel)
    statement.setString(32, category)
    statement.setString(33, fullblessRole)
    statement.setString(34, nemesisRole)
    statement.setString(35, allyPkRole)
    statement.setString(36, masslogRole)
    statement.setString(37, fullblessChannel)
    statement.setString(38, nemesisChannel)
    statement.setInt(39, 250)
    statement.setString(40, "true")
    statement.setString(41, "true")
    statement.setString(42, "true")
    statement.setString(43, "true")
    statement.setString(44, "true")
    statement.setString(45, "true")
    statement.setString(46, "on")
    statement.setInt(47, 8)
    statement.setInt(48, 8)
    statement.setString(49, "false")
    statement.setString(50, activityChannel)
    statement.setString(51, "true")
    statement.executeUpdate()

    statement.close()
  }

  def retrieveWorld(guildId: String, world: String): Map[String, String] =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    val statement = conn.prepareStatement("SELECT * FROM worlds WHERE name = ?;")
    val formalWorld = WorldName.formal(world)
    statement.setString(1, formalWorld)
    val result = statement.executeQuery()

    var configMap = Map[String, String]()
    while (result.next()) {
      configMap += ("name" -> result.getString("name"))
      configMap += ("allies_channel" -> result.getString("allies_channel"))
      configMap += ("enemies_channel" -> result.getString("enemies_channel"))
      configMap += ("neutrals_channel" -> result.getString("neutrals_channel"))
      configMap += ("levels_channel" -> result.getString("levels_channel"))
      configMap += ("deaths_channel" -> result.getString("deaths_channel"))
      configMap += ("category" -> result.getString("category"))
      configMap += ("fullbless_role" -> result.getString("fullbless_role"))
      configMap += ("nemesis_role" -> result.getString("nemesis_role"))
      configMap += ("allypk_role" -> result.getString("allypk_role"))
      configMap += ("masslog_role" -> result.getString("masslog_role"))
      configMap += ("fullbless_channel" -> result.getString("fullbless_channel"))
      configMap += ("nemesis_channel" -> result.getString("nemesis_channel"))
      configMap += ("fullbless_level" -> result.getInt("fullbless_level").toString)
      configMap += ("show_neutral_levels" -> result.getString("show_neutral_levels"))
      configMap += ("show_neutral_deaths" -> result.getString("show_neutral_deaths"))
      configMap += ("show_allies_levels" -> result.getString("show_allies_levels"))
      configMap += ("show_allies_deaths" -> result.getString("show_allies_deaths"))
      configMap += ("show_enemies_levels" -> result.getString("show_enemies_levels"))
      configMap += ("show_enemies_deaths" -> result.getString("show_enemies_deaths"))
      configMap += ("detect_hunteds" -> result.getString("detect_hunteds"))
      configMap += ("levels_min" -> result.getInt("levels_min").toString)
      configMap += ("deaths_min" -> result.getInt("deaths_min").toString)
      configMap += ("exiva_list" -> result.getString("exiva_list"))
      configMap += ("activity_channel" -> result.getString("activity_channel"))

      val combinedOnlineValue: String = Try(result.getString("combined_online")) match {
        case Success(value) => value // Column exists, use the retrieved value
        case Failure(_) => "false" // Column doesn't exist, use the default value
      }
      configMap += ("combined_online" -> combinedOnlineValue)
    }
    statement.close()
    configMap
  }

  def removeWorld(guildId: String, world: String): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    val statement = conn.prepareStatement("DELETE FROM worlds WHERE name = ?")
    val formalName = WorldName.formal(world)
    statement.setString(1, formalName)
    statement.executeUpdate()

    statement.close()
  }

  def updateWorldString(guildId: String, world: String, column: String, value: String): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    val statement = conn.prepareStatement(s"UPDATE worlds SET $column = ? WHERE name = ?;")
    statement.setString(1, value)
    statement.setString(2, world)
    statement.executeUpdate()

    statement.close()
  }

  def updateWorldInt(guildId: String, world: String, column: String, value: Int): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    val statement = conn.prepareStatement(s"UPDATE worlds SET $column = ? WHERE name = ?;")
    statement.setInt(1, value)
    statement.setString(2, world)
    statement.executeUpdate()

    statement.close()
  }
}
