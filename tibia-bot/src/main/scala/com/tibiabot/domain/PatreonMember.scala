package com.tibiabot.domain

/** One member of the Patreon campaign as returned by Patreon's API (see
 *  patreonapi.PatreonApiClient) — distinct from [[PatreonSeat]], which records
 *  which (guild, world) a supporter spent a seat on. What the paywall gate reads
 *  to decide whether somebody is subscribed at all, and what the dashboard's
 *  supporters panel renders.
 *
 *  Keyed by Patreon's member id: stable unlike `fullName`, and present even
 *  without `discordUserId` — a member who never connected Discord still syncs,
 *  they simply cannot be matched to an account and so cannot pass the gate.
 *
 *  `discordUsername` is resolved separately (see BotApp.syncPatreonMembers), since
 *  Patreon gives only the linked id, and is filled once per sync rather than on
 *  every dashboard poll. */
case class PatreonMember(
  patreonMemberId: String,
  fullName: String,
  patronStatus: Option[String],
  pledgeCents: Int,
  discordUserId: Option[String],
  discordUsername: Option[String] = None
)
