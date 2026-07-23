package com.tibiabot.domain

import java.time.ZonedDateTime

/** One of a Patreon supporter's seats, assigned to a specific (guildId, world)
 *  pair by running `/setup` — see paywall.PaywallService. `userName` is a
 *  snapshot of the owner's Discord username at assignment time (for the
 *  lapse notice), not kept in sync with later username changes. */
case class PatreonSeat(userId: String, userName: String, guildId: String, world: String, created: ZonedDateTime)
