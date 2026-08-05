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
  /** Drop records older than `before`. Safe well past the point where Tibia stops
   *  showing a former world (~a month): once the field has cleared, the same
   *  transfer cannot be detected again, so the record has nothing left to suppress. */
  def removeExpired(guildId: String, before: ZonedDateTime): Unit
}
