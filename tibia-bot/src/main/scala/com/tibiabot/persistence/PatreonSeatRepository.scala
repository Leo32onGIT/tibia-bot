package com.tibiabot.persistence

import com.tibiabot.domain.PatreonSeat

import java.time.ZonedDateTime

/** Persistence port for the shared `patreon_seats` table (the `bot_cache`
 *  database, not any guild's own database — a seat isn't tied to one
 *  discord). Each row is one Patreon supporter's seat, assigned to a
 *  (guildId, world) pair by a successful `/setup` — see
 *  [[com.tibiabot.paywall.PaywallService]]. */
trait PatreonSeatRepository {
  /** All seats currently owned by this user, across every guild. */
  def seatsForUser(userId: String): List[PatreonSeat]
  /** The seat assigned to this (guildId, world) pair, if any. */
  def seatFor(guildId: String, world: String): Option[PatreonSeat]
  /** Assign (or reassign, idempotently, if it already belongs to this user) a
   *  seat. `userName` is a snapshot of the caller's Discord username at this
   *  moment, for the lapse notice — see [[com.tibiabot.domain.PatreonSeat]]. */
  def assignSeat(userId: String, userName: String, guildId: String, world: String, created: ZonedDateTime): Unit
  /** Free the seat assigned to this (guildId, world) pair, if any — a no-op otherwise. */
  def releaseSeat(guildId: String, world: String): Unit
  /** Every seat, for the periodic subscription-status sweep. */
  def allSeats(): List[PatreonSeat]
}
