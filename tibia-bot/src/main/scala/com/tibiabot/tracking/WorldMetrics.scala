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
  fansiteDeathDetections: Long,
  fansiteDeathLagAvgSeconds: Double,
  battleyeGreen: Boolean,
  pvpType: String
)

object WorldSnapshot {
  /** A world the dashboard asked about before any poll has reported one —
   *  every counter zero. Named rather than spelled out positionally at the
   *  call site so adding a field here can't quietly land in the wrong slot. */
  val empty: WorldSnapshot =
    WorldSnapshot(0, None, None, 0, 0, 0, 0, 0.0, 0, 0, 0.0, battleyeGreen = true, pvpType = "")
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
  private var fansiteDeathDetections: Long = 0
  private var fansiteDeathLagTotalSeconds: Long = 0

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
   *  Not folded into [[incrementDeaths]], which counts posts: the same death posts
   *  once per discord tracking the world, so `deaths` is weighted by audience.
   *  This is called once per death, as it clears the dedup, so the average is over
   *  deaths rather than posts.
   *
   *  `lagSeconds` runs from the death's own timestamp on the character sheet, so
   *  it includes delays nobody here controls — Tibia publishing it, TibiaData
   *  scraping it, the upstream cache holding the sheet. The absolute figure is
   *  therefore large and uninteresting; it is for comparison, since little but a
   *  change to fetch scheduling moves it. */
  def recordDeathDetected(lagSeconds: Long, fansiteBacked: Boolean = false): Unit = synchronized {
    deathDetections += 1
    deathLagTotalSeconds += lagSeconds
    if (lagSeconds > deathLagMaxSeconds) deathLagMaxSeconds = lagSeconds
    // Counted a second time for the subset the fansite budget was spent on, so
    // the two averages can be read against each other. That comparison is the
    // only evidence that the second source buys anything; without it the lane
    // is a cost with no measured benefit. `fansiteBacked` means the character
    // held a roster slot, not that a fansite answer actually won the race --
    // the looser question, but the one that matches what the budget decides.
    if (fansiteBacked) {
      fansiteDeathDetections += 1
      fansiteDeathLagTotalSeconds += lagSeconds
    }
  }

  def resetCounters(): Unit = synchronized {
    deaths = 0
    levels = 0
    edits = 0
    deathDetections = 0
    deathLagTotalSeconds = 0
    deathLagMaxSeconds = 0
    fansiteDeathDetections = 0
    fansiteDeathLagTotalSeconds = 0
  }

  def snapshot(): WorldSnapshot = synchronized {
    WorldSnapshot(
      population, lastPollAt, nextPollAt, deaths, levels, edits,
      deathDetections,
      if (deathDetections == 0) 0.0 else deathLagTotalSeconds.toDouble / deathDetections,
      deathLagMaxSeconds,
      fansiteDeathDetections,
      if (fansiteDeathDetections == 0) 0.0 else fansiteDeathLagTotalSeconds.toDouble / fansiteDeathDetections,
      battleyeGreen, pvpType)
  }
}
