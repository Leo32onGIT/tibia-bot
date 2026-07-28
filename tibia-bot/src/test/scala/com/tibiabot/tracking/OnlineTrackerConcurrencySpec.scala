package com.tibiabot.tracking

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

/**
 * The world stream writes the tracker while the online-list sweep reads
 * [[OnlineTracker.snapshot]] from its own scheduled thread. Against the
 * unsynchronised mutable map this class used to hold, that read can observe a
 * half-applied structural update — returning a short or corrupt roster, or
 * throwing outright.
 */
class OnlineTrackerConcurrencySpec extends AnyFunSuite with Matchers {

  private val when = ZonedDateTime.parse("2026-01-01T00:00:00Z")

  private def roster(size: Int): Seq[(String, Int, String)] =
    (0 until size).map(i => (s"player-$i", 100 + i, "Elite Knight"))

  test("snapshot never observes a partially applied poll") {
    val tracker = new OnlineTracker
    val size = 500
    tracker.updateFromOnline(roster(size), when)

    val failure = new AtomicReference[Throwable](null)
    val stop = new java.util.concurrent.atomic.AtomicBoolean(false)
    val pool = Executors.newFixedThreadPool(3)
    val start = new CountDownLatch(1)
    val done = new CountDownLatch(3)

    // the stream: rewrites the whole roster over and over
    pool.submit(new Runnable {
      def run(): Unit = {
        start.await()
        try (0 until 200).foreach(i => tracker.updateFromOnline(roster(size), when.plusSeconds(i.toLong)))
        catch { case t: Throwable => failure.compareAndSet(null, t) }
        finally { stop.set(true); done.countDown() }
      }
    })
    // the stream again: per-player mutations interleaved with the rewrites
    pool.submit(new Runnable {
      def run(): Unit = {
        start.await()
        try while (!stop.get()) {
          (0 until size).foreach { i =>
            tracker.setGuild(s"player-$i", s"guild-$i")
            tracker.setFlag(s"player-$i", ":zap:")
          }
        }
        catch { case t: Throwable => failure.compareAndSet(null, t) }
        finally done.countDown()
      }
    })
    // the online-list sweep: reads the roster it is about to render
    pool.submit(new Runnable {
      def run(): Unit = {
        start.await()
        try while (!stop.get()) {
          val seen = tracker.snapshot
          // A snapshot is always a complete roster, never a torn one, and is
          // detached — iterating it while writers run must not blow up.
          seen.size shouldBe size
          seen.foreach(p => p.name should startWith("player-"))
        }
        catch { case t: Throwable => failure.compareAndSet(null, t) }
        finally done.countDown()
      }
    })

    start.countDown()
    done.await(60, TimeUnit.SECONDS) shouldBe true
    pool.shutdown()
    Option(failure.get()) shouldBe None
  }
}
