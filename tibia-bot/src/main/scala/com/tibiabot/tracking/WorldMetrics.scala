package com.tibiabot.tracking

import java.time.Instant

/** Point-in-time read of a world's metrics — see [[WorldMetrics]]. */
final case class WorldSnapshot(
  population: Int,
  lastPollAt: Option[Instant],
  nextPollAt: Option[Instant],
  deaths: Long,
  levels: Long,
  edits: Long
)

/** Per-world counters and poll timing for the monitoring dashboard. Population
 *  and poll timestamps are overwritten once per tick; deaths/levels/edits are
 *  simple counts since the last [[resetCounters]] call. Unlike
 *  [[com.tibiabot.discord.RateLimitedSender]]'s per-label stats (which reset on
 *  every read, safe there because there's exactly one reader), these counters
 *  are read far more often than they should reset — the dashboard may poll
 *  every few seconds — so resetting happens on a timer (the 15-minute window),
 *  not on read; the caller is expected to call [[resetCounters]] on a schedule. */
final class WorldMetrics {
  @volatile private var population: Int = 0
  @volatile private var lastPollAt: Option[Instant] = None
  @volatile private var nextPollAt: Option[Instant] = None
  private var deaths: Long = 0
  private var levels: Long = 0
  private var edits: Long = 0

  def recordPoll(currentPopulation: Int, polledAt: Instant, nextPollAt_ : Instant): Unit = {
    population = currentPopulation
    lastPollAt = Some(polledAt)
    nextPollAt = Some(nextPollAt_)
  }

  def incrementDeaths(): Unit = synchronized { deaths += 1 }
  def incrementLevels(): Unit = synchronized { levels += 1 }
  def incrementEdits(): Unit = synchronized { edits += 1 }

  def resetCounters(): Unit = synchronized {
    deaths = 0
    levels = 0
    edits = 0
  }

  def snapshot(): WorldSnapshot = synchronized {
    WorldSnapshot(population, lastPollAt, nextPollAt, deaths, levels, edits)
  }
}
