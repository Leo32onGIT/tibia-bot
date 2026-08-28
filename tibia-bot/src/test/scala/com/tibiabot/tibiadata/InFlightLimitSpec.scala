package com.tibiabot.tibiadata

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

/** The ceiling on concurrent upstream requests. Worth testing properly rather
 *  than by inspection: a permit leaked on any path ratchets the ceiling down
 *  over a process that runs for months, and the failure looks like the API
 *  slowly going quiet rather than like a bug here. */
class InFlightLimitSpec extends AnyFunSuite with Matchers {

  private implicit val ec: ExecutionContext = ExecutionContext.global
  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  test("work under the ceiling runs straight away") {
    val limit = new InFlightLimit(2)
    await(limit(Future.successful(1))) shouldBe 1
    limit.availablePermits shouldBe 2
  }

  test("work past the ceiling waits instead of running") {
    val limit = new InFlightLimit(2)
    val gate1, gate2, gate3 = Promise[Int]()
    val started = new AtomicInteger(0)
    def run(p: Promise[Int]) = limit { started.incrementAndGet(); p.future }

    val f1 = run(gate1); val f2 = run(gate2); val f3 = run(gate3)
    // Submitting only schedules the body on the execution context, so wait for
    // the two that may run rather than reading the counter the instant after.
    eventually { started.get shouldBe 2; limit.queueDepth shouldBe 1 }
    // ...and confirm the third stays put, which is the actual claim.
    Thread.sleep(50)
    started.get shouldBe 2 // the third has not been allowed to begin

    gate1.success(1)
    await(f1) shouldBe 1
    eventually(started.get shouldBe 3) // freeing a permit lets it start

    gate2.success(2); gate3.success(3)
    await(f2) shouldBe 2
    await(f3) shouldBe 3
    limit.availablePermits shouldBe 2
  }

  test("a failed future still gives its permit back") {
    val limit = new InFlightLimit(1)
    val boom = new RuntimeException("upstream said no")
    an[RuntimeException] should be thrownBy await(limit(Future.failed(boom)))
    limit.availablePermits shouldBe 1
    await(limit(Future.successful("after"))) shouldBe "after"
  }

  test("a body that throws before returning a future still gives its permit back") {
    // The leak that would otherwise be permanent: one synchronous throw and
    // that permit never comes back for the life of the process.
    val limit = new InFlightLimit(1)
    an[IllegalStateException] should be thrownBy
      await(limit[Int](throw new IllegalStateException("threw before the future")))
    limit.availablePermits shouldBe 1
    await(limit(Future.successful("after"))) shouldBe "after"
  }

  test("the body is not evaluated until a permit is actually free") {
    val limit = new InFlightLimit(1)
    val held = Promise[Int]()
    val evaluated = new AtomicInteger(0)
    val first = limit(held.future)
    val second = limit { evaluated.incrementAndGet(); Future.successful(0) }
    evaluated.get shouldBe 0
    held.success(1)
    await(first)
    await(second)
    evaluated.get shouldBe 1
  }

  test("waiters are served in arrival order, so nothing is starved") {
    val limit = new InFlightLimit(1)
    val held = Promise[Int]()
    val order = new java.util.concurrent.ConcurrentLinkedQueue[Int]()
    val first = limit(held.future)
    val queued = (1 to 5).map(i => limit { order.add(i); Future.successful(i) })
    held.success(0)
    await(first)
    queued.foreach(await)
    order.toArray.toList shouldBe List(1, 2, 3, 4, 5)
  }

  test("many concurrent callers never exceed the ceiling") {
    val ceiling = 8
    val limit = new InFlightLimit(ceiling)
    val live = new AtomicInteger(0)
    val peak = new AtomicInteger(0)
    val work = (1 to 500).map { _ =>
      limit {
        val now = live.incrementAndGet()
        peak.updateAndGet(p => math.max(p, now))
        Future { Thread.sleep(1); live.decrementAndGet(); () }
      }
    }
    await(Future.sequence(work))
    peak.get should be <= ceiling
    limit.availablePermits shouldBe ceiling
  }

  test("a ceiling of zero or less is rejected rather than silently deadlocking") {
    an[IllegalArgumentException] should be thrownBy new InFlightLimit(0)
    an[IllegalArgumentException] should be thrownBy new InFlightLimit(-1)
  }

  /** Retry an assertion briefly — permits hand off on another thread. */
  private def eventually(assertion: => Any): Unit = {
    val deadline = System.currentTimeMillis() + 2000
    var last: Throwable = null
    while (System.currentTimeMillis() < deadline) {
      try { assertion; return } catch { case e: Throwable => last = e; Thread.sleep(5) }
    }
    throw last
  }
}
