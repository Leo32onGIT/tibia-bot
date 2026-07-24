package com.tibiabot.migration

/** Minimal `--flag value` / bare `--flag` argument parsing, shared by
 *  Export/ImportHuntedAllied — these are one-off ops tools run by hand via
 *  `sbt runMain`, not worth pulling in a CLI-parsing dependency for. */
object MigrationCli {
  def parseArgs(args: Array[String]): Map[String, String] = {
    val flags = scala.collection.mutable.Map[String, String]()
    var i = 0
    while (i < args.length) {
      val arg = args(i)
      if (arg.startsWith("--")) {
        val key = arg.stripPrefix("--")
        if (i + 1 < args.length && !args(i + 1).startsWith("--")) {
          flags(key) = args(i + 1)
          i += 2
        } else {
          flags(key) = "true"
          i += 1
        }
      } else {
        i += 1
      }
    }
    flags.toMap
  }

  def require(flags: Map[String, String], key: String, usage: String): String =
    flags.getOrElse(key, throw new IllegalArgumentException(s"Missing required --$key\n\n$usage"))
}
