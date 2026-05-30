package com.tibiabot.discord

import com.tibiabot.tracking.BoundedMessageQueue
import com.typesafe.scalalogging.StrictLogging

/**
 * Owns the outbound Discord message queue and drains it one item per tick so we
 * never exceed Discord's rate limits.
 *
 * Each queued item is a `dispatch` thunk that performs the actual JDA send, which
 * keeps this class free of JDA types and unit-testable. Scheduling is injected via
 * `startTicker`: it must run the supplied drain action immediately and then on a
 * fixed delay, returning a handle that stops the ticker. The drain loop is started
 * lazily on the first enqueue.
 *
 * The default capacity (`Int.MaxValue`) reproduces the previous unbounded behaviour
 * exactly; a finite capacity drops messages instead of leaking memory under a burst.
 */
final class RateLimitedSender(
  startTicker: (() => Unit) => (() => Unit),
  capacity: Int = Int.MaxValue
) extends StrictLogging {

  private val queue = new BoundedMessageQueue[() => Unit](capacity)
  private var stopTicker: Option[() => Unit] = None

  /** Queue a send and ensure the drain loop is running. Thread-safe. */
  def enqueue(dispatch: () => Unit): Unit = synchronized {
    if (!queue.enqueue(dispatch))
      logger.warn(s"Outbound message queue full (capacity $capacity); dropped ${queue.dropped} messages so far")
    if (stopTicker.isEmpty) stopTicker = Some(startTicker(() => drainOne()))
  }

  /** Send the next queued message, if any. Failures are logged, never propagated. */
  private[discord] def drainOne(): Unit = {
    val next = synchronized { queue.dequeueOption() }
    next.foreach { dispatch =>
      try dispatch()
      catch {
        case ex: Exception => logger.error(s"Failed to send queued message: ${ex.getMessage}")
        case _: Throwable => logger.error("Failed to send queued message")
      }
    }
  }
}
