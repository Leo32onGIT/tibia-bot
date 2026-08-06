package com.tibiabot.discord

import com.tibiabot.tracking.BoundedMessageQueue
import com.typesafe.scalalogging.StrictLogging

/**
 * Owns the outbound Discord message queue and drains it one item per tick so we
 * never exceed Discord's rate limits.
 *
 * Each queued item is a `dispatch` thunk that performs the actual JDA send, which
 * keeps this class free of JDA types and unit-testable, tagged with a `label`
 * naming the Discord operation ("editmessage", "editchannel", "send") or the
 * kind of post it is ("activity", "admin", "level-up"), purely for
 * observability — it buckets the queue-wait stats in [[snapshotAndReset]], and
 * the worst of those buckets is what the dashboard shows as a lane's avg wait.
 * It says nothing about how many Discord calls were made; that is counted at
 * the HTTP layer instead (see app.Bootstrap), since plenty of this bot's
 * traffic never passes through here at all.
 * Scheduling is injected via `startTicker`: it must run the
 * supplied drain action immediately and then on a fixed delay, returning a handle
 * that stops the ticker. The drain loop is started lazily on the first enqueue.
 *
 * An optional `key` identifies what a send targets (e.g. a specific channel/message):
 * enqueueing under a key that's still pending replaces the earlier one instead of
 * queuing both, so a lane whose pace can't keep up with demand for "current state"
 * traffic (online-list content, renames) has its backlog bounded by the number of
 * distinct targets rather than growing without bound while every entry goes stale.
 * Leave `key` unset for one-off events (a notification, an alert) where every
 * enqueued item is meaningful and none should be silently replaced.
 *
 * An optional `group` names the resource a send competes for (e.g. the channel
 * it targets). With `perGroupMinGapMs` set, the drain skips past any item whose
 * group was dispatched less than that long ago and takes the next eligible one
 * instead, so a single group's items are spread out rather than draining
 * back-to-back. This matters because the pace above is bot-wide while Discord's
 * tighter limits on this traffic are per-channel: a channel whose online list
 * packs into several messages enqueues them all at once, and in strict FIFO
 * they leave in one tight burst that trips that per-channel limit even though
 * the bot-wide rate is fine. Skipping is not a delay — the slot goes to another
 * group's work, so total throughput is unchanged. Leave at 0 to drain in plain
 * FIFO order.
 *
 * The default capacity (`Int.MaxValue`) reproduces the previous unbounded behaviour
 * exactly; a finite capacity drops messages instead of leaking memory under a burst.
 */
final class RateLimitedSender(
  startTicker: (() => Unit) => (() => Unit),
  capacity: Int = Int.MaxValue,
  perGroupMinGapMs: Long = 0,
  now: () => Long = () => System.currentTimeMillis()
) extends StrictLogging {

  private case class Queued(label: String, key: Option[String], group: Option[String], enqueuedAtMs: Long, dispatch: () => Unit)

  private val queue = new BoundedMessageQueue[Queued](capacity)
  private var stopTicker: Option[() => Unit] = None
  private var stats: Map[String, RateLimitedSender.LabelStats] = Map.empty
  // Only groups dispatched within the last `perGroupMinGapMs` are of interest,
  // and expired entries are pruned on every drain, so this stays about as large
  // as the number of groups reachable inside one gap window.
  private var lastDispatchAtMs: Map[String, Long] = Map.empty

  /** Queue a send under `label`, superseding any still-pending item with the
   *  same `key` and spaced out from other items sharing its `group` (see class
   *  doc), and ensure the drain loop is running. Thread-safe. */
  def enqueue(label: String, key: Option[String] = None, group: Option[String] = None)(dispatch: () => Unit): Unit = synchronized {
    if (!queue.enqueue(Queued(label, key, group, now(), dispatch), key))
      logger.warn(s"Outbound message queue full (capacity $capacity); dropped ${queue.dropped} messages so far")
    if (stopTicker.isEmpty) stopTicker = Some(startTicker(() => drainOne()))
  }

  /** Send the next eligible queued message, if any. Failures are logged, never
   *  propagated. A tick on which every reachable item is still inside its
   *  group's gap sends nothing and simply retries on the next one. */
  private[discord] def drainOne(): Unit = {
    val next = synchronized { dequeueEligible() }
    next.foreach { queued =>
      val waitMs = now() - queued.enqueuedAtMs
      synchronized {
        val prior = stats.getOrElse(queued.label, RateLimitedSender.LabelStats.empty)
        stats = stats.updated(queued.label, prior.record(waitMs))
      }
      try queued.dispatch()
      catch {
        case ex: Exception => logger.error(s"Failed to send queued message (${queued.label}): ${ex.getMessage}")
        case _: Throwable => logger.error(s"Failed to send queued message (${queued.label})")
      }
    }
  }

  /** Take the next item that isn't still inside its group's gap, and record the
   *  dispatch time of whatever group it belongs to. Caller must hold the lock. */
  private def dequeueEligible(): Option[Queued] =
    if (perGroupMinGapMs <= 0) queue.dequeueOption()
    else {
      val nowMs = now()
      lastDispatchAtMs = lastDispatchAtMs.filter { case (_, atMs) => nowMs - atMs < perGroupMinGapMs }
      val picked = queue.dequeueFirstOption(RateLimitedSender.MaxGroupScan) { queued =>
        queued.group.forall(g => !lastDispatchAtMs.contains(g))
      }
      picked.foreach(_.group.foreach(g => lastDispatchAtMs = lastDispatchAtMs.updated(g, nowMs)))
      picked
    }

  /** Current backlog depth (items waiting to be sent). */
  def queueDepth: Int = synchronized { queue.size }

  /** Cumulative messages dropped since this sender was created (only possible with a finite capacity). */
  def totalDropped: Long = synchronized { queue.dropped }

  /** Cumulative messages superseded (replaced by a newer enqueue under the same key) since creation. */
  def totalSuperseded: Long = synchronized { queue.superseded }

  /** Per-label send count + average queue wait time since the last call to this
   *  method (or since startup, the first time), then resets the window. Intended
   *  for a periodic monitoring log, not for correctness-sensitive code. */
  def snapshotAndReset(): Map[String, RateLimitedSender.LabelStats] = synchronized {
    val snapshot = stats
    stats = Map.empty
    snapshot
  }

  /** Same data as [[snapshotAndReset]] without clearing the window. For a
   *  reader that doesn't own the window (e.g. a status endpoint that may be
   *  polled far more often than the periodic log resets it) — reading this
   *  never disturbs [[snapshotAndReset]]'s own accounting. */
  def snapshot(): Map[String, RateLimitedSender.LabelStats] = synchronized { stats }
}

object RateLimitedSender {

  /** How far past the head a group-spaced drain will look for an eligible item.
   *  A blocked run at the head is at most the items queued for the handful of
   *  groups touched within one gap window, so this is generous; it exists only
   *  so a pathological backlog can't turn one tick into a full-queue walk while
   *  holding the lock. */
  private val MaxGroupScan = 256

  final case class LabelStats(count: Long, totalWaitMs: Long) {
    def record(waitMs: Long): LabelStats = LabelStats(count + 1, totalWaitMs + waitMs)
    def avgWaitMs: Double = if (count == 0) 0.0 else totalWaitMs.toDouble / count
  }
  object LabelStats {
    val empty: LabelStats = LabelStats(0, 0)
  }
}
