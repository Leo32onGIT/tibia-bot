package com.tibiabot.persistence

import com.tibiabot.domain.DeathScreenshot

/** Persistence port for death-screenshot records (the `death_screenshots` table
 *  in each guild's own database). */
trait DeathScreenshotRepository {
  def store(guildId: String, world: String, characterName: String, deathTime: Long,
            screenshotUrl: String, addedBy: String, addedName: String, messageId: String): Unit

  def get(guildId: String, world: String, characterName: String, deathTime: Long): List[DeathScreenshot]

  /** Delete the matching screenshot only if `permitted(addedBy)` holds. The
   *  permission decision (e.g. owner-or-admin) is supplied by the caller so JDA
   *  stays out of persistence. Returns true if a row was deleted. */
  def deleteIfPermitted(guildId: String, characterName: String, deathTime: Long, screenshotUrl: String)
                       (permitted: String => Boolean): Boolean
}
