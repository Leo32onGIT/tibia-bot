package com.tibiabot.web

import java.time.Instant
import scala.collection.mutable

final case class LogEvent(at: Instant, level: String, logger: String, message: String, count: Int)

/** A bounded, thread-safe log of the most recent WARN and ERROR level events,
 *  for the monitoring dashboard's log-alerts widget. [[LogCapture.instance]]
 *  is the single shared instance [[DashboardLogAppender]] writes to —
 *  Logback instantiates appenders itself via reflection (from logback.xml),
 *  so it can't be handed a specific instance the way BotApp wires its other
 *  dependencies; this is the seam.
 *
 *  Errors and warnings are kept in separate bounded buffers (each up to
 *  `capacity`, independently), not one shared buffer split by count — a
 *  burst of frequent warnings would otherwise evict rare errors before
 *  anyone saw them. Same shape as [[com.tibiabot.tracking.RecentEvents]]
 *  within each buffer: recording never removes anything except to stay under
 *  `capacity`, and reading never removes anything at all.
 *
 *  Each buffer holds one entry per distinct *shape* (see [[normalize]]) per
 *  logger. A repeat bumps that entry's count and refreshes its message and
 *  timestamp, wherever it already sits, and moves it to the most-recent end —
 *  so a bursty repeating warning can't flood the buffer and push out
 *  genuinely different alerts, even when the message embeds a different value
 *  each time (a character name, a channel id, a retry-after ms count), which
 *  is the common case for real repeating warnings rather than the exception.
 *
 *  Matching across the whole buffer, rather than only against its newest
 *  entry, is what makes this hold when two noisy sources overlap: the
 *  TibiaData poll and JDA's Discord rate limiter can both burst at once, and
 *  while they interleave, run-only matching would collapse neither and fill
 *  every slot with alternating near-duplicates. The tradeoff is that
 *  ordering no longer distinguishes "A, then B, then A again" from "B, then
 *  A" — only each shape's latest occurrence and total count are kept, which
 *  is what an at-a-glance alerts panel wants. */
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
