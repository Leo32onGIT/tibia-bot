package com.tibiabot.highscores

import scala.concurrent.duration.FiniteDuration

/** How far apart to place the sweep's requests.
 *
 *  A snapshot's work is the same every hour and known before any of it starts:
 *  worlds × lists × 20 pages. Firing it at rollover would put several thousand
 *  tibia.com page loads into a couple of minutes from one IP, which is the one
 *  behaviour most likely to earn a Cloudflare challenge and take the boosted
 *  feed and a neighbouring droplet down with it. So the whole set is spread
 *  across most of the snapshot's life instead, and the sweep runs at a walk. */
object HighscorePace {

  /** The gap one lane leaves between its own requests.
   *
   *  `workers` lanes each sleeping this long gives an aggregate rate of
   *  `workers / gap`, so the arithmetic is the window divided by the requests
   *  each lane has to make. `minGap` is a floor for the case the arithmetic
   *  makes it silly — a handful of tracked worlds should still be a walk, not
   *  a burst that happens to fit.
   *
   *  Nothing to do (no requests) yields the floor rather than an infinity. */
  def perRequestGap(requests: Int, window: FiniteDuration, workers: Int, minGap: FiniteDuration): FiniteDuration = {
    val lanes = math.max(1, workers)
    if (requests <= 0) minGap
    else {
      val spread = (window * lanes.toLong) / requests.toLong
      if (spread > minGap) spread else minGap
    }
  }

  /** Total page requests a snapshot costs. */
  def requestsFor(worlds: Int, lists: Int, pagesPerList: Int): Int = worlds * lists * pagesPerList

  /** Roughly how long a sweep of `requests` will take at `gap`, for the log
   *  line that says whether it fits inside the snapshot. */
  def estimatedDuration(requests: Int, gap: FiniteDuration, workers: Int): FiniteDuration = {
    val lanes = math.max(1, workers)
    gap * math.ceil(math.max(0, requests).toDouble / lanes).toLong
  }
}
