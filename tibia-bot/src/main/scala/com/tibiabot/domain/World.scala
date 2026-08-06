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
  onlineCombined: String
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
