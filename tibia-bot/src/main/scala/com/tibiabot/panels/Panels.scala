package com.tibiabot.panels

import com.tibiabot.Config
import com.tibiabot.domain.Worlds
import com.tibiabot.panels.PanelIds.Panel
import com.tibiabot.presentation.Embeds
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.{Container, ContainerChildComponent}
import net.dv8tion.jda.api.components.section.Section
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.emoji.Emoji

import scala.jdk.CollectionConverters._

/** What `/settings`, `/hunted`, `/allies` and `/admin` actually answer with: an
 *  ephemeral message naming what the panel covers, and a button per thing you can
 *  do to it.
 *
 *  Ephemeral on purpose. These are one person's administrative errands, the reply
 *  is only ever useful to whoever ran the command, and Discord only lets the
 *  invoker press an ephemeral message's buttons — so the panel cannot be
 *  hijacked by somebody else in the channel.
 */
object Panels {

  private def row(buttons: List[Button]): ActionRow = ActionRow.of(buttons.asJava)

  /** Discord allows five buttons to a row, so anything longer is split rather
   *  than silently rejected. A panel may ask for fewer per row where the split
   *  itself carries meaning — see [[adminButtons]]. */
  private def rows(buttons: List[Button], perRow: Int = 5): List[ActionRow] =
    buttons.grouped(perRow).map(row).toList

  // --- /settings -----------------------------------------------------------

  /** One setting on the panel: the form its button opens, what it is called, the
   *  emoji it goes by, the group it sits under, and a line saying what it changes —
   *  written from what the form actually edits (see [[SettingsForms]]). */
  private final case class SettingRow(action: String, name: String, emoji: String, group: String, explains: String)

  /** In display order, grouped by what each applies to: one world at a time
   *  (the form asks which when there are several), or the whole server.
   *
   *  The fullbless emoji comes from config rather than being picked here — the
   *  server's own blessing icon, so the setting wears the symbol the feature
   *  already uses elsewhere — and arrives as an argument so building the panel
   *  never needs a configured environment. */
  private def settingRows(fullblessEmoji: String): List[SettingRow] = List(
    SettingRow(PanelIds.ChannelFilter, "Channel Filters", "📊", "Per world",
      "The lowest level shown in the levels and deaths channels, and whether players on neither list appear there."),
    SettingRow(PanelIds.Layout, "Online List", "📈", "Per world",
      "One channel for everyone or a channel per side, and the lowest level shown for enemies, allies and neutrals."),
    SettingRow(PanelIds.Fullbless, "Fullbless", fullblessEmoji, "Per world",
      "The level an enemy has to be for the fullbless role to be pinged when they fullbless."),
    SettingRow(PanelIds.CommandLog, "Command Log", "🖥️", "Server-wide",
      "Where the bot records changes to your lists and settings, and its notices to admins.")
  )

  /** The `/settings` reply: each setting's name and what it changes, with its own
   *  ⚙️ button beside it, under a small heading for per-world and server-wide.
   *
   *  Built from Discord's layout components (a container of sections) rather than
   *  an embed and a row of buttons, because that is the only way to put a button
   *  next to the text explaining it — with an embed, the reader has to match a
   *  paragraph to a button below it. The message must be sent with
   *  `useComponentsV2`, and can then carry no embed of its own. Every button is
   *  the same gear: the text beside it already names what it opens.
   *
   *  The header names every world the panel can configure, so somebody with one
   *  world can see that at a glance and somebody with six knows the forms will ask
   *  which. */
  def settingsPanel(worlds: List[Worlds], fullblessEmoji: String = Config.inqEmoji): Container = {
    val worldList =
      if (worlds.isEmpty) "_No worlds are set up yet — run `/setup` first._"
      else worlds.map(w => s"**${w.name}**").sorted.mkString(", ")
    val header: ContainerChildComponent = TextDisplay.of(
      s"### ⚙️ Server settings\n-# Tracking: $worldList · each button opens a form showing what it's set to now.")
    val rows = settingRows(fullblessEmoji)
    val body = rows.zipWithIndex.flatMap { case (setting, index) =>
      val startsGroup = index == 0 || rows(index - 1).group != setting.group
      val heading: List[ContainerChildComponent] =
        if (startsGroup) List(
          Separator.createDivider(Separator.Spacing.SMALL),
          TextDisplay.of(s"-# **${setting.group.toUpperCase}**"))
        else Nil
      heading :+ (Section.of(
        Button.secondary(PanelIds.button(Panel.Settings, setting.action), Emoji.fromUnicode("⚙️")),
        TextDisplay.of(s"${setting.emoji} **${setting.name}**\n-# ${setting.explains}")): ContainerChildComponent)
    }
    Container.of((header :: body).asJava)
  }

  // --- /hunted and /allies -------------------------------------------------

  // The list itself is panels.ListPanel.

  /** The Tag button under a Look up reply.
   *
   *  Tagging lives here rather than on the list's own row: you have just looked
   *  somebody up, and the button acts on the player in front of you rather than
   *  asking for a name again. The Add player form tags a whole batch.
   */
  def lookupButtons(panel: Panel, name: String, currentTag: String): ActionRow = {
    val tag = ListTags.find(currentTag)
    val label = tag.map(t => s"Tag: ${t.label}").getOrElse("Tag")
    val icon = tag.map(_.emoji).getOrElse("🏷️")
    ActionRow.of(Button.secondary(PanelIds.buttonFor(panel, PanelIds.TagOne, name), label)
      .withEmoji(Emoji.fromUnicode(icon)))
  }

  private def plural(n: Int, one: String, many: String): String = if (n == 1) one else many

  // --- /admin --------------------------------------------------------------

  /** Six buttons, and the only panel drawn three to a row rather than five: the
   *  split is the grouping. The top row acts on one particular server and needs
   *  its id — so the list that gives you one leads. The bottom row is the three
   *  bot-wide refreshes, which act everywhere and ask for nothing.
   *
   *  Leaving a server is the only red button. Reposting boosted touches every
   *  guild the bot is in, but all it reposts is a message that reposts itself at
   *  the next server save anyway, so it is not destructive the way leaving is. */
  private val adminLabels: Map[String, (String, String)] = Map(
    PanelIds.GuildList   -> ("Server list" -> "🗒️"),
    PanelIds.Message     -> ("Message" -> "✉️"),
    PanelIds.Leave       -> ("Leave" -> "🚪"),
    PanelIds.Dreamscar   -> ("Dreamscar" -> "🌙"),
    PanelIds.WorldList   -> ("Worlds" -> "🌍"),
    PanelIds.BoostedPost -> ("Repost boosted" -> "📢")
  )

  def adminButtons: List[ActionRow] =
    rows(PanelIds.adminActions.map { action =>
      val (label, emoji) = adminLabels(action)
      val id = PanelIds.button(Panel.Admin, action)
      val button = if (action == PanelIds.Leave) Button.danger(id, label) else Button.secondary(id, label)
      button.withEmoji(Emoji.fromUnicode(emoji))
    }, perRow = 3)

  /** Names the one number the creator wants at a glance. It is also the size of
   *  what Server list is about to print, which is worth knowing before pressing
   *  it — that reply is one ephemeral message per three thousand characters. */
  def adminEmbed(guilds: Int): MessageEmbed =
    new EmbedBuilder()
      .setTitle("Bot creator tools")
      .setDescription(
        s"In **$guilds** ${plural(guilds, "server", "servers")}.\n\n" +
          "The top row acts on one server and needs its id — **Server list** has them. " +
          "The bottom row refreshes something everywhere and takes no input.")
      .setColor(Embeds.BrandColor)
      .build()
}
