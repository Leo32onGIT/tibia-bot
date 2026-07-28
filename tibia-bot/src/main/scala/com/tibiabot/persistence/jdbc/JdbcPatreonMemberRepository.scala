package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.PatreonMember
import com.tibiabot.persistence.{ConnectionProvider, PatreonMemberRepository}

import java.sql.{Connection, ResultSet, Timestamp}
import java.time.ZonedDateTime
import scala.collection.mutable.ListBuffer

/** JDBC implementation of PatreonMemberRepository, routed through
 *  JdbcSupport.withConnection so the connection is always released. Lives in
 *  the shared `bot_cache` database (`connectionProvider.cache`). */
final class JdbcPatreonMemberRepository(connectionProvider: ConnectionProvider) extends PatreonMemberRepository {

  private def ensureTable(conn: Connection): Unit = {
    val statement = conn.createStatement()
    val tableExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'patreon_members'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    if (!tableExists) {
      statement.executeUpdate(
        """CREATE TABLE patreon_members (
          |patreon_member_id VARCHAR(255) PRIMARY KEY,
          |full_name VARCHAR(255) NOT NULL,
          |patron_status VARCHAR(50),
          |pledge_cents INT NOT NULL,
          |discord_user_id VARCHAR(255),
          |discord_username VARCHAR(255),
          |synced_at TIMESTAMP NOT NULL
          |);""".stripMargin)
    }

    val discordUsernameExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'patreon_members' AND COLUMN_NAME = 'discord_username'")
    val discordUsernameExists = discordUsernameExistsQuery.next()
    discordUsernameExistsQuery.close()

    if (!discordUsernameExists) {
      statement.execute("ALTER TABLE patreon_members ADD COLUMN discord_username VARCHAR(255)")
    }

    statement.close()
  }

  private def toMember(rs: ResultSet): PatreonMember = {
    val patronStatus = Option(rs.getString("patron_status"))
    val discordUserId = Option(rs.getString("discord_user_id"))
    val discordUsername = Option(rs.getString("discord_username"))
    PatreonMember(
      rs.getString("patreon_member_id"),
      rs.getString("full_name"),
      patronStatus,
      rs.getInt("pledge_cents"),
      discordUserId,
      discordUsername
    )
  }

  def replaceSnapshot(members: List[PatreonMember], syncedAt: ZonedDateTime): Unit =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val upsert = conn.prepareStatement(
        "INSERT INTO patreon_members (patreon_member_id, full_name, patron_status, pledge_cents, discord_user_id, discord_username, synced_at) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?) " +
        "ON CONFLICT (patreon_member_id) DO UPDATE SET " +
        "full_name = EXCLUDED.full_name, patron_status = EXCLUDED.patron_status, " +
        "pledge_cents = EXCLUDED.pledge_cents, discord_user_id = EXCLUDED.discord_user_id, " +
        "discord_username = EXCLUDED.discord_username, synced_at = EXCLUDED.synced_at;"
      )
      members.foreach { member =>
        upsert.setString(1, member.patreonMemberId)
        upsert.setString(2, member.fullName)
        upsert.setString(3, member.patronStatus.orNull)
        upsert.setInt(4, member.pledgeCents)
        upsert.setString(5, member.discordUserId.orNull)
        upsert.setString(6, member.discordUsername.orNull)
        upsert.setTimestamp(7, Timestamp.from(syncedAt.toInstant))
        upsert.executeUpdate()
      }
      upsert.close()

      val prune = conn.prepareStatement("DELETE FROM patreon_members WHERE synced_at < ?")
      prune.setTimestamp(1, Timestamp.from(syncedAt.toInstant))
      prune.executeUpdate()
      prune.close()
    }

  def snapshot(): List[PatreonMember] =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      ensureTable(conn)
      val statement = conn.createStatement()
      val result = statement.executeQuery("SELECT patreon_member_id, full_name, patron_status, pledge_cents, discord_user_id, discord_username FROM patreon_members")
      val members = new ListBuffer[PatreonMember]()
      while (result.next()) members += toMember(result)
      statement.close()
      members.toList
    }
}
