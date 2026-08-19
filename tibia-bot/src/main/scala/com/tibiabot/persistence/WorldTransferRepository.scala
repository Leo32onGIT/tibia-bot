package com.tibiabot.persistence

import com.tibiabot.domain.WorldTransfer

import java.time.ZonedDateTime

/** Persistence port for the per-guild `world_transfers` table — the record of
 *  which incoming transfers a discord's activity channel has already announced,
 *  so the same one is not posted on every poll. Keyed by guildId; callers pass
 *  `guild.getId` so JDA stays in BotApp. */
trait WorldTransferRepository {
  /** Every posted-transfer record for a guild (creating the table on first use). */
  def getTransfers(guildId: String): List[WorldTransfer]
  /** Record a transfer as posted (ON CONFLICT(name) DO UPDATE), so a later
   *  transfer by the same character replaces it rather than adding a row. */
  def record(guildId: String, name: String, formerWorlds: List[String], detectedAt: ZonedDateTime): Unit
  /** Drop the record filed under `name`. Used to clear the key a character's
   *  arrival was announced under once it has been moved onto the name they carry
   *  now — a rename leaves the old key behind, still suppressing a transfer for a
   *  character who no longer answers to it. */
  def remove(guildId: String, name: String): Unit
  /** Drop records older than `before`. Only safe well past the point where Tibia
   *  stops showing a former world (~180 days): until then the field is still there
   *  to be detected, and a record dropped early lets the same transfer be announced
   *  a second time. After it, the record has nothing left to suppress. */
  def removeExpired(guildId: String, before: ZonedDateTime): Unit
}
