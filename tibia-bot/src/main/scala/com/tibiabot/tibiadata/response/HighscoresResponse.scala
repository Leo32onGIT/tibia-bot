package com.tibiabot.tibiadata.response

/** One row of a highscore list.
 *
 *  `value` is whatever the category ranks on: the plain integer skill level for
 *  a skill category (no percentage — tibia.com does not publish one here), and
 *  total experience for the experience category. It is a Long because
 *  experience passes 1.7e11 at the top of an old world and would overflow Int.
 *
 *  `level` is the character level, present on every row whatever the category,
 *  which is what lets a skill advance be gated on `levels_min` without a
 *  second lookup.
 *
 *  `rank` is dense: ties share a rank, so the fiftieth row of page 20 can read
 *  rank 857 while still being the thousandth record. Never treat it as a
 *  position.
 *
 *  Deliberately narrower than the published schema — the loyalty category adds
 *  a `title`, and we never ask for loyalty. */
case class HighscoreEntry(
    rank: Int,
    name: String,
    vocation: String,
    world: String,
    level: Int,
    value: Long
)

/** Paging state. `total_records` is capped at 1000 and `total_pages` at 20 on
 *  every world tried — tibia.com's own limit, not TibiaData's, so it is a
 *  constant in practice rather than something to page past. See
 *  [[com.tibiabot.tibiadata.Highscores]]. */
case class HighscorePage(current_page: Int, total_pages: Int, total_records: Int)

/** `highscore_age` is how many minutes ago tibia.com last rebuilt this page,
 *  lifted from its own "Last Update: N minutes ago" line. It is global: every
 *  world and category read within the same minute reports the same age, so one
 *  probe request establishes the snapshot for the whole game. See
 *  [[com.tibiabot.tibiadata.HighscoreSnapshot]] for turning it into an instant. */
case class HighscoreData(
    world: String,
    category: String,
    vocation: String,
    highscore_age: Int,
    highscore_list: List[HighscoreEntry],
    highscore_page: HighscorePage
)

case class HighscoresResponse(highscores: HighscoreData, information: Information)
