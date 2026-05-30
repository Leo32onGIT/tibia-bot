package com.tibiabot.persistence

import com.tibiabot.domain.CustomSort

/** Persistence port for the per-guild `online_list_categories` table (custom
 *  online-list sort categories). Keyed by guildId. */
trait CustomSortRepository {
  /** All categories (creating the table on first use). */
  def getAll(guildId: String): List[CustomSort]
  def add(guildId: String, entity: String, name: String, label: String, emoji: String): Unit
  def removeByNameEntity(guildId: String, entity: String, name: String): Unit
  def removeByLabel(guildId: String, label: String): Unit
}
