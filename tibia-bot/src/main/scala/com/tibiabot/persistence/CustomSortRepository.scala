package com.tibiabot.persistence

import com.tibiabot.domain.CustomSort

/** Persistence port for the per-guild `online_list_categories` table (custom
 *  online-list sort categories). Keyed by guildId.
 *
 *  Read-only: the command that wrote these rows was never registered with
 *  Discord, so it and its service were removed. Rows a guild already has are
 *  still loaded at boot and still sort its online list — see BotApp's
 *  customSortConfig and TibiaBot's online-list build. */
trait CustomSortRepository {
  /** All categories (creating the table on first use). */
  def getAll(guildId: String): List[CustomSort]
}
