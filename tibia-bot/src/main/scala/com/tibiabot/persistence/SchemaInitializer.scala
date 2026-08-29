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
  def dropGuild(guildId: String): Unit = {
    // Before the DROP, not after: Postgres refuses to drop a database anything
    // is still connected to, and a pool holding an idle connection to the guild
    // the bot has just left is exactly that. A provider that pools nothing has
    // nothing to do here.
    connectionProvider.evictGuild(guildId)
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
              logger.warn("Database 'bot_cache' already exists, skipping creation", e)
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
           |tag VARCHAR(255),
           |bot_id VARCHAR(255) NOT NULL DEFAULT ''
           |);""".stripMargin

      // Which incoming world transfers have already been announced. World-scoped
      // like deaths and levels, and for the same reason: the answer to "have we
      // seen this arrival before?" is a fact about the world, not about any one
      // discord. Keyed per-guild it used to mean a discord adding a world it had
      // never tracked found an empty table and announced every former-world flag
      // Tibia still had set — up to six months of them.
      val createWorldTransfersTable =
        s"""CREATE TABLE IF NOT EXISTS world_transfers (
           |world VARCHAR(255) NOT NULL,
           |name VARCHAR(255) NOT NULL,
           |former_worlds VARCHAR(255) NOT NULL,
           |detected TIMESTAMP NOT NULL,
           |PRIMARY KEY (world, name)
           |);""".stripMargin

      newStatement.executeUpdate(createDeathsTable)
      //logger.info("Table 'deaths' created successfully")

      newStatement.executeUpdate(createLevelsTable)
      //logger.info("Table 'levels' created successfully")

      newStatement.executeUpdate(createListTable)
      //logger.info("Table 'list' created successfully")

      newStatement.executeUpdate(createSatchelTable)
      //logger.info("Table 'satchel' created successfully")

      newStatement.executeUpdate(createWorldTransfersTable)
      //logger.info("Table 'world_transfers' created successfully")

      // The two DM subscriptions behind the notification-channel autoroles.
      // Guild-scoped rows in the shared cache database (see NotifyRepository for
      // why they aren't in the per-guild ones), so every lookup keys on guildid
      // and world as well as the user.
      val createMasslogNotificationsTable =
        s"""CREATE TABLE IF NOT EXISTS masslog_notifications (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |guildid VARCHAR(255) NOT NULL,
           |world VARCHAR(255) NOT NULL,
           |userid VARCHAR(255) NOT NULL,
           |threshold INTEGER NOT NULL,
           |enabled BOOLEAN NOT NULL DEFAULT TRUE,
           |muted_until TIMESTAMP,
           |last_notified TIMESTAMP,
           |CONSTRAINT unique_masslog_subscription UNIQUE (guildid, world, userid)
           |);""".stripMargin

      val createBountyNotificationsTable =
        s"""CREATE TABLE IF NOT EXISTS bounty_notifications (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |guildid VARCHAR(255) NOT NULL,
           |world VARCHAR(255) NOT NULL,
           |userid VARCHAR(255) NOT NULL,
           |character_name VARCHAR(255) NOT NULL,
           |cooldown_minutes INTEGER NOT NULL,
           |enabled BOOLEAN NOT NULL DEFAULT TRUE,
           |muted_until TIMESTAMP,
           |last_notified TIMESTAMP
           |);""".stripMargin

      // Case-insensitive on the name: Tibia treats "Bubble" and "bubble" as the
      // same character, and someone re-adding a bounty with different casing
      // means to adjust the one they have, not to hold two.
      val createBountyUniqueIndex =
        s"""CREATE UNIQUE INDEX IF NOT EXISTS unique_bounty_subscription
           |ON bounty_notifications (guildid, world, userid, LOWER(character_name));""".stripMargin

      newStatement.executeUpdate(createMasslogNotificationsTable)
      newStatement.executeUpdate(createBountyNotificationsTable)
      newStatement.executeUpdate(createBountyUniqueIndex)

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
        logger.debug(s"Database '$guildId' already exists")
      }
      statement.close()
      !exist
    }

    if (needsTables) {
      JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { newConn =>
        val newStatement = newConn.createStatement()
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
              |bounty_role VARCHAR(255) NOT NULL DEFAULT '0',
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
              |show_neutral_activity VARCHAR(255) NOT NULL DEFAULT 'true',
              |PRIMARY KEY (name)
              |);""".stripMargin

        // Not logged one line per table: the database line above already says a
        // guild was set up, and these only ever run together with it.
        newStatement.executeUpdate(createDiscordInfoTable)
        newStatement.executeUpdate(createHuntedPlayersTable)
        newStatement.executeUpdate(createHuntedGuildsTable)
        newStatement.executeUpdate(createAlliedPlayersTable)
        newStatement.executeUpdate(createAlliedGuildsTable)
        newStatement.executeUpdate(createWorldsTable)
        newStatement.close()
      }
    }
  }
}
