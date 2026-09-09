package com.tibiabot.patreonapi

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.FiniteDuration

/** A "no more than once every `cooldown`" gate over the Patreon member sync,
 *  shared by the periodic sweep and `/setup`'s on-demand one so a `/setup` moments
 *  after a scheduled sync reuses what it wrote. Split out of BotApp so it is
 *  testable — BotApp is an `App` object, so touching it from a test boots the bot.
 *
 *  Times are raw `System.nanoTime` readings, passed in so tests can drive the
 *  clock. `Long.MinValue` is the "nothing has run yet" sentinel and is checked
 *  explicitly: `nanoTime`'s origin is arbitrary and may be negative, so no fixed
 *  number reads as "long ago", and `now - Long.MinValue` would overflow. */
final class SyncThrottle(cooldown: FiniteDuration) {

  private val lastStartedNanos = new AtomicLong(Long.MinValue)

  /** Claims the right to start a sync at `now`, or refuses because one started
   *  within the cooldown. The compare-and-set is what makes exactly one of two
   *  callers racing on separate command threads win. */
  def tryAcquire(now: Long): Boolean = {
    val last = lastStartedNanos.get()
    val due = last == Long.MinValue || (now - last) >= cooldown.toNanos
    due && lastStartedNanos.compareAndSet(last, now)
  }

  /** Records a sync that started without going through [[tryAcquire]] — the
   *  periodic sweep, which runs on its own schedule and is never refused, but
   *  whose result an on-demand caller should still be able to reuse. */
  def record(now: Long): Unit = lastStartedNanos.set(now)
}
