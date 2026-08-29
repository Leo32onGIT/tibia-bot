package com.tibiabot.persistence

import com.tibiabot.domain.SatchelStamp

import java.time.ZonedDateTime

/** Persistence port for Galthen's Satchel cooldown stamps (the `satchel` table
 *  in the `bot_cache` database).
 *
 *  Several bots can share one `bot_cache`, so everything to do with delivering
 *  the expiry DM is scoped by bot identity the same way [[BoostedRepository]]
 *  is: a stamp is owned by one bot or still unclaimed, and no bot may drop
 *  another bot's stamps on the strength of its own failures. The plain CRUD
 *  below stays unscoped — it runs off a command the user just used, which is
 *  about their satchels rather than about any one bot's delivery.
 */
trait GalthenRepository {
  /** All stamps for a user (creating the table on first use). */
  def getStamps(userId: String): Option[List[SatchelStamp]]
  /** Insert or update the stamp for (user, tag). */
  def add(user: String, when: ZonedDateTime, tag: String): Unit
  /** Delete the stamp for (user, tag). */
  def del(user: String, tag: String): Unit
  /** Delete all stamps for a user. */
  def delAll(user: String): Unit

  /** Stamps expired as of `before` that this bot is the one to notify for: the
   *  ones it owns, plus any still unclaimed. */
  def expiredStamps(before: ZonedDateTime, botId: String): List[SatchelStamp]

  /** Clear the expired stamps this bot just notified for. Scoped the same way
   *  as [[expiredStamps]] so a bot can't delete rows out from under the bot
   *  that was going to DM them. */
  def deleteExpired(before: ZonedDateTime, botId: String): Unit

  /** A DM reached this user: take ownership of their stamps and clear their
   *  failure count. */
  def claim(userId: String, botId: String): Unit

  /** Count one undeliverable DM against this bot and return the running total.
   *
   *  Held per user rather than per stamp because an expiry DM deletes the very
   *  row it was sent for — a counter living on that row could never reach a
   *  second failure. Returns 0 when this bot owns none of the user's stamps,
   *  so failing at someone another bot serves costs them nothing. */
  def recordDeliveryFailure(userId: String, botId: String): Int

  /** Give up on this user as far as this bot is concerned: drop the stamps it
   *  owns for them, and the failure count that got them here. */
  def forget(userId: String, botId: String): Unit
}
