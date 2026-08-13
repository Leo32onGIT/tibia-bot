package com.tibiabot.persistence

import com.tibiabot.domain.{BountySub, MasslogSub}

import java.time.Instant

/** Storage for the mass-log and bounty DM subscriptions.
 *
 *  Both tables live in the shared `bot_cache` database rather than a guild's
 *  own, for the same reason the satchel and boosted-notification tables do:
 *  they are read on a timer for every guild at once, and a per-guild database
 *  would mean opening a connection per guild per sweep to answer a question that
 *  is almost always "nobody". The guild id is a column instead.
 */
trait NotifyRepository {

  /** Every subscription of both kinds. Read once at startup — the service keeps
   *  them in memory from there and writes through. */
  def allMasslog(): List[MasslogSub]
  def allBounty(): List[BountySub]

  /** Create or update this user's mass-log subscription for a world; there is
   *  only ever one, so re-pressing the button adjusts the threshold. Returns the
   *  stored row. */
  def upsertMasslog(guildId: String, world: String, userId: String, threshold: Int): MasslogSub

  /** Add a bounty, or update the cooldown on one already held for that
   *  character. Returns the stored row. */
  def upsertBounty(guildId: String, world: String, userId: String, character: String, cooldownMinutes: Int): BountySub

  def masslogById(id: Long): Option[MasslogSub]
  def bountyById(id: Long): Option[BountySub]

  def setMasslogEnabled(id: Long, enabled: Boolean): Unit
  def setBountyEnabled(id: Long, enabled: Boolean): Unit

  def muteMasslog(id: Long, until: Instant): Unit
  def muteBounty(id: Long, until: Instant): Unit

  def setMasslogThreshold(id: Long, threshold: Int): Unit

  def markMasslogNotified(id: Long, at: Instant): Unit
  def markBountyNotified(id: Long, at: Instant): Unit

  /** Drop everything for a guild — called when a world or the whole guild goes
   *  away. Guild-scoped rows in a shared database have no other way of being
   *  cleaned up, since dropping the guild's database doesn't touch them. */
  def deleteGuild(guildId: String): Unit
  def deleteWorld(guildId: String, world: String): Unit
}
