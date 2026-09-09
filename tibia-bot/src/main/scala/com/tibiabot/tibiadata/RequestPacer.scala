package com.tibiabot
package tibiadata

import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.duration._

/** A floor on how often requests leave this process for one upstream, so a
 *  lane's traffic arrives as a stream rather than a burst.
 *
 *  This is not [[InFlightLimit]] with different numbers. That bounds how many
 *  requests are *open*; what an edge counts is how many *arrive*, and the two
 *  are related only through latency, which is the upstream's choice rather than
 *  ours. Eight permits is thirteen requests a second against a 600ms upstream
 *  and a hundred and thirty against a 60ms one, so a concurrency ceiling that
 *  looks safe is only safe while the upstream stays slow — and the moment it
 *  speeds up, the same configuration produces the burst that earns a block.
 *  Pacing on time is the only form of this that holds still.
 *
 *  '''Spacing is jittered on purpose.''' `fansiteapi.tibia.com` blocks by IP
 *  through Cloudflare rather than answering 429, and what refuses us there
 *  scores requests rather than counting them — the `User-Agent` gate in
 *  [[com.tibiabot.fansiteapi.FansiteApiClient]] is the proof, since no rate
 *  limiter reads that header. Metronome-regular arrivals are one of the signals
 *  such scoring looks for, so a perfectly even gap would trade one bot giveaway
 *  for another. The mean holds the rate; the spread keeps the shape irregular.
 *
 *  '''The wait doubles as admission control.''' How long a reservation is told
 *  to wait is exactly how much work is queued ahead of it, so `maxDelay` on
 *  [[tryReserve]] bounds the backlog in the only unit that matters — a fansite
 *  sheet arriving after the poll tick it belonged to is worth nothing, and
 *  queueing it anyway spends a request to answer a question already answered.
 *  Refusing costs nothing: every caller here already falls back to TibiaData on
 *  a Left.
 *
 *  Reservations are virtual time. A caller is told how long to wait and no
 *  thread or timer is held per request, and the arithmetic stays pure so a test
 *  can prove the spacing without waiting for it — the same reason
 *  [[com.tibiabot.highscores.HighscoreSweep]] takes its delay injected. */
final class RequestPacer(
    spacing: FiniteDuration,
    burst: Int,
    jitter: Double,
    now: () => Long = () => System.nanoTime(),
    random: () => Double = () => ThreadLocalRandom.current().nextDouble()
) {
  require(spacing > Duration.Zero, s"spacing must be positive, got $spacing")
  require(burst >= 1, s"burst must be at least 1, got $burst")
  require(jitter >= 0.0 && jitter < 1.0, s"jitter must be in [0, 1), got $jitter")

  private val spacingNanos: Long = spacing.toNanos
  private val lock = new Object

  /** When the next request may leave, on `System.nanoTime`'s clock. Starts far
   *  enough back that a cold process is owed a full burst and no more. */
  private var nextSlot: Long = now() - (burst - 1).toLong * spacingNanos

  /** The gap to put after the request just admitted. Symmetric around
   *  `spacing`, so jitter changes the shape without moving the mean rate. */
  private def gapNanos(): Long =
    if (jitter == 0.0) spacingNanos
    else {
      val factor = 1.0 + (random() * 2.0 - 1.0) * jitter
      math.max(1L, (spacingNanos * factor).toLong)
    }

  /** How long the caller must wait before sending, or `None` if the queue
   *  already stretches past `maxDelay` and the answer would arrive too late to
   *  be worth the request.
   *
   *  A refusal leaves the schedule untouched, so a rejected caller neither
   *  holds nor consumes a slot — the next one to ask is offered the same one. */
  def tryReserve(maxDelay: FiniteDuration): Option[FiniteDuration] =
    lock.synchronized {
      val at = now()
      // Idle time banks at most `burst` slots. Without this floor an upstream
      // left alone for an hour would owe us an hour of requests and be handed
      // them all at once, which is the precise shape this class exists to stop.
      val floor = at - (burst - 1).toLong * spacingNanos
      val slot = math.max(floor, nextSlot)
      val wait = math.max(0L, slot - at)
      if (wait > maxDelay.toNanos) None
      else {
        nextSlot = slot + gapNanos()
        Some(wait.nanos)
      }
    }

  /** How long a reservation taken now would wait, without taking it.
   *  Diagnostic only — a caller that acts on this races every other caller. */
  private[tibiabot] def pendingDelay: FiniteDuration =
    lock.synchronized(math.max(0L, nextSlot - now()).nanos)
}

object RequestPacer {

  /** Every fansite API request this process makes passes through this one.
   *
   *  Process-wide for the same reason [[InFlightLimit.fansiteApi]] is: what it
   *  protects is a single IP address, and a per-instance pacer would smooth one
   *  world's traffic while the fleet arrived together.
   *
   *  There is deliberately no TibiaData equivalent. `api.tibiadata.com`
   *  documents no production rate limit and absorbs the character firehose
   *  without complaint, and a delay in front of that lane would be paid for
   *  directly in death-detection latency — the one number here a user can
   *  actually feel. */
  val fansiteApi: RequestPacer = new RequestPacer(
    Config.FansiteApi.minRequestGap,
    Config.FansiteApi.burst,
    Config.FansiteApi.requestGapJitter
  )
}
