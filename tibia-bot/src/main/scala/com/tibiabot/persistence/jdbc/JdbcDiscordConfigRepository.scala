package com.tibiabot.persistence.jdbc

import com.tibiabot.persistence.{ConnectionProvider, DiscordConfigRepository}

import java.sql.Timestamp
import java.time.ZonedDateTime

/** JDBC implementation of DiscordConfigRepository. Bodies moved verbatim from
 *  BotApp's discordRetrieveConfig/discordCreateConfig/discordUpdateConfig, with
 *  the Guild parameter reduced to guildId. */
final class JdbcDiscordConfigRepository(connectionProvider: ConnectionProvider) extends DiscordConfigRepository {

  def getConfig(guildId: String): Map[String, String] = {
    val conn = connectionProvider.guild(guildId)
    val statement = conn.createStatement()

    val channelExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'discord_info' AND COLUMN_NAME = 'boosted_channel'")
    val channelExists = channelExistsQuery.next()
    channelExistsQuery.close()

    // Add the column if it doesn't exist
    if (!channelExists) {
      statement.execute("ALTER TABLE discord_info ADD COLUMN boosted_channel VARCHAR(255) DEFAULT '0'")
    }

    val lastWorldExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'discord_info' AND COLUMN_NAME = 'last_world'")
    val lastWorldExists = lastWorldExistsQuery.next()
    lastWorldExistsQuery.close()

    // Add the column if it doesn't exist
    if (!lastWorldExists) {
      statement.execute("ALTER TABLE discord_info ADD COLUMN last_world VARCHAR(255) DEFAULT '0'")
    }

    val messageExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'discord_info' AND COLUMN_NAME = 'boosted_messageid'")
    val messageExists = messageExistsQuery.next()
    messageExistsQuery.close()

    // Add the column if it doesn't exist
    if (!messageExists) {
      statement.execute("ALTER TABLE discord_info ADD COLUMN boosted_messageid VARCHAR(255) DEFAULT '0'")
    }

    val result = statement.executeQuery(s"SELECT * FROM discord_info")
    var configMap = Map[String, String]()
    while (result.next()) {
      configMap += ("guild_name" -> result.getString("guild_name"))
      configMap += ("guild_owner" -> result.getString("guild_owner"))
      configMap += ("admin_category" -> result.getString("admin_category"))
      configMap += ("admin_channel" -> result.getString("admin_channel"))
      configMap += ("boosted_channel" -> result.getString("boosted_channel"))
      configMap += ("boosted_messageid" -> result.getString("boosted_messageid"))
      configMap += ("last_world" -> result.getString("last_world"))
      configMap += ("flags" -> result.getString("flags"))
      configMap += ("created" -> result.getString("created"))
    }

    statement.close()
    conn.close()
    configMap
  }

  def create(guildId: String, guildName: String, guildOwner: String, adminCategory: String,
             adminChannel: String, boostedChannel: String, boostedMessageId: String, created: ZonedDateTime): Unit = {
    val conn = connectionProvider.guild(guildId)
    val statement = conn.prepareStatement("INSERT INTO discord_info(guild_name, guild_owner, admin_category, admin_channel, boosted_channel, boosted_messageid, flags, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(guild_name) DO UPDATE SET guild_owner = EXCLUDED.guild_owner, admin_category = EXCLUDED.admin_category, admin_channel = EXCLUDED.admin_channel, boosted_channel = EXCLUDED.boosted_channel, boosted_messageid = EXCLUDED.boosted_messageid, flags = EXCLUDED.flags, created = EXCLUDED.created;")
    statement.setString(1, guildName)
    statement.setString(2, guildOwner)
    statement.setString(3, adminCategory)
    statement.setString(4, adminChannel)
    statement.setString(5, boostedChannel)
    statement.setString(6, boostedMessageId)
    statement.setString(7, "none")
    statement.setTimestamp(8, Timestamp.from(created.toInstant))
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  def update(guildId: String, adminCategory: String, adminChannel: String, boostedChannel: String,
             boostedMessage: String, lastWorld: String): Unit = {
    val conn = connectionProvider.guild(guildId)
    // update category if exists
    if (adminCategory != "") {
      val statement = conn.prepareStatement("UPDATE discord_info SET admin_category = ?;")
      statement.setString(1, adminCategory)
      statement.executeUpdate()
      statement.close()
    }
    if (adminChannel != "") {
      // update channel
      val statement = conn.prepareStatement("UPDATE discord_info SET admin_channel = ?;")
      statement.setString(1, adminChannel)
      statement.executeUpdate()
      statement.close()
    }

    if (boostedChannel != "") {
      // update channel
      val statement = conn.prepareStatement("UPDATE discord_info SET boosted_channel = ?;")
      statement.setString(1, boostedChannel)
      statement.executeUpdate()
      statement.close()
    }

    if (boostedMessage != "") {
      // update channel
      val statement = conn.prepareStatement("UPDATE discord_info SET boosted_messageid = ?;")
      statement.setString(1, boostedMessage)
      statement.executeUpdate()
      statement.close()
    }

    if (lastWorld != "") {
      // update channel
      val statement = conn.prepareStatement("UPDATE discord_info SET last_world = ?;")
      statement.setString(1, lastWorld)
      statement.executeUpdate()
      statement.close()
    }

    conn.close()
  }
}
