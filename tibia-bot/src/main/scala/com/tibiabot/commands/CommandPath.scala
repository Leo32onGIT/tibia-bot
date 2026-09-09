package com.tibiabot.commands

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** The dispatch key for a slash interaction: the command name, then the
 *  subcommand group and subcommand it was invoked through, space-separated.
 *
 *  `/help` is "help", `/hunted player` is "hunted player", and
 *  `/settings neutral levels` is "settings neutral levels".
 *
 *  Routing keys off this rather than off the command name alone so a handler can
 *  be registered at whatever depth actually owns the behaviour — one entry for a
 *  whole group, or one per leaf. [[CommandRouter]] falls back to the longest
 *  registered prefix, so a command that later grows a subcommand (or is folded
 *  underneath a root) keeps reaching the same handler without a routing change.
 */
object CommandPath {

  /** The path itself, free of JDA so it can be tested directly.
   *
   *  Absent segments are dropped rather than left as gaps: JDA reports null for
   *  both when a command has no subcommands at all, and null for the group when
   *  a subcommand is not nested in one. A group never arrives without a
   *  subcommand — Discord has no way to invoke one — so the order is safe. */
  def of(name: String, group: Option[String], subcommand: Option[String]): String =
    List(Some(name), group, subcommand).flatten.mkString(" ")

  def of(event: SlashCommandInteractionEvent): String =
    of(event.getName, Option(event.getSubcommandGroup), Option(event.getSubcommandName))
}
