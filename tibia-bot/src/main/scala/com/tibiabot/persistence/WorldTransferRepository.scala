package com.tibiabot.persistence

import com.tibiabot.domain.WorldTransfer

import java.time.ZonedDateTime

/** Persistence port for the `world_transfers` table in the shared `bot_cache`
 *  database — the record of which incoming transfers have already been
 *  announced, so the same one is not posted on every poll.
 *
 *  Keyed by world, alongside the deaths and levels caches, because that is what
 *  the record is a fact about: a character arrived on a world once, and every
 *  discord tracking that world is looking at the same arrival. Keyed per-guild
 *  it used to mean each discord kept its own answer, so one adding a world for
 *  the first time started from nothing and announced every former-world flag
 *  Tibia still had set — a backlog up to six months deep. */
trait WorldTransferRepository {
  /** Every posted-transfer record for a world. */
  def getTransfers(world: String): List[WorldTransfer]
  /** Record a transfer as posted (ON CONFLICT(world, name) DO UPDATE), so a later
   *  transfer by the same character replaces it rather than adding a row. */
  def record(world: String, name: String, formerWorlds: List[String], detectedAt: ZonedDateTime): Unit
  /** Drop the record filed under `name`. Used to clear the key a character's
   *  arrival was announced under once it has been moved onto the name they carry
   *  now — a rename leaves the old key behind, still suppressing a transfer for a
   *  character who no longer answers to it. */
  def remove(world: String, name: String): Unit
  /** Drop a world's records older than `before`. Only safe well past the point
   *  where Tibia stops showing a former world (~180 days): until then the field is
   *  still there to be detected, and a record dropped early lets the same transfer
   *  be announced a second time. After it, the record has nothing left to
   *  suppress. */
  def removeExpired(world: String, before: ZonedDateTime): Unit
}
