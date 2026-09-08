package com.tibiabot.persistence.jdbc

import com.tibiabot.domain.{Guilds, Players}
import com.tibiabot.persistence.{ConnectionProvider, HuntedAlliedRepository}
import org.postgresql.util.PSQLException

import scala.collection.mutable.ListBuffer

/** JDBC implementation of HuntedAlliedRepository. Table names are interpolated
 *  directly into SQL, but are always drawn from a fixed option/table set chosen
 *  by the caller, never from user input. */
final class JdbcHuntedAlliedRepository(connectionProvider: ConnectionProvider) extends HuntedAlliedRepository {

  /** Bring a guild's player tables up to the current shape.
   *
   *  Each column is checked on its own, deliberately. Gating them all behind one
   *  probe looks tidier and is wrong: a table migrated by an earlier version
   *  already has that column, so the whole block is skipped and every column
   *  added since is silently never created. The failure then surfaces as a query
   *  against a column that does not exist, a long way from here.
   *
   *  Idempotent and cheap, and run before every read rather than once at startup:
   *  a guild's database is created lazily by /setup, so there is no single moment
   *  when every guild's schema is known to have been seen.
   */
  private def ensurePlayerColumns(conn: java.sql.Connection, table: String): Unit = {
    val statement = conn.createStatement()
    def ensure(column: String, definition: String): Unit = {
      val existing = statement.executeQuery(
        s"SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = '$table' AND COLUMN_NAME = '$column'")
      val present = existing.next()
      existing.close()
      if (!present) statement.execute(s"ALTER TABLE $table ADD COLUMN $column $definition")
    }
    ensure("traded_when_added", "VARCHAR(255) NOT NULL DEFAULT 'false'")
    ensure("flagged_reason", "VARCHAR(255) NOT NULL DEFAULT ''")
    ensure("flagged_at", "VARCHAR(255) NOT NULL DEFAULT ''")
    statement.close()
  }

  /** True only for the string the columns actually store; anything else — an old
   *  row defaulted to '', a value written by hand — reads as not traded, which is
   *  the safe way round. A wrong `false` costs a player being flagged for review;
   *  a wrong `true` would silence a flag that should have been raised. */
  private def storedFlag(value: String): Boolean = value == "true"

  def getPlayers(guildId: String, query: String): List[Players] =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    ensurePlayerColumns(conn, query)
    val statement = conn.createStatement()
    val result = statement.executeQuery(
      s"SELECT name,reason,reason_text,added_by,traded_when_added,flagged_reason,flagged_at FROM $query")

    val results = new ListBuffer[Players]()
    while (result.next()) {
      val name = Option(result.getString("name")).getOrElse("")
      val reason = Option(result.getString("reason")).getOrElse("")
      val reasonText = Option(result.getString("reason_text")).getOrElse("")
      val addedBy = Option(result.getString("added_by")).getOrElse("")
      val tradedWhenAdded = storedFlag(Option(result.getString("traded_when_added")).getOrElse(""))
      val flaggedReason = Option(result.getString("flagged_reason")).getOrElse("")
      val flaggedAt = Option(result.getString("flagged_at")).getOrElse("")
      results += Players(name, reason, reasonText, addedBy, tradedWhenAdded, flaggedReason, flaggedAt)
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

  /** Insert a list entry.
   *
   *  `tradedWhenAdded` is only stored for players — a guild cannot be traded, and
   *  the guild tables have no such column. It is recorded here and never
   *  recomputed: see Players for why the flag it comes from cannot be asked for
   *  after the fact.
   */
  def addHunted(guildId: String, option: String, name: String, reason: String, reasonText: String,
                addedBy: String, tradedWhenAdded: Boolean = false): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    val table = (if (option == "guild") "hunted_guilds" else if (option == "player") "hunted_players").toString
    insertEntry(conn, table, option, name, reason, reasonText, addedBy, tradedWhenAdded)
  }

  def addAllied(guildId: String, option: String, name: String, reason: String, reasonText: String,
                addedBy: String, tradedWhenAdded: Boolean = false): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
    val table = (if (option == "guild") "allied_guilds" else if (option == "player") "allied_players").toString
    insertEntry(conn, table, option, name, reason, reasonText, addedBy, tradedWhenAdded)
  }

  private def insertEntry(conn: java.sql.Connection, table: String, option: String, name: String,
                          reason: String, reasonText: String, addedBy: String,
                          tradedWhenAdded: Boolean): Unit = {
    val statement =
      if (option == "player") {
        ensurePlayerColumns(conn, table)
        val prepared = conn.prepareStatement(
          s"INSERT INTO $table(name, reason, reason_text, added_by, traded_when_added) " +
            "VALUES (?,?,?,?,?) ON CONFLICT (name) DO NOTHING;")
        prepared.setString(5, tradedWhenAdded.toString)
        prepared
      } else {
        conn.prepareStatement(
          s"INSERT INTO $table(name, reason, reason_text, added_by) VALUES (?,?,?,?) ON CONFLICT (name) DO NOTHING;")
      }
    statement.setString(1, name)
    statement.setString(2, reason)
    statement.setString(3, reasonText)
    statement.setString(4, addedBy)
    statement.executeUpdate()
    statement.close()
  }

  /** Record why an entry was flagged, and that it has been.
   *
   *  Writing it is what makes the admin-channel notice one-shot — the detection
   *  runs every sweep, the announcement must not. An entry already carrying a
   *  reason keeps it: whichever came first is enough to act on, and a second
   *  notice for the same doomed entry says nothing new.
   */
  def flagPlayer(guildId: String, table: String, name: String, reason: String): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
      ensurePlayerColumns(conn, table)
      val statement = conn.prepareStatement(
        s"UPDATE $table SET flagged_reason = ?, flagged_at = ? WHERE LOWER(name) = LOWER(?) AND flagged_reason = '';")
      statement.setString(1, reason)
      statement.setString(2, java.time.ZonedDateTime.now().toString)
      statement.setString(3, name)
      statement.executeUpdate()
      statement.close()
    }

  /** Clear a flag, leaving the entry on the list.
   *
   *  What happens when the reason stops being true before the grace period is up
   *  — most obviously a world move undone by the server setting that world up.
   *  The entry goes back to being ordinary, and can be flagged again later on its
   *  own merits.
   */
  def unflagPlayer(guildId: String, table: String, name: String): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
      ensurePlayerColumns(conn, table)
      val statement = conn.prepareStatement(
        s"UPDATE $table SET flagged_reason = '', flagged_at = '' WHERE LOWER(name) = LOWER(?);")
      statement.setString(1, name)
      statement.executeUpdate()
      statement.close()
    }

  /** Empty one list table, returning how many rows went.
   *
   *  One statement rather than a delete per name: a long list was a round trip
   *  per entry, and the count comes back for free where counting the loop's
   *  successes did not.
   */
  def clearAll(guildId: String, table: String): Int =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
      val statement = conn.prepareStatement(s"DELETE FROM $table;")
      val removed = statement.executeUpdate()
      statement.close()
      removed
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
        val deleteStatement = conn.prepareStatement(s"DELETE FROM $table WHERE LOWER(name) = LOWER(?);")
        deleteStatement.setString(1, newName)
        deleteStatement.executeUpdate()
        deleteStatement.close()

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
