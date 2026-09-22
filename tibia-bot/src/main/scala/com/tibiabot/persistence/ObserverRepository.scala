package com.tibiabot.persistence

import com.tibiabot.domain.{ObserverStatus, ObserverToken}

/** Persistence port for members' Tibia Observer links (the `observer_tokens`
 *  table in `bot_cache`), keyed by (guildId, userId).
 *
 *  The access token is stored encrypted; `tokenEncFor` returns the opaque blob
 *  for the observer client to decrypt, and nothing else here exposes it. */
trait ObserverRepository {
  /** Every stored link across all guilds — loaded once at startup into the cache. */
  def all(): List[ObserverToken]

  def forUser(guildId: String, userId: String): Option[ObserverToken]

  /** Store (or replace) a member's encrypted credential (the pending 5-char code
   *  while unverified, or the durable refresh token once linked). Re-adding resets
   *  it to the given status and clears the resolved world, since a new credential
   *  must be re-verified before it can claim one. */
  def upsert(guildId: String, userId: String, tokenEnc: String, status: ObserverStatus,
             accountLabel: Option[String], world: Option[String]): ObserverToken

  /** The stored encrypted token blob for a member, for the client to decrypt. */
  def tokenEncFor(guildId: String, userId: String): Option[String]

  /** Update health and (once verified) the resolved world. */
  def setStatus(id: Long, status: ObserverStatus, world: Option[String]): Unit

  /** Remove one member's link; true when a row actually went. */
  def delete(guildId: String, userId: String): Boolean

  def deleteGuild(guildId: String): Unit
  def deleteUser(guildId: String, userId: String): Unit
}
