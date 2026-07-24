package com.tibiabot.domain

/** One member of the Patreon campaign, as returned by Patreon's own API
 *  (see patreonapi.PatreonApiClient) — distinct from [[PatreonSeat]], which
 *  is Discord-role-derived and drives the actual paywall gate. This is purely
 *  informational for the dashboard, keyed by Patreon's own member id (stable,
 *  unlike `fullName`, and present even when `discordUserId` isn't — a member
 *  who's never linked Discord to Patreon still gets synced, just without a
 *  Discord id to cross-reference against).
 *
 *  `discordUsername` is resolved separately from Patreon's own response (see
 *  BotApp.syncPatreonMembers) — Patreon only gives us the linked Discord id,
 *  not a display name — and is filled in once per sync (infrequent), not on
 *  every dashboard poll. */
case class PatreonMember(
  patreonMemberId: String,
  fullName: String,
  patronStatus: Option[String],
  pledgeCents: Int,
  discordUserId: Option[String],
  discordUsername: Option[String] = None
)
