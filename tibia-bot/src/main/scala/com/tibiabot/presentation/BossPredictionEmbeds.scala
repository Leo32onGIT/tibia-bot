package com.tibiabot.presentation

import com.tibiabot.statistics.{BossPrediction, Chance, DailyReport}
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

/** The boss prediction that rides beside the daily statistics post.
 *
 *  Its own embed rather than another field on that one, because it answers a
 *  different question: the statistics embed is what happened, this is what might
 *  happen today. A reader acts on one and reads the other.
 *
 *  Only bosses that might actually be up are listed. The catalogue holds
 *  fifty-seven predictable bosses and on an ordinary day most are a few days into
 *  a long window — listing them would be three thousand characters of "not due",
 *  and would bury the four lines somebody came for.
 *
 *  Config-free like its neighbour, for the reason
 *  [[StatisticsEmbeds]] documents. */
object BossPredictionEmbeds {

  /** Deep green — a forecast rather than a record, and told apart at a glance
   *  from the statistics embed it sits under. */
  val PredictionColor: Int = 2400045

  /** How many bosses each band names before it starts counting instead.
   *
   *  Twelve high and eight low. On a mature history a busy world can have a
   *  couple of dozen bosses inside some window at once, and this embed shares a
   *  6,000-character message with the statistics one. */
  val MaxHigh: Int = 12
  val MaxLow: Int = 8

  /** None when there is nothing worth posting — no boss due, and no history to
   *  explain why. A world still waiting for its first sightings gets the note
   *  rather than silence, so the feature does not look broken while it warms up.
   */
  def build(report: DailyReport): Option[MessageEmbed] = {
    val due = report.dueBosses
    if (due.isEmpty && report.awaitingSighting == 0 && report.predictions.isEmpty) Option.empty
    else {
      val embed = new EmbedBuilder()
      embed.setTitle(s":dragon: Bosses due on ${report.world}")
      embed.setColor(PredictionColor)
      embed.setDescription(description(report, due))
      footer(report).foreach(embed.setFooter)
      Some(embed.build())
    }
  }

  private def description(report: DailyReport, due: List[BossPrediction]): String = {
    val high = due.filter(_.best == Chance.High)
    val low = due.filter(_.best == Chance.Low)
    val bands = List(
      band(":green_circle: **Due now**", high, MaxHigh),
      band(":yellow_circle: **Possible**", low, MaxLow)
    ).flatten
    if (bands.nonEmpty) bands.mkString("\n\n")
    else if (report.predictions.nonEmpty)
      s"*No boss is inside a spawn window today, out of ${report.predictions.size} being tracked.*"
    else "*Not enough history yet to predict anything — see below.*"
  }

  private def band(heading: String, bosses: List[BossPrediction], limit: Int): Option[String] =
    if (bosses.isEmpty) Option.empty
    else {
      val lines = bosses.take(limit).map(line)
      val hidden = bosses.size - lines.size
      val tail = if (hidden > 0) List(s"*…and $hidden more*") else Nil
      Some((heading :: lines ::: tail).mkString("\n"))
    }

  /** One boss: how long since it was last seen, and the window it is counting
   *  towards.
   *
   *  A boss with several spawn points shows how many of them are up, since "two
   *  of four Rotworm Queens are due" is a different trip from one. */
  private def line(prediction: BossPrediction): String = {
    val chance = prediction.chances.filter(_.chance == prediction.best)
    val window = chance.headOption.map(c => c.windowMax match {
      case Some(max) => s"${c.windowMin}–$max"
      case scala.None => s"${c.windowMin}+"
    }).getOrElse("")
    val spawns = if (chance.sizeIs > 1) s" ×${chance.size}" else ""
    val days = prediction.daysSince
    s"**${prediction.boss.name}**$spawns — $days ${plural(days, "day", "days")} *(window $window)*"
  }

  private def plural(count: Int, one: String, many: String): String = if (count == 1) one else many

  /** What the reader needs to know to trust the list, and nothing else.
   *
   *  A boss with no sighting in our history is not predicted at all, so a short
   *  list on a young history means "we do not know yet" rather than "nothing is
   *  due" — and those read identically without this line. */
  private def footer(report: DailyReport): Option[String] =
    if (report.awaitingSighting <= 0) Option.empty
    else Some(s"${report.awaitingSighting} boss(es) not yet predicted — each becomes " +
      "predictable the first time it is killed after tracking started.")
}
