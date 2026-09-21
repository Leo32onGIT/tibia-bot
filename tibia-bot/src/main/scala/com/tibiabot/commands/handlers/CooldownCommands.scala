package com.tibiabot.commands.handlers

import com.tibiabot.presentation.CooldownEmbeds
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/cooldowns`: the per-user collectible cooldown trackers.
 *
 *  Answers with the same panel `/setup` posts into the notifications channel,
 *  so somebody who cannot see that channel — or who is nowhere near it — still
 *  has a way in. Ephemeral because [[com.tibiabot.BotListener]] defers every
 *  slash command that way; the panel is about the caller's own cooldowns and
 *  concerns nobody else in the channel.
 *
 *  Everything past this point is a button: the panel names the kinds, and each
 *  one's list carries its own Add, Remove and Clear All. That is why the command
 *  takes no options — the old `/galthen satchel character:<tag>` pre-filled a
 *  tag for a reply that no longer exists, and the Add Cooldown form asks for the
 *  same tag anyway.
 */
object CooldownCommands {

  def handle(event: SlashCommandInteractionEvent): Unit =
    event.getHook.sendMessageEmbeds(CooldownEmbeds.panel())
      .addComponents(CooldownEmbeds.panelControls()).queue()
}
