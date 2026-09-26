package com.tibiabot.presentation

import net.dv8tion.jda.api.components.container.{Container, ContainerChildComponent}
import net.dv8tion.jda.api.components.separator.Separator
import net.dv8tion.jda.api.components.textdisplay.TextDisplay

import java.util.Locale

import scala.jdk.CollectionConverters._

/** The daily statistics post as Components V2 cards (26 Sep 2026; embeds before).
 *
 *  Four cards, one per part of the day: the experience board, the war, the kill
 *  statistics and the bosses due, each edged in its own colour. A card opens on
 *  its `##` title, and every section under it is a small grey label in bold
 *  capitals over its rows, with a divider before it — the same pieces the
 *  notifications and raids cards are made of.
 *
 *  ==Why a day can take several messages==
 *  A V2 message holds 4,000 characters of text across everything on it, and 40
 *  components. An embed message held 6,000. The count is over the raw text, so
 *  every character's tibia.com link and every custom emoji spends it, and a
 *  busy day is three messages where it was two. Nothing is dropped to make it
 *  fit: the day is the record.
 *
 *  So [[messages]] packs whole cards where it can — a card that won't fit in
 *  what is left of one message starts the next — and cuts a card between its
 *  sections only when it is too long for a message of its own, carrying the rest
 *  on in a card of the same colour. A section is never cut, unless it alone is
 *  past the limit, which none of this post's lists comes near; then it goes by
 *  rows.
 */
object StatisticsCard {

  /** Everything on one V2 message, added together. */
  val MaxText: Int = 4000

  /** Components on one V2 message, counting each card, text and divider. */
  val MaxComponents: Int = 40

  /** One card of the post: its edge colour, and its blocks — the title first,
   *  then each section — with a divider between every two. */
  final case class Part(colour: Int, blocks: List[String]) {

    /** The card's text, block after block, which is what a test reads. */
    def text: String = blocks.mkString("\n")
  }

  /** A section: its label as a small grey line in bold capitals, then its
   *  rows. Discord's grey subtext comes in one size, and bold capitals are the
   *  largest it reads (small caps until 26 Sep 2026). `icon` goes in front of
   *  the label and is left as it is. */
  def section(label: String, rows: List[String], icon: String = ""): String = {
    val lead = if (icon.isEmpty) "" else s"$icon "
    (s"-# $lead**${label.toUpperCase(Locale.ROOT)}**" :: rows).mkString("\n")
  }

  /** The day's cards as the messages that carry them, in order. */
  def messages(parts: List[Part]): List[List[Container]] =
    pack(parts).map(_.map { case (colour, blocks) => card(colour, blocks) })

  /** The packing itself, as colours and blocks, so a test can read it without
   *  taking cards apart. */
  private[presentation] def pack(parts: List[Part]): List[List[(Int, List[String])]] = {
    val done = List.newBuilder[List[(Int, List[String])]]
    var message = Vector.empty[(Int, Vector[String])]
    var text = 0
    var components = 0

    def close(): Unit = if (message.nonEmpty) {
      done += message.map { case (colour, blocks) => colour -> blocks.toList }.toList
      message = Vector.empty
      text = 0
      components = 0
    }

    parts.filter(_.blocks.nonEmpty).foreach { part =>
      val blocks = part.blocks.flatMap(fitted)
      val alone = (blocks.map(_.length).sum, cost(blocks.size))
      val fitsAlone = alone._1 <= MaxText && alone._2 <= MaxComponents
      if (fitsAlone && (text + alone._1 > MaxText || components + alone._2 > MaxComponents)) close()

      var open = false
      blocks.foreach { block =>
        // A block opens a card (card and text) or joins one (divider and text).
        val adds = 2
        if (text + block.length > MaxText || components + adds > MaxComponents) {
          close()
          open = false
        }
        if (open) message = message.init :+ (message.last._1 -> (message.last._2 :+ block))
        else message = message :+ (part.colour -> Vector(block))
        open = true
        text += block.length
        components += adds
      }
    }
    close()
    done.result()
  }

  /** What a card of `blocks` spends of a message's 40: itself, each text, and
   *  a divider between every two. */
  private def cost(blocks: Int): Int = if (blocks == 0) 0 else 1 + blocks + (blocks - 1)

  /** A block no message could hold, as pieces that can, cut between rows. */
  private def fitted(block: String): List[String] =
    if (block.length <= MaxText) List(block)
    else block.linesIterator.flatMap(line => line.grouped(MaxText)).foldLeft(List.empty[String]) {
      case (Nil, line) => List(line)
      case (piece :: earlier, line) =>
        if (piece.length + 1 + line.length <= MaxText) s"$piece\n$line" :: earlier
        else line :: piece :: earlier
    }.reverse

  private def card(colour: Int, blocks: List[String]): Container = {
    val children = blocks.zipWithIndex.flatMap { case (block, index) =>
      val text: ContainerChildComponent = TextDisplay.of(block)
      if (index == 0) List(text) else List(Separator.createDivider(Separator.Spacing.SMALL), text)
    }
    Container.of(children.asJava).withAccentColor(Int.box(colour))
  }
}
