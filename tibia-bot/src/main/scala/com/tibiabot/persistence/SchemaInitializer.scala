package com.tibiabot.persistence

import com.typesafe.scalalogging.StrictLogging

/** Creates the bot's databases and tables at startup / on guild join. Bodies
 *  moved verbatim from BotApp's checkConfigDatabase/createPremiumDatabase/
 *  createCacheDatabase/createConfigDatabase, with the Guild parameter reduced to
 *  guildId/guildName. Behaviour preserved exactly (including the pre-existing
 *  quirk that initPremium creates 'bot_cache'). */
final class SchemaInitializer(connectionProvider: ConnectionProvider) extends StrictLogging {

  def guildDatabaseExists(guildId: String): Boolean = {
    val conn = connectionProvider.admin()
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT datname FROM pg_database WHERE datname = '_$guildId'")
    val exist = result.next()

    statement.close()
    conn.close()

    // check if database for discord exists
    if (exist) {
      true
    } else {
      false
    }
  }

  def initPremium(): Unit = {
    val conn = connectionProvider.admin()
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT datname FROM pg_database WHERE datname = 'premium'")
    val exist = result.next()

    // if bot_configuration doesn't exist
    if (!exist) {
      statement.executeUpdate(s"CREATE DATABASE bot_cache;")
      logger.info(s"Database 'bot_cache' created successfully")
      statement.close()
      conn.close()

      val newConn = connectionProvider.premium()
      val newStatement = newConn.createStatement()
      // create the tables in bot_configuration
      val createPaymentsTable =
        s"""CREATE TABLE payments (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |discord_id VARCHAR(255) NOT NULL,
           |discord_name VARCHAR(255) NOT NULL,
           |user_id VARCHAR(255) NOT NULL,
           |user_name VARCHAR(255) NOT NULL,
           |expiry VARCHAR(255) NOT NULL
           |);""".stripMargin

      newStatement.executeUpdate(createPaymentsTable)
      logger.info("Table 'payments' created successfully")
      newStatement.close()
      newConn.close()
    } else {
      statement.close()
      conn.close()
    }
  }

  def initCache(): Unit = {
    val conn = connectionProvider.admin()
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT datname FROM pg_database WHERE datname = 'bot_cache'")
    val exist = result.next()

    // if bot_configuration doesn't exist
    if (!exist) {
      statement.executeUpdate(s"CREATE DATABASE bot_cache;")
      logger.info(s"Database 'bot_cache' created successfully")
      statement.close()
      conn.close()

      val newConn = connectionProvider.cache()
      val newStatement = newConn.createStatement()
      // create the tables in bot_configuration
      val createDeathsTable =
        s"""CREATE TABLE deaths (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |world VARCHAR(255) NOT NULL,
           |name VARCHAR(255) NOT NULL,
           |time VARCHAR(255) NOT NULL
           |);""".stripMargin

      val createLevelsTable =
        s"""CREATE TABLE levels (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |world VARCHAR(255) NOT NULL,
           |name VARCHAR(255) NOT NULL,
           |level VARCHAR(255) NOT NULL,
           |vocation VARCHAR(255) NOT NULL,
           |last_login VARCHAR(255) NOT NULL,
           |time VARCHAR(255) NOT NULL
           |);""".stripMargin

     val createListTable =
       s"""CREATE TABLE list (
          |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
          |world VARCHAR(255) NOT NULL,
          |former_worlds VARCHAR(255),
          |name VARCHAR(255) NOT NULL,
          |former_names VARCHAR(1000),
          |level VARCHAR(255) NOT NULL,
          |guild_name VARCHAR(255),
          |vocation VARCHAR(255) NOT NULL,
          |last_login VARCHAR(255) NOT NULL,
          |time VARCHAR(255) NOT NULL
          |);""".stripMargin

    val createGalthenTable =
      s"""CREATE TABLE satchel (
         |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
         |userid VARCHAR(255) NOT NULL,
         |time VARCHAR(255) NOT NULL,
         |tag VARCHAR(255)
         |);""".stripMargin

      newStatement.executeUpdate(createDeathsTable)
      logger.info("Table 'deaths' created successfully")
      newStatement.executeUpdate(createLevelsTable)
      logger.info("Table 'levels' created successfully")
      newStatement.executeUpdate(createListTable)
      logger.info("Table 'list' created successfully")
      newStatement.executeUpdate(createGalthenTable)
      logger.info("Table 'galthen' created successfully")
      newStatement.close()
      newConn.close()
    } else {
      statement.close()
      conn.close()
    }
  }

