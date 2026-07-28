package com.tibiabot.commands.handlers

import com.tibiabot.{BotApp, Config}
import com.tibiabot.presentation.GuildActivity
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

import scala.jdk.CollectionConverters._

/** `/patreon` — a supporter's self-service view of their own seats: a
 *  `PaywallService.effectiveSeatLimit`-length list of slots (the global
 *  default plus any dashboard-granted per-user adjustment), each either a
 *  claimed (guild, world) pair or `*empty*`, plus a button per claimed seat
 *  to release it (freeing it up to reassign elsewhere, or leaving the world
 *  running ungated as a legacy setup — see PaywallService.hasSeat). Always
 *  ephemeral, via BotListener's global deferReply(true), so this is safe to
 *  run in any shared server regardless of who else is there. */
object PatreonCommands {

  def handle(event: SlashCommandInteractionEvent): Unit = {
    val userId = event.getUser.getId
    if (!BotApp.paywallService.callerIsSubscribed(userId)) {
      val embed = new EmbedBuilder()
        .setColor(GuildActivity.activityColor(huntedGuild = true, alliedGuild = false))
        .setThumbnail(Config.webHookAvatar)
        .setDescription(
          s"${Config.noEmoji} You are not an active Patreon supporter.\n" +
          "Join as a paid member to `/setup` and use the bot\n\n" +
          "[Website](https://violentbot.xyz) | [Discord](https://discord.gg/SWMq9Pz8ud) | [Patreon](https://patreon.com/violentbot)"
        )
        .build()
      event.getHook.sendMessageEmbeds(embed).queue()
    } else {
      val seats = BotApp.paywallService.seatsForUser(userId)
      val slotLines = (0 until BotApp.paywallService.effectiveSeatLimit(userId)).map { i =>
        seats.lift(i) match {
          case Some(seat) =>
            val guild = BotApp.discordGateway.guildById(seat.guildId)
            val guildName = Option(guild).map(_.getName).getOrElse("Unknown server")
            s"${Config.torchOnEmoji} **`$guildName` → ${seat.world}**"
          case None => s"${Config.torchOffEmoji} *empty*"
        }
      }
      val embed = new EmbedBuilder()
        .setColor(GuildActivity.activityColor(huntedGuild = false, alliedGuild = true))
        .setThumbnail(Config.webHookAvatar)
        .addField(
          "Thanks for supporting Violent Bot!",
          "Here you can *view* and *deactivate* `/setups` that are tied to your subscription (seats).",
          true
        )
        .addField("Status", Config.yesEmoji, true)
        .addField("Seats", slotLines.mkString("\n"), false)
        .build()
      // guildId is a pure-digit snowflake, world never contains an
      // underscore — so the button click handler can split payload on the
      // first '_' unambiguously (see ButtonHandler's "patreon_release_" case).
      val buttons = seats.map(seat => Button.danger(s"patreon_release_${seat.guildId}_${seat.world}", s"Deactivate ${seat.world}"))
      if (buttons.nonEmpty) {
        event.getHook.sendMessageEmbeds(embed).setComponents(ActionRow.of(buttons.asJava)).queue()
      } else {
        event.getHook.sendMessageEmbeds(embed).queue()
      }
    }
  }
}
