package com.tibiabot.presentation

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

/** Discord's two size limits, and how a post that outgrows them is split.
 *
 *  A description caps at 4,096 characters and everything on one message caps at
 *  6,000 across at most ten embeds. The daily statistics post can pass both on a
 *  busy day: ten gainers, ten fraggers, two five-row lists and twenty bosses is
 *  around 7,700 characters, and long names alone can push the PVP embed past
 *  4,096 on its own.
 *
 *  Neither is a reason to shorten a list. The post is the day's record, so it
 *  spills onto a second embed and a second message rather than dropping rows —
 *  the reader scrolls, which costs them nothing, instead of silently losing
 *  names nobody can get back.
 *
 *  Splitting is at line boundaries, so a row is never cut in half. A single line
 *  longer than a whole description cannot happen with any row this post builds,
 *  but is hard-split rather than left to throw.
 */
object EmbedPages {

  /** One embed's description. */
  val MaxDescription: Int = MessageEmbed.DESCRIPTION_MAX_LENGTH

  /** Every embed on one message, added together. */
  val MaxMessage: Int = MessageEmbed.EMBED_MAX_LENGTH_BOT

  /** Embeds on one message. */
  val MaxEmbeds: Int = 10

  /** One section of the post as one or more embeds.
   *
   *  The footer goes on the last page, which is where a reader expects to find
   *  it when a section runs long. The colour goes on every page, since that is
   *  what says the pages belong together.
   *
   *  An empty body produces no embed at all rather than an empty one.
   */
  def build(color: Int, body: String, footer: Option[String] = None): List[MessageEmbed] = {
    val pages = split(body)
    pages.zipWithIndex.map { case (page, index) =>
      val embed = new EmbedBuilder()
      embed.setColor(color)
      embed.setDescription(page)
      if (index == pages.size - 1) footer.foreach(embed.setFooter)
      embed.build()
    }
  }

  /** A body as pages that each fit a description, split between lines. */
  def split(body: String): List[String] =
    if (body.isEmpty) Nil
    else body.linesIterator.toList.flatMap(hardSplit).foldLeft(List.empty[List[String]]) {
      case (Nil, line) => List(List(line))
      case (page :: earlier, line) =>
        // The +1 is the newline that rejoining will put back in front of it.
        if (page.map(_.length + 1).sum + line.length <= MaxDescription) (line :: page) :: earlier
        else List(line) :: page :: earlier
    }.reverse.map(_.reverse.mkString("\n"))

  /** Embeds grouped into the messages that will carry them, in order.
   *
   *  A message takes as many as fit, so the ordinary day — three embeds well
   *  inside the limit — is still one message and reads as one entry in the
   *  channel. Only a day that genuinely does not fit becomes two.
   */
  def messages(embeds: List[MessageEmbed]): List[List[MessageEmbed]] =
    embeds.foldLeft(List.empty[List[MessageEmbed]]) {
      case (Nil, embed) => List(List(embed))
      case (message :: earlier, embed) =>
        if (message.sizeIs < MaxEmbeds && message.map(_.getLength).sum + embed.getLength <= MaxMessage)
          (embed :: message) :: earlier
        else List(embed) :: message :: earlier
    }.reverse.map(_.reverse)

  /** A line that could not fit a description even on a page of its own, cut into
   *  pieces that can. Nothing this post builds is anywhere near it — the longest
   *  row is a couple of hundred characters — so this exists to keep a surprise
   *  from throwing rather than to be used. */
  private def hardSplit(line: String): List[String] =
    if (line.length <= MaxDescription) List(line)
    else line.grouped(MaxDescription).toList
}
