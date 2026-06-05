package com.tibiabot.persistence

import com.tibiabot.persistence.jdbc.JdbcSupport
import com.typesafe.scalalogging.StrictLogging

/** Creates the bot's databases and tables at startup / on guild join. Bodies
 *  moved verbatim from BotApp's checkConfigDatabase/createPremiumDatabase/
 *  createCacheDatabase/createConfigDatabase, with the Guild parameter reduced to
 *  guildId/guildName. Behaviour preserved exactly (including the pre-existing
 *  quirk that initPremium creates 'bot_cache'). Connections are released via
 *  JdbcSupport.withConnection so a failed CREATE can't leak them; the admin
 *  connection is still closed before the per-database connection is opened. */
final class SchemaInitializer(connectionProvider: ConnectionProvider) extends StrictLogging {

  // A guild's Postgres database name. Guild IDs are Discord snowflakes (digits
  // only); validate that before interpolating, since a database name can't be a
  // bound parameter — this keeps the CREATE/DROP DATABASE DDL injection-proof.
  private def guildDbName(guildId: String): String = {
    require(guildId.nonEmpty && guildId.forall(_.isDigit), s"refusing unsafe guild database name: '$guildId'")
    s"_$guildId"
  }

  def guildDatabaseExists(guildId: String): Boolean =
    JdbcSupport.withConnection(connectionProvider.admin) { conn =>
      val statement = conn.createStatement()
      val result = statement.executeQuery(s"SELECT datname FROM pg_database WHERE datname = '${guildDbName(guildId)}'")
      val exist = result.next()
      statement.close()
      exist
    }

  /** Drop a guild's database when the bot leaves it (moved verbatim from BotApp's
   *  removeConfigDatabase). No-op if it doesn't exist. */
  def dropGuild(guildId: String): Unit =
    JdbcSupport.withConnection(connectionProvider.admin) { conn =>
      val statement = conn.createStatement()
      val result = statement.executeQuery(s"SELECT datname FROM pg_database WHERE datname = '${guildDbName(guildId)}'")
      val exist = result.next()
      if (exist) {
        statement.executeUpdate(s"DROP DATABASE ${guildDbName(guildId)};")
        logger.info(s"Database '$guildId' removed successfully")
      } else {
        logger.info(s"Database '$guildId' was not removed as it doesn't exist")
      }
      statement.close()
    }

  /** PLANNED FEATURE — intentionally not wired yet (do not delete as "dead code").
   *  Scaffolding for the Patreon/premium tier: creates the `payments` database/
   *  table. No caller hooks this into startup today, so the premium DB is never
   *  created at runtime; wire a call to this in (and add the premium read path)
   *  when the premium feature is built out. NOTE: carries a pre-existing quirk —
   *  it checks for a 'premium' database but creates 'bot_cache'; fix when wiring. */
  def initPremium(): Unit = {
    val needsTables = JdbcSupport.withConnection(connectionProvider.admin) { conn =>
      val statement = conn.createStatement()
      val result = statement.executeQuery(s"SELECT datname FROM pg_database WHERE datname = 'premium'")
      val exist = result.next()
      if (!exist) {
        statement.executeUpdate(s"CREATE DATABASE bot_cache;")
        logger.info(s"Database 'bot_cache' created successfully")
      }
      statement.close()
      !exist
    }

    if (needsTables) {
      JdbcSupport.withConnection(connectionProvider.premium) { newConn =>
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
      }
    }
  }

  def initCache(): Unit = {

    JdbcSupport.withConnection(connectionProvider.admin) { conn =>
      val statement = conn.createStatement()

      val result = statement.executeQuery(
        "SELECT datname FROM pg_database WHERE datname = 'bot_cache'"
      )

      try {
        val exist = result.next()

        if (!exist) {
          try {
            statement.executeUpdate("CREATE DATABASE bot_cache")
            logger.info("Database 'bot_cache' created successfully")
          } catch {
            case e: Throwable =>
              logger.info("Database 'bot_cache' already exists, skipping creation", e)
          }
        }
      } finally {
        result.close()
        statement.close()
      }
    }
    JdbcSupport.withConnection(connectionProvider.cache) { newConn =>
      val newStatement = newConn.createStatement()

      val createDeathsTable =
        s"""CREATE TABLE IF NOT EXISTS deaths (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |world VARCHAR(255) NOT NULL,
           |name VARCHAR(255) NOT NULL,
           |time VARCHAR(255) NOT NULL
           |);""".stripMargin

      val createLevelsTable =
        s"""CREATE TABLE IF NOT EXISTS levels (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |world VARCHAR(255) NOT NULL,
           |name VARCHAR(255) NOT NULL,
           |level VARCHAR(255) NOT NULL,
           |vocation VARCHAR(255) NOT NULL,
           |last_login VARCHAR(255) NOT NULL,
           |time VARCHAR(255) NOT NULL
           |);""".stripMargin

      val createListTable =
        s"""CREATE TABLE IF NOT EXISTS list (
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

      val createSatchelTable =
        s"""CREATE TABLE IF NOT EXISTS satchel (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |userid VARCHAR(255) NOT NULL,
           |time VARCHAR(255) NOT NULL,
           |tag VARCHAR(255)
           |);""".stripMargin

      newStatement.executeUpdate(createDeathsTable)
      //logger.info("Table 'deaths' created successfully")

      newStatement.executeUpdate(createLevelsTable)
      //logger.info("Table 'levels' created successfully")

      newStatement.executeUpdate(createListTable)
      //logger.info("Table 'list' created successfully")

      newStatement.executeUpdate(createSatchelTable)
      //logger.info("Table 'satchel' created successfully")

      newStatement.close()
    }
  }

  def initGuild(guildId: String, guildName: String): Unit = {
    val needsTables = JdbcSupport.withConnection(connectionProvider.admin) { conn =>
      val statement = conn.createStatement()
      val result = statement.executeQuery(s"SELECT datname FROM pg_database WHERE datname = '${guildDbName(guildId)}'")
      val exist = result.next()
      if (!exist) {
        statement.executeUpdate(s"CREATE DATABASE ${guildDbName(guildId)};")
        logger.info(s"Database '$guildId' for discord '$guildName' created successfully")
      } else {
        logger.info(s"Database '$guildId' already exists")
      }
      statement.close()
      !exist
    }

    if (needsTables) {
      JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { newConn =>
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
      }
    }
  }
}
