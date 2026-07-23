package com.tibiabot.domain

import java.time.ZonedDateTime

/** One of a Patreon supporter's seats, assigned to a specific (guildId, world)
 *  pair by running `/setup` — see paywall.PaywallService. */
case class PatreonSeat(userId: String, guildId: String, world: String, created: ZonedDateTime)
