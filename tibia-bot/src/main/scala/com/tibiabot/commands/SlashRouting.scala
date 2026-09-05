package com.tibiabot.commands

import com.tibiabot.commands.handlers._
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** The slash-command dispatch table: command name -> handler. Kept separate from
 *  BotListener so it is unit-testable (SlashRoutingSpec checks it stays in step
 *  with the registered command schemas) and so adding a command is a one-line
 *  edit here next to the other routing config.
 *
 *  Building this map only initialises the (stateless) handler objects, never
 *  BotApp, so it is cheap to reference from a test. */
object SlashRouting {

  val handlers: Map[String, SlashCommandInteractionEvent => Unit] = Map(
    "setup"        -> (ChannelCommands.setup _),
    "remove"       -> (ChannelCommands.remove _),
    "hunted"       -> (HuntedCommands.handle _),
    "allies"       -> (AlliesCommands.handle _),
    "neutral"      -> (NeutralCommands.handle _),
    "fullbless"    -> (FullblessCommands.handle _),
    "filter"       -> (FilterCommands.handle _),
    "admin"        -> (AdminCommands.handle _),
    "exiva"        -> (ExivaCommands.handle _),
    "help"         -> (HelpCommands.handle _),
    "repair"       -> (ChannelCommands.repair _),
    "galthen"      -> (GalthenCommands.handle _),
    "online"       -> (OnlineListCommands.handle _),
    "boosted"      -> (BoostedCommands.handle _),
    "patreon"      -> (PatreonCommands.handle _),
    "stamina"      -> (RespawnCommands.handle _),
    "bookings"     -> (RespawnCommands.bookings _),
    "lootsplit"    -> (LootSplitCommands.handle _)
  )

  /** Commands that answer with a form rather than a message.
   *
   *  `replyModal` has to be an interaction's first response, so BotListener must
   *  know before it dispatches that this one cannot be deferred — and cannot wait
   *  for a worker either, since nothing has acknowledged Discord yet. Kept here
   *  rather than in the handler because that is too late to be asked.
   */
  val opensModal: Set[String] = Set("lootsplit")
}
