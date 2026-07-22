package com.tibiabot.discord

import com.tibiabot.tracking.BoundedMessageQueue
import com.typesafe.scalalogging.StrictLogging

/**
 * Owns the outbound Discord message queue and drains it one item per tick so we
 * never exceed Discord's rate limits.
 *
 * Each queued item is a `dispatch` thunk that performs the actual JDA send, which
 * keeps this class free of JDA types and unit-testable, tagged with a `label`
 * (e.g. "rename", "online-list", "level-up") purely for observability — see
 * [[snapshotAndReset]]. Scheduling is injected via `startTicker`: it must run the
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
 * The default capacity (`Int.MaxValue`) reproduces the previous unbounded behaviour
 * exactly; a finite capacity drops messages instead of leaking memory under a burst.
 */
final class RateLimitedSender(
  startTicker: (() => Unit) => (() => Unit),
  capacity: Int = Int.MaxValue
) extends StrictLogging {

  private case class Queued(label: String, key: Option[String], enqueuedAtMs: Long, dispatch: () => Unit)

  private val queue = new BoundedMessageQueue[Queued](capacity)
  private var stopTicker: Option[() => Unit] = None
  private var stats: Map[String, RateLimitedSender.LabelStats] = Map.empty
  private var supersededCount = 0L

  /** Queue a send under `label`, superseding any still-pending item with the
   *  same `key` (see class doc), and ensure the drain loop is running. Thread-safe. */
  def enqueue(label: String, key: Option[String] = None)(dispatch: () => Unit): Unit = synchronized {
    key.foreach { k =>
      val removed = queue.removeWhere(_.key.contains(k))
      supersededCount += removed
    }
    if (!queue.enqueue(Queued(label, key, System.currentTimeMillis(), dispatch)))
      logger.warn(s"Outbound message queue full (capacity $capacity); dropped ${queue.dropped} messages so far")
    if (stopTicker.isEmpty) stopTicker = Some(startTicker(() => drainOne()))
  }

  /** Send the next queued message, if any. Failures are logged, never propagated. */
  private[discord] def drainOne(): Unit = {
    val next = synchronized { queue.dequeueOption() }
    next.foreach { queued =>
      val waitMs = System.currentTimeMillis() - queued.enqueuedAtMs
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

  /** Current backlog depth (items waiting to be sent). */
  def queueDepth: Int = synchronized { queue.size }

  /** Cumulative messages dropped since this sender was created (only possible with a finite capacity). */
  def totalDropped: Long = synchronized { queue.dropped }

  /** Cumulative messages superseded (replaced by a newer enqueue under the same key) since creation. */
  def totalSuperseded: Long = synchronized { supersededCount }

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
  final case class LabelStats(count: Long, totalWaitMs: Long) {
    def record(waitMs: Long): LabelStats = LabelStats(count + 1, totalWaitMs + waitMs)
    def avgWaitMs: Double = if (count == 0) 0.0 else totalWaitMs.toDouble / count
  }
  object LabelStats {
    val empty: LabelStats = LabelStats(0, 0)
  }
}
