package com.tibiabot

import java.time.{Duration, ZonedDateTime}

/** A thread-safe TTL cache for a list that is expensive to fetch (a blocking
 *  API call). Within `ttl` of the last successful fetch the cached value is
 *  returned without re-fetching; after that it re-fetches. On a failed fetch it
 *  falls back to the last good value, or to `fallback` if there is none.
 *
 *  The fetcher, clock and fallback are injected so the hit/miss/expiry/fallback
 *  behaviour is unit-testable with no network or ActorSystem (CachedListSpec).
 */
final class CachedList[A](
  fetch: () => Either[String, List[A]],
  fallback: => List[A],
  ttl: Duration,
  now: () => ZonedDateTime
) {
  private var cached: Option[List[A]] = None
  private var fetchedAt: Option[ZonedDateTime] = None

  def get(): List[A] = synchronized {
    val fresh = (cached, fetchedAt) match {
      case (Some(value), Some(t)) if now().isBefore(t.plus(ttl)) => Some(value)
      case _                                                     => None
    }
    fresh.getOrElse {
      fetch() match {
        case Right(value) =>
          cached = Some(value)
          fetchedAt = Some(now())
          value
        case Left(_) =>
          cached.getOrElse(fallback)
      }
    }
  }
}
