package com.tibiabot.domain.time

import java.time.{Instant, ZoneId, ZonedDateTime}

/** Time-source port (Dependency Inversion).
 *
 *  Time-dependent logic depends on this trait rather than calling
 *  `Instant.now()` / `ZonedDateTime.now()` directly, so it can be tested
 *  deterministically with a fixed clock. Wiring the existing wall-clock call
 *  sites onto this port happens incrementally (e.g. with the scheduler step);
 *  the pure cycle math in this package is already parameterized by explicit
 *  instants and needs no clock.
 */
trait Clock {
  def instant: Instant
  def now: ZonedDateTime
}

object Clock {
  /** The game's reference time zone, used throughout the bot. */
  val Berlin: ZoneId = ZoneId.of("Europe/Berlin")
}

/** Real clock backed by system time, in the game's Europe/Berlin zone. */
final class SystemClock extends Clock {
  def instant: Instant = Instant.now()
  def now: ZonedDateTime = ZonedDateTime.now(Clock.Berlin)
}
