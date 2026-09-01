package com.tibiabot.web

import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentHashMap

/** Remembers what a visitor was allowed to do.
 *
 *  Resolving access is a Discord REST call — the bot keeps no member cache — and
 *  it was paid on *every* request, including the ten-second poll each open tab
 *  makes, so having the board open cost six round trips a minute.
 *
 *  Permissions change, so this is a memory rather than a store. Anything acting on
 *  somebody else's claim ignores it and resolves live (see
 *  [[DashboardAccessService.accessFor]]), so the worst a stale entry does is let
 *  somebody *read* a board they were removed from moments ago.
 *
 *  ==Two horizons==
 *  Three states rather than two, because "old enough to refresh" and "too old to
 *  use" are different questions that were answered with one number.
 *
 *   - Fresh, until `staleAfter`: used as it stands.
 *   - Stale, until `hardTtl`: still handed to the reader, who is expected to kick
 *     off a refresh behind them — see
 *     [[DashboardAccessService.rememberedReportFor]].
 *   - Gone: resolved from scratch with the reader waiting.
 *
 *  The middle state is what lets a busy dashboard scale. With one horizon, every
 *  entry falling due put a blocking chain of Discord calls in front of whichever
 *  poll arrived at that moment, so the cost was paid on the request path by a
 *  random unlucky reader. Refreshing behind the old answer takes that off the
 *  request path without making anybody's answer older: the refresh fires exactly
 *  when the old code would have re-resolved.
 *
 *  Expired entries are dropped on read and the map is swept past a bound, so an
 *  idle process does not hold a row per visitor for ever. */
final class AccessCache(staleAfter: Duration,
                        /** How long past `staleAfter` an answer may still be
                         *  served while a refresh is tried. Bounds how long a
                         *  *failing* refresh can keep an old answer alive; past
                         *  it, a reader waits for a live one. */
                        hardTtl: Duration = AccessCache.DefaultHardTtl,
                        /** How long an *incomplete* pass is remembered — one
                         *  that failed to reach a server. Much shorter, and
                         *  never served stale; see [[put]]. */
                        partialTtl: Duration = AccessCache.DefaultPartialTtl,
                        maxEntries: Int = AccessCache.MaxEntries,
                        now: () => Instant = () => Instant.now()) {

  // From the companion rather than from here: a case class nested in a class
  // carries a reference to its enclosing instance, which the compiler cannot
  // check when matching on it, and warns about every time this cache reads an
  // entry back out.
  import AccessCache.Entry

  private val entries = new ConcurrentHashMap[String, Entry]()

  /** The remembered answer and whether it wants refreshing, or nothing when
   *  there isn't one worth handing over at all.
   *
   *  The key carries the guild list the answer was worked out from, so signing
   *  in again with a different set of servers cannot be answered from the old
   *  one. Nothing else invalidates this: what it holds is permissions, and no
   *  claim or booking changes those.
   */
  def get(key: String): Option[AccessCache.Cached] = {
    val at = now()
    Option(entries.get(key)) match {
      case Some(entry) if entry.expiresAt.isAfter(at) =>
        Some(AccessCache.Cached(entry.value, stale = !entry.staleAt.isAfter(at)))
      case Some(_) => entries.remove(key); None
      case None    => None
    }
  }

  /** Remember a pass, for as long as it deserves to be believed.
   *
   *  A complete answer gets both horizons: refreshable after `staleAfter`,
   *  unusable after `hardTtl`.
   *
   *  An incomplete one gets neither, and is simply dropped after
   *  `partialTtl`. It is not really an answer — it is a report that something
   *  was too slow once — so there is nothing here worth serving stale, and
   *  "reload in a moment to try again" has to mean a real retry rather than
   *  being handed the same failure back. Kept for the full window it turned a
   *  single missed round trip into three quarters of a minute of a picker that
   *  said a server was missing, on every reload, long after the bot in question
   *  had started answering again.
   */
  def put(key: String, access: AccessReport): Unit = {
    if (entries.size >= maxEntries) sweep()
    val at = now()
    val entry =
      if (access.complete) Entry(access, at.plus(staleAfter), at.plus(hardTtl))
      else Entry(access, at.plus(partialTtl), at.plus(partialTtl))
    entries.put(key, entry)
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
  /** The whole [[AccessReport]], not just the guilds it granted. A pass that
   *  failed to reach a server has to keep saying so for as long as it is
   *  remembered - caching only the successes would turn an incomplete answer
   *  into one indistinguishable from a complete one, which is precisely the
   *  confusion the report exists to remove. */
  private final case class Entry(value: AccessReport, staleAt: Instant, expiresAt: Instant)

  /** A remembered answer, and whether whoever takes it should refresh it. */
  final case class Cached(report: AccessReport, stale: Boolean)

  /** How long an answer stands before it is worth resolving again.
   *
   *  Was forty-five seconds, from when every expiry was paid by a reader waiting on
   *  Discord. Two things make three minutes better now: a refresh no longer blocks
   *  anybody, so a shorter window only shrinks how long a *read* can be stale
   *  (moderator actions resolve live and are not governed by this); and the
   *  Discord calls are the binding constraint on how many people can have the
   *  dashboard open — the rate is visitors divided by this number, against a
   *  global budget of ~50 calls a second, and at forty-five seconds a few hundred
   *  visitors took a fifth of it.
   *
   *  The trade: somebody removed from a channel can keep *reading* that board for
   *  three minutes rather than one. They cannot act on anybody else's claim in
   *  that window — see [[DashboardAccessService.accessIn]]. */
  val DefaultStaleAfter: Duration = Duration.ofMinutes(3)

  /** The outer horizon: how long an answer may be served while refreshes are
   *  failing. Reached only when Discord has been unreachable for the whole of
   *  it, at which point a reader waits for a live answer rather than being
   *  handed one this old. */
  val DefaultHardTtl: Duration = Duration.ofMinutes(10)

  /** How long a pass that could not reach a server is remembered.
   *
   *  Long enough to absorb the burst of requests one page load makes, short
   *  enough that "try again" means trying again rather than being handed the
   *  same failure back from memory. */
  val DefaultPartialTtl: Duration = Duration.ofSeconds(5)

  /** A row per signed-in visitor. Generous for a bot this size, and bounded so
   *  a burst of visitors cannot grow the heap indefinitely. */
  val MaxEntries: Int = 5000
}
