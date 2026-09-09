package com.tibiabot.presentation

import com.tibiabot.Config
import com.tibiabot.tibiadata.HighscoreCategory

/** The emoji a skill advance carries in the Levels channel, one per highscore
 *  category that posts one.
 *
 *  Here rather than on [[com.tibiabot.tibiadata.HighscoreCategory]] itself, a domain enum
 *  in the `tibiadata` package and has no business knowing Discord markup, and
 *  rather than inside `HighscoreAnnouncement`, which is deliberately Config-free
 *  so the decision about *which* advances a server sees stays testable without
 *  loading a configuration. Same shape as [[GuildIcons.icon]]: a total match from
 *  a domain value onto a configured string.
 *
 *  Experience returns empty, and nothing is missing by that: it is recorded and
 *  never announced, because the online-list poll already posts that level-up
 *  about an hour before the sweep would see it.
 *
 *  These deliberately duplicate the vocation emoji in the three cases where they
 *  overlap — a knight advancing shielding reads with a shield at both ends of
 *  "advanced to", and likewise a paladin's bow and a monk's fist. The vocation
 *  emoji says who is advancing and this one says what advanced, so the repetition
 *  is two different facts that happen to share a picture. */
object SkillEmojis {

  def icon(category: HighscoreCategory): String = category match {
    case HighscoreCategory.SwordFighting    => Config.swordEmoji
    case HighscoreCategory.ClubFighting     => Config.clubEmoji
    case HighscoreCategory.AxeFighting      => Config.axeEmoji
    case HighscoreCategory.DistanceFighting => Config.bowEmoji
    case HighscoreCategory.Shielding        => Config.shieldEmoji
    case HighscoreCategory.FistFighting     => Config.fistEmoji
    case HighscoreCategory.MagicLevel       => Config.mlvlEmoji
    case HighscoreCategory.Experience       => ""
  }
}
