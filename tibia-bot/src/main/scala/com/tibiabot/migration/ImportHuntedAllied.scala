package com.tibiabot.migration

import com.tibiabot.persistence.{JdbcConnectionProvider, SchemaInitializer}
import com.tibiabot.persistence.jdbc.JdbcHuntedAlliedRepository
import spray.json._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** Takes one or more ExportHuntedAllied JSON files and merges them into a
 *  destination Postgres instance — see the "Migrate Blue/Red hunted+allied
 *  data" plan. For a guild with no database yet at the destination, builds a
 *  full standard shell via SchemaInitializer.initGuild first (so discord_info
 *  and worlds exist, empty, exactly as a genuinely new guild would have them)
 *  before inserting the watchlist rows. For a guild that already has a
 *  database, skips shell creation and merges rows straight in —
 *  JdbcHuntedAlliedRepository's inserts are `ON CONFLICT (name) DO NOTHING`,
 *  so existing rows there are never touched or duplicated.
 *
 *  Usage: sbt "runMain com.tibiabot.migration.ImportHuntedAllied
 *    --host <postgres-host> --password <postgres-password>
 *    --in <file1.json>[,<file2.json>,...] [--user postgres] [--dry-run]"
 */
object ImportHuntedAllied extends HuntedAlliedExportFormat {

  private val usage =
    """Usage: --host <postgres-host> --password <postgres-password> --in <file1.json>[,<file2.json>,...] [--user postgres] [--dry-run]"""

  def main(args: Array[String]): Unit = {
    val flags = MigrationCli.parseArgs(args)
    val host = MigrationCli.require(flags, "host", usage)
    val password = MigrationCli.require(flags, "password", usage)
    val inPaths = MigrationCli.require(flags, "in", usage).split(",").map(_.trim).toList
    val user = flags.getOrElse("user", "postgres")
    val dryRun = flags.contains("dry-run")

    val exports = inPaths.flatMap(readExportFile)
    val byGuild = mergeByGuild(exports)
    println(s"Loaded ${exports.size} guild record(s) from ${inPaths.size} file(s), ${byGuild.size} distinct guild(s) after merging duplicates across files")
    if (dryRun) println("--- DRY RUN: no changes will be written ---")

    val connectionProvider = new JdbcConnectionProvider(host, password, user)
    val schemaInitializer = new SchemaInitializer(connectionProvider)
    val repository = new JdbcHuntedAlliedRepository(connectionProvider)

    byGuild.values.toList.sortBy(_.guildId).foreach { g =>
      val existed = schemaInitializer.guildDatabaseExists(g.guildId)
      val action = if (existed) "merge into existing" else "create shell + merge"
      println(f"  ${g.guildId}%-20s ${g.guildName}%-30s [$action%-19s]" +
        f" hunted: ${g.huntedPlayers.size}%3d players, ${g.huntedGuilds.size}%3d guilds" +
        f" | allied: ${g.alliedPlayers.size}%3d players, ${g.alliedGuilds.size}%3d guilds")

      if (!dryRun) {
        if (!existed) schemaInitializer.initGuild(g.guildId, g.guildName)
        g.huntedPlayers.foreach(p => repository.addHunted(g.guildId, "player", p.name, p.reason, p.reasonText, p.addedBy))
        g.huntedGuilds.foreach(h => repository.addHunted(g.guildId, "guild", h.name, h.reason, h.reasonText, h.addedBy))
        g.alliedPlayers.foreach(p => repository.addAllied(g.guildId, "player", p.name, p.reason, p.reasonText, p.addedBy))
        g.alliedGuilds.foreach(h => repository.addAllied(g.guildId, "guild", h.name, h.reason, h.reasonText, h.addedBy))
      }
    }

    println(if (dryRun) "\nDry run complete — re-run without --dry-run to write these changes." else "\nImport complete.")
  }

  private def readExportFile(path: String): List[GuildExport] = {
    val json = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
    json.parseJson.convertTo[List[GuildExport]]
  }

  /** Guilds appearing in more than one input file (shouldn't normally happen
   *  — a Discord guild lives on one source bot at a time — but concatenating
   *  their watchlist rows rather than silently dropping one file's data is
   *  the safer default if it ever does). */
  private def mergeByGuild(exports: List[GuildExport]): Map[String, GuildExport] =
    exports.groupBy(_.guildId).view.mapValues { dupes =>
      dupes.reduce { (a, b) =>
        a.copy(
          huntedPlayers = a.huntedPlayers ++ b.huntedPlayers,
          huntedGuilds = a.huntedGuilds ++ b.huntedGuilds,
          alliedPlayers = a.alliedPlayers ++ b.alliedPlayers,
          alliedGuilds = a.alliedGuilds ++ b.alliedGuilds
        )
      }
    }.toMap
}
