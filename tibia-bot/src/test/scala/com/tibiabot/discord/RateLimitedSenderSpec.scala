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

    List("a", "b", "c").foreach(s => sender.enqueue("test")(() => sent += s))

    sent shouldBe empty            // nothing sent until a tick fires
    ticker.tick(); sent.toList shouldBe List("a")
    ticker.tick(); sent.toList shouldBe List("a", "b")
    ticker.tick(); sent.toList shouldBe List("a", "b", "c")
    ticker.tick(); sent.toList shouldBe List("a", "b", "c") // empty tick is a no-op
  }

  test("starts the ticker only once across many enqueues") {
    val ticker = new ManualTicker
    val sender = new RateLimitedSender(ticker.start)
    (1 to 5).foreach(_ => sender.enqueue("test")(() => ()))
    ticker.starts shouldBe 1
  }

  test("a failing dispatch is swallowed and the next still sends") {
    val ticker = new ManualTicker
    val sender = new RateLimitedSender(ticker.start)
    val sent = ListBuffer.empty[String]

    sender.enqueue("test")(() => throw new RuntimeException("boom"))
    sender.enqueue("test")(() => sent += "after")

    noException should be thrownBy ticker.tick()
    ticker.tick()
    sent.toList shouldBe List("after")
  }

  test("a finite capacity drops overflow instead of growing unbounded") {
    val ticker = new ManualTicker
    val sender = new RateLimitedSender(ticker.start, capacity = 2)
    val sent = ListBuffer.empty[String]

    List("a", "b", "c").foreach(s => sender.enqueue("test")(() => sent += s)) // "c" dropped (tail drop)
    ticker.tick(); ticker.tick(); ticker.tick()
    sent.toList shouldBe List("a", "b")
    sender.totalDropped shouldBe 1
  }

  test("queueDepth reflects the current backlog, draining as ticks fire") {
    val ticker = new ManualTicker
    val sender = new RateLimitedSender(ticker.start)

    List("a", "b", "c").foreach(s => sender.enqueue("test")(() => ()))
    sender.queueDepth shouldBe 3
    ticker.tick()
    sender.queueDepth shouldBe 2
    ticker.tick(); ticker.tick()
    sender.queueDepth shouldBe 0
  }

  test("snapshotAndReset reports per-label counts and clears the window") {
    val ticker = new ManualTicker
    val sender = new RateLimitedSender(ticker.start)

    sender.enqueue("rename")(() => ())
    sender.enqueue("rename")(() => ())
    sender.enqueue("online-list")(() => ())
    ticker.tick(); ticker.tick(); ticker.tick()

    val snapshot = sender.snapshotAndReset()
    snapshot("rename").count shouldBe 2
    snapshot("online-list").count shouldBe 1
    snapshot.get("rename").map(_.avgWaitMs) should not be empty

    // window resets: nothing new happened since the snapshot
    sender.snapshotAndReset() shouldBe empty
  }

  test("enqueueing under the same key supersedes the earlier pending item") {
    val ticker = new ManualTicker
    val sender = new RateLimitedSender(ticker.start)
    val sent = ListBuffer.empty[String]

    sender.enqueue("test", Some("channel-1"))(() => sent += "stale")
    sender.enqueue("test", Some("channel-1"))(() => sent += "fresh")
    sender.queueDepth shouldBe 1
    sender.totalSuperseded shouldBe 1

    ticker.tick()
    sent.toList shouldBe List("fresh")
  }

  test("different keys never supersede each other") {
    val ticker = new ManualTicker
    val sender = new RateLimitedSender(ticker.start)
    val sent = ListBuffer.empty[String]

    sender.enqueue("test", Some("channel-1"))(() => sent += "a")
    sender.enqueue("test", Some("channel-2"))(() => sent += "b")
    sender.queueDepth shouldBe 2
    sender.totalSuperseded shouldBe 0

    ticker.tick(); ticker.tick()
    sent.toList shouldBe List("a", "b")
  }

  test("no key means every enqueue is kept, even for identical labels") {
    val ticker = new ManualTicker
    val sender = new RateLimitedSender(ticker.start)
    val sent = ListBuffer.empty[String]

    sender.enqueue("test")(() => sent += "a")
    sender.enqueue("test")(() => sent += "b")
    sender.queueDepth shouldBe 2
    sender.totalSuperseded shouldBe 0

    ticker.tick(); ticker.tick()
    sent.toList shouldBe List("a", "b")
  }
}
