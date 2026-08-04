package com.tibiabot.tracking

import java.time.ZonedDateTime
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/** Short-lived memory of killer levels resolved from the TibiaData character
 *  endpoint, for the `[level]` shown beside a PvP killer's name on a death.
 *
 *  Only consulted for killers missing from TibiaBot's own `onlineListTable`
 *  (players online on this world, refreshed every 5 minutes) — so in practice
 *  this holds cross-world killers and anyone who logged in since that table
 *  was last rebuilt.
 *
 *  Failed lookups are cached too, as `NoLevel`. Without that, a killer the
 *  API has no answer for (deleted character, transfer in progress, a summon
 *  name that got past the parser) is re-fetched on every death they appear in
 *  — and `getKillerFallback` deliberately bypasses the Date-header character
 *  cache, so every one of those is a full request and parse.
 *
 *  Names are matched case-insensitively, matching `onlineListTable`.
 *  Thread-safe: filled from a stream thread, read while building embeds.
 */
final class KillerLevelCache(ttl: FiniteDuration, maxEntries: Int = 4096) {

  private case class Entry(level: Option[Int], at: ZonedDateTime)

  private val lock = new Object()
  private val entries = mutable.Map.empty[String, Entry]

  private def fresh(entry: Entry, now: ZonedDateTime): Boolean =
    java.time.Duration.between(entry.at, now).getSeconds < ttl.toSeconds

  /** Is a lookup for this name worth making — i.e. is there no fresh answer
   *  (successful *or* failed) already? */
  def needsLookup(name: String, now: ZonedDateTime): Boolean = lock.synchronized {
    !entries.get(name.toLowerCase).exists(fresh(_, now))
  }

  /** The cached level, or None if unknown, expired, or known to have no level. */
  def levelFor(name: String, now: ZonedDateTime): Option[Int] = lock.synchronized {
    entries.get(name.toLowerCase).filter(fresh(_, now)).flatMap(_.level)
  }

  /** Record the outcome of a lookup — `None` records a failure (see class doc). */
  def record(name: String, level: Option[Int], now: ZonedDateTime): Unit = lock.synchronized {
    entries.update(name.toLowerCase, Entry(level, now))
    if (entries.size > maxEntries) evictOldest(entries.size - maxEntries)
  }

  /** Record a failure for `name` only if nothing fresh is already known, and
   *  report whether it did.
   *
   *  For giving up on a lookup that never came back: the request may land
   *  between deciding it is unresolved and writing it off, and a real level
   *  that arrived must not be overwritten by the write-off. Checking and
   *  writing under one lock closes that window, and makes the count of names
   *  actually written off exact rather than an estimate. */
  def recordMissIfAbsent(name: String, now: ZonedDateTime): Boolean = lock.synchronized {
    val key = name.toLowerCase
    if (entries.get(key).exists(fresh(_, now))) false
    else {
      entries.update(key, Entry(None, now))
      if (entries.size > maxEntries) evictOldest(entries.size - maxEntries)
      true
    }
  }

  /** Drop expired entries. Called once per death batch, not per lookup. */
  def prune(now: ZonedDateTime): Unit = lock.synchronized {
    entries.filterInPlace { case (_, entry) => fresh(entry, now) }
  }

  def size: Int = lock.synchronized { entries.size }

  // Only reachable if a single batch inserted more distinct killers than the
  // cap allows before the next prune — a hard backstop, not the normal path.
  private def evictOldest(count: Int): Unit =
    entries.toList.sortBy(_._2.at.toEpochSecond).take(count).foreach { case (name, _) => entries.remove(name) }
}
