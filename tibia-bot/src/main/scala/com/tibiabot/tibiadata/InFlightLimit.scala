package com.tibiabot
package tibiadata

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

/** A ceiling on how many things run at once, where waiting for a turn does not
 *  cost a thread.
 *
 *  A plain semaphore would block, which is exactly wrong here: the callers are
 *  akka dispatcher threads serving every other world's stream, and parking one
 *  to wait for a request slot would stall unrelated work. So a caller with no
 *  permit available is handed a Future that completes when one frees up, and
 *  the thread goes back to doing something useful.
 *
 *  The queue of waiters is unbounded, which is safe only because what feeds it
 *  is already bounded: each world's fan-out runs at a fixed concurrency, so a
 *  process can never have more work waiting here than the sum of those. Do not
 *  put an unbounded producer in front of this.
 *
 *  Fair in arrival order — a waiter cannot be starved by later arrivals, which
 *  matters because a starved character fetch is a death nobody hears about. */
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
   *  `f` is by-name and evaluated only after the permit is in hand, so nothing
   *  starts before it is allowed to. A synchronous throw from `f` releases the
   *  permit just as a failed Future does — without that, one thrown exception
   *  would leak a permit permanently and the ceiling would ratchet down to
   *  zero over a long-running process.
   *
   *  The returned Future completes strictly after the permit has gone back, so
   *  a caller that waits on it can rely on the slot being free. Registering the
   *  release as a side callback instead would leave the two racing, and a
   *  caller that finished a batch could find the ceiling still full. */
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
}
