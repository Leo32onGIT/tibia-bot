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

import scala.jdk.CollectionConverters._

/** The server-save message in a guild's notifications channel, as one card.
 *
 *  Everything that builds the message still builds embeds: the mini world
 *  changes, the boosted boss and creature, Rashid, Dream Courts and the Drome
 *  are each a description and a thumbnail, and each becomes one section of the
 *  card here, in the order given, with a divider between. The Server Save
 *  Notifications button goes under the card.
 *
 *  Kept as embeds up to the last moment because the message is also read back.
 *  `/repair` and the mini world change watcher both rebuild it around the boss
 *  and creature it was posted with, and [[blocksOf]] hands them those as the
 *  embeds they always were, whether the message is one of these cards or an
 *  embed message posted before 25 Sep 2026.
 */
object ServerSaveCard {

  def notifyButton(letterEmoji: String): Button =
    Button.primary("boosted list", "Server Save Notifications").withEmoji(Emoji.fromFormatted(letterEmoji))

  /** The message: `blocks` as one card, then the button. `letterEmoji` defaults
   *  to the configured one; a test passes its own. */
  def components(blocks: List[MessageEmbed], letterEmoji: String = Config.letterEmoji): List[MessageTopLevelComponent] = {
    val sections = blocks.map(section)
    val withDividers = sections.zipWithIndex.flatMap { case (part, i) =>
      if (i == 0) List(part) else List(Separator.createDivider(Separator.Spacing.SMALL), part)
    }
    List(Container.of(withDividers.asJava), ActionRow.of(notifyButton(letterEmoji)))
  }

  /** A block with a picture is a section with it on the right; one without is
   *  just its text. */
  private def section(block: MessageEmbed): ContainerChildComponent = {
    val text = TextDisplay.of(Option(block.getDescription).getOrElse(""))
    Option(block.getThumbnail).flatMap(t => Option(t.getUrl)) match {
      case Some(url) => Section.of(Thumbnail.fromUrl(url), text)
      case None      => text
    }
  }

  /** The blocks of a posted message: its embeds, or the sections of its card
   *  read back into them. */
  def blocksOf(message: Message): List[MessageEmbed] =
    if (message.isUsingComponentsV2) blocksOfCard(message.getComponents.asScala.toList)
    else message.getEmbeds.asScala.toList

  /** The sections of a card, read back into the embeds they were built from. */
  def blocksOfCard(components: List[MessageTopLevelComponent]): List[MessageEmbed] =
    components.collect { case card: Container => card }.flatMap(_.getComponents.asScala).collect {
      case s: Section =>
        val text = s.getContentComponents.asScala.collect { case t: TextDisplay => t.getContent }.mkString("\n")
        val picture = s.getAccessory match {
          case t: Thumbnail => Option(t.getUrl)
          case _            => None
        }
        block(text, picture)
      case t: TextDisplay => block(t.getContent, None)
    }

  private def block(text: String, picture: Option[String]): MessageEmbed = {
    val embed = new EmbedBuilder().setDescription(text).setColor(Embeds.BrandColor)
    picture.foreach(url => embed.setThumbnail(url))
    embed.build()
  }
}
