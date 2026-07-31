package com.tibiabot.domain

import java.time.ZonedDateTime

/** The running grace timer for one (guildId, world) setup that currently has
 *  no active Patreon subscription behind it — either it was never tied to a
 *  seat (a legacy setup, grandfathered in before the seat system existed) or
 *  its seat's owner has lapsed. A row exists only while a setup is
 *  non-compliant: it appears the first sweep that notices, and is deleted the
 *  moment the setup is back in good standing — see paywall.PaywallService.
 *
 *  `started` is when the sweep first saw it non-compliant, and the clock the
 *  grace period counts from; it is never bumped by later sweeps, so the
 *  deadline can't drift forwards. `notified` records that the pause notice has
 *  already gone out, so a bot restart (which loses the in-memory active-status
 *  map) can't re-announce a pause that already happened. */
case class PatreonGrace(guildId: String, world: String, started: ZonedDateTime, notified: Boolean)
