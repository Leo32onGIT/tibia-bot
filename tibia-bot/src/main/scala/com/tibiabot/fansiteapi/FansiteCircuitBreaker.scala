package com.tibiabot.fansiteapi

import com.tibiabot.Config
import com.typesafe.scalalogging.StrictLogging

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** Stops calling the fansite API once it has told us to stop.
 *
 *  It sits behind Cloudflare, and a burst past what the edge tolerates earns a
 *  blanket '''403 against the whole IP''' — not a 429, not a per-request refusal.
 *  Every later call fails the same way, including the unauthenticated status
 *  endpoint, for as long as the edge decides.
 *
 *  Two things follow, both learned on a local smoke test that got the machine
 *  blocked inside four minutes:
 *
 *  1. '''Retrying is worse than useless.''' [[com.tibiabot.tibiadata.RetryPolicy]]
 *     reasons about transient failures, and a 403 is a standing instruction.
 *     Sending deepens the block, and the poll would send one per online character
 *     per cycle, forever.
 *
 *  2. '''Silence is the real danger.''' A 403 becomes a Left, which
 *     [[DualCharacterApi]] quietly covers with TibiaData — so the bot works
 *     perfectly while half its design is dead. Four minutes of a totally failed
 *     upstream produced not one log line. This logs the transition once, at WARN.
 *
 *  While open, calls are refused locally with no request made at all — both the
 *  point and free, since the caller already falls back on a Left.
 *
 *  Trips on the first 403 rather than a threshold: a block is not a flaky response
 *  to confirm by trying again, and by the time one arrives the damage is done — so
 *  stop immediately and find out later whether it has lifted. */
final class FansiteCircuitBreaker(
    openFor: java.time.Duration,
    now: () => Instant = () => Instant.now()
) extends StrictLogging {

  /** When the breaker stops refusing calls; EPOCH means closed. */
  private val openUntil = new AtomicReference[Instant](Instant.EPOCH)

  /** True when calls should be refused without being made. Closing again is a
   *  side effect of asking, so nothing has to run on a timer. */
  def isOpen: Boolean = {
    val until = openUntil.get()
    if (until == Instant.EPOCH) false
    else if (now().isBefore(until)) true
    else {
      if (openUntil.compareAndSet(until, Instant.EPOCH))
        logger.info(s"Fansite API circuit closed after ${openFor.getSeconds}s — resuming requests; the next failure reopens it")
      false
    }
  }

  /** Record a response that means "stop sending" — a 403 (an edge-level block)
   *  or a 429. Logs only on the transition, never per suppressed call, so a
   *  block does not become its own log flood. */
  def recordBlocked(status: Int): Unit = {
    val until = now().plus(openFor)
    val previous = openUntil.getAndSet(until)
    if (previous == Instant.EPOCH || !now().isBefore(previous))
      logger.warn(
        s"Fansite API returned $status — treating it as an edge-level block and pausing all requests to it for " +
          s"${openFor.getSeconds}s. TibiaData covers character fetches meanwhile, so deaths keep being detected, " +
          "but the second source is contributing nothing until this clears.")
  }

  /** Statuses that mean "stop sending" rather than "this request failed".
   *  401 is deliberately absent: a bad token is a configuration problem that
   *  pausing cannot fix, and it should keep surfacing rather than be hidden
   *  behind a breaker. */
  def blocks(status: Int): Boolean = status == 403 || status == 429

  /** When the breaker reopens, or `None` while it is closed. Read by the
   *  dashboard as well as by tests: a source that has gone silent behind a
   *  block is exactly the state worth being able to see without reading logs. */
  def openUntilInstant: Option[Instant] =
    Option(openUntil.get()).filterNot(_ == Instant.EPOCH)
}

object FansiteCircuitBreaker {

  /** One breaker for the process, because what trips it is one IP being
   *  blocked rather than one world being unlucky.
   *
   *  Per-instance would mean every world stack has to learn the block for
   *  itself -- on a fleet that is dozens more requests into an edge that has
   *  already said stop, each one deepening it, and dozens of separate WARN
   *  lines for a single event. */
  lazy val shared: FansiteCircuitBreaker = new FansiteCircuitBreaker(Config.FansiteApi.circuitOpenFor)
}
