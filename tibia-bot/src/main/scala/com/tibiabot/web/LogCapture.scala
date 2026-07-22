package com.tibiabot.web

import java.time.Instant
import scala.collection.mutable

final case class LogEvent(at: Instant, level: String, logger: String, message: String, count: Int)

/** A bounded, thread-safe log of the most recent WARN+ level events, for the
 *  monitoring dashboard's log-alerts widget. [[LogCapture.instance]] is the
 *  single shared instance [[DashboardLogAppender]] writes to — Logback
 *  instantiates appenders itself via reflection (from logback.xml), so it
 *  can't be handed a specific instance the way BotApp wires its other
 *  dependencies; this is the seam. Same shape as
 *  [[com.tibiabot.tracking.RecentEvents]]: recording never removes anything
 *  except to stay under `capacity`, reading never removes anything at all —
 *  except that a repeat of the same (level, logger) whose message has the
 *  same *shape* as the latest entry (see [[normalize]]) bumps that entry's
 *  count and replaces its message/timestamp instead of adding a new one, so
 *  a bursty repeating warning can't flood the buffer and push out genuinely
 *  different alerts — even when the message embeds a different value each
 *  time (a character name, a channel id, a retry-after ms count), which is
 *  the common case for real repeating warnings, not the exception. Only
 *  collapses *runs* of the same shape, not every past occurrence — a
 *  different message in between starts a new entry. */
final class LogCapture(capacity: Int = 50) {
  private val events = mutable.Queue.empty[LogEvent]

  /** Blanks out the parts of a message likely to vary between otherwise-
   *  identical occurrences (quoted values, numbers) so two warnings differing
   *  only by, say, a character name or a channel id compare as the same
   *  shape. Deliberately approximate — an occasional false-positive collapse
   *  of two genuinely distinct messages is an acceptable tradeoff for a
   *  monitoring widget, not a correctness-sensitive comparison. */
  private def normalize(message: String): String =
    message.replaceAll("'[^']*'", "'X'").replaceAll("\\d+", "N")

  def record(level: String, logger: String, message: String): Unit = synchronized {
    events.lastOption match {
      case Some(last) if last.level == level && last.logger == logger && normalize(last.message) == normalize(message) =>
        events(events.size - 1) = last.copy(at = Instant.now(), message = message, count = last.count + 1)
      case _ =>
        events.enqueue(LogEvent(Instant.now(), level, logger, message, count = 1))
        while (events.size > capacity) events.dequeue()
    }
  }

  def recent(): List[LogEvent] = synchronized { events.toList.reverse }
}

object LogCapture {
  val instance = new LogCapture()
}
