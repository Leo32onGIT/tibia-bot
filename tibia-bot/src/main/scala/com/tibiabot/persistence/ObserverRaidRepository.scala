package com.tibiabot.persistence

import java.time.Instant

/** Persistence for the raids-channel delivery: each (guild, world) raids channel,
 *  and the dedup of raid stages already posted to a guild. Both live in `bot_cache`. */
trait ObserverRaidRepository {
  /** Set (or replace) a guild's raids channel for one world. */
  def setChannel(guildId: String, world: String, channelId: String): Unit
  /** Remove a guild's raids channel for one world (on `/remove <world>`). */
  def clearChannel(guildId: String, world: String): Unit
  /** Remove all of a guild's raids channels (on guild leave). */
  def clearGuild(guildId: String): Unit
  def channelFor(guildId: String, world: String): Option[String]
  /** Every guild's raids channel for a world, as (guildId, channelId) — the poller's
   *  fan-out set for that world. */
  def channelsForWorld(world: String): List[(String, String)]

  /** Record that this raid stage has been posted to this guild; returns true only
   *  the first time, so the caller posts once. */
  def markPostedIfNew(guildId: String, raidId: String, category: String): Boolean
  /** Drop dedup rows older than `cutoff` — raids are short-lived, so old rows are
   *  never needed again. */
  def prunePostedOlderThan(cutoff: Instant): Unit
}
