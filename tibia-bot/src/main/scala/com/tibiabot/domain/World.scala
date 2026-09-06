package com.tibiabot.domain

/** Per-world tracking configuration (one row of the per-guild `worlds` table). */
case class Worlds(name: String,
  alliesChannel: String,
  enemiesChannel: String,
  neutralsChannel: String,
  levelsChannel: String,
  deathsChannel: String,
  category: String,
  fullblessRole: String,
  nemesisRole: String,
  allyPkRole: String,
  masslogRole: String,
  /** Marks members subscribed to bounty login DMs on this world. */
  bountyRole: String,
  fullblessChannel: String,
  nemesisChannel: String,
  fullblessLevel: Int,
  showNeutralLevels: String,
  showNeutralDeaths: String,
  showAlliesLevels: String,
  showAlliesDeaths: String,
  showEnemiesLevels: String,
  showEnemiesDeaths: String,
  detectHunteds: String,
  levelsMin: Int,
  deathsMin: Int,
  exivaList: String,
  activityChannel: String,
  onlineCombined: String,
  /** Whether the activity channel carries events for characters in no tracked
   *  guild and on no tracked list — currently just a high-level stranger
   *  transferring in. On by default, like its show_neutral_ siblings: the level
   *  bar keeps the volume to a handful, and a server that does not want it has
   *  `/neutral activity hide`. */
  showNeutralActivity: String,
  /** Level floors for the online list, per tracked category. A character below
   *  its category's floor is left out of that list entirely.
   *
   *  Separate from `levelsMin`/`deathsMin`, which gate the levels and deaths
   *  *channels*: this one is about who is worth looking at in a roster, and the
   *  answer differs — a level 8 dying is worth a line, a level 8 sitting in the
   *  online list all day is the thing being hidden.
   *
   *  0 means no floor, which is what every world had before these existed. */
  onlineAlliesMin: Int = 0,
  onlineEnemiesMin: Int = 0,
  onlineNeutralsMin: Int = 0
)

case class CustomSort(entityType: String, name: String, label: String, emoji: String)
case class BossEntry(world: String, boss: String)

/** One read of the Dream Courts boss-of-the-day page.
 *
 *  `renderedDay` is the weekday the page says it is ("Today is Wednesday …").
 *  It matters because the page is served from Fandom's parser cache and
 *  regularly lags behind — the page itself carries a "purge the cache" link for
 *  exactly this — so the caller can compare it against the real game day and
 *  tell a fresh read from a stale one. None when the page didn't say. */
case class DreamScarSnapshot(renderedDay: Option[java.time.DayOfWeek], bosses: List[BossEntry])
