package com.tibiabot.tibiadata

import scala.concurrent.duration._
import scala.util.Random

/** What [[RetryPolicy]] decided to do with a failed request. */
sealed trait RetryDecision
object RetryDecision {
  /** Don't retry — hand the response back, where it becomes a logged Left. */
  case object GiveUp extends RetryDecision
  /** Retry once this delay has passed. */
  final case class RetryIn(delay: FiniteDuration) extends RetryDecision
}

/** Whether, and how soon, to retry a failed TibiaData request.
 *
 *  Pulled out of the client so the policy — the part worth getting right — is
 *  testable without HTTP. `api.tibiadata.com` sits behind Cloudflare and a Kong
 *  gateway, so a retry is not free: it is a fresh edge request that counts
 *  against whatever rate limiting those apply, and the character poll issues
 *  roughly one request per online character per world per cycle. Retrying badly
 *  turns a brief upstream blip into a self-inflicted one.
 *
 *  The rules:
 *
 *  - '''429 is never retried inline.''' Being rate-limited means "send less",
 *    so spending two more requests on it is exactly wrong: it consumes more
 *    quota and can extend the penalty window. The natural retry is the next
 *    poll cycle, a minute away, which is the right timescale to back off on.
 *  - '''Nor is anything else, for a caller that polls again soon.''' The same
 *    argument as 429 applies wherever the caller has its own retry already:
 *    spending two extra requests to save at most a minute is a bad trade when
 *    the caller will ask again in a minute anyway. It is a very bad one on the
 *    character poll, which is ~99% of the traffic against this API and where
 *    503s are both common and bursty — a 250ms retry lands inside the same
 *    burst, fails alongside it, and triples the load arriving at an upstream
 *    that is already failing. Callers that fetch once and have nobody behind
 *    them keep the inline retry, since for them the next attempt is not
 *    minutes away, it is never.
 *  - '''A `Retry-After` longer than `maxHonouredRetryAfter` means give up
 *    now,''' rather than holding the request open for it. These run inside the
 *    poll's bounded concurrency, so sleeping for a server-suggested 30s would
 *    stall the stream far worse than simply missing this character until the
 *    next cycle. Honouring the header here means respecting the "stop asking"
 *    part of it, not the exact duration.
 *  - '''A short `Retry-After` is honoured exactly''', in preference to our own
 *    backoff — the server knows better than we do.
 *  - '''Backoff is jittered.''' Every world stream hits the same upstream, so a
 *    fixed delay makes them all retry in lockstep and arrive as one wave. */
final class RetryPolicy(
  maxRetries: Int = 2,
  maxHonouredRetryAfter: FiniteDuration = 2.seconds,
  // Injected so tests are deterministic; bound is inclusive.
  jitterMs: Int => Int = bound => Random.nextInt(bound + 1)
) {

  /** Transient upstream failures worth a second attempt. Deliberately excludes
   *  429 (see class doc) and every 4xx, which are answers, not failures. */
  val retryableStatusCodes: Set[Int] = Set(500, 502, 503, 504)

  /** Roughly 250ms, then 500ms, spread by up to ±50% so concurrent streams
   *  don't retry in step. */
  def backoff(attempt: Int): FiniteDuration = {
    val base = 250 * (attempt + 1)
    (base / 2 + jitterMs(base)).milliseconds
  }

  /** Decide what to do with a response carrying `statusCode`, given any
   *  `Retry-After` it asked for and how many attempts have already been made.
   *
   *  `callerRetriesSoon` says the caller is on its own schedule and will ask
   *  again shortly, which makes an inline retry a cost with almost no benefit
   *  — see the class doc. */
  def onResponse(statusCode: Int, retryAfter: Option[FiniteDuration], attempt: Int, callerRetriesSoon: Boolean = false): RetryDecision =
    if (attempt >= maxRetries) RetryDecision.GiveUp
    else if (callerRetriesSoon) RetryDecision.GiveUp
    else if (!retryableStatusCodes.contains(statusCode)) RetryDecision.GiveUp
    else
      retryAfter match {
        case Some(delay) if delay > maxHonouredRetryAfter => RetryDecision.GiveUp
        case Some(delay)                                  => RetryDecision.RetryIn(delay)
        case None                                         => RetryDecision.RetryIn(backoff(attempt))
      }

  /** Decide what to do when the request failed below the HTTP layer (timeout,
   *  connection reset) — there is no status or `Retry-After` to consult.
   *
   *  Treated the same way as a transient status for a caller that retries soon,
   *  and for the same reason. A timeout is the worse case of the two to retry
   *  inline: it has already held its slot in the poll's bounded concurrency for
   *  the full timeout before failing. */
  def onConnectionFailure(attempt: Int, callerRetriesSoon: Boolean = false): RetryDecision =
    if (attempt >= maxRetries || callerRetriesSoon) RetryDecision.GiveUp
    else RetryDecision.RetryIn(backoff(attempt))

  /** True when this status means "you are sending too much" — logged
   *  distinctly by the client, since it is the one worth acting on. */
  def isRateLimited(statusCode: Int): Boolean = statusCode == 429
}
