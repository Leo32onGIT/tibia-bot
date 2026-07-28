package com.tibiabot.tracking

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class BoundedMessageQueueSpec extends AnyFunSuite with Matchers {

  test("unbounded queue preserves FIFO order and retains everything (current behaviour)") {
    val q = new BoundedMessageQueue[Int]() // Int.MaxValue == today's unbounded queue
    (1 to 5).foreach(q.enqueue)
    q.size shouldBe 5
    q.dropped shouldBe 0
    List.fill(5)(q.dequeueOption()).flatten shouldBe List(1, 2, 3, 4, 5)
    q.dequeueOption() shouldBe None
  }

  test("under capacity nothing is dropped") {
    val q = new BoundedMessageQueue[Int](capacity = 3)
    q.enqueue(1) shouldBe true
    q.enqueue(2) shouldBe true
    q.size shouldBe 2
    q.dropped shouldBe 0
  }

  test("tail-drop: when full, incoming items are rejected and the backlog is kept") {
    val q = new BoundedMessageQueue[Int](capacity = 3, dropNewest = true)
    (1 to 3).foreach(q.enqueue)
    q.enqueue(4) shouldBe false   // rejected
    q.enqueue(5) shouldBe false
    q.size shouldBe 3
    q.dropped shouldBe 2
    List.fill(3)(q.dequeueOption()).flatten shouldBe List(1, 2, 3)
  }

  test("drop-oldest: when full, oldest is evicted to make room for the newest") {
    val q = new BoundedMessageQueue[Int](capacity = 3, dropNewest = false)
    (1 to 3).foreach(q.enqueue)
    q.enqueue(4) shouldBe true
    q.size shouldBe 3
    q.dropped shouldBe 1
    List.fill(3)(q.dequeueOption()).flatten shouldBe List(2, 3, 4)
  }

  test("re-enqueueing a key replaces the pending item and moves it to the tail") {
    val q = new BoundedMessageQueue[Int]()
    q.enqueue(1, Some("a"))
    q.enqueue(2, Some("b"))
    q.enqueue(3, Some("a")) // supersedes 1, and goes behind 2
    q.size shouldBe 2
    q.superseded shouldBe 1
    List.fill(2)(q.dequeueOption()).flatten shouldBe List(2, 3)
  }

  test("distinct keys are independent and keep FIFO order") {
    val q = new BoundedMessageQueue[Int]()
    q.enqueue(1, Some("a"))
    q.enqueue(2, Some("b"))
    q.size shouldBe 2
    q.superseded shouldBe 0
    List.fill(2)(q.dequeueOption()).flatten shouldBe List(1, 2)
  }

  test("keyed and unkeyed items interleave in one FIFO order") {
    val q = new BoundedMessageQueue[Int]()
    q.enqueue(1, Some("a"))
    q.enqueue(2)             // unkeyed
    q.enqueue(3, Some("b"))
    q.enqueue(4)             // unkeyed, never superseded by another unkeyed
    q.size shouldBe 4
    q.superseded shouldBe 0
    List.fill(4)(q.dequeueOption()).flatten shouldBe List(1, 2, 3, 4)
  }

  test("superseding at capacity replaces rather than drops") {
    val q = new BoundedMessageQueue[Int](capacity = 2)
    q.enqueue(1, Some("a"))
    q.enqueue(2, Some("b"))
    q.enqueue(3, Some("a")) shouldBe true // frees a slot before re-inserting
    q.size shouldBe 2
    q.dropped shouldBe 0
    q.superseded shouldBe 1
    List.fill(2)(q.dequeueOption()).flatten shouldBe List(2, 3)
  }
}
