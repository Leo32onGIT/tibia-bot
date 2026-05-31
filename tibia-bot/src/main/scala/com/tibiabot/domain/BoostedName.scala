package com.tibiabot.domain

/** Normalises a player-typed boss/creature name for matching against stored
 *  boosted subscriptions. Keeps letters, apostrophes, hyphens and whitespace (so
 *  names like "Yselda's" or "Mega-Magmaoid" survive), drops everything else
 *  (digits, punctuation), then trims and lowercases. Pure; see BoostedNameSpec. */
object BoostedName {
  def sanitize(raw: String): String =
    raw.replaceAll("[^a-zA-Z'\\-\\s]", "").trim.toLowerCase
}
