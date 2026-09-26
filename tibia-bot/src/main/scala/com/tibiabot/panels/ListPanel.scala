package com.tibiabot.panels

import com.tibiabot.panels.PanelIds.Panel
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.{Container, ContainerChildComponent}
import net.dv8tion.jda.api.components.section.Section
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.entities.emoji.Emoji

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

/** What `/hunted` and `/allies` answer with: the list as one card, laid out with
 *  Discord's layout components.
 *
 *  A header with the list's picture and its counts; the guilds under a heading
 *  that carries **Add guild**; the players, a small heading per world, under one
 *  that carries **Add player**; and at the foot one row for everything that is
 *  about the list as a whole — Remove, Look up, Config and Clear All. An Add on a
 *  heading already knows whether it is adding players or guilds, so its form does
 *  not ask.
 *
 *  ==Spilling onto more messages==
 *  A message in this layout holds 4,000 characters of text across all of it,
 *  against the 6,000 an embed message had, so a long list takes more messages.
 *  Lines fill a message until the next one would not fit, and a world cut in two
 *  is headed again where it continues. The header leads the first message and the
 *  row of buttons closes the last, which is where a list scrolled to the bottom
 *  leaves you.
 *
 *  Config-free: every emoji here is unicode, and the caller hands over the lines
 *  already rendered.
 */
object ListPanel {

  /** Under Discord's 4,000, with room for the button labels. */
  val TextBudget: Int = 3800

  private val FooterReserve = 100

  private def title(panel: Panel): String =
    if (panel == Panel.Hunted) "☠️ Hunted list" else "🤝 Allies list"

  private def plural(n: Int, one: String, many: String): String = s"$n ${if (n == 1) one else many}"

  private def divider: Separator = Separator.createDivider(Separator.Spacing.SMALL)

  private def heading(panel: Panel, action: String, name: String, count: Int): (Section, String) = {
    val text = s"**$name**\n-# $count on the list"
    val label = if (action == PanelIds.AddGuild) "Add guild" else "Add player"
    (Section.of(
      Button.secondary(PanelIds.button(panel, action), label).withEmoji(Emoji.fromUnicode("➕")),
      TextDisplay.of(text)), text)
  }

  /** The row at the foot of the list. */
  def footer(panel: Panel): ActionRow = ActionRow.of(PanelIds.listFooterActions.map { action =>
    val id = PanelIds.button(panel, action)
    action match {
      case PanelIds.Remove => Button.secondary(id, "Remove").withEmoji(Emoji.fromUnicode("➖"))
      case PanelIds.Info   => Button.secondary(id, "Look up").withEmoji(Emoji.fromUnicode("🔍"))
      case PanelIds.Config => Button.secondary(id, "Config").withEmoji(Emoji.fromUnicode("⚙️"))
      case _               => Button.danger(id, "Clear All").withEmoji(Emoji.fromUnicode("🗑️"))
    }
  }.asJava)

  /** The list, as however many messages it needs — never none.
   *
   *  @param guilds  one rendered line per guild
   *  @param players each world with its rendered lines, in display order */
  def pages(panel: Panel, guilds: List[String],
            players: List[(String, List[String])]): List[Container] = {
    val pages = ListBuffer.empty[List[ContainerChildComponent]]
    val current = ListBuffer.empty[ContainerChildComponent]
    val pending = new StringBuilder
    var used = 0

    def room: Int = TextBudget - used - pending.length
    def flush(): Unit = if (pending.nonEmpty) {
      val text = pending.toString.stripSuffix("\n")
      current += TextDisplay.of(text)
      used += text.length
      pending.clear()
    }
    def newPage(): Unit = {
      flush()
      pages += current.toList
      current.clear()
      used = 0
    }
    def block(component: ContainerChildComponent, text: Int = 0): Unit = {
      flush()
      current += component
      used += text
    }
    /** A line of the list; on a fresh message, `continued` heads it again. */
    def line(text: String, continued: Option[String] = None): Unit = {
      if (text.length + 1 > room) {
        newPage()
        continued.foreach(h => pending.append(h).append('\n'))
      }
      pending.append(text).append('\n')
    }
    /** A heading and the first line under it travel together. */
    def headed(section: Section, text: String, first: String): Unit = {
      if (text.length + first.length + 2 > room) newPage()
      block(section, text.length)
    }

    val playerCount = players.map(_._2.size).sum
    val headerText = s"### ${title(panel)}\n-# ${plural(guilds.size, "guild", "guilds")} · ${plural(playerCount, "player", "players")}"
    // No picture beside it (the coffin and the angel statue went on 27 Sep 2026).
    block(TextDisplay.of(headerText), headerText.length)

    val guildLines = if (guilds.isEmpty) List("*No guilds on the list yet.*") else guilds
    block(divider)
    val (guildHeading, guildText) = heading(panel, PanelIds.AddGuild, "Guilds", guilds.size)
    headed(guildHeading, guildText, guildLines.head)
    guildLines.foreach(l => line(l))

    block(divider)
    val (playerHeading, playerText) = heading(panel, PanelIds.AddPlayer, "Players", playerCount)
    val firstPlayer = players.headOption.map { case (w, ls) => s"-# **${w.toUpperCase}**\n${ls.headOption.getOrElse("")}" }
      .getOrElse("*Nobody on the list yet.*")
    headed(playerHeading, playerText, firstPlayer)
    if (players.isEmpty) line("*Nobody on the list yet.*")
    players.zipWithIndex.foreach { case ((world, lines), index) =>
      val worldHeading = s"-# **${world.toUpperCase}**"
      val continued = s"-# **${world.toUpperCase}**, continued"
      // The heading and the world's first player stay together.
      val gap = if (index == 0 || pending.isEmpty) "" else "\n"
      if (gap.length + worldHeading.length + lines.headOption.map(_.length).getOrElse(0) + 2 > room) newPage()
      else if (gap.nonEmpty) pending.append(gap)
      pending.append(worldHeading).append('\n')
      lines.foreach(l => line(l, Some(continued)))
    }

    if (room < FooterReserve) newPage()
    block(divider)
    block(footer(panel))
    newPage()
    pages.toList.filter(_.nonEmpty).map(children => Container.of(children.asJava))
  }

  /** The question Clear All asks before it does anything, in place of the list's
   *  last message. */
  def confirmClear(panel: Panel, players: Int, guilds: Int, noEmoji: String): Container =
    Container.of(
      TextDisplay.of(
        s"$noEmoji This clears **${plural(players, "player", "players")}** and " +
          s"**${plural(guilds, "guild", "guilds")}** from the ${panel.noun}.\n\nThis cannot be undone."),
      ActionRow.of(
        Button.danger(PanelIds.button(panel, PanelIds.ClearConfirm), "Yes, clear it"),
        Button.secondary(PanelIds.button(panel, PanelIds.Cancel), "Cancel")))

  /** A one-line answer in place of the list's last message — what Clear All did,
   *  or that there was nothing to clear. */
  def notice(text: String): Container = Container.of(TextDisplay.of(text))
}
