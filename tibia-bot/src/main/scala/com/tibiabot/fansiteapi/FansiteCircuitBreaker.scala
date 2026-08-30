package com.tibiabot.fansiteapi

import com.typesafe.scalalogging.StrictLogging

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** Stops calling the fansite API once it has told us to stop.
 *
 *  This exists because of how the API says no. It sits behind Cloudflare, and
 *  when a burst crosses whatever the edge tolerates the answer is a blanket
 *  '''403 against the whole IP''' — not a 429, not a per-request refusal. Every
 *  subsequent call fails the same way, including to the unauthenticated status
 *  endpoint, and it stays that way for as long as the edge decides.
 *
 *  Two things follow, and both were learned the hard way on a local smoke test
 *  that got the machine blocked inside four minutes:
 *
 *  1. '''Retrying is worse than useless.''' [[com.tibiabot.tibiadata.RetryPolicy]]
 *     reasons about transient upstream failures, and a 403 is not one — it is a
 *     standing instruction. Continuing to send only deepens the block, and the
 *     character poll would send one per online character per cycle, forever.
 *
 *  2. '''Silence is the real danger.''' A 403 becomes a Left, which
 *     [[DualCharacterApi]] quietly covers with TibiaData — so the bot keeps
 *     working perfectly while half its design is dead, and nothing says so.
 *     That is exactly what happened: four minutes of a totally failed upstream
 *     produced not one log line. This logs the transition, once, at WARN.
 *
 *  While open, calls are refused locally without a request being made at all,
 *  which is both the point (stop sending) and free (the caller falls back to
 *  TibiaData on a Left, as it already does for any other failure).
 *
 *  Deliberately trips on the first 403 rather than after a threshold. A block
 *  is not a flaky response to be confirmed by trying again — by the time one
 *  arrives the damage is done, and the cheapest correct reaction is to stop
 *  immediately and find out later whether it has lifted. */
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
        logger.info(s"Fansite API circuit closed after ${openFor.toSeconds}s — resuming requests; the next failure reopens it")
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
          s"${openFor.toSeconds}s. TibiaData covers character fetches meanwhile, so deaths keep being detected, " +
          "but the second source is contributing nothing until this clears.")
  }

  /** Statuses that mean "stop sending" rather than "this request failed".
   *  401 is deliberately absent: a bad token is a configuration problem that
   *  pausing cannot fix, and it should keep surfacing rather than be hidden
   *  behind a breaker. */
  def blocks(status: Int): Boolean = status == 403 || status == 429

  /** Test/diagnostic only. */
  private[fansiteapi] def openUntilInstant: Option[Instant] =
    Option(openUntil.get()).filterNot(_ == Instant.EPOCH)
}
