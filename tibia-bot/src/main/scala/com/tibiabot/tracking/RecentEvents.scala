package com.tibiabot.tracking

import java.time.Instant
import scala.collection.mutable

final case class ActivityEvent(at: Instant, tag: String, world: String, text: String)

/** A bounded, thread-safe log of the most recent bot-wide events (deaths,
 *  level-ups, renames, online-list edits, ...) for the monitoring dashboard's
 *  live feed. Unlike [[BoundedMessageQueue]] (drain-oriented: items are
 *  removed by whoever reads them), this is read-many/write-many — recording
 *  never removes anything except to stay under `capacity`, and reading never
 *  removes anything at all. */
final class RecentEvents(capacity: Int = 50) {
  private val events = mutable.Queue.empty[ActivityEvent]

  def record(tag: String, world: String, text: String): Unit = synchronized {
    events.enqueue(ActivityEvent(Instant.now(), tag, world, text))
    while (events.size > capacity) events.dequeue()
  }

  /** Most recent first. */
  def recent(): List[ActivityEvent] = synchronized { events.toList.reverse }
}
