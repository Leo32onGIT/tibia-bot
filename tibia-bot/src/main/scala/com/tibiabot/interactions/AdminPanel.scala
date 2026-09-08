package com.tibiabot.interactions

import com.tibiabot.panels.{AdminForms, PanelForms, PanelIds}
import com.tibiabot.presentation.Embeds
import com.tibiabot.{BotApp, Config, WorldManager}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent

/** The `/admin` panel's buttons and the two forms behind them — what used to be
 *  six subcommands of `/admin`.
 *
 *  Lives apart from [[PanelButtons]] and [[PanelModals]], which delegate to it in
 *  a line each, because it shares almost nothing with them beyond the id format:
 *  no world, no guild-scoped permission, and a caller who is the bot's creator
 *  rather than somebody's server admin. Keeping it here means a change to admin
 *  tooling cannot reach `/hunted`.
 *
 *  ==Acknowledging==
 *  Four of the six buttons answer with a message and are deferred before they get
 *  here; the two that open a form are not, because `replyModal` has to be the
 *  interaction's first response. See `PanelIds.adminAck`.
 *
 *  Every send sets ephemeral explicitly rather than relying on the deferral. The
 *  first send inherits it, but a *followup* defaults to public — and Server list
 *  is several messages whenever the bot is in enough guilds, which is always.
 */
object AdminPanel extends StrictLogging {

  /** A press. The permission check has already run — see PanelButtons.permitted. */
  def press(event: ButtonInteractionEvent, action: String): Unit = action match {
    case PanelIds.GuildList =>
      BotApp.adminService.info(embeds => embeds.foreach(send(event, _)))

    case PanelIds.Dreamscar =>
      send(event, BotApp.adminService.resyncDreamCourtBosses())

    case PanelIds.WorldList =>
      send(event, refreshWorldList())

    case PanelIds.BoostedPost =>
      // Refetches from TibiaData before it can say how it went, so the reply
      // arrives through the callback rather than as a return value.
      BotApp.adminService.refreshBoostedMessages(embed => send(event, embed))

    // Leave and Message. Nothing has acknowledged Discord yet, so this must be
    // the first response — building the form touches nothing that could block.
    case _ =>
      AdminForms.modal(action) match {
        case Some(modal) => event.replyModal(modal).queue()
        case None =>
          logger.debug(s"Ignoring unknown admin action '$action'")
          event.reply(s"${Config.noEmoji} That isn't available here.").setEphemeral(true).queue()
      }
  }

  /** A form submission, always deferred by BotListener before it gets here.
   *
   *  Neither of these validates the id: AdminService resolves it and says so
   *  itself, which covers the prune sweep and anything else that leaves a guild
   *  as well as this form.
   */
  def submit(event: ModalInteractionEvent, action: String): Unit = action match {
    case PanelIds.Leave =>
      send(event, BotApp.adminService.leave(
        text(event, PanelForms.GuildIdField), text(event, PanelForms.ReasonField)))

    case PanelIds.Message =>
      send(event, BotApp.adminService.message(
        text(event, PanelForms.GuildIdField), text(event, PanelForms.MessageField)))

    case _ =>
      send(event, Embeds.response(s"${Config.noEmoji} That isn't available here."))
  }

  /** Refetch the world list. The one action here whose failure is worth naming:
   *  it is a network call to TibiaData, which fails often enough to matter (see
   *  the flaky 503s), and a silent success would be indistinguishable from one. */
  private def refreshWorldList(): MessageEmbed =
    try {
      WorldManager.getWorldList()
      Embeds.response(s"${Config.yesEmoji} The worlds list has been refreshed.")
    } catch {
      case ex: Exception =>
        logger.warn("Failed to refresh the worlds list", ex)
        Embeds.response(s"${Config.noEmoji} The worlds list has failed to refresh.")
    }

  private def text(event: ModalInteractionEvent, id: String): String =
    Option(event.getValue(id)).map(_.getAsString.trim).getOrElse("")

  private def send(event: ButtonInteractionEvent, embed: MessageEmbed): Unit =
    event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue()

  private def send(event: ModalInteractionEvent, embed: MessageEmbed): Unit =
    event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue()
}
