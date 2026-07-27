package com.tibiabot.migration

import com.tibiabot.persistence.JdbcConnectionProvider

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.util.Try

/** Plans a full guild-database migration from one Postgres instance onto
 *  another — see the "Shared world-cycle" plan's step 5 (moving Red's guild
 *  databases onto Blue once Blue becomes primary; Red keeps its own Discord
 *  application, so this is a full-database copy, not just the hunted/allied
 *  subset the earlier consolidation plan needed).
 *
 *  This tool only plans — it does not copy any data. A full per-guild
 *  database (arbitrary tables, whatever ALTER-added columns a given guild
 *  happens to have) is exactly what `pg_dump`/`pg_restore` exist for;
 *  reimplementing that generically over JDBC would just be a worse version
 *  of tools Postgres already ships. What this tool *does* do is the part
 *  those tools can't: discover which guild IDs are safe to migrate versus
 *  which already exist at the destination (a collision — e.g. a shared
 *  support/testing guild present on both bots today) and so need a manual
 *  decision rather than a blind overwrite.
 *
 *  Usage: sbt "runMain com.tibiabot.migration.PlanGuildMigration
 *    --source-host <host> --source-password <password>
 *    --dest-host <host> --dest-password <password>
 *    [--source-port 5432] [--dest-port 5432] [--user postgres] [--out <path.txt>]"
 *
 *  The port flags exist for the real Red->Blue run, where the two Postgres
 *  instances live on separate VPS private networks that can't reach each
 *  other directly — see two local SSH tunnels (e.g. -L 15432:localhost:5432
 *  into each host) forwarded to distinct local ports, then --source-host
 *  localhost --source-port 15432 --dest-host localhost --dest-port 15433.
 *
 *  With --out, writes the safe-to-migrate guild IDs one per line, for a
 *  dump/restore script to loop over. */
object PlanGuildMigration {

  private val usage =
    """Usage: --source-host <host> --source-password <password> --dest-host <host> --dest-password <password> [--source-port 5432] [--dest-port 5432] [--user postgres] [--out <path.txt>]"""

  def main(args: Array[String]): Unit = {
    val flags = MigrationCli.parseArgs(args)
    val sourceHost = MigrationCli.require(flags, "source-host", usage)
    val sourcePassword = MigrationCli.require(flags, "source-password", usage)
    val destHost = MigrationCli.require(flags, "dest-host", usage)
    val destPassword = MigrationCli.require(flags, "dest-password", usage)
    val user = flags.getOrElse("user", "postgres")
    val sourcePort = flags.get("source-port").map(_.toInt).getOrElse(5432)
    val destPort = flags.get("dest-port").map(_.toInt).getOrElse(5432)

    val source = new JdbcConnectionProvider(sourceHost, sourcePassword, user, sourcePort)
    val dest = new JdbcConnectionProvider(destHost, destPassword, user, destPort)

    val sourceGuildIds = discoverGuildIds(source).toSet
    val destGuildIds = discoverGuildIds(dest).toSet
    println(s"Found ${sourceGuildIds.size} guild database(s) on $sourceHost, ${destGuildIds.size} on $destHost")

    val toMigrate = (sourceGuildIds -- destGuildIds).toList.sorted
    val collisions = (sourceGuildIds intersect destGuildIds).toList.sorted

    println(s"\nSafe to migrate (${toMigrate.size}):")
    toMigrate.foreach { guildId =>
      val name = readGuildName(source, guildId).getOrElse("?")
      println(f"  $guildId%-20s $name")
    }

    if (collisions.nonEmpty) {
      println(s"\nCOLLISIONS — already exist at the destination, NOT included above (${collisions.size}):")
      collisions.foreach { guildId =>
        val sourceName = readGuildName(source, guildId).getOrElse("?")
        val destName = readGuildName(dest, guildId).getOrElse("?")
        println(f"  $guildId%-20s source: $sourceName%-30s dest: $destName")
      }
      println("These need a manual decision (default: keep the destination's copy) — see the plan file.")
    }

    flags.get("out").foreach { outPath =>
      Files.write(Paths.get(outPath), toMigrate.mkString("\n").getBytes(StandardCharsets.UTF_8))
      println(s"\nWrote ${toMigrate.size} guild ID(s) to $outPath")
    }
  }

  /** Every `_<guildId>` database on this Postgres instance — same discovery
   *  pattern as ExportHuntedAllied on the migration-tools branch. */
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

  /** Best-effort, for a friendlier report only — never load-bearing. */
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
