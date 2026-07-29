package com.tibiabot.discord

/** Maps the online-list lane's current queue depth to how often each guild's
 *  online-list should be re-checked, so refresh frequency backs off under
 *  load instead of blindly enqueueing at a fixed cadence regardless of how
 *  backed up the lane already is. Thresholds picked from observed production
 *  behavior (healthy: near 0; saturated: 650-770) — expect to retune against
 *  the dashboard's live queueDepth reading after this ships.
 *
 *  The healthy tier is the one that sets the bot's baseline edit volume, and
 *  cadence is the only lever that moves it much: by the time a busy channel is
 *  refreshed, most of its embeds contain a roster change and have to be
 *  rewritten regardless of how the list is packed into messages, so edits/hour
 *  scale with refreshes/hour almost linearly. Raised 90s -> 120s on that basis
 *  (a third fewer refreshes, hence a third fewer edits) while staying frequent
 *  enough that the list still reads as live. */
object AdaptiveRefreshInterval {
  private val tiers: List[(Int, Int)] = List( // (maxQueueDepth, intervalSeconds)
    100 -> 120,
    400 -> 150,
    800 -> 300,
    Int.MaxValue -> 600
  )

  def intervalSeconds(queueDepth: Int): Int =
    tiers.collectFirst { case (maxDepth, seconds) if queueDepth <= maxDepth => seconds }.get
}
