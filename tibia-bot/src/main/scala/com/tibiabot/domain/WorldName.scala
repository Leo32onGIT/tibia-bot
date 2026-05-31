package com.tibiabot.domain

/** Canonical ("formal") Tibia world name: lower-cased then first-letter
 *  upper-cased, so any casing of a single-word world resolves to the same form
 *  ("antica"/"ANTICA" -> "Antica"). This was the `world.toLowerCase.capitalize`
 *  idiom repeated across BotApp and the world-config repository. */
object WorldName {
  def formal(world: String): String = world.toLowerCase.capitalize
}
