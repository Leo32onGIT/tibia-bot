package com.tibiabot.migration

import com.tibiabot.persistence.JdbcConnectionProvider
import com.tibiabot.persistence.jdbc.JdbcHuntedAlliedRepository
import spray.json._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.util.Try

/** Reads every guild's hunted/allied watchlists off a source Postgres
 *  instance (Blue or Red) and writes them to a single portable JSON file for
 *  ImportHuntedAllied to consume later — see the "Migrate Blue/Red
 *  hunted+allied data" plan. Read-only against the source: safe to run
 *  against a still-live instance before any backup/wipe.
 *
 *  Usage: sbt "runMain com.tibiabot.migration.ExportHuntedAllied
 *    --host <postgres-host> --password <postgres-password> --out <path.json>
 *    [--user postgres]"
 */
object ExportHuntedAllied extends HuntedAlliedExportFormat {

  private val usage =
    """Usage: --host <postgres-host> --password <postgres-password> --out <path.json> [--user postgres]"""

  def main(args: Array[String]): Unit = {
    val flags = MigrationCli.parseArgs(args)
    val host = MigrationCli.require(flags, "host", usage)
    val password = MigrationCli.require(flags, "password", usage)
    val outPath = MigrationCli.require(flags, "out", usage)
    val user = flags.getOrElse("user", "postgres")

    val connectionProvider = new JdbcConnectionProvider(host, password, user)
    val repository = new JdbcHuntedAlliedRepository(connectionProvider)

    val guildIds = discoverGuildIds(connectionProvider)
    println(s"Found ${guildIds.size} guild database(s) on $host")

    val exports = guildIds.map { guildId =>
      val guildName = readGuildName(connectionProvider, guildId).getOrElse(guildId)
      val huntedPlayers = repository.getPlayers(guildId, "hunted_players")
      val huntedGuilds = repository.getGuilds(guildId, "hunted_guilds")
      val alliedPlayers = repository.getPlayers(guildId, "allied_players")
      val alliedGuilds = repository.getGuilds(guildId, "allied_guilds")
      println(
        f"  $guildId%-20s $guildName%-30s hunted: ${huntedPlayers.size}%3d players, ${huntedGuilds.size}%3d guilds" +
        f" | allied: ${alliedPlayers.size}%3d players, ${alliedGuilds.size}%3d guilds"
      )
      GuildExport(guildId, guildName, huntedPlayers, huntedGuilds, alliedPlayers, alliedGuilds)
    }

    Files.write(Paths.get(outPath), exports.toJson.prettyPrint.getBytes(StandardCharsets.UTF_8))
    println(s"\nWrote ${exports.size} guild(s) to $outPath")
  }

  /** Every `_<guildId>` database on this Postgres instance — the same naming
   *  convention SchemaInitializer.guildDbName uses, just discovered rather
   *  than looked up for one known ID. */
  private def discoverGuildIds(connectionProvider: JdbcConnectionProvider): List[String] = {
    val conn = connectionProvider.admin()
    try {
      val statement = conn.createStatement()
      val result = statement.executeQuery("SELECT datname FROM pg_database WHERE datname ~ '^_[0-9]+$' ORDER BY datname")
      val ids = Iterator.continually(result.next()).takeWhile(identity).map(_ => result.getString("datname").stripPrefix("_")).toList
      statement.close()
      ids
    } finally conn.close()
  }

  /** Best-effort: discord_info might be empty (never actually reached a real
   *  /setup) or, in principle, missing — either way this is only used for a
   *  friendlier log line, never written anywhere on import, so failure here
   *  just falls back to the guildId. */
  private def readGuildName(connectionProvider: JdbcConnectionProvider, guildId: String): Option[String] =
    Try {
      val conn = connectionProvider.guild(guildId)
      try {
        val statement = conn.createStatement()
        val result = statement.executeQuery("SELECT guild_name FROM discord_info LIMIT 1")
        val name = if (result.next()) Option(result.getString("guild_name")) else None
        statement.close()
        name
      } finally conn.close()
    }.toOption.flatten
}
