package com.tibiabot.tracking

import scala.collection.mutable

/** FIFO message queue with an optional size cap.
 *
 *  Production currently uses an unbounded `mutable.Queue` (TibiaBot.scala 1827)
 *  drained one item per tick — under a burst (server save / masslog) it can
 *  grow without bound. `capacity = Int.MaxValue` reproduces today's behaviour
 *  exactly; a finite capacity drops messages instead of leaking memory.
 *
 *  @param capacity   max retained items (default: unbounded == current behaviour)
 *  @param dropNewest if true, reject the incoming item when full (tail drop);
 *                    if false, evict the oldest queued item to make room.
 */
final class BoundedMessageQueue[T](capacity: Int = Int.MaxValue, dropNewest: Boolean = true) {
  private val q = mutable.Queue.empty[T]
  private var droppedCount = 0L

  def size: Int = q.size
  def isEmpty: Boolean = q.isEmpty
  def dropped: Long = droppedCount

  /** Enqueue an item. Returns true if it was retained, false if dropped. */
  def enqueue(item: T): Boolean = {
    if (q.size < capacity) {
      q.enqueue(item)
      true
    } else if (dropNewest) {
      droppedCount += 1
      false
    } else {
      q.dequeue()        // evict oldest
      q.enqueue(item)
      droppedCount += 1
      true
    }
  }

  /** Remove and return the head, or None if empty (FIFO). */
  def dequeueOption(): Option[T] = if (q.isEmpty) None else Some(q.dequeue())
}
