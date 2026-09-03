package com.tibiabot
package tibiadata

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

/** A ceiling on how many things run at once, where waiting for a turn costs no
 *  thread. A plain semaphore would block, which is wrong here: the callers are
 *  pekko dispatcher threads serving every other world's stream. A caller with no
 *  permit gets a Future that completes when one frees up.
 *
 *  The waiter queue is unbounded, safe only because what feeds it is not: each
 *  world's fan-out runs at a fixed concurrency. Do not put an unbounded producer
 *  in front of this.
 *
 *  Fair in arrival order — a starved character fetch is a death nobody hears
 *  about. */
final class InFlightLimit(permits: Int) {
  require(permits > 0, s"permits must be positive, got $permits")

  private val lock = new Object
  private var available: Int = permits
  private val waiting = mutable.Queue.empty[Promise[Unit]]

  /** Permits not currently held. Test/diagnostic only. */
  private[tibiadata] def availablePermits: Int = lock.synchronized(available)

  /** Callers currently waiting for a turn. Test/diagnostic only. */
  private[tibiadata] def queueDepth: Int = lock.synchronized(waiting.size)

  private def acquire(): Future[Unit] = lock.synchronized {
    if (available > 0) {
      available -= 1
      Future.unit
    } else {
      val promise = Promise[Unit]()
      waiting.enqueue(promise)
      promise.future
    }
  }

  /** Hand the permit to the next waiter if there is one, rather than returning
   *  it to the pool and making them race for it. Completed outside the lock:
   *  completing a Promise runs its callbacks, and running those while holding
   *  this lock invites a deadlock through whatever they touch. */
  private def release(): Unit = {
    val next = lock.synchronized {
      if (waiting.nonEmpty) Some(waiting.dequeue())
      else { available += 1; None }
    }
    next.foreach(_.success(()))
  }

  /** Run `f` once a permit is free, releasing it however `f` ends.
   *
   *  `f` is by-name and evaluated only with the permit in hand. A synchronous
   *  throw releases it just as a failed Future does — otherwise one exception
   *  leaks a permit permanently and the ceiling ratchets to zero.
   *
   *  The returned Future completes strictly after the permit has gone back, so a
   *  caller waiting on it can rely on the slot being free; a side callback would
   *  leave the two racing and a finished batch could find the ceiling full. */
  def apply[A](f: => Future[A])(implicit ec: ExecutionContext): Future[A] =
    acquire().flatMap { _ =>
      val started =
        try f
        catch { case NonFatal(e) => Future.failed(e) }
      started.transform { outcome => release(); outcome }
    }
}

object InFlightLimit {
  /** Every TibiaData request this process makes passes through this one.
   *
   *  Process-wide for the same reason [[com.tibiabot.tracking.ApiMetrics]] is:
   *  what it bounds is the process. [[TibiaDataClient]] is built in three
   *  places and one instance exists per world stream, so a per-instance limit
   *  would bound a single world while the thing that actually reaches the
   *  upstream — every world at once — stayed unbounded.
   *
   *  In normal running this should never bind. Worlds are staggered across the
   *  poll interval (see `TibiaBot.firstPollDelay`) and most polls
   *  ask for almost nothing, so in-flight requests sit far below the ceiling.
   *  It exists for the cases staggering cannot help: a cold start, where every
   *  world wants every character at once, and recovery after an upstream outage,
   *  where every character has come due together. Those are precisely the
   *  moments the upstream is least able to absorb a spike from one address. */
  val tibiaData: InFlightLimit = new InFlightLimit(Config.tibiaDataMaxInFlight)

  /** The same ceiling for CipSoft's fansite API, kept separate on purpose. A
   *  shared limit would let one upstream stalling consume every permit and
   *  throttle the other — the precise failure the second source exists to
   *  survive. */
  val fansiteApi: InFlightLimit = new InFlightLimit(Config.FansiteApi.maxInFlight)
}
