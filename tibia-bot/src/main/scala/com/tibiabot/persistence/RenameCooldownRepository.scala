package com.tibiabot.persistence

import java.time.ZonedDateTime

/** Persistence port for the shared `rename_cooldowns` table (the `bot_cache`
 *  database) — lets TibiaBot.onlineListCategoryTimer survive a restart, so a
 *  channel renamed moments before a restart isn't treated as never-renamed
 *  and immediately eligible again. */
trait RenameCooldownRepository {
  /** Records that a rename was just dispatched for this channel/category id. */
  def recordRename(world: String, channelOrCategoryId: String, at: ZonedDateTime): Unit
  /** All known rename timestamps for a world's channels/categories. */
  def loadForWorld(world: String): Map[String, ZonedDateTime]
}
