package com.tibiabot.commands.handlers

import com.tibiabot.interactions.LootSplit
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** `/lootsplit` — the same form the claim-ended DM offers, reachable without one.
 *
 *  The button is on a DM that only arrives when a respawn claim runs out, which
 *  means it is there for the hunts this bot already knows about and nowhere else.
 *  A party splitting a hunt they never claimed a spawn for, or splitting a second
 *  time after the button has been spent, has no way back to the form otherwise.
 *
 *  No options: the analyser is a paragraph of pasted text, and a slash command's
 *  arguments are a single line each. So this opens the form and does nothing else
 *  — which is also why it must not be deferred, and why `BotListener` treats it
 *  apart from every other command (see `SlashRouting.opensModal`).
 */
object LootSplitCommands {

  def handle(event: SlashCommandInteractionEvent): Unit = event.replyModal(LootSplit.modal).queue()
}
