package com.tibiabot.commands.handlers

import com.tibiabot.commands.Permissions
import com.tibiabot.domain.Worlds
import com.tibiabot.panels.PanelIds.Panel
import com.tibiabot.panels.{ListPanel, Panels}
import com.tibiabot.{BotApp, Config}
import com.tibiabot.presentation.Embeds
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent


/** `/settings`, `/hunted` and `/allies` — three commands that each answer with a
 *  panel of buttons rather than carrying a subcommand tree.
 *
 *  Between them they were twenty-four subcommands, and Discord's command picker
 *  lists every leaf, so they were twenty-four of the roughly thirty-four rows
 *  somebody saw when they typed a slash. They are three rows now, and every
 *  setting behind them can be read as well as written — a form opens showing what
 *  the value is now, which no arrangement of subcommands could do.
 */
object PanelCommands {

  def settings(event: SlashCommandInteractionEvent): Unit =
    withGuild(event) { _ =>
      if (!Permissions.callerHasManageServer(event)) refuse(event, manageServerText)
      else {
        val worlds = worldsOf(event)
        if (worlds.isEmpty) refuse(event, noWorldsText)
        // Laid out with Discord's layout components, so the message is flagged as
        // such and carries no embed — see Panels.settingsPanel.
        else event.getHook.sendMessageComponents(Panels.settingsPanel(worlds))
          .useComponentsV2().setEphemeral(true).queue()
      }
    }

  def hunted(event: SlashCommandInteractionEvent): Unit = listPanel(event, Panel.Hunted)

  def allies(event: SlashCommandInteractionEvent): Unit = listPanel(event, Panel.Allies)

  private def listPanel(event: SlashCommandInteractionEvent, panel: Panel): Unit =
    withGuild(event) { guildId =>
      // Manage Server or the guild's moderator role — see Permissions.isModerator.
      if (!Permissions.callerIsModerator(event, BotApp.moderatorRoleId(guildId))) refuse(event, moderatorText)
      else if (worldsOf(event).isEmpty) refuse(event, noWorldsText)
      else {
        val pages = listPagesFor(event.getGuild, panel)
        // Every send carries setEphemeral, not just the ones after the first.
        // The deferral was ephemeral, so the first send inherits it — but each one
        // after that is a *followup*, and a followup defaults to public. A list
        // long enough to need a second message therefore posted the rest of itself
        // to the channel, buttons and all, for everybody to see.
        pages.foreach(page =>
          event.getHook.sendMessageComponents(page).useComponentsV2().setEphemeral(true).queue())
      }
    }

  /** Draw a list panel: the list itself, as a card with its buttons — see
   *  panels.ListPanel for the layout and how a long list spills onto further
   *  messages, the last of which carries the row of buttons.
   *
   *  The list is the reply rather than something behind a button. It is built
   *  from cache and costs nothing, so there was never a reason to make somebody
   *  press for it — and a panel that opened on "75 players and 6 guilds" told
   *  them the one thing they already knew.
   */
  private[tibiabot] def listPagesFor(guild: net.dv8tion.jda.api.entities.Guild, panel: Panel)
      : List[net.dv8tion.jda.api.components.container.Container] = {
    val which = if (panel == Panel.Hunted) "hunted" else "allies"
    val service = BotApp.huntedAlliedService
    ListPanel.pages(panel, service.listThumbnail(which), service.guildLines(guild, which),
      service.playerLines(guild, which))
  }

  private[handlers] def counts(guildId: String, panel: Panel): (Int, Int) =
    if (panel == Panel.Hunted)
      (BotApp.huntedPlayersData.getOrElse(guildId, List()).size,
        BotApp.huntedGuildsData.getOrElse(guildId, List()).size)
    else
      (BotApp.alliedPlayersData.getOrElse(guildId, List()).size,
        BotApp.alliedGuildsData.getOrElse(guildId, List()).size)

  private def worldsOf(event: SlashCommandInteractionEvent): List[Worlds] =
    BotApp.worldsData.getOrElse(event.getGuild.getId, List())

  private def withGuild(event: SlashCommandInteractionEvent)(body: String => Unit): Unit =
    Option(event.getGuild) match {
      case Some(guild) => body(guild.getId)
      case None        => refuse(event, s"${Config.noEmoji} That only works inside a server.")
    }

  private val manageServerText: String =
    s"${Config.noEmoji} You need **Manage Server** to change these settings."

  private val moderatorText: String =
    s"${Config.noEmoji} You do not have permission to use this command."

  private val noWorldsText: String =
    s"${Config.noEmoji} No worlds are set up here yet — run `/setup` first."

  private def refuse(event: SlashCommandInteractionEvent, text: String): Unit =
    replyEmbed(event, Embeds.response(text))

  private def replyEmbed(event: SlashCommandInteractionEvent, embed: MessageEmbed): Unit =
    event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue()
}
