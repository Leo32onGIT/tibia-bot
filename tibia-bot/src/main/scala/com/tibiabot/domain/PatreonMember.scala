package com.tibiabot.domain

/** One member of the Patreon campaign, as returned by Patreon's own API
 *  (see patreonapi.PatreonApiClient) — distinct from [[PatreonSeat]], which
 *  records which (guild, world) a supporter has spent a seat on. This is what
 *  the paywall gate reads to decide whether someone is subscribed at all
 *  (`patronStatus` == "active_patron", matched on `discordUserId`), and what
 *  the dashboard's supporters panel renders. Keyed by Patreon's own member id
 *  (stable, unlike `fullName`, and present even when `discordUserId` isn't —
 *  a member who's never connected Discord to Patreon still gets synced, they
 *  just can't be matched to a Discord account, so they can't pass the gate).
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
