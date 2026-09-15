package com.tibiabot.highscores

import scala.concurrent.duration._

/** How far apart to place the sweep's requests.
 *
 *  A snapshot's work is the same every hour and known before any of it starts:
 *  worlds × lists × 20 pages. Firing it at rollover would put several thousand
 *  tibia.com page loads into a couple of minutes from one IP, which is the one
 *  behaviour most likely to earn a Cloudflare challenge and take the boosted
 *  feed and a neighbouring droplet down with it. So the whole set is spread
 *  across most of the snapshot's life instead, and the sweep runs at a walk. */
object HighscorePace {

  /** What a page costs beyond the sleep taken before it, until a sweep has
   *  measured the real figure.
   *
   *  A seed and nothing more: [[observedLatency]] replaces it from the first
   *  sweep that finishes, so a wrong value here costs one sweep's pacing rather
   *  than every sweep's. 400ms is what the fleet measured in September 2026,
   *  where a 68-world sweep took 72 minutes and the sleeps in it accounted for
   *  45 of them. */
  val SeedLatency: FiniteDuration = 400.millis

  /** The gap one lane leaves between its own requests.
   *
   *  A lane spends `gap + latency` on every page — the sleep is taken before
   *  the request rather than around it — so the budget per page is the window
   *  divided by the pages one lane must fetch, and the sleep is what is left of
   *  that once the request has been paid for. Leaving the request out is not a
   *  rounding error: at the fleet's own numbers it was more than half the
   *  budget, and the sweep ran 27 minutes past the window it was sized for,
   *  drifting later every hour until it began missing whole snapshots.
   *
   *  `minGap` is a floor for the case the arithmetic makes it silly — a handful
   *  of tracked worlds should still be a walk, not a burst that happens to fit.
   *  It is also what keeps the latency term from backfiring when the upstream
   *  slows down: the gap is pinned there rather than going negative, and since
   *  the real rate is `workers / (gap + latency)`, a slow upstream still makes
   *  this sweep slower rather than turning it into a hammer on something that
   *  is already struggling.
   *
   *  Nothing to do (no requests) yields the floor rather than an infinity. */
  def perRequestGap(
      requests: Int,
      window: FiniteDuration,
      workers: Int,
      minGap: FiniteDuration,
      latency: FiniteDuration
  ): FiniteDuration = {
    val lanes = math.max(1, workers)
    if (requests <= 0) minGap
    else {
      val budget = (window * lanes.toLong) / requests.toLong
      val spread = budget - latency
      if (spread > minGap) spread else minGap
    }
  }

  /** Total page requests a snapshot costs. */
  def requestsFor(worlds: Int, lists: Int, pagesPerList: Int): Int = worlds * lists * pagesPerList

  /** Roughly how long a sweep of `requests` will take, for the log line that
   *  says whether it fits inside the snapshot. Counts the request as well as
   *  the sleep before it, which together are what a lane spends on a page. */
  def estimatedDuration(
      requests: Int,
      gap: FiniteDuration,
      workers: Int,
      latency: FiniteDuration
  ): FiniteDuration = {
    val lanes = math.max(1, workers)
    (gap + latency) * math.ceil(math.max(0, requests).toDouble / lanes).toLong
  }

  /** What a page actually cost beyond its sleep, from a sweep that finished.
   *
   *  The figure [[perRequestGap]] needs, measured rather than configured,
   *  because it is a property of how far away tibia.com is today and not of
   *  this install. One lane spends `gap + latency` on every page it attempts,
   *  so the sweep divided by one lane's attempts is that sum, and the gap it
   *  ran at comes back out of it.
   *
   *  `attempted` is pages asked for, read and failed alike. The ones past the
   *  end of a list shorter than 20 pages are not asked for and cost neither a
   *  sleep nor a request, so counting them here would read as a sweep that went
   *  faster than it did.
   *
   *  None when there is nothing to divide by. Never negative: a sweep that
   *  somehow beat its own sleeps is a clock artefact, not a free request. */
  def observedLatency(
      took: FiniteDuration,
      attempted: Int,
      gap: FiniteDuration,
      workers: Int
  ): Option[FiniteDuration] = {
    val lanes = math.max(1, workers)
    val perLane = math.ceil(math.max(0, attempted).toDouble / lanes).toLong
    if (perLane <= 0) None
    else {
      val spent = took / perLane
      Some(if (spent > gap) spent - gap else Duration.Zero)
    }
  }
}
