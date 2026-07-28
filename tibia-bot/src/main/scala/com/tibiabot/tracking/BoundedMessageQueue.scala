package com.tibiabot.tracking

/** FIFO message queue with an optional size cap and O(1) supersede-by-key.
 *  Backs [[com.tibiabot.discord.RateLimitedSender]]'s outbound queue.
 *
 *  Items may carry a `key` identifying what they target. Enqueueing under a key
 *  that is still pending replaces that entry and moves it to the tail, so the
 *  backlog for "current state" traffic is bounded by the number of distinct
 *  targets rather than by how often they are refreshed.
 *
 *  Backed by a `java.util.LinkedHashMap` keyed by the caller's key, or by a
 *  synthetic sequence number for unkeyed items so they interleave in the same
 *  FIFO order. Every operation — enqueue, supersede, dequeue, size — is O(1);
 *  the previous implementation scanned the whole queue on each keyed enqueue,
 *  which on a lane running hundreds deep cost O(depth) per send while holding
 *  the sender's lock.
 *
 *  @param capacity   max retained items (default: unbounded)
 *  @param dropNewest if true, reject the incoming item when full (tail drop);
 *                    if false, evict the oldest queued item to make room.
 */
final class BoundedMessageQueue[T](capacity: Int = Int.MaxValue, dropNewest: Boolean = true) {
  // Insertion-ordered; re-inserting an existing key requires an explicit
  // remove-then-put to move it to the tail (LinkedHashMap.put alone keeps the
  // original position).
  private val q = new java.util.LinkedHashMap[Any, T]()
  private var droppedCount = 0L
  private var supersededCount = 0L
  private var seq = 0L

  def size: Int = q.size
  def isEmpty: Boolean = q.isEmpty
  def dropped: Long = droppedCount

  /** Cumulative items replaced by a later enqueue under the same key. */
  def superseded: Long = supersededCount

  /** Enqueue an item. Returns true if it was retained, false if dropped. */
  def enqueue(item: T): Boolean = enqueue(item, None)

  /** Enqueue an item, superseding any pending item under the same `key`.
   *  A supersede always leaves room, so it can never drop. */
  def enqueue(item: T, key: Option[String]): Boolean = {
    val slot: Any = key match {
      case Some(k) =>
        if (q.remove(k) != null) supersededCount += 1
        k
      case None =>
        seq += 1
        seq
    }
    if (q.size < capacity) {
      q.put(slot, item)
      true
    } else if (dropNewest) {
      droppedCount += 1
      false
    } else {
      removeOldest()     // evict oldest
      q.put(slot, item)
      droppedCount += 1
      true
    }
  }

  /** Remove and return the head, or None if empty (FIFO). */
  def dequeueOption(): Option[T] = if (q.isEmpty) None else Some(removeOldest())

  private def removeOldest(): T = {
    val it = q.entrySet().iterator()
    val head = it.next()
    it.remove()
    head.getValue
  }
}
