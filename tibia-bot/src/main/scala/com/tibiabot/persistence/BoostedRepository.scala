package com.tibiabot.persistence

import com.tibiabot.domain.BoostedStamp

/** Persistence port for boosted-boss/creature notification subscriptions
 *  (the `boosted_notifications` table in bot_cache), keyed by Discord userId. */
trait BoostedRepository {
  /** All subscriptions across all users, each carrying its owning bot. */
  def all(): List[BoostedStamp]
  /** A single user's subscriptions. */
  def forUser(userId: String): List[BoostedStamp]
  def subscribe(userId: String, name: String, boostedType: String): Unit
  def unsubscribe(userId: String, name: String): Unit
  def unsubscribeAll(userId: String): Unit

  /** Make `botId` the owner of every one of this user's subscriptions and clear
   *  their failure count — they've just been reached, whether by a delivered DM
   *  or by running a `/boosted` command on that bot. */
  def claim(userId: String, botId: String): Unit

  /** Count one failed delivery against the rows `botId` owns for this user, and
   *  return the new consecutive-failure count (0 if it owns none — an unclaimed
   *  row's failure means "wrong bot", which is not the user's problem and must
   *  not count against them). */
  def recordDeliveryFailure(userId: String, botId: String): Int

  /** Drop only the subscriptions `botId` owns for this user, leaving any another
   *  bot is successfully delivering. */
  def unsubscribeAllFor(userId: String, botId: String): Unit
}
