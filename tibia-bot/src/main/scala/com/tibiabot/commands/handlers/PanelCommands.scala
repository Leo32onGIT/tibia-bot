package com.tibiabot.commands.handlers

import com.tibiabot.commands.Permissions
import com.tibiabot.domain.Worlds
import com.tibiabot.panels.PanelIds.Panel
import com.tibiabot.panels.{PanelIds, Panels}
import com.tibiabot.{BotApp, Config}
import com.tibiabot.presentation.{Embeds, ListEmbeds}
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

import scala.jdk.CollectionConverters._

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
        else event.getHook.sendMessageEmbeds(Panels.settingsEmbed(worlds))
          .setComponents(Panels.settingsButtons.asJava).setEphemeral(true).queue()
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
        // Split by what a message can actually carry, not by embed count: the cap
        // that bites is the 6000 characters summed across a message's embeds, and
        // two full pages are already past it. See ListEmbeds.batches.
        val pages = ListEmbeds.batches(listEmbedsFor(event.getGuild, panel))
        // Buttons go under the *last* message. A list long enough to span several
        // is a list you have scrolled to the bottom of, and the controls belong
        // where that leaves you rather than back above the part you just read.
        // An empty list still draws two "nothing on it" embeds, so this is never
        // empty in practice — but a deferred reply nobody answers hangs as
        // "thinking" forever, which is too poor a failure to leave to that.
        val pagesToSend = if (pages.nonEmpty) pages else List(List(Panels.emptyListEmbed(panel)))
        // Every send carries setEphemeral, not just the ones after the first.
        // The deferral was ephemeral, so the first send inherits it — but each one
        // after that is a *followup*, and a followup defaults to public. A list
        // long enough to need a second message therefore posted the rest of itself
        // to the channel, buttons and all, for everybody to see.
        pagesToSend.init.foreach(page =>
          event.getHook.sendMessageEmbeds(page.asJava).setEphemeral(true).queue())
        event.getHook.sendMessageEmbeds(pagesToSend.last.asJava)
          .setComponents(Panels.listButtons(panel).asJava).setEphemeral(true).queue()
      }
    }

  /** Draw a list panel: the list itself, with the buttons under it.
   *
   *  The list is the reply rather than something behind a button. It is built
   *  from cache and costs nothing (see HuntedAlliedService.playersEmbeds), so
   *  there was never a reason to make somebody press for it — and a panel that
   *  opened on "75 players and 6 guilds" told them the one thing they already
   *  knew.
   *
   *  Discord takes ten embeds to a message. A list long enough to page past that
   *  sends the rest behind it rather than losing them; the buttons stay on the
   *  first message, where the eye starts.
   */
  private[handlers] def listEmbedsFor(guild: net.dv8tion.jda.api.entities.Guild, panel: Panel): List[MessageEmbed] = {
    val which = if (panel == Panel.Hunted) "hunted" else "allies"
    BotApp.huntedAlliedService.guildsEmbeds(guild, which) ++
      BotApp.huntedAlliedService.playersEmbeds(guild, which)
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
