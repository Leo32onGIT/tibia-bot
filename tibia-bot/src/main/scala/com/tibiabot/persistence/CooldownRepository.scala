package com.tibiabot.persistence

import com.tibiabot.domain.{CooldownKind, CooldownStamp}

import java.time.ZonedDateTime

/** Persistence port for collectible cooldown stamps (the `satchel` table in the
 *  `bot_cache` database — named for the only kind it held when it was created).
 *
 *  Several bots can share one `bot_cache`, so everything to do with delivering
 *  the expiry DM is scoped by bot identity the same way [[BoostedRepository]]
 *  is: a stamp is owned by one bot or still unclaimed, and no bot may drop
 *  another bot's stamps on the strength of its own failures. The plain CRUD
 *  below stays unscoped — it runs off a command the user just used, which is
 *  about their cooldowns rather than about any one bot's delivery.
 *
 *  The CRUD is scoped by [[com.tibiabot.domain.CooldownKind]] instead, because each
 *  kind is its own tracker with its own list and its own Clear All. Delivery is
 *  not: an inbox this bot can reach is reachable whatever expired, so [[claim]],
 *  [[recordDeliveryFailure]] and [[forget]] act on a user across both kinds.
 */
trait CooldownRepository {
  /** All of a user's stamps of one kind (creating the table on first use). */
  def getStamps(userId: String, kind: CooldownKind): Option[List[CooldownStamp]]
  /** Insert or update the stamp for (user, kind, tag). */
  def add(user: String, kind: CooldownKind, when: ZonedDateTime, tag: String): Unit
  /** Delete the stamp for (user, kind, tag). */
  def del(user: String, kind: CooldownKind, tag: String): Unit
  /** Delete all of a user's stamps of one kind, leaving the other kind alone. */
  def delAll(user: String, kind: CooldownKind): Unit

  /** Stamps of this kind expired as of `before` that this bot is the one to
   *  notify for: the ones it owns, plus any still unclaimed.
   *
   *  Taken a kind at a time because `before` is the caller's clock minus that
   *  kind's own duration, and the two durations differ. */
  def expiredStamps(kind: CooldownKind, before: ZonedDateTime, botId: String): List[CooldownStamp]

  /** Clear the expired stamps this bot just notified for. Scoped the same way
   *  as [[expiredStamps]] so a bot can't delete rows out from under the bot
   *  that was going to DM them. */
  def deleteExpired(kind: CooldownKind, before: ZonedDateTime, botId: String): Unit

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
   *  owns for them — of either kind, since what failed was the inbox — and the
   *  failure count that got them here. */
  def forget(userId: String, botId: String): Unit
}
