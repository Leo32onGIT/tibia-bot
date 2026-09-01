package com.tibiabot.discord

import com.tibiabot.tracking.BoundedMessageQueue
import com.typesafe.scalalogging.StrictLogging

/**
 * Owns the outbound Discord message queue and drains it one item per tick so we
 * never exceed Discord's rate limits.
 *
 * Each queued item is a `dispatch` thunk performing the actual JDA send, which
 * keeps this class free of JDA types and testable. Its `label` names the Discord
 * operation or the kind of post, purely for observability: it buckets the
 * queue-wait stats in [[snapshotAndReset]], whose worst bucket is the lane's avg
 * wait on the dashboard. It says nothing about how many Discord calls were made —
 * that is counted at the HTTP layer (see app.Bootstrap), since plenty of traffic
 * never passes through here. Scheduling is injected via `startTicker`, which must
 * run the drain immediately then on a fixed delay and return a stop handle; the
 * loop starts lazily on the first enqueue.
 *
 * An optional `key` identifies what a send targets. Enqueueing under a pending key
 * replaces the earlier item rather than queuing both, so a lane that cannot keep
 * up with "current state" traffic has its backlog bounded by distinct targets
 * rather than growing while every entry goes stale. Leave unset for one-off events
 * where every item is meaningful.
 *
 * An optional `group` names the resource a send competes for. With
 * `perGroupMinGapMs` set, the drain skips an item whose group was dispatched more
 * recently than that and takes the next eligible one, spreading a group's items
 * out. The pace above is bot-wide while Discord's limits on this traffic are
 * per-channel, so a channel's several messages leaving in one FIFO burst trip the
 * per-channel limit even at a fine bot-wide rate. Skipping is not a delay — the
 * slot goes to another group — so throughput is unchanged. 0 drains plain FIFO.
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
