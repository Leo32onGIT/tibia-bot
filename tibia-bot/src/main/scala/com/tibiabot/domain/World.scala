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
