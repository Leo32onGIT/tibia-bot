package com.tibiabot.persistence

import java.time.ZonedDateTime

/** Persistence port for the shared `patreon_seat_overrides` table (the
 *  `bot_cache` database, alongside `patreon_seats`) — a per-user adjustment
 *  (positive or negative) on top of the global `Config.Patreon.seatsPerUser`
 *  default, set by the dashboard's "grant extra seats" admin action. Only
 *  users with a non-default adjustment have a row here — see
 *  [[com.tibiabot.paywall.PaywallService.effectiveSeatLimit]]. */
trait PatreonSeatOverrideRepository {
  /** This user's seat-count adjustment, or 0 if they have none. */
  def extraSeatsFor(userId: String): Int
  /** Set (or replace) this user's adjustment. */
  def setExtraSeats(userId: String, extraSeats: Int, updated: ZonedDateTime): Unit
  /** Every user with a non-default adjustment, for a single bulk lookup
   *  rather than one query per supporter. */
  def allExtraSeats(): Map[String, Int]
}
