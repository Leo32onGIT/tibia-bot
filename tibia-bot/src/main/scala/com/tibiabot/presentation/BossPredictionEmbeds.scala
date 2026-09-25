package com.tibiabot.presentation

import com.tibiabot.presentation.StatisticsCard.{Part, section}
import com.tibiabot.statistics.{BossChance, BossPrediction, Chance, DailyReport}

import java.time.Instant

/** The last card: which bosses might be up today.
 *
 *  ==Two groups, by chance==
 *  High chance, then low, each under a small-caps label led by its dot, so the
 *  rows carry no dot of their own (26 Sep 2026; one list with a dot on every row
 *  before). Within a group the order is the predictor's, most overdue first.
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

  /** Nemesis purple, the colour a rare boss already wears wherever else the bot
   *  mentions one. */
  val PredictionColor: Int = Embeds.NemesisPurple

  /** Empty when there is nothing worth posting: no boss due, and no history to
   *  explain why. A world still waiting for its first sightings gets the note
   *  instead of silence, so the feature does not look broken while it warms up.
   *
   *  Every due boss is listed. A mature history on a busy world can have a
   *  couple of dozen inside some window at once. A boss somebody could go and
   *  kill today is not worth hiding to save a reader a scroll, and
   *  [[StatisticsCard.messages]] carries a long card on to another message.
   *
   *  @param titleIcon the icon on the heading — the boosted-boss one, which
   *                   reads as "bosses" in general rather than as any one of them
   *  @param bossIcon  the icon that leads every boss row
   *  @param bossTitle the wiki's page title for a boss, by name, to link it to;
   *                   None for one the wiki lookup does not match, which reads
   *                   unlinked
   */
  def build(report: DailyReport, titleIcon: String, bossIcon: String,
            bossTitle: String => Option[String]): Option[Part] = {
    val due = report.dueBosses
    if (due.isEmpty && report.predictions.isEmpty && report.awaitingSighting == 0) None
    else {
      val heading = s"## $titleIcon Bosses Due"
      val blocks =
        if (due.nonEmpty) heading :: groups(due, bossIcon, bossTitle)
        else if (report.predictions.nonEmpty)
          List(s"$heading\n*No boss is inside a spawn window today, out of ${report.predictions.size} being tracked.*")
        else List(s"$heading\n*Not enough history yet to predict anything — see below.*")
      Some(Part(PredictionColor, footer(report).fold(blocks)(note => blocks.init :+ s"${blocks.last}\n-# $note")))
    }
  }

  /** The due bosses, high chance then low, each group under its dot. */
  private def groups(due: List[BossPrediction], bossIcon: String, bossTitle: String => Option[String]): List[String] =
    List(
      (Chance.High, ":green_circle:", "High chance"),
      (Chance.Low, ":yellow_circle:", "Low chance")
    ).flatMap { case (chance, dot, label) =>
      val rows = due.filter(_.best == chance).map(line(_, bossIcon, bossTitle))
      Option.when(rows.nonEmpty)(section(label, rows, icon = dot))
    }

  /** One boss: the icon, the name linked to its wiki page, and when its window
   *  turns over. The chance is on the group's label.
   *
   *  A boss with several spawn points says how many of them are up, since "two
   *  of four Rotworm Queens are due" is a different trip from one. The count
   *  sits outside the link. */
  private def line(prediction: BossPrediction, bossIcon: String, bossTitle: String => Option[String]): String = {
    val leading = prediction.leading
    val spawns = if (leading.sizeIs > 1) s" ×${leading.size}" else ""
    val when = leading.headOption.map(timing).getOrElse("")
    val name = prediction.boss.name
    val shown = bossTitle(name).fold(name)(page => s"[$name](${CreatureWiki.urlForTitle(page)})")
    s"$bossIcon **$shown**$spawns${StatLines.Dot}$when"
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
   *  closed. A boss past the point where its windows overlap has none, and can
   *  never read as overdue against one. */
  private def daysBetweenSaves(chance: BossChance): Int =
    chance.windowMax.getOrElse(Int.MaxValue)

  /** Discord's relative timestamp. It renders as "in 3 days" or "22 days ago"
   *  against the reader's own clock, and keeps doing so after the post is old. */
  private def relative(instant: Instant): String = s"<t:${instant.getEpochSecond}:R>"

  /** What the reader needs to trust the list, and nothing else: a small grey
   *  line at the foot of the card, where the embed had its footer.
   *
   *  A boss with no sighting in our history is not predicted at all, so a short
   *  list on a young history means "we do not know yet" rather than "nothing is
   *  due" — and those two read identically without this line. */
  private def footer(report: DailyReport): Option[String] =
    if (report.awaitingSighting <= 0) Option.empty
    else Some(s"${report.awaitingSighting} boss(es) not yet predicted")
}
