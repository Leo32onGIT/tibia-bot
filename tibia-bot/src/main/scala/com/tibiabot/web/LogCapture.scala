package com.tibiabot.web

import java.time.Instant
import scala.collection.mutable

final case class LogEvent(at: Instant, level: String, logger: String, message: String, count: Int)

/** A bounded, thread-safe log of the most recent WARN and ERROR events, for the
 *  dashboard's log-alerts widget. [[LogCapture.instance]] is the shared instance
 *  [[DashboardLogAppender]] writes to — Logback builds appenders by reflection, so
 *  it cannot be handed one the way BotApp wires everything else.
 *
 *  Errors and warnings get separate bounded buffers, each up to `capacity`, rather
 *  than one shared buffer, or a burst of warnings would evict rare errors before
 *  anyone saw them. Within each, recording removes nothing except to stay under
 *  `capacity` and reading removes nothing at all.
 *
 *  Each buffer holds one entry per distinct *shape* (see `normalize`) per logger. A
 *  repeat bumps its count, refreshes its message and timestamp, and moves it to
 *  the recent end — so a bursty warning cannot flood the buffer even when the
 *  message embeds a different value each time, which is the common case.
 *
 *  Matching across the whole buffer rather than only its newest entry is what
 *  holds when two noisy sources overlap: the TibiaData poll and JDA's rate limiter
 *  can burst together, and run-only matching would collapse neither. The tradeoff
 *  is that ordering no longer distinguishes "A, B, A" from "B, A" — only each
 *  shape's latest occurrence and total count, which is what an at-a-glance alerts
 *  panel wants. */
final class LogCapture(capacity: Int = 50) {
  private val errorEvents = mutable.Queue.empty[LogEvent]
  private val warnEvents = mutable.Queue.empty[LogEvent]

  /** Blanks out the parts of a message likely to vary between otherwise-
   *  identical occurrences (quoted values, numbers) so two warnings differing
   *  only by, say, a character name or a channel id compare as the same
   *  shape. Deliberately approximate — an occasional false-positive collapse
   *  of two genuinely distinct messages is an acceptable tradeoff for a
   *  monitoring widget, not a correctness-sensitive comparison.
   *
   *  The quote-matching regex treats a `'` as content rather than a closing
   *  delimiter when it's immediately followed by a word character — e.g. a
   *  Tibia character name like "Sir'Locke" embeds its own apostrophe, and a
   *  naive `'[^']*'` would close the match on that inner apostrophe instead
   *  of the real closing quote, leaving the rest of the message unblanked
   *  and different on every occurrence. A genuine closing quote is followed
   *  by whitespace, punctuation, or the end of the message, never a letter
   *  or digit. */
  private def normalize(message: String): String =
    message.replaceAll("'(?:[^']|'(?=\\w))*'", "'X'").replaceAll("\\d+", "N")

  private def recordInto(queue: mutable.Queue[LogEvent], level: String, logger: String, message: String): Unit = {
    val shape = normalize(message)
    val existing = queue.indexWhere(e => e.logger == logger && normalize(e.message) == shape)
    if (existing >= 0) {
      val merged = queue(existing).copy(at = Instant.now(), message = message, count = queue(existing).count + 1)
      queue.remove(existing)
      queue.enqueue(merged) // it just happened, so it is now the most recent
    } else {
      queue.enqueue(LogEvent(Instant.now(), level, logger, message, count = 1))
      while (queue.size > capacity) queue.dequeue()
    }
  }

  def record(level: String, logger: String, message: String): Unit = synchronized {
    if (level == "ERROR") recordInto(errorEvents, level, logger, message)
    else recordInto(warnEvents, level, logger, message)
  }

  def recentErrors(): List[LogEvent] = synchronized { errorEvents.toList.reverse }
  def recentWarnings(): List[LogEvent] = synchronized { warnEvents.toList.reverse }
}

object LogCapture {
  val instance = new LogCapture()
}
