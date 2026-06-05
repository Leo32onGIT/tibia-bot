package com.tibiabot.persistence

import com.tibiabot.domain.SatchelStamp

import java.time.ZonedDateTime

/** Persistence port for Galthen's Satchel cooldown stamps (the `satchel` table
 *  in the `bot_cache` database). */
trait GalthenRepository {
  /** All stamps for a user (creating the table on first use). */
  def getStamps(userId: String): Option[List[SatchelStamp]]
  /** Insert or update the stamp for (user, tag). */
  def add(user: String, when: ZonedDateTime, tag: String): Unit
  /** Delete the stamp for (user, tag). */
  def del(user: String, tag: String): Unit
  /** Delete all stamps for a user. */
  def delAll(user: String): Unit
}
