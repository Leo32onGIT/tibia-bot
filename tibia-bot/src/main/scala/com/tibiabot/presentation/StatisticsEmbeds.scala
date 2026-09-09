package com.tibiabot.presentation

import com.tibiabot.domain.{ExperienceDelta, HighscoreEvent}
import com.tibiabot.statistics.{DailyReport, DayKillSummary}
import com.tibiabot.tibiadata.HighscoreCategory
import net.dv8tion.jda.api.entities.MessageEmbed

import java.time.format.DateTimeFormatter
import java.util.Locale

/** The first embed of the daily post: what the world did.
 *
 *  ==Why there are no fields==
 *  Everything is one description. A field caps at 1,024 characters and a ten-row
 *  leaderboard of linked names is already 1,200, but the deciding reason is the
 *  heading: only a description can hold `##` and `###`, and the date has to
 *  outrank its own sections. Fields also reflow differently on a phone, which
 *  this post no longer has to think about.
 *
 *  Long enough to need more than one embed on a busy day, so the body goes
 *  through [[EmbedPages]] rather than straight into a builder — a tenth gainer
 *  is never dropped to make the post fit.
 *
 *  ==Why the icons are arguments==
 *  Config-free, so a test of this file does not need a database host set — the
 *  same trap the `Panels` object documents. The vocation emoji come from
 *  [[Emojis]], which is a pure table, but the experience icons, the skill icon
 *  and the ally/enemy side icons are all configured strings and arrive from the
 *  caller. `sideIcon` is a function of the character's name because the answer is
 *  a fact about the *reading discord*, not about the world: two servers see the
 *  same board with different icons on it.
 */
object StatisticsEmbeds {

  /** Deep blue — a daily digest, not an event. Told apart at a glance from the
   *  brand colour of an ordinary reply and from the allegiance colours the
   *  deaths and levels channels use, none of which mean anything here. */
  val WorldColor: Int = 2201331

  /** The wiki file the thumbnail is drawn from, resolved through the same
   *  Special:Redirect builder every other creature image uses. */
  val ThumbnailFile: String = "Golden_Newspaper"

  private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)

  /** @param sideIcon   the ally/enemy icon for a character, by name; empty for
   *                    somebody this discord does not track
   *  @param skillIcon  the configured icon for a highscore category
   *  @param xpUp       rising and falling experience icons, used in place of a
   *                    sign so the direction reads before the number does
   *  @param thumbnail  the resolved image URL, or empty for no thumbnail
   */
  def build(
      report: DailyReport,
      sideIcon: String => String,
      skillIcon: HighscoreCategory => String,
      xpUp: String,
      xpDown: String,
      thumbnail: String
  ): List[MessageEmbed] = {
    val sections = List(
      Some(s"## :bar_chart: [${report.saveDay.format(dayFormat)}](${Urls.worldUrl(report.world)})"),
      Some(section("Top Experience Gained", gains(report, sideIcon, xpUp))),
      report.loss.map(delta =>
        section("Top Experience Lost", List(gainLine(delta, sideIcon, xpDown)))),
      report.advance.map(event =>
        section("Top Skill Advancement", List(advanceLine(event, sideIcon, skillIcon)))),
      report.kills.flatMap(killLines).map(section("Creature Stats", _))
    ).flatten

    EmbedPages.build(WorldColor, sections.mkString("\n"), thumbnail)
  }

  /** A section is its heading and its rows. Absent sections are dropped by the
   *  caller rather than printed empty, so a quiet day is short rather than a
   *  column of headings with nothing under them.
   *
   *  The label carries no emoji: the `##` title above it has one, and repeating
   *  the trick on every `###` under that turns a hierarchy into a row of badges. */
  private def section(title: String, rows: List[String]): String =
    (s"### $title" :: rows).mkString("\n")

  private def gains(report: DailyReport, sideIcon: String => String, xpUp: String): List[String] =
    if (report.gains.isEmpty) List("*Nobody in the top 1,000 gained experience.*")
    else report.gains.map(gainLine(_, sideIcon, xpUp))

  private def gainLine(delta: ExperienceDelta, sideIcon: String => String, icon: String): String =
    StatLines.cells(
      StatLines.who(delta.vocation, delta.displayName, sideIcon(delta.name)),
      StatLines.level(delta.level),
      s"$icon **${StatLines.number(delta.gained)}**")

  /** The day's best advance, named by what it was.
   *
   *  Falls back to the stored slug for a category this build no longer knows,
   *  the same way [[com.tibiabot.highscores.HighscoreFeed]] tolerates one — an
   *  older row should read plainly rather than crash the day's post. */
  private def advanceLine(event: HighscoreEvent, sideIcon: String => String,
                          skillIcon: HighscoreCategory => String): String = {
    val category = HighscoreCategory.fromSlug(event.category)
    val icon = category.map(skillIcon).filter(_.nonEmpty).map(_ + " ").getOrElse("")
    val reached = category.map(_.advancement(event.score)).getOrElse(s"${event.category} **${event.score}**")
    StatLines.cells(
      StatLines.who(event.vocation, event.displayName, sideIcon(event.name)),
      StatLines.level(event.level),
      s"$icon$reached")
  }

  /** The day's creature figures, count first.
   *
   *  A world can genuinely have a day where no creature killed a player, so each
   *  line is dropped on its own rather than the section being all-or-nothing. */
  private def killLines(kills: DayKillSummary): Option[List[String]] = {
    val lines = List(
      kills.mostKilled.map { case (race, count) => s"**${StatLines.number(count.toLong)}** $race killed" },
      kills.deadliest.map { case (race, count) =>
        s"**${StatLines.number(count.toLong)}** ${plural(count, "player", "players")} killed by $race" }
    ).flatten
    if (lines.isEmpty) None else Some(lines)
  }

  private def plural(count: Int, one: String, many: String): String = if (count == 1) one else many
}
