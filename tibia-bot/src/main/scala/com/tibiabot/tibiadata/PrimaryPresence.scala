package com.tibiabot
package tibiadata

import com.tibiabot.persistence.RedisCache

import java.time.Instant
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Whether a shared-world-cycle primary is alive, from a heartbeat key it
 *  refreshes on a short TTL. This is what makes it safe for a secondary to stop
 *  fetching for itself: consuming only what the primary publishes, a dead primary
 *  would blind every secondary at once, and the heartbeat is the signal to go back
 *  to fetching directly.
 *
 *  '''It fails open in three ways.''' It starts saying the primary is absent, so a
 *  cold start fetches directly; a Redis error reads as absent rather than
 *  propagating; and an answer is believed only for `refreshEvery` before decaying
 *  back to absent. The asymmetry is the point — believing a dead primary alive
 *  stops the secondary dead, where believing a live one dead costs duplicate
 *  requests.
 *
 *  Reads never touch Redis inline: `isAlive` answers from the last known value and
 *  kicks off a refresh as it goes stale. The caller is a per-character cache miss
 *  on a poll, so a round trip per call would put the whole fleet's misses on the
 *  wire. */
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
