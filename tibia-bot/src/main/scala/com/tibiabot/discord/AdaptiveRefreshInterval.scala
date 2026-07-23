package com.tibiabot.discord

/** Maps the online-list lane's current queue depth to how often each guild's
 *  online-list should be re-checked, so refresh frequency backs off under
 *  load instead of blindly enqueueing at a fixed cadence regardless of how
 *  backed up the lane already is. Thresholds picked from observed production
 *  behavior (healthy: near 0; saturated: 650-770) — expect to retune against
 *  the dashboard's live queueDepth reading after this ships. */
object AdaptiveRefreshInterval {
  private val tiers: List[(Int, Int)] = List( // (maxQueueDepth, intervalSeconds)
    100 -> 90,
    400 -> 150,
    800 -> 300,
    Int.MaxValue -> 600
  )

  def intervalSeconds(queueDepth: Int): Int =
    tiers.collectFirst { case (maxDepth, seconds) if queueDepth <= maxDepth => seconds }.get
}
