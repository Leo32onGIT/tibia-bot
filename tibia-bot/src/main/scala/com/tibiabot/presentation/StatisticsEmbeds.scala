package com.tibiabot.presentation

import com.tibiabot.domain.{ExperienceDelta, HighscoreEvent}
import com.tibiabot.statistics.DailyReport
import com.tibiabot.tibiadata.HighscoreCategory
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

import java.time.format.DateTimeFormatter
import java.util.Locale

/** The daily Statistics post.
 *
 *  Pure, like the other builders here: it is handed a finished
 *  [[com.tibiabot.statistics.DailyReport]] and turns it into an embed. What goes
 *  in the report is [[com.tibiabot.statistics.DailyStatistics]]' business.
 *
 *  The leaderboard lives in the description rather than in a field, because ten
 *  lines of linked name plus a figure runs to roughly 1,200 characters and a
 *  field caps at 1,024 — it fit during development and would have started
 *  silently truncating on worlds with longer names. The description's 4,096
 *  leaves the same list about three times the room it needs.
 *
 *  Config-free, which is why the skill emoji is passed in rather than looked up:
 *  reading Config here would make merely building an embed require a fully
 *  configured environment, and every test of this file would need a database
 *  host set. Same reason and same shape as
 *  [[com.tibiabot.highscores.HighscoreAnnouncement.line]]'s `skillIcon`. */
object StatisticsEmbeds {

  /** Deep blue — a daily digest, not an event. Told apart at a glance from the
   *  brand colour of an ordinary reply and from the allegiance colours the
   *  deaths and levels channels use, none of which mean anything here. */
  val StatisticsColor: Int = 2201331

  private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)

  def build(report: DailyReport, skillIcon: HighscoreCategory => String = _ => ""): MessageEmbed = {
    val embed = new EmbedBuilder()
    embed.setTitle(s":bar_chart: ${report.world} — ${report.saveDay.format(dayFormat)}", Urls.worldUrl(report.world))
    embed.setColor(StatisticsColor)
    embed.setDescription(description(report))

    report.loss.foreach { delta =>
      embed.addField(":skull: Biggest experience loss", lossLine(delta), false)
    }
    report.advance.foreach { event =>
      embed.addField(":trophy: Highest skill reached", advanceLine(event, skillIcon), false)
    }

    // Said once, in the one place a reader will look when a name they expected
    // is missing: the experience list is only a thousand deep, so a character
    // outside it has no figures at all rather than a figure of zero.
    embed.setFooter("Experience from the world's top 1,000 · the server save day named above")
    embed.build()
  }

  private def description(report: DailyReport): String =
    if (report.gains.isEmpty) "*Nobody in the top 1,000 gained experience.*"
    else {
      val lines = report.gains.zipWithIndex.map { case (delta, index) => gainLine(delta, index + 1) }
      (s"**:chart_with_upwards_trend: Top experience gained**" :: lines).mkString("\n")
    }

  private def gainLine(delta: ExperienceDelta, rank: Int): String =
    s"`${rank.toString.reverse.padTo(2, ' ').reverse}.` ${Emojis.vocEmoji(delta.vocation)} " +
      s"**[${delta.displayName}](${Urls.charUrl(delta.displayName)})** — " +
      s"**+${number(delta.gained)}** ${levels(delta)}"

  private def lossLine(delta: ExperienceDelta): String =
    s"${Emojis.vocEmoji(delta.vocation)} **[${delta.displayName}](${Urls.charUrl(delta.displayName)})** — " +
      s"**${number(delta.gained)}** ${levels(delta)}"

  /** The levels either side of the day, or just the one when it did not change —
   *  which is the common case, and "412 → 412" reads as a mistake. */
  private def levels(delta: ExperienceDelta): String =
    if (delta.level == delta.previousLevel) s"*(level ${delta.level})*"
    else s"*(level ${delta.previousLevel} → ${delta.level})*"

  /** The day's best advance, named by what it was.
   *
   *  Falls back to the stored slug for a category this build no longer knows,
   *  the same way [[com.tibiabot.highscores.HighscoreFeed]] tolerates one — an
   *  older row should read plainly rather than crash the day's post. */
  private def advanceLine(event: HighscoreEvent, skillIcon: HighscoreCategory => String): String = {
    val category = HighscoreCategory.fromSlug(event.category)
    val icon = category.map(skillIcon).filter(_.nonEmpty).map(_ + " ").getOrElse("")
    val reached = category.map(_.advancement(event.score)).getOrElse(s"${event.category} **${event.score}**")
    s"${Emojis.vocEmoji(event.vocation)} **[${event.displayName}](${Urls.charUrl(event.displayName)})** " +
      s"reached $icon$reached *(level ${event.level})*"
  }

  /** Thousands separators, and a sign that survives them.
   *
   *  `%,d` on a negative number puts the minus in front of the grouping, which
   *  is what is wanted — the loss line renders "-4,182,993" and needs no sign of
   *  its own, while the gain line adds its own "+". */
  private def number(value: Long): String = String.format(Locale.ENGLISH, "%,d", Long.box(value))
}
