package com.tibiabot.persistence

import java.time.Instant

/** Persistence for the raids-channel delivery: each guild's raids channel, and the
 *  dedup of raid stages already posted to a guild. Both live in `bot_cache`. */
trait ObserverRaidRepository {
  /** Set (or replace) a guild's raids channel. */
  def setChannel(guildId: String, channelId: String): Unit
  /** Remove a guild's raids channel. */
  def clearChannel(guildId: String): Unit
  def channelFor(guildId: String): Option[String]
  /** Every guild's raids channel, as (guildId, channelId). */
  def allChannels(): List[(String, String)]

  /** Record that this raid stage has been posted to this guild; returns true only
   *  the first time, so the caller posts once. */
  def markPostedIfNew(guildId: String, raidId: String, category: String): Boolean
  /** Drop dedup rows older than `cutoff` — raids are short-lived, so old rows are
   *  never needed again. */
  def prunePostedOlderThan(cutoff: Instant): Unit
}
