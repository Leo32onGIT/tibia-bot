package com.tibiabot.persistence

import com.tibiabot.domain.PatreonMember

import java.time.ZonedDateTime

/** Persistence port for the shared `patreon_members` table (the `bot_cache`
 *  database) — a point-in-time snapshot of the Patreon campaign's member
 *  list, refreshed periodically by patreonapi.PatreonApiClient. Backs the
 *  dashboard's supporters panel and, via [[isActivePatron]], the paywall gate
 *  itself (see [[com.tibiabot.paywall.PaywallService.callerIsSubscribed]]) —
 *  so a sync that can't be trusted must not be written here; see
 *  BotApp.syncPatreonMembers. */
trait PatreonMemberRepository {
  /** Upserts every member in the given sync, then deletes any row whose
   *  `synced_at` predates `syncedAt` — i.e. anyone not present in this run.
   *  A single call replaces the whole snapshot without a window where the
   *  table is empty (unlike a delete-then-insert). Callers must never pass a
   *  list they aren't certain is complete: anyone missing from it loses their
   *  row, and with it their subscription. */
  def replaceSnapshot(members: List[PatreonMember], syncedAt: ZonedDateTime): Unit
  /** The current snapshot, for the dashboard's supporters panel. */
  def snapshot(): List[PatreonMember]
  /** Is this Discord account linked to a member Patreon currently reports as
   *  an active patron? A targeted query rather than a [[snapshot]] scan — the
   *  paywall gate calls this per user, including once per seat owner on every
   *  periodic sweep. Only `active_patron` counts: a `declined_patron` (a
   *  failed payment) reads as not subscribed, which starts the grace period
   *  rather than cutting anyone off, giving them time to fix their card. */
  def isActivePatron(discordUserId: String): Boolean
}
