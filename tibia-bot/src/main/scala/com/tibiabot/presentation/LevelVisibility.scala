package com.tibiabot.presentation

/** Pure decision for whether a level-up should be posted to the levels channel.
 *
 *  Mirrors the death-visibility rule but adds a minimum-level floor. The
 *  player's allegiance is resolved by the caller (the rendered guild icon is
 *  matched against the Config emoji lists, which can't live here without
 *  pulling in Config), so this takes the resolved category as three booleans.
 *  At most one is true; all false means an unrecognised category, gated by the
 *  level floor alone. */
object LevelVisibility {

  /** A category whose show-flag is "false" is suppressed; otherwise the level-up
   *  shows only when it reaches `minimumLevel`. */
  def shouldPost(
    isNeutral: Boolean, isAlly: Boolean, isEnemy: Boolean,
    showNeutral: String, showAllies: String, showEnemies: String,
    level: Int, minimumLevel: Int
  ): Boolean =
    if (isNeutral && showNeutral == "false") false
    else if (isAlly && showAllies == "false") false
    else if (isEnemy && showEnemies == "false") false
    else level >= minimumLevel
}