  def initGuild(guildId: String, guildName: String): Unit = {
    val conn = connectionProvider.admin()
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT datname FROM pg_database WHERE datname = '_$guildId'")
    val exist = result.next()

    // if bot_configuration doesn't exist
    if (!exist) {
      statement.executeUpdate(s"CREATE DATABASE _$guildId;")
      logger.info(s"Database '$guildId' for discord '$guildName' created successfully")
      statement.close()
      conn.close()

      val newConn = connectionProvider.guild(guildId)
      val newStatement = newConn.createStatement()
      // create the tables in bot_configuration
      val createDiscordInfoTable =
        s"""CREATE TABLE discord_info (
           |guild_name VARCHAR(255) NOT NULL,
           |guild_owner VARCHAR(255) NOT NULL,
           |admin_category VARCHAR(255) NOT NULL,
           |admin_channel VARCHAR(255) NOT NULL,
           |boosted_channel VARCHAR(255) NOT NULL,
           |boosted_messageid VARCHAR(255) NOT NULL,
           |flags VARCHAR(255) NOT NULL,
           |created TIMESTAMP NOT NULL,
           |PRIMARY KEY (guild_name)
           |);""".stripMargin

      val createHuntedPlayersTable =
        s"""CREATE TABLE hunted_players (
           |name VARCHAR(255) NOT NULL,
           |reason VARCHAR(255) NOT NULL,
           |reason_text VARCHAR(255) NOT NULL,
           |added_by VARCHAR(255) NOT NULL,
           |PRIMARY KEY (name)
           |);""".stripMargin

      val createHuntedGuildsTable =
        s"""CREATE TABLE hunted_guilds (
           |name VARCHAR(255) NOT NULL,
           |reason VARCHAR(255) NOT NULL,
           |reason_text VARCHAR(255) NOT NULL,
           |added_by VARCHAR(255) NOT NULL,
           |PRIMARY KEY (name)
           |);""".stripMargin

      val createAlliedPlayersTable =
        s"""CREATE TABLE allied_players (
           |name VARCHAR(255) NOT NULL,
           |reason VARCHAR(255) NOT NULL,
           |reason_text VARCHAR(255) NOT NULL,
           |added_by VARCHAR(255) NOT NULL,
           |PRIMARY KEY (name)
           |);""".stripMargin

      val createAlliedGuildsTable =
        s"""CREATE TABLE allied_guilds (
           |name VARCHAR(255) NOT NULL,
           |reason VARCHAR(255) NOT NULL,
           |reason_text VARCHAR(255) NOT NULL,
           |added_by VARCHAR(255) NOT NULL,
           |PRIMARY KEY (name)
           |);""".stripMargin

      val createWorldsTable =
         s"""CREATE TABLE worlds (
            |name VARCHAR(255) NOT NULL,
            |allies_channel VARCHAR(255) NOT NULL,
            |enemies_channel VARCHAR(255) NOT NULL,
            |neutrals_channel VARCHAR(255) NOT NULL,
            |levels_channel VARCHAR(255) NOT NULL,
            |deaths_channel VARCHAR(255) NOT NULL,
            |category VARCHAR(255) NOT NULL,
            |fullbless_role VARCHAR(255) NOT NULL,
            |nemesis_role VARCHAR(255) NOT NULL,
            |allypk_role VARCHAR(255) NOT NULL,
            |masslog_role VARCHAR(255) NOT NULL,
            |fullbless_channel VARCHAR(255) NOT NULL,
            |nemesis_channel VARCHAR(255) NOT NULL,
            |fullbless_level INT NOT NULL,
            |show_neutral_levels VARCHAR(255) NOT NULL,
            |show_neutral_deaths VARCHAR(255) NOT NULL,
            |show_allies_levels VARCHAR(255) NOT NULL,
            |show_allies_deaths VARCHAR(255) NOT NULL,
            |show_enemies_levels VARCHAR(255) NOT NULL,
            |show_enemies_deaths VARCHAR(255) NOT NULL,
            |detect_hunteds VARCHAR(255) NOT NULL,
            |levels_min INT NOT NULL,
            |deaths_min INT NOT NULL,
            |exiva_list VARCHAR(255) NOT NULL,
            |online_combined VARCHAR(255) NOT NULL,
            |PRIMARY KEY (name)
            |);""".stripMargin

      newStatement.executeUpdate(createDiscordInfoTable)
      logger.info("Table 'discord_info' created successfully")
      newStatement.executeUpdate(createHuntedPlayersTable)
      logger.info("Table 'hunted_players' created successfully")
      newStatement.executeUpdate(createHuntedGuildsTable)
      logger.info("Table 'hunted_guilds' created successfully")
      newStatement.executeUpdate(createAlliedPlayersTable)
      logger.info("Table 'allied_players' created successfully")
      newStatement.executeUpdate(createAlliedGuildsTable)
      logger.info("Table 'allied_guilds' created successfully")
      newStatement.executeUpdate(createWorldsTable)
      logger.info("Table 'worlds' created successfully")
      newStatement.close()
      newConn.close()
    } else {
      logger.info(s"Database '$guildId' already exists")
      statement.close()
      conn.close()
    }
  }
}
