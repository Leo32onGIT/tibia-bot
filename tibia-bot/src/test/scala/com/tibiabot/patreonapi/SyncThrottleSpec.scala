package com.tibiabot.patreonapi

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration._

/** Pins the cooldown gate `/setup`'s on-demand Patreon sync runs behind (see
 *  BotApp.syncPatreonMembersForSetup). The clock is passed in, so this drives
 *  it directly rather than sleeping. */
class SyncThrottleSpec extends AnyFunSuite with Matchers {

  private val cooldown = 60.seconds
  private def at(t: FiniteDuration): Long = t.toNanos

  test("the first acquire always wins, whatever the clock reads") {
    // nanoTime's origin is arbitrary and may be negative — a throttle that
    // compared against a plain 0 would refuse the very first /setup after a
    // boot on a JVM whose clock happens to read negative.
    new SyncThrottle(cooldown).tryAcquire(Long.MinValue + 1) shouldBe true
    new SyncThrottle(cooldown).tryAcquire(0L) shouldBe true
    new SyncThrottle(cooldown).tryAcquire(-5000000000L) shouldBe true
  }

  test("refuses a second acquire inside the cooldown and allows one after it") {
    val throttle = new SyncThrottle(cooldown)
    throttle.tryAcquire(at(0.seconds)) shouldBe true
    throttle.tryAcquire(at(1.second)) shouldBe false
    throttle.tryAcquire(at(59.seconds)) shouldBe false
    throttle.tryAcquire(at(60.seconds)) shouldBe true
  }

  test("a refused acquire doesn't move the window") {
    // Otherwise a steady drip of /setup calls, each refused, would keep
    // pushing the deadline out and no sync would ever run again.
    val throttle = new SyncThrottle(cooldown)
    throttle.tryAcquire(at(0.seconds)) shouldBe true
    (1 to 59).foreach(s => throttle.tryAcquire(at(s.seconds)) shouldBe false)
    throttle.tryAcquire(at(60.seconds)) shouldBe true
  }

  test("a periodic sync recorded without acquiring still holds off /setup's") {
    // The point of sharing one clock: /setup right after the half-hourly sweep
    // reuses what that just wrote instead of refetching it.
    val throttle = new SyncThrottle(cooldown)
    throttle.record(at(10.seconds))
    throttle.tryAcquire(at(30.seconds)) shouldBe false
    throttle.tryAcquire(at(70.seconds)) shouldBe true
  }

  test("only one of several callers racing on separate threads acquires") {
    // Two people running /setup at the same moment, on separate JDA command
    // threads. A plain read-then-write would let both through.
    val throttle = new SyncThrottle(cooldown)
    val threadCount = 16
    val start = new CountDownLatch(1)
    val done = new CountDownLatch(threadCount)
    val acquired = new AtomicInteger(0)
    (1 to threadCount).foreach { i =>
      val t = new Thread(() => {
        start.await()
        // Staggered within the cooldown, so only the very first may win.
        if (throttle.tryAcquire(at(i.seconds))) acquired.incrementAndGet()
        done.countDown()
      })
      t.setDaemon(true)
      t.start()
    }
    start.countDown()
    done.await(10, TimeUnit.SECONDS) shouldBe true
    acquired.get() shouldBe 1
  }
}
