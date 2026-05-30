package com.tibiabot.persistence

import com.tibiabot.domain.BoostedStamp

/** Persistence port for boosted-boss/creature notification subscriptions
 *  (the `boosted_notifications` table in bot_cache), keyed by Discord userId. */
trait BoostedRepository {
  /** All subscriptions across all users. */
  def all(): List[BoostedStamp]
  /** A single user's subscriptions. */
  def forUser(userId: String): List[BoostedStamp]
  def subscribe(userId: String, name: String, boostedType: String): Unit
  def unsubscribe(userId: String, name: String): Unit
  def unsubscribeAll(userId: String): Unit
}
