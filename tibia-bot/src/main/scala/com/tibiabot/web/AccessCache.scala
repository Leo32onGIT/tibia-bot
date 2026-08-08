package com.tibiabot.web

import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentHashMap

/** Remembers what a visitor was allowed to do, for a few seconds.
 *
 *  Resolving access asks Discord whether somebody is a member of a guild and
 *  which channels they can see, which is a REST call — the bot keeps no member
 *  cache. That was being paid on *every* request, including the poll each open
 *  tab makes every ten seconds, so simply having the board open cost a round
 *  trip to Discord six times a minute and put a few hundred milliseconds on
 *  every read.
 *
 *  Permissions do change, though, so this is a short memory rather than a
 *  store: long enough that a poll is free, short enough that somebody who lost
 *  a role waits seconds rather than minutes. Anything that acts on somebody
 *  else's claim ignores it entirely and resolves live — see
 *  [[DashboardAccessService.accessFor]] — so the worst a stale entry can do is
 *  let somebody *read* a board they were removed from moments ago.
 *
 *  Entries are dropped on read once expired, and the whole map is swept when it
 *  grows past a bound, so an idle process does not hold a row per visitor for
 *  ever.
 */
final class AccessCache(ttl: Duration, maxEntries: Int = AccessCache.MaxEntries,
                        now: () => Instant = () => Instant.now()) {

  private final case class Entry(value: List[GuildAccess], expiresAt: Instant)

  private val entries = new ConcurrentHashMap[String, Entry]()

  /** The remembered answer, or nothing when there isn't a fresh one.
   *
   *  The key carries the guild list the answer was worked out from, so signing
   *  in again with a different set of servers cannot be answered from the old
   *  one. Nothing else invalidates this: what it holds is permissions, and no
   *  claim or booking changes those.
   */
  def get(key: String): Option[List[GuildAccess]] =
    Option(entries.get(key)) match {
      case Some(entry) if entry.expiresAt.isAfter(now()) => Some(entry.value)
      case Some(_) => entries.remove(key); None
      case None    => None
    }

  def put(key: String, access: List[GuildAccess]): Unit = {
    if (entries.size >= maxEntries) sweep()
    entries.put(key, Entry(access, now().plus(ttl)))
    ()
  }

  def size: Int = entries.size

  /** Drop what has expired; if that frees nothing, drop everything rather than
   *  grow without bound. A cleared cache costs a REST call per visitor once,
   *  which is the same price as never having cached at all. */
  private def sweep(): Unit = {
    val cutoff = now()
    entries.entrySet().removeIf(e => !e.getValue.expiresAt.isAfter(cutoff))
    if (entries.size >= maxEntries) entries.clear()
  }
}

object AccessCache {
  /** Long enough that a ten-second poll never pays for a Discord call, short
   *  enough that a permission change is felt in under a minute. */
  val DefaultTtl: Duration = Duration.ofSeconds(45)

  /** A row per signed-in visitor. Generous for a bot this size, and bounded so
   *  a burst of visitors cannot grow the heap indefinitely. */
  val MaxEntries: Int = 5000
}
