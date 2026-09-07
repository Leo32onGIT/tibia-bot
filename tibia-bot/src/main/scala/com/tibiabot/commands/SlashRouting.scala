package com.tibiabot.commands

import com.tibiabot.commands.handlers._
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** The slash-command dispatch table: invoked path -> handler. Kept separate from
 *  BotListener so it is unit-testable (SlashRoutingSpec checks it stays in step
 *  with the registered command schemas) and so adding a command is a one-line
 *  edit here next to the other routing config.
 *
 *  Keys are [[CommandPath]] paths, matched by longest registered prefix, so an
 *  entry can own a whole command ("hunted", which reads its own subcommand), a
 *  group, or a single leaf. A command with no subcommands is just its name.
 *
 *  Building this map only initialises the (stateless) handler objects, never
 *  BotApp, so it is cheap to reference from a test. */
object SlashRouting {

  val handlers: Map[String, SlashCommandInteractionEvent => Unit] = Map(
    "setup"     -> (ChannelCommands.setup _),
    "remove"    -> (ChannelCommands.remove _),
    "repair"    -> (ChannelCommands.repair _),
    "help"      -> (HelpCommands.handle _),
    // Three panels, no subcommands: each answers with buttons, and everything
    // they can do happens on a button or in the form it opens. See panels.Panels.
    "settings"  -> (PanelCommands.settings _),
    "hunted"    -> (PanelCommands.hunted _),
    "allies"    -> (PanelCommands.allies _),
    "galthen"   -> (GalthenCommands.handle _),
    "boosted"   -> (BoostedCommands.handle _),
    "patreon"   -> (PatreonCommands.handle _),
    "stamina"   -> (RespawnCommands.handle _),
    "bookings"  -> (RespawnCommands.bookings _),
    "lootsplit" -> (LootSplitCommands.handle _),
    "admin"     -> (AdminCommands.handle _)
  )

  /** Commands that answer with a form rather than a message.
   *
   *  `replyModal` has to be an interaction's first response, so BotListener must
   *  know before it dispatches that this one cannot be deferred — and cannot wait
   *  for a worker either, since nothing has acknowledged Discord yet. Kept here
   *  rather than in the handler because that is too late to be asked.
   *
   *  A [[CommandPath]] path like every other key here, so a form command folded
   *  under a root is named at the depth it actually lives at rather than
   *  silently matching — or missing — on the root alone.
   */
  val opensModal: Set[String] = Set("lootsplit")
}
