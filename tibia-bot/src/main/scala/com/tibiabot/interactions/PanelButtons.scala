package com.tibiabot.interactions

import com.tibiabot.commands.Permissions
import com.tibiabot.domain.Worlds
import com.tibiabot.panels.PanelIds.Panel
import com.tibiabot.panels.{ListForms, PanelIds, Panels, SettingsForms}
import com.tibiabot.presentation.Embeds
import com.tibiabot.{BotApp, Config}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent

import scala.jdk.CollectionConverters._

/** The buttons on the `/settings`, `/hunted` and `/allies` panels.
 *
 *  Routed on the `panel:` prefix rather than as branches of [[ButtonHandler]]'s
 *  if/else chain, the same way the respawn buttons are: this family shares an id
 *  format and a permission model, so one branch there covers however many buttons
 *  the panels grow.
 *
 *  ==Acknowledging==
 *  Most of these open a form, and `replyModal` has to be an interaction's first
 *  response - so BotListener defers nothing for them and the handler must reach
 *  Discord quickly. It can: building a form reads worlds already held in memory.
 *  The rest either reply with a message or rewrite the panel, and are deferred
 *  accordingly - see `PanelIds.ackFor`.
 */
object PanelButtons extends StrictLogging {

  def handles(componentId: String): Boolean = PanelIds.handlesButton(componentId)

  def handle(event: ButtonInteractionEvent): Unit =
    PanelIds.parse(event.getComponentId) match {
      case None =>
        // A panel from an older deploy whose ids no longer parse. The panel is
        // ephemeral and one command away, so say so rather than throwing.
        logger.debug(s"Ignoring unparseable panel id '${event.getComponentId}'")
        reply(event, s"${Config.noEmoji} That panel is out of date - run the command again.")

      case Some((panel, action)) =>
        val guild = event.getGuild
        if (guild == null) reply(event, s"${Config.noEmoji} That only works inside a server.")
        else if (!permitted(event, panel)) reply(event, refusalFor(panel))
        else dispatch(event, panel, action)
    }

  /** Re-checked on every press rather than trusted from the command that drew the
   *  panel: an ephemeral panel is only pressable by whoever ran the command, but a
   *  role can be taken away while it sits open. */
  private def permitted(event: ButtonInteractionEvent, panel: Panel): Boolean = {
    val member = event.getMember
    if (panel == Panel.Settings) Permissions.hasManageServer(member)
    else Permissions.isModerator(member, BotApp.moderatorRoleId(event.getGuild.getId))
  }

  private def refusalFor(panel: Panel): String =
    if (panel == Panel.Settings) s"${Config.noEmoji} You need **Manage Server** to change these settings."
    else s"${Config.noEmoji} You do not have permission to use this command."

  private def dispatch(event: ButtonInteractionEvent, panel: Panel, action: String): Unit = {
    val guildId = event.getGuild.getId
    val worlds: List[Worlds] = BotApp.worldsData.getOrElse(guildId, List())
    action match {
      case PanelIds.Clear =>
        val (players, guilds) = counts(guildId, panel)
        if (players == 0 && guilds == 0)
          event.getHook.editOriginalEmbeds(Embeds.response(
            s"${Config.noEmoji} The ${panel.noun} is already empty.")).setComponents().queue()
        else
          event.getHook.editOriginalEmbeds(Panels.clearConfirmEmbed(panel, players, guilds))
            .setComponents(Panels.clearConfirmButtons(panel)).queue()

      // Back out: put the panel back exactly as it was, changing nothing.
      case PanelIds.Cancel =>
        redrawList(event, panel)

      case PanelIds.ClearConfirm =>
        val embed =
          if (panel == Panel.Hunted) BotApp.huntedAlliedService.clearHunted(event)
          else BotApp.huntedAlliedService.clearAllies(event)
        event.getHook.editOriginalEmbeds(embed).setComponents().queue()

      // Everything else opens a form. Nothing has been acknowledged, so this must
      // be the first response - see the class doc.
      case _ =>
        val form =
          if (panel == Panel.Settings) SettingsForms.modal(action, worlds)
          else ListForms.modal(panel, action, worlds)
        form match {
          case Some(modal) => event.replyModal(modal).queue()
          case None =>
            event.reply(s"${Config.noEmoji} That isn't available here.").setEphemeral(true).queue()
        }
    }
  }

  /** Put the panel back as the command drew it: the list, with its buttons. */
  private def redrawList(event: ButtonInteractionEvent, panel: Panel): Unit = {
    val which = if (panel == Panel.Hunted) "hunted" else "allies"
    val embeds = BotApp.huntedAlliedService.guildsEmbeds(event.getGuild, which) ++
      BotApp.huntedAlliedService.playersEmbeds(event.getGuild, which)
    // The message being edited is the one the buttons were on, which is the last
    // of however many the list needed - so it is the last batch that goes back
    // into it, not the first. Earlier messages are left as they were: nothing
    // holds a reference to them, and they still read correctly.
    val pages = com.tibiabot.presentation.ListEmbeds.batches(embeds)
    val last = pages.lastOption.getOrElse(List(Panels.emptyListEmbed(panel)))
    event.getHook.editOriginalEmbeds(last.asJava)
      .setComponents(Panels.listButtons(panel).asJava).queue()
  }

  private def counts(guildId: String, panel: Panel): (Int, Int) =
    if (panel == Panel.Hunted)
      (BotApp.huntedPlayersData.getOrElse(guildId, List()).size,
        BotApp.huntedGuildsData.getOrElse(guildId, List()).size)
    else
      (BotApp.alliedPlayersData.getOrElse(guildId, List()).size,
        BotApp.alliedGuildsData.getOrElse(guildId, List()).size)

  /** A press that opens a form has not been acknowledged; everything else here
   *  has. Both are answered ephemerally either way. */
  private def reply(event: ButtonInteractionEvent, text: String): Unit = {
    val embed = Embeds.response(text)
    if (event.isAcknowledged) event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue()
    else event.replyEmbeds(embed).setEphemeral(true).queue()
  }
}
