package com.tibiabot.discord

/** Maps the online-list lane's current queue depth to how often each guild's
 *  online-list should be re-checked, so refresh frequency backs off under
 *  load instead of blindly enqueueing at a fixed cadence regardless of how
 *  backed up the lane already is.
 *
 *  Cadence is the only lever that moves this bot's edit volume much: by the time
 *  a busy channel is refreshed, most of its embeds contain a roster change and
 *  have to be rewritten regardless of how the list is packed into messages, so
 *  edits/hour scale with refreshes/hour almost linearly.
 *
 *  Thresholds come from observed production behaviour. Production settles at a
 *  depth of ~400, i.e. in the second tier — so that tier, not the healthy one,
 *  is what actually governs the bot's cadence, and it is the one to change to
 *  move real volume (raised 150s -> 200s on that basis).
 */
object AdaptiveRefreshInterval {
  private val tiers: List[(Int, Int)] = List( // (maxQueueDepth, intervalSeconds)
    100 -> 120,
    400 -> 200,
    800 -> 300,
    Int.MaxValue -> 600
  )

  /** How far below a tier's ceiling the depth must fall before that (faster)
   *  tier is adopted. Backing off is immediate; speeding up requires clearing
   *  this margin, so a lane parked on a boundary — which is exactly where
   *  production sits — holds one cadence instead of flapping between two.
   *  Users perceive that flapping as the list randomly going stale. */
  private val ReleaseMargin = 0.15

  /** The tier for this depth, ignoring hysteresis. */
  def intervalSeconds(queueDepth: Int): Int =
    tiers.collectFirst { case (maxDepth, seconds) if queueDepth <= maxDepth => seconds }.get

  /** The tier for this depth given the interval currently in force.
   *
   *  Asymmetric on purpose: a slower tier is adopted the moment the depth calls
   *  for it (the lane is congested, back off now), while a faster one is only
   *  adopted once the depth is `ReleaseMargin` clear of that tier's ceiling.
   *  Pass 0 (or any value at least as fast as every tier) to opt out — the
   *  natural tier is then returned, which is what the first call wants. */
  def intervalSeconds(queueDepth: Int, currentSeconds: Int): Int = {
    val natural = intervalSeconds(queueDepth)
    if (natural >= currentSeconds) natural
    else {
      val ceiling = tiers.collectFirst { case (maxDepth, seconds) if seconds == natural => maxDepth }.get
      val release = if (ceiling == Int.MaxValue) ceiling else ceiling - (ceiling * ReleaseMargin).toInt
      if (queueDepth <= release) natural else currentSeconds
    }
  }
}
