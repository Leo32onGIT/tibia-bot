package com.tibiabot.persistence

import com.tibiabot.domain.PatreonGrace

import java.time.ZonedDateTime

/** Persistence port for the shared `patreon_grace` table (the `bot_cache`
 *  database, alongside `patreon_seats` — a grace timer isn't tied to one
 *  guild's own database any more than the seat it's waiting on is). One row
 *  per (guildId, world) setup currently running without an active
 *  subscription behind it; see [[com.tibiabot.domain.PatreonGrace]] and
 *  [[com.tibiabot.paywall.PaywallService.applyRefresh]]. */
trait PatreonGraceRepository {
  /** Start the clock for this setup, if it isn't already running — an
   *  existing row is left exactly as it is, `started` and `notified`
   *  included. Every sweep calls this for every non-compliant setup, so it
   *  has to be the no-op-on-conflict shape: bumping `started` here would
   *  push the deadline forward forever and the grace period would never
   *  expire. */
  def beginGrace(guildId: String, world: String, started: ZonedDateTime): Unit
  /** Record that the pause notice for this setup has been sent. */
  def markNotified(guildId: String, world: String): Unit
  /** Stop the clock — the setup is back in good standing. A no-op if it
   *  wasn't running. */
  def clearGrace(guildId: String, world: String): Unit
  /** Every running timer, for a single bulk lookup per sweep rather than one
   *  query per configured (guild, world) pair. */
  def allGrace(): List[PatreonGrace]
}
