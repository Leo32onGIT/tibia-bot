package com.tibiabot.panels

import com.tibiabot.Config
import com.tibiabot.domain.Worlds
import com.tibiabot.panels.PanelIds.Panel
import com.tibiabot.presentation.Embeds
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.emoji.Emoji

import scala.jdk.CollectionConverters._

/** What `/settings`, `/hunted` and `/allies` actually answer with: an ephemeral
 *  message naming what the panel covers, and a button per thing you can do to it.
 *
 *  Ephemeral on purpose. These are one person's administrative errands, the reply
 *  is only ever useful to whoever ran the command, and Discord only lets the
 *  invoker press an ephemeral message's buttons — so the panel cannot be
 *  hijacked by somebody else in the channel.
 */
object Panels {

  private def row(buttons: List[Button]): ActionRow = ActionRow.of(buttons.asJava)

  /** Discord allows five buttons to a row, so anything longer is split rather
   *  than silently rejected. */
  private def rows(buttons: List[Button]): List[ActionRow] =
    buttons.grouped(5).map(row).toList

  // --- /settings -----------------------------------------------------------

  private val settingsLabels: Map[String, (String, String)] = Map(
    PanelIds.Fullbless     -> ("Fullbless" -> "🛡️"),
    PanelIds.Exiva         -> ("Exiva lists" -> "🔎"),
    PanelIds.Layout        -> ("Online layout" -> "🧭"),
    PanelIds.Neutral       -> ("Neutrals" -> "⚪"),
    PanelIds.ChannelFilter -> ("Channel filters" -> "📊"),
    PanelIds.OnlineFilter  -> ("Online filters" -> "📋")
  )

  def settingsButtons: List[ActionRow] =
    rows(PanelIds.settingsActions.map { action =>
      val (label, emoji) = settingsLabels(action)
      Button.secondary(PanelIds.button(Panel.Settings, action), label).withEmoji(Emoji.fromUnicode(emoji))
    })

  /** Names every world the panel can configure, so somebody with one world can
   *  see that at a glance and somebody with six knows the forms will ask which. */
  def settingsEmbed(worlds: List[Worlds]): MessageEmbed = {
    val worldList =
      if (worlds.isEmpty) "_No worlds are set up yet — run `/setup` first._"
      else worlds.map(w => s"**${w.name}**").sorted.mkString(", ")
    new EmbedBuilder()
      .setTitle("Server settings")
      .setDescription(
        "Pick what you want to change. Each one opens a form showing what it is " +
          "set to now, so you can check a setting without changing it.\n\n" +
          s"Tracking: $worldList")
      .setColor(Embeds.BrandColor)
      .build()
  }

  // --- /hunted and /allies -------------------------------------------------

  private def listLabels(panel: Panel): Map[String, (String, String)] = Map(
    PanelIds.Add     -> ("Add" -> "➕"),
    PanelIds.Remove  -> ("Remove" -> "➖"),
    PanelIds.Info    -> ("Look up" -> "🔍"),
    PanelIds.Display -> ("Display" -> "⚙️"),
    PanelIds.Clear   -> ("Clear all" -> "🗑️")
  )

  def listButtons(panel: Panel): List[ActionRow] = {
    val labels = listLabels(panel)
    rows(PanelIds.listActions(panel).map { action =>
      val (label, emoji) = labels(action)
      val button =
        if (action == PanelIds.Clear) Button.danger(PanelIds.button(panel, action), label)
        else Button.secondary(PanelIds.button(panel, action), label)
      button.withEmoji(Emoji.fromUnicode(emoji))
    })
  }

  /** The fallback when a list somehow draws nothing at all. Not reachable from
   *  the list builders, which always draw at least a "nothing on it" embed for
   *  each half — this exists so a panel can never answer with silence. */
  def emptyListEmbed(panel: Panel): MessageEmbed =
    new EmbedBuilder()
      .setTitle(s"${panel.noun.capitalize}")
      .setDescription("Nothing on it yet.")
      .setColor(Embeds.BrandColor)
      .build()

  /** Replaces the panel's buttons while Clear is waiting for an answer, so the
   *  only things on screen are the two answers to the question just asked. */
  def clearConfirmButtons(panel: Panel): ActionRow =
    ActionRow.of(
      Button.danger(PanelIds.button(panel, PanelIds.ClearConfirm), "Yes, clear it"),
      Button.secondary(PanelIds.button(panel, PanelIds.Cancel), "Cancel"))

  def clearConfirmEmbed(panel: Panel, players: Int, guilds: Int): MessageEmbed =
    new EmbedBuilder()
      .setDescription(
        s"${Config.noEmoji} This clears **$players** ${plural(players, "player", "players")} and " +
          s"**$guilds** ${plural(guilds, "guild", "guilds")} from the ${panel.noun}.\n\nThis cannot be undone.")
      .setColor(Embeds.BrandColor)
      .build()

  private def plural(n: Int, one: String, many: String): String = if (n == 1) one else many
}
