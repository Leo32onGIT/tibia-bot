package com.tibiabot.presentation

import com.tibiabot.Config
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.container.{Container, ContainerChildComponent}
import net.dv8tion.jda.api.components.section.Section
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay
import net.dv8tion.jda.api.components.thumbnail.Thumbnail
import net.dv8tion.jda.api.entities.{Message, MessageEmbed}
import net.dv8tion.jda.api.entities.emoji.Emoji

import java.util.Locale

import scala.jdk.CollectionConverters._

/** The server-save message in a guild's notifications channel, as one card.
 *
 *  Everything that builds the message still builds embeds: the mini world
 *  changes, the boosted boss and creature, Rashid, Dream Courts and the Drome
 *  are each a description, and all but the mini world changes a thumbnail too.
 *  Each becomes one block of the card here, in the order given, with a divider
 *  between: a section with its picture on the right, or just its text across
 *  the card's width when it has none (the mini world changes, whose heading has
 *  a divider of its own under it). The Server Save Notifications button goes
 *  under the card.
 *
 *  Each daily block is labelled the same way, by [[dailyText]]: a small grey
 *  line in bold capitals saying what it is, then the name it is about.
 *
 *  Kept as embeds up to the last moment because the message is also read back.
 *  `/repair` and the mini world change watcher both rebuild it around the boss
 *  and creature it was posted with, and [[blocksOf]] hands them those as the
 *  embeds they always were, whether the message is one of these cards or an
 *  embed message posted before 25 Sep 2026.
 */
object ServerSaveCard {

  // What each daily block is labelled. Shared by every place that builds one:
  // the server-save refresh, BoostedService (the /setup, /repair and admin
  // repost path) and the boosted DM, which carries the same boss and creature.
  val BossLabel = "Boosted boss"
  val CreatureLabel = "Boosted creature"
  val RashidLabel = "Rashid can be found in"
  def dreamCourtsLabel(world: String): String = s"Dream Courts boss in $world"
  val DromeLabel = "Drome cycle ends"

  /** A small grey label in bold capitals. Discord's grey subtext comes in one
   *  size and bold capitals are the largest it reads, so every card's labels
   *  are set this way — this card's, the raid cards' and the statistics
   *  cards' (small caps until 26 Sep 2026). */
  def label(text: String): String = s"-# **${text.toUpperCase(Locale.ROOT)}**"

  /** One daily block's text: its [[label]], then what it names under it.
   *  `mark` is what goes before the name — the indent and the block's own
   *  emoji. */
  def dailyText(what: String, mark: String, name: String): String =
    s"${label(what)}\n### $mark $name"

  def notifyButton(letterEmoji: String): Button =
    Button.primary("boosted list", "Server Save Notifications").withEmoji(Emoji.fromFormatted(letterEmoji))

  /** The message: `blocks` as one card, then the button. `letterEmoji` defaults
   *  to the configured one; a test passes its own. */
  def components(blocks: List[MessageEmbed], letterEmoji: String = Config.letterEmoji): List[MessageTopLevelComponent] = {
    val withDividers = blocks.map(parts).zipWithIndex.flatMap { case (block, i) =>
      if (i == 0) block else divider :: block
    }
    List(Container.of(withDividers.asJava), ActionRow.of(notifyButton(letterEmoji)))
  }

  private def divider: Separator = Separator.createDivider(Separator.Spacing.SMALL)

  /** A block with a picture is a section with it on the right; one without is
   *  just its text. A blank line in the text of one without is a divider within
   *  it: the mini world changes have one under their heading, as the cooldown
   *  tracker and role card do. */
  private def parts(block: MessageEmbed): List[ContainerChildComponent] = {
    val text = Option(block.getDescription).getOrElse("")
    Option(block.getThumbnail).flatMap(t => Option(t.getUrl)) match {
      case Some(url) => List(Section.of(Thumbnail.fromUrl(url), TextDisplay.of(text)))
      case None =>
        text.split("\n\n", 2).toList.filter(_.nonEmpty).map(TextDisplay.of) match {
          case List(above, below) => List(above, divider, below)
          case whole              => whole
        }
    }
  }

  /** The blocks of a posted message: its embeds, or the sections of its card
   *  read back into them. */
  def blocksOf(message: Message): List[MessageEmbed] =
    if (message.isUsingComponentsV2) blocksOfCard(message.getComponents.asScala.toList)
    else message.getEmbeds.asScala.toList

  /** The sections of a card, read back into the embeds they were built from.
   *  Text with no picture straight after text with no picture is the rest of the
   *  same block, below the divider within it (see [[components]]). */
  def blocksOfCard(components: List[MessageTopLevelComponent]): List[MessageEmbed] = {
    val parts = components.collect { case card: Container => card }.flatMap(_.getComponents.asScala).collect {
      case s: Section =>
        val text = s.getContentComponents.asScala.collect { case t: TextDisplay => t.getContent }.mkString("\n")
        val picture = s.getAccessory match {
          case t: Thumbnail => Option(t.getUrl)
          case _            => None
        }
        (text, picture)
      case t: TextDisplay => (t.getContent, None)
    }
    val joined = parts.foldLeft(List.empty[(String, Option[String])]) {
      case ((above, None) :: done, (below, None)) => (s"$above\n\n$below", None) :: done
      case (done, part)                           => part :: done
    }
    joined.reverse.map { case (text, picture) => block(text, picture) }
  }

  private def block(text: String, picture: Option[String]): MessageEmbed = {
    val embed = new EmbedBuilder().setDescription(text).setColor(Embeds.BrandColor)
    picture.foreach(url => embed.setThumbnail(url))
    embed.build()
  }
}
