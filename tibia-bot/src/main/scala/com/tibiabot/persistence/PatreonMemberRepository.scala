package com.tibiabot.persistence

import com.tibiabot.domain.PatreonMember

import java.time.ZonedDateTime

/** Persistence port for the shared `patreon_members` table (the `bot_cache`
 *  database) — a point-in-time snapshot of the Patreon campaign's member
 *  list, refreshed periodically by patreonapi.PatreonApiClient. Purely
 *  informational for the dashboard; does not back the paywall's own
 *  Discord-role gate (see [[PatreonSeatRepository]] for that). */
trait PatreonMemberRepository {
  /** Upserts every member in the given sync, then deletes any row whose
   *  `synced_at` predates `syncedAt` — i.e. anyone not present in this run.
   *  A single call replaces the whole snapshot without a window where the
   *  table is empty (unlike a delete-then-insert). */
  def replaceSnapshot(members: List[PatreonMember], syncedAt: ZonedDateTime): Unit
  /** The current snapshot, for the dashboard's supporters panel. */
  def snapshot(): List[PatreonMember]
}
