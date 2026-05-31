package com.tibiabot.presentation

import com.tibiabot.Config

/** Pure classification of a character's relationship to a guild's allied /
 *  hunted lists, plus the Discord icon that represents it.
 *
 *  Extracted from the two byte-for-byte identical `guildIcon` matches in
 *  TibiaBot (the level-notification path and the online-list builder).
 *  `classify` is Config-free so the priority ordering can be unit-tested
 *  directly (pinned by GuildIconsSpec); `icon`/`guildIcon` are the thin
 *  production mapping onto the configured emojis.
 */
object GuildIcons {

  sealed trait Relation
  object Relation {
    case object AllyGuild extends Relation
    case object HuntedGuild extends Relation
    case object AllyPlayerNoGuild extends Relation
    case object AllyPlayerNeutralGuild extends Relation
    case object HuntedPlayerNoGuild extends Relation
    case object HuntedPlayerNeutralGuild extends Relation
    case object NeutralNoGuild extends Relation
    case object NeutralGuild extends Relation
  }

  /** Classify with the same priority order as the original match: an allied
   *  guild wins over everything, then a hunted guild, then a player-level ally,
   *  then a player-level hunted — and within each player tier the no-guild case
   *  ("") is distinguished from the neutral-guild case. */
  def classify(
    guildName: String,
    allyGuild: Boolean,
    huntedGuild: Boolean,
    allyPlayer: Boolean,
    huntedPlayer: Boolean
  ): Relation = {
    import Relation._
    (guildName, allyGuild, huntedGuild, allyPlayer, huntedPlayer) match {
      case (_, true, _, _, _)  => AllyGuild
      case (_, _, true, _, _)  => HuntedGuild
      case ("", _, _, true, _) => AllyPlayerNoGuild
      case (_, _, _, true, _)  => AllyPlayerNeutralGuild
      case ("", _, _, _, true) => HuntedPlayerNoGuild
      case (_, _, _, _, true)  => HuntedPlayerNeutralGuild
      case ("", _, _, _, _)    => NeutralNoGuild
      case _                   => NeutralGuild
    }
  }

  /** The configured Discord icon for a relation. */
  def icon(relation: Relation): String = {
    import Relation._
    relation match {
      case AllyGuild                => Config.allyGuild
      case HuntedGuild              => Config.enemyGuild
      case AllyPlayerNoGuild        => Config.ally
      case AllyPlayerNeutralGuild   => s"${Config.otherGuild}${Config.ally}"
      case HuntedPlayerNoGuild      => Config.enemy
      case HuntedPlayerNeutralGuild => s"${Config.otherGuild}${Config.enemy}"
      case NeutralNoGuild           => ""
      case NeutralGuild             => Config.otherGuild
    }
  }

  /** Classify and map to the configured icon in one call — the form the two
   *  TibiaBot call sites use. */
  def guildIcon(
    guildName: String,
    allyGuild: Boolean,
    huntedGuild: Boolean,
    allyPlayer: Boolean,
    huntedPlayer: Boolean
  ): String = icon(classify(guildName, allyGuild, huntedGuild, allyPlayer, huntedPlayer))

  // ---------------------------------------------------------------------------
  // List variant: the `/allies list` and `/hunted list` commands classify a row
  // differently depending on which list is being rendered (`arg`), so a guild's
  // ally/hunted status can cross with the list context. Extracted from the two
  // identical matches in BotApp (the cached-row path and the fetched-char path).
  // ---------------------------------------------------------------------------

  sealed trait ListRelation
  object ListRelation {
    case object AlliedGuild extends ListRelation                // allies list: their guild is allied
    case object AlliedPlayerInHuntedGuild extends ListRelation  // allies list: but their guild is hunted
    case object HuntedGuild extends ListRelation                // hunted list: their guild is hunted
    case object HuntedPlayerInAlliedGuild extends ListRelation  // hunted list: but their guild is allied
    case object HuntedPlayerNoGuild extends ListRelation
    case object AlliedPlayerNoGuild extends ListRelation
    case object HuntedPlayerNeutralGuild extends ListRelation
    case object AlliedPlayerNeutralGuild extends ListRelation
    case object Unclassified extends ListRelation               // arg is neither "allies" nor "hunted"
  }

  /** Classify a list row. `arg` is the list being rendered ("allies"/"hunted").
   *  Config-free; preserves the original match order exactly. */
  def classifyList(guildName: String, allyGuild: Boolean, huntedGuild: Boolean, arg: String): ListRelation = {
    import ListRelation._
    (guildName, allyGuild, huntedGuild, arg) match {
      case (_, true, _, "allies")  => AlliedGuild
      case (_, _, true, "allies")  => AlliedPlayerInHuntedGuild
      case (_, _, true, "hunted")  => HuntedGuild
      case (_, true, _, "hunted")  => HuntedPlayerInAlliedGuild
      case ("", _, _, "hunted")    => HuntedPlayerNoGuild
      case ("", _, _, "allies")    => AlliedPlayerNoGuild
      case (_, _, _, "hunted")     => HuntedPlayerNeutralGuild
      case (_, _, _, "allies")     => AlliedPlayerNeutralGuild
      case _                       => Unclassified
    }
  }

  /** The configured Discord icon for a list relation. */
  def listIcon(relation: ListRelation): String = {
    import ListRelation._
    relation match {
      case AlliedGuild               => Config.allyGuild
      case AlliedPlayerInHuntedGuild => s"${Config.enemyGuild}${Config.ally}"
      case HuntedGuild               => Config.enemyGuild
      case HuntedPlayerInAlliedGuild => s"${Config.allyGuild}${Config.enemy}"
      case HuntedPlayerNoGuild       => Config.enemy
      case AlliedPlayerNoGuild       => Config.ally
      case HuntedPlayerNeutralGuild  => s"${Config.otherGuild}${Config.enemy}"
      case AlliedPlayerNeutralGuild  => s"${Config.otherGuild}${Config.ally}"
      case Unclassified              => ""
    }
  }

  /** Classify a list row and map to the configured icon in one call — the form
   *  the two BotApp call sites use. */
  def listGuildIcon(guildName: String, allyGuild: Boolean, huntedGuild: Boolean, arg: String): String =
    listIcon(classifyList(guildName, allyGuild, huntedGuild, arg))
}
