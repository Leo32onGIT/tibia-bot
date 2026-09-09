package com.tibiabot.highscores

import com.tibiabot.domain.HighscoreEvent
import com.tibiabot.presentation.{Emojis, GuildIcons, LevelVisibility, Urls}
import com.tibiabot.tibiadata.HighscoreCategory

/** One discord's Levels channel for one world, reduced to what deciding a post
 *  actually needs.
 *
 *  Name sets are lowercased by the caller. Built from the same `worlds` row and
 *  allied/hunted lists the level-up path reads, so a skill advance obeys exactly
 *  the settings a server already set for level-ups — there is no second set of
 *  switches to discover. */
final case class HighscoreTarget(
    guildId: String,
    guildLabel: String,
    channelId: String,
    showNeutral: String,
    showAllies: String,
    showEnemies: String,
    minimumLevel: Int,
    alliedGuilds: Set[String],
    huntedGuilds: Set[String],
    alliedPlayers: Set[String],
    huntedPlayers: Set[String]
) {

  /** Whether knowing the character's guild could change this target's answer.
   *
   *  It cannot when the server tracks no guilds: the ally/enemy classification
   *  then rests on the player lists alone, which match on name. Worth asking,
   *  because the guild is the one thing a highscore row does not carry and the
   *  only way to learn it is to spend a request. */
  def needsGuild: Boolean = alliedGuilds.nonEmpty || huntedGuilds.nonEmpty
}

/** Whether an advance is announced to a target, and how it reads.
 *
 *  Pure and Config-free. The three visibility flags are decided from the
 *  classified relation directly rather than by matching the rendered emoji
 *  against lists of configured icons the way the level-up path does — same
 *  answer, without needing Config to reach a decision. */
object HighscoreAnnouncement {

  def relation(target: HighscoreTarget, event: HighscoreEvent, guildName: String): GuildIcons.Relation = {
    val guild = guildName.toLowerCase
    GuildIcons.classify(
      guildName = guildName,
      allyGuild = guild.nonEmpty && target.alliedGuilds.contains(guild),
      huntedGuild = guild.nonEmpty && target.huntedGuilds.contains(guild),
      allyPlayer = target.alliedPlayers.contains(event.name),
      huntedPlayer = target.huntedPlayers.contains(event.name)
    )
  }

  /** The same rule level-ups use: a category whose show-flag is off is
   *  suppressed, and everything else has to clear the world's minimum level. */
  def shouldPost(target: HighscoreTarget, event: HighscoreEvent, guildName: String): Boolean = {
    val category = relation(target, event, guildName)
    LevelVisibility.shouldPost(
      isNeutral = isNeutral(category),
      isAlly = isAlly(category),
      isEnemy = isEnemy(category),
      showNeutral = target.showNeutral,
      showAllies = target.showAllies,
      showEnemies = target.showEnemies,
      level = event.level,
      minimumLevel = target.minimumLevel
    )
  }

  /** One line, in the shape the Levels channel already uses for level-ups, so a
   *  reader sees one kind of message rather than two.
   *
   *  `skillIcon` names what advanced and sits where the level-up path puts its
   *  own — between "advanced to" and the figure — while `icon` stays at the end
   *  saying whether the character is an ally or an enemy. Passed in rather than
   *  looked up, like `icon` already is, so this object stays Config-free; see
   *  [[com.tibiabot.presentation.SkillEmojis]]. Empty renders as nothing, so a
   *  category with no emoji configured reads exactly as it did before. */
  def line(event: HighscoreEvent, category: HighscoreCategory, icon: String, skillIcon: String = ""): String =
    s"${Emojis.vocEmoji(event.vocation)} **[${event.displayName}](${Urls.charUrl(event.displayName)})** " +
      s"advanced to ${prefixed(skillIcon)}${category.advancement(event.score)} $icon"

  /** A separator only when there is something to separate, so an unconfigured
   *  emoji cannot leave a double space in the middle of the sentence. */
  private def prefixed(skillIcon: String): String =
    if (skillIcon.isEmpty) "" else s"$skillIcon "

  /** Every line this target should see from one batch of advances, in rank
   *  order. Empty when the target's settings suppress all of them. */
  def linesFor(
      target: HighscoreTarget,
      category: HighscoreCategory,
      advances: List[HighscoreEvent],
      guildOf: String => String,
      skillIcon: String = ""
  ): List[String] =
    advances.flatMap { event =>
      val guildName = guildOf(event.name)
      if (shouldPost(target, event, guildName))
        Some(line(event, category, GuildIcons.icon(relation(target, event, guildName)), skillIcon))
      else None
    }

  private def isAlly(relation: GuildIcons.Relation): Boolean = relation match {
    case GuildIcons.Relation.AllyGuild | GuildIcons.Relation.AllyPlayerNoGuild |
         GuildIcons.Relation.AllyPlayerNeutralGuild => true
    case _ => false
  }

  private def isEnemy(relation: GuildIcons.Relation): Boolean = relation match {
    case GuildIcons.Relation.HuntedGuild | GuildIcons.Relation.HuntedPlayerNoGuild |
         GuildIcons.Relation.HuntedPlayerNeutralGuild => true
    case _ => false
  }

  private def isNeutral(relation: GuildIcons.Relation): Boolean = relation match {
    case GuildIcons.Relation.NeutralNoGuild | GuildIcons.Relation.NeutralGuild => true
    case _ => false
  }
}
