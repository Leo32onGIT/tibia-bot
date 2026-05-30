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
}
