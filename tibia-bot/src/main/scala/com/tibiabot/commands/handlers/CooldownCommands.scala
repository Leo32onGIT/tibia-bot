package com.tibiabot.commands.handlers

import com.tibiabot.BotApp
import com.tibiabot.presentation.CooldownEmbeds
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

/** Handles `/cooldowns`: the caller's own collectible cooldowns, straight away.
 *
 *  The same card the tracker in the notifications channel opens — both items,
 *  when each of yours comes back, and the buttons to act on each — so somebody
 *  who cannot see that channel, or is nowhere near it, loses nothing. Ephemeral
 *  because [[com.tibiabot.BotListener]] defers every slash command that way; the
 *  card is about the caller's own cooldowns and concerns nobody else here.
 *
 *  It takes no options: the old `/galthen satchel character:<tag>` pre-filled a
 *  tag, and the "For a character…" form asks for the same thing.
 */
object CooldownCommands {

  def handle(event: SlashCommandInteractionEvent): Unit = {
    val user = event.getUser
    val card = CooldownEmbeds.personal(
      kind => BotApp.cooldownService.getStamps(user.getId, kind).getOrElse(Nil), user.getName)
    event.getHook.sendMessageComponents(card).useComponentsV2().queue()
  }
}
