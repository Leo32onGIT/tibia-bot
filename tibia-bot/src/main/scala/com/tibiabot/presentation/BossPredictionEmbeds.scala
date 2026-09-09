package com.tibiabot.presentation

import com.tibiabot.statistics.{BossChance, BossPrediction, Chance, DailyReport}
import net.dv8tion.jda.api.entities.MessageEmbed

import java.time.Instant

/** The third embed: which bosses might be up today.
 *
 *  ==One list, not two bands==
 *  The reference implementation groups by chance under two headings. Ordering
 *  already carries that — most overdue first — so the heading was saying it
 *  twice, and a dot on the row says it in the space of one character.
 *
 *  ==Timestamps rather than day counts==
 *  "22 days (window 18–25)" asks a reader to know that the first number is days
 *  since the boss was last seen and the bracket is the window it is counting
 *  towards. Discord's relative timestamps say the thing itself — "window closes
 *  in 3 days" — and, unlike a rendered day count, they are recomputed against
 *  every viewer's clock. The post keeps telling the truth when somebody scrolls
 *  back to it two days later, which a frozen "22 days" does not.
 *
 *  Only bosses that might actually be up are listed. The catalogue holds
 *  fifty-seven predictable bosses and on an ordinary day most are a few days
 *  into a long window; printing them would be three thousand characters of "not
 *  due" burying the handful somebody came for.
 */
object BossPredictionEmbeds {

  /** Deep green — a forecast rather than a record. */
  val PredictionColor: Int = 2400045

  /** Empty when there is nothing worth posting: no boss due, and no history to
   *  explain why. A world still waiting for its first sightings gets the note
   *  instead of silence, so the feature does not look broken while it warms up.
   *
   *  Every due boss is listed. A mature history on a busy world can have a
   *  couple of dozen inside some window at once, and the list used to stop at
   *  twenty and count the rest — but that cap existed because this embed shared
   *  one 6,000-character message with the other two, and [[EmbedPages]] means it
   *  no longer has to. A boss somebody could go and kill today is not worth
   *  hiding to save a reader a scroll.
   *
   *  @param titleIcon the icon on the heading — the boosted-boss one, which
   *                   reads as "bosses" in general rather than as any one of them
   *  @param bossIcon  the icon that leads every boss row
   */
  def build(report: DailyReport, titleIcon: String, bossIcon: String): List[MessageEmbed] = {
    val due = report.dueBosses
    if (due.isEmpty && report.predictions.isEmpty && report.awaitingSighting == 0) Nil
    else EmbedPages.build(
      PredictionColor, description(report, due, titleIcon, bossIcon), footer = footer(report))
  }

  private def description(report: DailyReport, due: List[BossPrediction],
                          titleIcon: String, bossIcon: String): String = {
    val heading = s"## $titleIcon Bosses Due"
    val rows =
      if (due.nonEmpty) due.map(line(_, bossIcon))
      else if (report.predictions.nonEmpty)
        List(s"*No boss is inside a spawn window today, out of ${report.predictions.size} being tracked.*")
      else List("*Not enough history yet to predict anything — see below.*")
    (heading :: rows).mkString("\n")
  }

  /** One boss: a dot for the chance, the icon, the name, and when its window
   *  turns over.
   *
   *  A boss with several spawn points says how many of them are up, since "two
   *  of four Rotworm Queens are due" is a different trip from one. */
  private def line(prediction: BossPrediction, bossIcon: String): String = {
    val dot = if (prediction.best == Chance.High) ":green_circle:" else ":yellow_circle:"
    val leading = prediction.leading
    val spawns = if (leading.sizeIs > 1) s" ×${leading.size}" else ""
    val when = leading.headOption.map(timing).getOrElse("")
    s"$dot $bossIcon **${prediction.boss.name}**$spawns${StatLines.Dot}$when"
  }

  /** What the window is doing, as a relative timestamp.
   *
   *  Three states, because a window edge only means something relative to where
   *  the boss currently is: not open yet, open now, or past an end it had. */
  private def timing(chance: BossChance): String =
    if (chance.daysSince < chance.windowMin) s"opens ${relative(chance.opensAt)}"
    else chance.closesAt match {
      case Some(close) if chance.daysSince > daysBetweenSaves(chance) => s"overdue since ${relative(close)}"
      case Some(close) => s"window closes ${relative(close)}"
      case None => s"overdue since ${relative(chance.opensAt)}"
    }

  /** The upper bound in days, for deciding whether the window has already
   *  closed. Parsed back from the rendered figure rather than carried twice. */
  private def daysBetweenSaves(chance: BossChance): Int =
    chance.windowMax.flatMap(max => scala.util.Try(max.toInt).toOption).getOrElse(Int.MaxValue)

  /** Discord's relative timestamp. It renders as "in 3 days" or "22 days ago"
   *  against the reader's own clock, and keeps doing so after the post is old. */
  private def relative(instant: Instant): String = s"<t:${instant.getEpochSecond}:R>"

  /** What the reader needs to trust the list, and nothing else.
   *
   *  A boss with no sighting in our history is not predicted at all, so a short
   *  list on a young history means "we do not know yet" rather than "nothing is
   *  due" — and those two read identically without this line. */
  private def footer(report: DailyReport): Option[String] =
    if (report.awaitingSighting <= 0) Option.empty
    else Some(s"${report.awaitingSighting} boss(es) not yet predicted — each becomes " +
      "predictable the first time it is killed after tracking started.")
}
