package com.tibiabot.discord

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

class RateLimitedSenderSpec extends AnyFunSuite with Matchers {

  /** A ticker that captures the drain action so the test can fire it by hand. */
  private class ManualTicker {
    var drain: Option[() => Unit] = None
    var starts = 0
    val start: (() => Unit) => (() => Unit) = d => {
      starts += 1
      drain = Some(d)
      () => { drain = None }
    }
    def tick(): Unit = drain.foreach(_())
  }

  test("drains queued messages in FIFO order, one per tick") {
    val ticker = new ManualTicker
    val sender = new RateLimitedSender(ticker.start)
    val sent = ListBuffer.empty[String]

    List("a", "b", "c").foreach(s => sender.enqueue(() => sent += s))

    sent shouldBe empty            // nothing sent until a tick fires
    ticker.tick(); sent.toList shouldBe List("a")
    ticker.tick(); sent.toList shouldBe List("a", "b")
    ticker.tick(); sent.toList shouldBe List("a", "b", "c")
    ticker.tick(); sent.toList shouldBe List("a", "b", "c") // empty tick is a no-op
  }

  test("starts the ticker only once across many enqueues") {
    val ticker = new ManualTicker
    val sender = new RateLimitedSender(ticker.start)
    (1 to 5).foreach(_ => sender.enqueue(() => ()))
    ticker.starts shouldBe 1
  }

  test("a failing dispatch is swallowed and the next still sends") {
    val ticker = new ManualTicker
    val sender = new RateLimitedSender(ticker.start)
    val sent = ListBuffer.empty[String]

    sender.enqueue(() => throw new RuntimeException("boom"))
    sender.enqueue(() => sent += "after")

    noException should be thrownBy ticker.tick()
    ticker.tick()
    sent.toList shouldBe List("after")
  }

  test("a finite capacity drops overflow instead of growing unbounded") {
    val ticker = new ManualTicker
    val sender = new RateLimitedSender(ticker.start, capacity = 2)
    val sent = ListBuffer.empty[String]

    List("a", "b", "c").foreach(s => sender.enqueue(() => sent += s)) // "c" dropped (tail drop)
    ticker.tick(); ticker.tick(); ticker.tick()
    sent.toList shouldBe List("a", "b")
  }
}
