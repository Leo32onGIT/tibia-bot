package com.tibiabot.tracking

import java.time.Instant

/** Point-in-time read of a world's metrics — see [[WorldMetrics]]. */
final case class WorldSnapshot(
  population: Int,
  lastPollAt: Option[Instant],
  nextPollAt: Option[Instant],
  deaths: Long,
  levels: Long,
  edits: Long,
  deathDetections: Long,
  deathLagAvgSeconds: Double,
  deathLagMaxSeconds: Long,
  battleyeGreen: Boolean,
  pvpType: String
)

object WorldSnapshot {
  /** A world the dashboard asked about before any poll has reported one —
   *  every counter zero. Named rather than spelled out positionally at the
   *  call site so adding a field here can't quietly land in the wrong slot. */
  val empty: WorldSnapshot =
    WorldSnapshot(0, None, None, 0, 0, 0, 0, 0.0, 0, battleyeGreen = true, pvpType = "")
}

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
  // A world's BattlEye status and PvP type essentially never change once it
  // exists, but they're only known from the same poll response population
  // comes from, so they're overwritten alongside it rather than configured
  // separately.
  @volatile private var battleyeGreen: Boolean = true
  @volatile private var pvpType: String = ""
  private var deaths: Long = 0
  private var levels: Long = 0
  private var edits: Long = 0
  private var deathDetections: Long = 0
  private var deathLagTotalSeconds: Long = 0
  private var deathLagMaxSeconds: Long = 0

  def recordPoll(currentPopulation: Int, polledAt: Instant, nextPollAt_ : Instant, battleyeGreen_ : Boolean, pvpType_ : String): Unit = {
    population = currentPopulation
    lastPollAt = Some(polledAt)
    nextPollAt = Some(nextPollAt_)
    battleyeGreen = battleyeGreen_
    pvpType = pvpType_
  }

  def incrementDeaths(): Unit = synchronized { deaths += 1 }
  def incrementLevels(): Unit = synchronized { levels += 1 }
  def incrementEdits(): Unit = synchronized { edits += 1 }

  /** Record how far behind a death this bot was when it first *detected* it.
   *
   *  Deliberately not folded into [[incrementDeaths]], which counts posts, not
   *  deaths: the same death posts once per discord tracking this world, so
   *  `deaths` is already weighted by how many discords watch a world. This is
   *  called once per death, at the point it first clears the recent-deaths
   *  dedup, so the average is an average over deaths rather than over posts.
   *
   *  `lagSeconds` is measured from the death's own timestamp on the character
   *  sheet, so it includes delays nobody here controls — Tibia publishing the
   *  death, TibiaData scraping it, and the upstream cache holding the sheet.
   *  That makes the absolute figure fairly large and not very interesting; what
   *  it is for is comparison, since a change to how the bot schedules its
   *  character fetches moves this number and little else does. */
  def recordDeathDetected(lagSeconds: Long): Unit = synchronized {
    deathDetections += 1
    deathLagTotalSeconds += lagSeconds
    if (lagSeconds > deathLagMaxSeconds) deathLagMaxSeconds = lagSeconds
  }

  def resetCounters(): Unit = synchronized {
    deaths = 0
    levels = 0
    edits = 0
    deathDetections = 0
    deathLagTotalSeconds = 0
    deathLagMaxSeconds = 0
  }

  def snapshot(): WorldSnapshot = synchronized {
    WorldSnapshot(
      population, lastPollAt, nextPollAt, deaths, levels, edits,
      deathDetections,
      if (deathDetections == 0) 0.0 else deathLagTotalSeconds.toDouble / deathDetections,
      deathLagMaxSeconds,
      battleyeGreen, pvpType)
  }
}
