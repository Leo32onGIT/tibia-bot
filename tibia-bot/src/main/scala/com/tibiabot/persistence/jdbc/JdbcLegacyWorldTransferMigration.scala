package com.tibiabot.persistence.jdbc

import com.tibiabot.persistence.ConnectionProvider
import com.typesafe.scalalogging.StrictLogging

/** One-shot carry-forward of the old per-guild `world_transfers` tables into the
 *  world-scoped one in `bot_cache`.
 *
 *  Announced transfers used to be filed per guild, which is why a discord adding
 *  a world it had never tracked found an empty table and announced every
 *  former-world flag Tibia still had set. Moving the record to world scope fixes
 *  that, but leaves every existing guild's history stranded: without carrying it
 *  over, the first sweep after the deploy would replay that same backlog for
 *  every world at once — the very burst the move exists to stop.
 *
 *  Only guilds configured with exactly one world are carried over, because the
 *  old table has no world column: for a single-world guild every row can only
 *  have come from that world, so the attribution is exact rather than guessed. A
 *  guild tracking several is skipped and takes one burst per world, which is the
 *  honest outcome — filing its rows under all of its worlds would suppress real
 *  arrivals on the ones the character never came to.
 *
 *  Either way the legacy table is renamed out of the way afterwards, so this runs
 *  once and only once per guild. Renamed rather than dropped: nothing reads it
 *  any more, but a skipped guild's history is the only copy there is, and a
 *  rename keeps it recoverable if a better migration is ever wanted. The rename
 *  is also what makes the outcome deterministic — a guild that later drops from
 *  two worlds to one cannot have its ambiguous rows migrated onto the survivor.
 *
 *  Delete this class (and its call in BotApp) once every deployment has booted
 *  past the cutover. */
final class JdbcLegacyWorldTransferMigration(connectionProvider: ConnectionProvider) extends StrictLogging {

  private val LegacyTable = "world_transfers"
  private val RetiredTable = "world_transfers_legacy"

  /** Carry `guildId`'s legacy records over to `worlds` if there is exactly one of
   *  them, then retire the legacy table either way. A no-op once retired, and for
   *  a guild that never had the table at all.
   *
   *  Best-effort: a guild whose database is missing or unreadable is logged and
   *  skipped rather than allowed to stop the bot booting. */
  def migrate(guildId: String, worlds: List[String]): Unit =
    try {
      val rows = readLegacy(guildId)
      if (rows.nonEmpty) worlds match {
        case world :: Nil =>
          val carried = write(world, rows)
          logger.info(s"Carried $carried world transfer record(s) for guild '$guildId' forward onto '$world'")
        case several =>
          logger.info(s"Guild '$guildId' tracks ${several.size} worlds; leaving its ${rows.size} legacy world transfer record(s) behind rather than guessing which world they belong to")
      }
      retireLegacy(guildId)
    } catch {
      case e: Throwable => logger.warn(s"Failed to migrate legacy world transfers for guild '$guildId'", e)
    }

  /** The legacy rows, or Nil when the table has already been retired or never
   *  existed. Returned as raw column values — this is the only reader left, and
   *  it has no use for a domain type. */
  private def readLegacy(guildId: String): List[(String, String, java.sql.Timestamp)] =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
      if (!hasLegacyTable(conn)) Nil
      else {
        val statement = conn.createStatement()
        val result = statement.executeQuery(s"SELECT name,former_worlds,detected FROM $LegacyTable")
        val rows = List.newBuilder[(String, String, java.sql.Timestamp)]
        while (result.next()) {
          val name = Option(result.getString("name")).getOrElse("")
          val formerWorlds = Option(result.getString("former_worlds")).getOrElse("")
          val detected = result.getTimestamp("detected")
          if (name.nonEmpty && detected != null) rows += ((name, formerWorlds, detected))
        }
        statement.close()
        rows.result()
      }
    }

  /** Insert the carried rows, keeping whichever record is the more recent.
   *
   *  Never overwrites something newer, which is what makes a re-run harmless and
   *  what keeps two single-world guilds tracking the same world from undoing each
   *  other: the union of what they knew is the right answer, and where they hold
   *  the same character the later sighting is the one that describes the arrival
   *  as it stands now. */
  private def write(world: String, rows: List[(String, String, java.sql.Timestamp)]): Int =
    JdbcSupport.withConnection(connectionProvider.cache) { conn =>
      val statement = conn.prepareStatement(
        s"""
           |INSERT INTO world_transfers(world, name, former_worlds, detected)
           |VALUES (?,?,?,?)
           |ON CONFLICT (world, name)
           |DO UPDATE SET
           |  former_worlds = excluded.former_worlds,
           |  detected = excluded.detected
           |WHERE excluded.detected > world_transfers.detected;
           |""".stripMargin
      )
      rows.foreach { case (name, formerWorlds, detected) =>
        statement.setString(1, world)
        // Lowercased to match how the live repository writes the key: the legacy
        // table stored it that way too, but a row written before that was true
        // would otherwise arrive as a second record of the same arrival.
        statement.setString(2, name.toLowerCase)
        statement.setString(3, formerWorlds)
        statement.setTimestamp(4, detected)
        statement.addBatch()
      }
      // Summed rather than counted off the batch length: a row the ON CONFLICT
      // guard declined to overwrite reports zero, and saying it was carried over
      // would overstate what the migration actually did.
      val written = statement.executeBatch().filter(_ > 0).sum
      statement.close()
      written
    }

  /** Rename the legacy table so this guild is never considered again. */
  private def retireLegacy(guildId: String): Unit =
    JdbcSupport.withConnection(() => connectionProvider.guild(guildId)) { conn =>
      if (hasLegacyTable(conn)) {
        val statement = conn.createStatement()
        statement.executeUpdate(s"ALTER TABLE $LegacyTable RENAME TO $RetiredTable")
        statement.close()
      }
    }

  private def hasLegacyTable(conn: java.sql.Connection): Boolean = {
    val statement = conn.prepareStatement("SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?")
    statement.setString(1, LegacyTable)
    val result = statement.executeQuery()
    val exists = result.next()
    result.close()
    statement.close()
    exists
  }
}
