package com.tibiabot.presentation

import com.tibiabot.domain.{ExperienceDelta, FragTally, HighscoreEvent}
import com.tibiabot.statistics.{DailyReport, DayKillSummary}
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

  def build(
      report: DailyReport,
      frags: FragTally = FragTally.empty,
      skillIcon: HighscoreCategory => String = _ => ""
  ): MessageEmbed = {
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
    report.kills.flatMap(killLines).foreach { lines =>
      embed.addField(":crossed_swords: Around the world", lines, false)
    }
    // The one guild-scoped part of the post, and the only part two servers
    // watching the same world will see differently — which is the point of it.
    if (frags.nonEmpty) {
      embed.addField(":dagger: Frags", fragTotals(frags), false)
      fraggerField(frags.topAllied, ":green_circle: Top fraggers")
        .foreach(value => embed.addField(":green_circle: Top fraggers", value, true))
      fraggerField(frags.topEnemy, ":red_circle: Enemy fraggers")
        .foreach(value => embed.addField(":red_circle: Enemy fraggers", value, true))
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

  /** Both sides' losses, always both lines even when one is zero — "0 allies
   *  lost" is the good half of the news and dropping it would leave a reader
   *  wondering whether it was zero or unmeasured. */
  private def fragTotals(frags: FragTally): String =
    s"Enemies killed — **${number(frags.enemiesKilled.toLong)}**\n" +
      s"Allies lost — **${number(frags.alliesKilled.toLong)}**"

  /** One side's leaderboard, or None when nobody is on it.
   *
   *  Inline, so the two sides sit beside each other rather than one under the
   *  other — a reader compares them. Names are plain rather than linked: ten
   *  linked names is about 900 characters against a field's 1,024 cap, and these
   *  two are the fields most likely to be full. */
  private def fraggerField(fraggers: List[(String, Int)], label: String): Option[String] =
    if (fraggers.isEmpty) None
    else Some(fraggers.zipWithIndex.map { case ((name, count), index) =>
      s"`${(index + 1).toString.reverse.padTo(2, ' ').reverse}.` $name — **$count**"
    }.mkString("\n"))

  /** The day's kill statistics, or None when the snapshot said nothing worth a
   *  field.
   *
   *  A world can genuinely have a day where no creature killed a player, so each
   *  line is dropped on its own rather than the field being all-or-nothing. PvP
   *  deaths get a line of their own because the endpoint counts them as a race
   *  called "players" — miscategorised rather than uninteresting, and the two
   *  lines above deliberately exclude them. */
  private def killLines(kills: DayKillSummary): Option[String] = {
    val lines = List(
      kills.mostKilled.map { case (race, count) => s"Most killed — **$race** (${number(count.toLong)})" },
      kills.deadliest.map { case (race, count) =>
        s"Deadliest — **$race** (${number(count.toLong)} ${plural(count, "player", "players")})" },
      Option(kills.playerDeaths).filter(_ > 0)
        .map(count => s"Killed by other players — **${number(count.toLong)}**"),
      Option(kills.totalKilled).filter(_ > 0).map(total => s"Creatures killed in all — **${number(total)}**")
    ).flatten
    if (lines.isEmpty) None else Some(lines.mkString("\n"))
  }

  private def plural(count: Int, one: String, many: String): String = if (count == 1) one else many

  /** Thousands separators, and a sign that survives them.
   *
   *  `%,d` on a negative number puts the minus in front of the grouping, which
   *  is what is wanted — the loss line renders "-4,182,993" and needs no sign of
   *  its own, while the gain line adds its own "+". */
  private def number(value: Long): String = String.format(Locale.ENGLISH, "%,d", Long.box(value))
}
