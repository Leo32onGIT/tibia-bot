package com.tibiabot.presentation

import com.tibiabot.Config
import com.tibiabot.domain.Killers

/** The `exiva` block a death post can carry: the few killers worth chasing, one
 *  `exiva "Name"` line each, hardest first.
 *
 *  It is no longer part of the post as sent. An ally death carries an exiva
 *  button instead, and the block is written into the embed only when somebody
 *  presses it — so rather than holding the names in memory against a press that
 *  may never come, they are read back out of the description that is already
 *  there. The button therefore still works on a post from last week, and across
 *  a restart.
 *
 *  The icons are passed in by the Config-reading overloads below rather than
 *  read here, so the interesting half of this is unit-testable without a
 *  configured environment — same reason Panels does not hold its own labels in a
 *  `val`.
 */
object ExivaList {

  /** Discord's own ceiling on an embed description. */
  private val DescriptionLimit = 4096

  /** The block itself, each line prefixed with its own newline so it appends
   *  straight onto a description. Empty for no targets. */
  def render(targets: Seq[String], exivaIcon: String, indentIcon: String): String =
    targets.zipWithIndex.map { case (name, i) =>
      // The exiva icon names the block once; the rest sit indented under it.
      val icon = if (i == 0) exivaIcon else indentIcon
      "\n" + icon + " `exiva \"" + name + "\"`"
    }.mkString

  def render(targets: Seq[String]): String =
    render(targets, Config.exivaEmoji, Config.indentEmoji)

  /** A player killer is the only thing a death description links to a character
   *  page — creatures are plain text, and the victim's guild link points at a
   *  guild page — so the link target is what identifies one, not where it sits in
   *  the text. A summon's link is its summoner, which is who gets exiva'd. */
  private val KillerLink =
    """\[(.+?)\]\(https://www\.tibia\.com/community/\?name=[^)]*\)""".r

  /** The " [415]" the embed renders beside a killer whose level resolved. */
  private val WithLevel = """(.*) \[(\d+)\]""".r

  /** Player killers named in a death description, in the order it names them,
   *  each with the level rendered beside it. */
  def killersIn(description: String): Seq[(String, Option[Int])] =
    KillerLink.findAllMatchIn(description).map(_.group(1)).map {
      case WithLevel(name, level) => (name, level.toIntOption)
      case name                   => (name, None)
    }.toSeq

  /** The block to append to a death description, or "" when it names nobody to
   *  chase or already carries one.
   *
   *  The post reserved room for the whole block when it was built, so it
   *  normally fits as it stands. A post cut short by `out of space` did not, and
   *  an overlong edit is one Discord refuses outright — which would leave the
   *  press with no answer at all. Lines are dropped from the bottom instead: the
   *  first one names the hardest killer, and is the one worth keeping. */
  def sectionFor(description: String, exivaIcon: String, indentIcon: String): String =
    if (description.contains(exivaIcon + " `exiva")) ""
    else {
      val room = DescriptionLimit - description.length
      val targets = Killers.exivaTargets(killersIn(description))
      targets.inits.map(render(_, exivaIcon, indentIcon)).find(_.length <= room).getOrElse("")
    }

  def sectionFor(description: String): String =
    sectionFor(description, Config.exivaEmoji, Config.indentEmoji)
}
