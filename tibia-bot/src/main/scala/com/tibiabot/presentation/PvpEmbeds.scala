package com.tibiabot.presentation

import com.tibiabot.domain.{ExperienceDelta, FragTally, Fragger, Repeat, TopKill}
import net.dv8tion.jda.api.entities.MessageEmbed

/** The second embed: the guild's own war.
 *
 *  The only guild-scoped part of the daily post. Everything above and below it
 *  is a fact about the world; this is a fact about who that discord decided to
 *  hunt, so two servers watching the same fighting get opposite versions of it
 *  and both are right.
 *
 *  ==The bar==
 *  One run split where the day was won, rather than two bars to compare. It
 *  answers "did we win today?" before the numbers underneath answer "by how
 *  much?", which is the order a reader actually wants them in. How much of it is
 *  coloured at all answers a third question — how big the day was — against a
 *  ceiling the caller sizes to the world.
 *
 *  ==Counts against kills==
 *  The two figures under the bar count *deaths* — a victim killed by eight
 *  people is one loss, not eight — while the fragger rows count kills per
 *  killer. They deliberately do not sum to each other, and both are the right
 *  answer to their own question.
 *
 *  Config-free for the reason [[StatisticsEmbeds]] documents; the bar emoji, the
 *  side icons, the vocation lookup and the falling experience icon all arrive
 *  from the caller.
 */
object PvpEmbeds {

  /** Enemy red, the same the activity channel gives a hunted guild — the other
   *  half of the pair the board above it opens with. */
  val PvpColor: Int = Embeds.EnemyRed

  /** @param barScale  how this world turns frags into a bar — what fills it, and
   *                   what an ordinary character on it is worth; see [[Bars.Scale]]
   *  @param vocationOf the vocation of a character, by lowercased name, from the
   *                   sheets the hunted and allied lists are drawn from; empty
   *                   for somebody nothing has recorded, which renders as no
   *                   icon rather than a guessed one
   *  @param jumpUrl builds a link back to a death from its stored message id,
   *                 or None when the death was never posted — the channel can be
   *                 off, the level under `deaths_min`, or the send have failed
   */
  def build(
      world: String,
      frags: FragTally,
      enemyLosses: List[ExperienceDelta],
      sideIcon: String => String,
      vocationOf: String => String,
      barEmoji: ((String, String)) => String,
      barScale: Bars.Scale,
      xpDown: String,
      jumpUrl: String => Option[String]
  ): List[MessageEmbed] = {
    val sections = List(
      Some(List(
        "## :dagger: PVP",
        Bars.split(
          Bars.weigh(frags.enemyLevels, frags.enemiesKilled, barScale.referenceLevel),
          Bars.weigh(frags.allyLevels, frags.alliesKilled, barScale.referenceLevel),
          barEmoji, barScale.ceiling),
        s"**${frags.enemiesKilled}** enemies killed vs **${frags.alliesKilled}** allies killed").mkString("\n")),
      if (frags.fraggers.isEmpty) None
      else Some(section("Most Kills", frags.fraggers.map(fraggerLine(_, sideIcon, vocationOf)))),
      if (frags.mostWanted.isEmpty) None
      else Some(section("Most Deaths", frags.mostWanted.map(repeatLine(_, sideIcon, vocationOf)))),
      if (enemyLosses.isEmpty) None
      else Some(section("Most Exp Lost", enemyLosses.map(lossLine(_, sideIcon, xpDown)))),
      frags.topEnemyKilled.map(kill =>
        section("Top Enemy Killed", killLines(kill, sideIcon, vocationOf, jumpUrl))),
      frags.topAllyKilled.map(kill =>
        section("Top Ally Killed", killLines(kill, sideIcon, vocationOf, jumpUrl)))
    ).flatten

    EmbedPages.build(PvpColor, sections.mkString("\n"))
  }

  private def section(title: String, rows: List[String]): String =
    (s"### $title" :: rows).mkString("\n")

  private def fraggerLine(row: Fragger, sideIcon: String => String, vocationOf: String => String): String =
    StatLines.cells(
      who(row.name, sideIcon, vocationOf),
      s"**${row.kills} ${plural(row.kills, "kill", "kills")}**")

  private def repeatLine(row: Repeat, sideIcon: String => String, vocationOf: String => String): String =
    StatLines.cells(
      who(row.name, sideIcon, vocationOf),
      StatLines.level(row.level),
      s"**${row.deaths} ${plural(row.deaths, "death", "deaths")}**")

  private def lossLine(delta: ExperienceDelta, sideIcon: String => String, icon: String): String =
    StatLines.cells(
      StatLines.who(delta.vocation, delta.displayName, sideIcon(delta.name)),
      StatLines.level(delta.level),
      s"$icon **${StatLines.number(delta.gained)}**")

  /** The kill itself, and under it the death it came from — as subtext, since the
   *  kill is the fact and the link is a way to go and look at it.
   *
   *  The link is dropped rather than rendered dead when the death was never
   *  posted, which leaves a line that still reads on its own. */
  private def killLines(kill: TopKill, sideIcon: String => String, vocationOf: String => String,
                        jumpUrl: String => Option[String]): List[String] = {
    val row = StatLines.cells(who(kill.name, sideIcon, vocationOf), StatLines.level(kill.level))
    row :: jumpUrl(kill.deathMessageId).map(url => s"-# [Jump to the death]($url)").toList
  }

  /** The frag tables keep names, not vocations — a killer is a name on a death
   *  message and nothing more — so both icons are looked up by that name here.
   *  Lowercased for the lookup, the same key every other name in this bot is
   *  matched on, while the row itself keeps the casing tibia.com showed. */
  private def who(name: String, sideIcon: String => String, vocationOf: String => String): String =
    StatLines.who(vocationOf(name.toLowerCase), name, sideIcon(name.toLowerCase))

  private def plural(count: Int, one: String, many: String): String = if (count == 1) one else many
}
