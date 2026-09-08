package com.tibiabot.commands.handlers

import com.tibiabot.commands.Permissions
import com.tibiabot.panels.Panels
import com.tibiabot.presentation.Embeds
import com.tibiabot.{BotApp, Config}
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

import scala.jdk.CollectionConverters._

/** `/admin`: the bot creator's maintenance panel.
 *
 *  Six subcommands once — leave, message, info, dreamscar, worldlist and boosted
 *  — and now one bare command that answers with six buttons. The same trade the
 *  three list and settings panels made, for a different reason: `/admin` is
 *  registered in the support guilds alone, so its rows were never crowding
 *  anybody's picker. What it buys here is that a button can be labelled and
 *  grouped, and that the two acting on one particular server ask for its id in a
 *  form that says where to find one — where a subcommand offered `guildid` and a
 *  description copied from the wrong option.
 *
 *  Everything past this point is [[com.tibiabot.interactions.AdminPanel]].
 */
object AdminCommands {

  def handle(event: SlashCommandInteractionEvent): Unit =
    // Discord gates commands on permission flags and has no flag for "is the
    // application owner", so the command carries Manage Server and the real gate
    // is here — and again on every press, since the panel outlives this check.
    if (!Permissions.isBotCreator(event.getUser.getId, BotApp.botOwner))
      event.getHook.sendMessageEmbeds(Embeds.response(
        s"${Config.noEmoji} This command is only available to the bot creator.")).queue()
    else
      event.getHook.sendMessageEmbeds(Panels.adminEmbed(BotApp.discordGateway.guilds.size))
        .setComponents(Panels.adminButtons.asJava).setEphemeral(true).queue()
}
