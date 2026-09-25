package com.tibiabot.presentation

import com.tibiabot.domain.{ExperienceDelta, HighscoreEvent}
import com.tibiabot.presentation.StatisticsCard.{Part, section}
import com.tibiabot.statistics.DailyReport
import com.tibiabot.tibiadata.HighscoreCategory

import java.time.format.DateTimeFormatter
import java.util.Locale

/** The first card of the daily post, what the world did, and the kill
 *  statistics card further down. Cards since 26 Sep 2026, embeds before, which
 *  is where the name comes from — see [[StatisticsCard]] for the shape.
 *
 *  The date is the card's `##` title so it outranks its own sections, which are
 *  small-caps labels under it.
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

  /** Ally green, the same the activity channel gives an allied guild. The board
   *  is the world doing well — experience gained, a skill reached — and the PVP
   *  card under it answers in the other half of the pair. */
  val WorldColor: Int = Embeds.AllyGreen

  /** The bot's yellow, which everywhere else means "this happened on its own
   *  rather than because somebody asked". The creature figures are the one part
   *  of the post that is purely the world getting on with it — no ally, no
   *  enemy, nobody's war — so they wear it. */
  val CreatureColor: Int = Embeds.AutomaticColor

  private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)

  /** The date on the post is the morning it is published, not the save day it
   *  reports — see [[DailyReport.postedDay]]. */
  private def heading(report: DailyReport, titleIcon: String): String =
    s"## $titleIcon [${report.postedDay.format(dayFormat)}](${Urls.topExperienceUrl(report.world)})"

  /** @param titleIcon the icon on the date heading — the animated newspaper,
   *                   which is why this card carries no picture: the same
   *                   picture twice, once of it moving and once of it still
   *  @param sideIcon  the ally/enemy icon for a character, by name; empty for
   *                   somebody this discord does not track
   *  @param skillIcon the configured icon for a highscore category
   *  @param xpUp      rising and falling experience icons, used in place of a
   *                   sign so the direction reads before the number does
   */
  def build(
      report: DailyReport,
      titleIcon: String,
      sideIcon: String => String,
      skillIcon: HighscoreCategory => String,
      xpUp: String,
      xpDown: String
  ): Part =
    Part(WorldColor, List(
      Some(heading(report, titleIcon)),
      Some(section("Top Experience Gained", gains(report, sideIcon, xpUp))),
      Option.when(report.losses.nonEmpty)(
        section("Top Experience Lost", report.losses.map(gainLine(_, sideIcon, xpDown)))),
      report.advance.map(event =>
        section("Top Skill Advancement", List(advanceLine(event, sideIcon, skillIcon))))
    ).flatten)

  /** The day's kill statistics, as their own card: what the world's creatures
   *  lost, and which special bosses died.
   *
   *  Titled like the other three cards, with the creatures and the special
   *  kills as its two sections (26 Sep 2026; until then Special Kills was a
   *  second title of its own, behind the gold emoji). Absent when both are
   *  empty.
   *
   *  No date on it: the board above it has already said which day this is.
   *
   *  No `killed` on the rows either. The title already says what the figures
   *  count, and repeating the verb on every row spends the width on the one word
   *  that never varies.
   *
   *  @param titleIcon   leads the title. Not the newspaper the board leads with:
   *                     a second newspaper reads as a second bulletin rather than
   *                     another part of one
   *  @param specialIcon the configured emoji for a special boss, by its key;
   *                     empty for one nothing is configured for, which renders as
   *                     no icon rather than a gap
   *  @param creatureTitle the wiki's page title for a name — asked for both
   *                     lists, by the reported race for a creature and by its
   *                     own title for a boss. It is the title rather than the
   *                     URL because the row needs both halves of it: the link,
   *                     and the wiki's spelling to print the race in. None for
   *                     anything unmatched, which prints unlinked rather than
   *                     differently; see [[CreatureWiki]] for why that is cheap
   */
  def creatureStats(report: DailyReport, titleIcon: String,
                    specialIcon: String => String,
                    creatureTitle: String => Option[String]): Option[Part] = {
    val creatures = report.topKills.map { row =>
      // Looked up by the race as reported; printed in the casing of whatever
      // page that matched, which is the only thing that knows whether the
      // apostrophe in this one is Mooh'Tah or Druid's.
      val title = creatureTitle(row.race)
      val shown = title.fold(Urls.titleCase(row.race))(CreatureWiki.casedLike(row.race, _))
      s"**${StatLines.number(row.killed.toLong)}** ${linked(shown, title)}"
    }
    val specials = report.specialKills.map { case (kill, count) =>
      val icon = specialIcon(kill.emoji)
      val lead = if (icon.isEmpty) "" else s"$icon "
      // A boss carries its own spelling, singular and plural, so nothing here
      // is derived — the title is looked up only for somewhere to link to.
      s"$lead**${StatLines.number(count.toLong)}** ${linked(kill.nameFor(count), creatureTitle(kill.name))}"
    }
    val sections = List(
      Option.when(creatures.nonEmpty)(section("Creatures", creatures)),
      Option.when(specials.nonEmpty)(section("Special Kills", specials))
    ).flatten

    Option.when(sections.nonEmpty)(Part(CreatureColor, s"## $titleIcon Kill Statistics" :: sections))
  }

  /** A name as the post prints it, linked to its wiki page where there is one.
   *
   *  Both lists go through here so a boss and a creature are dressed the same:
   *  the visible difference between the two sections should be the figure, not
   *  whether the name is a link.
   *
   *  @param shown what the row reads
   *  @param title the wiki page it matched, if it matched one */
  private def linked(shown: String, title: Option[String]): String =
    title.fold(shown)(page => s"[$shown](${CreatureWiki.urlForTitle(page)})")

  private def gains(report: DailyReport, sideIcon: String => String, xpUp: String): List[String] =
    if (report.gains.isEmpty) List("*Nobody in the top 1,000 gained experience.*")
    else report.gains.map(gainLine(_, sideIcon, xpUp))

  private def gainLine(delta: ExperienceDelta, sideIcon: String => String, icon: String): String =
    StatLines.cells(
      StatLines.who(delta.vocation, delta.displayName, sideIcon(delta.name), Some(delta.level)),
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
      StatLines.who(event.vocation, event.displayName, sideIcon(event.name), Some(event.level)),
      s"$icon$reached")
  }

}
