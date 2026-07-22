package com.tibiabot.tracking

import scala.collection.mutable

/** FIFO message queue with an optional size cap. Backs
 *  [[com.tibiabot.discord.RateLimitedSender]]'s outbound queue.
 *
 *  @param capacity   max retained items (default: unbounded)
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
