package com.tibiabot.presentation

/** Which deaths a channel shows, by the colour the death block gave them. The
 *  post itself is a card since 27 Sep 2026 — see [[DeathCard]]. No Config, no
 *  JDA gateway. */
object DeathEmbeds {

  // Death-embed allegiance colours, mirroring the embedColor assignments in the
  // death-processing block: neutral covers the default plus its situational
  // variants; enemy and ally each have a single colour.
  private val neutralDeathColors = Set(3092790, 14869218, 4540237, 14397256)
  private val enemyDeathColor = 36941
  private val allyDeathColor = 13773097

  /** Whether a death embed should be posted, given its allegiance colour and the
   *  channel's per-category show flags ("false" suppresses that category).
   *  Colours outside the three allegiance groups (e.g. the purple "notable"
   *  case) are always shown. */
  def shouldShow(embedColor: Int, showNeutralDeaths: String, showAlliesDeaths: String, showEnemiesDeaths: String): Boolean =
    if (neutralDeathColors.contains(embedColor)) showNeutralDeaths != "false"
    else if (embedColor == enemyDeathColor) showEnemiesDeaths != "false"
    else if (embedColor == allyDeathColor) showAlliesDeaths != "false"
    else true
}
