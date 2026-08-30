package com.tibiabot
package tibiadata

import com.tibiabot.persistence.RedisCache

import java.time.Instant
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Whether a shared-world-cycle primary is currently alive, from a heartbeat
 *  key it refreshes on a short TTL.
 *
 *  This is what makes it safe for a secondary to stop fetching for itself. Once
 *  it consumes only what the primary publishes, a primary that dies would
 *  otherwise blind every secondary at once; the heartbeat is the signal to go
 *  back to fetching directly.
 *
 *  '''It fails open, deliberately, in three separate ways.''' It starts out
 *  saying the primary is absent, so a cold start fetches directly rather than
 *  waiting for a bot it has not heard from yet. A Redis error reads as absent
 *  rather than propagating. And an answer is only ever believed for
 *  `refreshEvery`, after which it decays back to absent unless refreshed. The
 *  asymmetry is the point: believing a dead primary is alive stops the
 *  secondary dead, while believing a live one is dead costs some duplicate
 *  requests. Only one of those is recoverable without somebody noticing.
 *
 *  Reads are cheap because they never touch Redis inline. `isAlive` answers
 *  from the last known value and kicks off a refresh when that value is going
 *  stale — the caller is a per-character cache miss on a poll, so a Redis round
 *  trip per call would put the whole fleet's misses on the wire. */
final class PrimaryPresence(
    cache: RedisCache,
    refreshEvery: FiniteDuration,
    now: () => Instant = () => Instant.now()
)(implicit ec: ExecutionContext) {

  private val lastAnswer = new AtomicReference[Option[Instant]](None)
  private val refreshing = new AtomicBoolean(false)

  /** True only while a heartbeat seen within `refreshEvery` said so. */
  def isAlive: Boolean = {
    val at = now()
    val answer = lastAnswer.get()
    val fresh = answer.exists(seenAt => !seenAt.plusSeconds(refreshEvery.toSeconds).isBefore(at))
    if (!fresh) refresh(at)
    fresh
  }

  /** One refresh at a time — a burst of misses must not become a burst of
   *  Redis reads for the same answer. */
  private def refresh(at: Instant): Unit =
    if (refreshing.compareAndSet(false, true))
      cache.get(PrimaryPresence.HeartbeatKey)
        .recover { case NonFatal(_) => None }
        .foreach { value =>
          lastAnswer.set(value.map(_ => at))
          refreshing.set(false)
        }

  /** Test/diagnostic only: block until the current view is refreshed. */
  private[tibiabot] def refreshNow(): Future[Boolean] = {
    val at = now()
    cache.get(PrimaryPresence.HeartbeatKey).recover { case NonFatal(_) => None }.map { value =>
      lastAnswer.set(value.map(_ => at))
      value.isDefined
    }
  }
}

object PrimaryPresence {
  /** Written by the primary, read by every secondary. One key for the whole
   *  fleet: there is only ever one primary, and "which one" is not a question
   *  anybody downstream asks. */
  val HeartbeatKey = "tibia:primary-alive"
}
