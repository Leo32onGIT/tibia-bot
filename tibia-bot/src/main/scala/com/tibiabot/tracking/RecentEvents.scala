package com.tibiabot.tracking

import java.time.Instant
import scala.collection.mutable

final case class ActivityEvent(at: Instant, tag: String, text: String)

/** A bounded, thread-safe log of one world's most recent events (deaths,
 *  level-ups, renames, online-list edits, ...) for the monitoring dashboard's
 *  live feed. One instance per world (see [[RecentEventsRegistry]]) — no
 *  `world` field on [[ActivityEvent]], since which world an event belongs to
 *  is just whichever instance it was recorded on. Unlike [[BoundedMessageQueue]]
 *  (drain-oriented: items are removed by whoever reads them), this is
 *  read-many/write-many — recording never removes anything except to stay
 *  under `capacity`, and reading never removes anything at all. */
final class RecentEvents(capacity: Int = 50) {
  private val events = mutable.Queue.empty[ActivityEvent]

  def record(tag: String, text: String): Unit = synchronized {
    events.enqueue(ActivityEvent(Instant.now(), tag, text))
    while (events.size > capacity) events.dequeue()
  }

  /** Most recent first. */
  def recent(): List[ActivityEvent] = synchronized { events.toList.reverse }
}
