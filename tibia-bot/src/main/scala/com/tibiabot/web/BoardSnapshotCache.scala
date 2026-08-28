package com.tibiabot.web

import com.tibiabot.respawn.RespawnBoardEntry

import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentHashMap

/** The board of a guild, held for a few seconds and shared by everyone reading
 *  it.
 *
 *  A board is the same for every member of a guild — which spawn is taken, by
 *  whom, until when. Only the trimmings differ per reader, and those are worked
 *  out from the same rows. Yet every open tab polls every ten seconds and each
 *  poll went to the database for its own copy, so a guild with ten people
 *  watching paid for the whole board sixty times a minute to answer sixty
 *  identical questions.
 *
 *  Held in this process rather than in Redis, deliberately. The database is on
 *  the same host and the rows are small; a Redis round trip is not obviously
 *  cheaper than the reads it would replace, and it would buy the one thing not
 *  wanted here — an answer outliving a restart. A cold process reads the board
 *  once and is warm.
 *
 *  ==Staleness==
 *  Bounded by the shorter of two things: this TTL, and a write going through
 *  the dashboard, which clears the guild's entry so whoever made it sees their
 *  own change at once. What is left is a change made somewhere this cannot see
 *  — a Claim button pressed in Discord, or another bot's sweep — which shows up
 *  within the TTL. That is well inside the poll it is answering, so nobody sees
 *  a board older than they would have anyway.
 */
final class BoardSnapshotCache(
  read: String => List[RespawnBoardEntry],
  ttl: Duration = BoardSnapshotCache.DefaultTtl,
  maxEntries: Int = BoardSnapshotCache.MaxEntries,
  now: () => Instant = () => Instant.now()
) {

  // From the companion rather than from here: a case class nested in a class
  // carries a reference to its enclosing instance, which the compiler cannot
  // check when matching on it, and warns about every time this cache reads an
  // entry back out.
  import BoardSnapshotCache.Entry

  private val entries = new ConcurrentHashMap[String, Entry]()

  /** This guild's board, from memory when it is fresh and from the database
   *  when it is not.
   *
   *  Two readers arriving together on a cold entry both read: the alternative
   *  is holding a lock across a database call, which trades a rare duplicated
   *  read for a queue of threads waiting on one. The answer is the same either
   *  way, so the duplicate costs only itself.
   */
  def board(guildId: String): List[RespawnBoardEntry] =
    Option(entries.get(guildId)) match {
      case Some(entry) if entry.expiresAt.isAfter(now()) => entry.board
      case _ =>
        val fresh = read(guildId)
        if (entries.size >= maxEntries) sweep()
        entries.put(guildId, Entry(fresh, now().plus(ttl)))
        fresh
    }

  /** Forget a guild, because something just changed it.
   *
   *  Called after a write rather than instead of the TTL: it is what makes the
   *  person who acted see their action, where the TTL is what covers everybody
   *  else and every change this process never saw.
   */
  def invalidate(guildId: String): Unit = { entries.remove(guildId); () }

  def size: Int = entries.size

  private def sweep(): Unit = {
    val cutoff = now()
    entries.entrySet().removeIf(e => !e.getValue.expiresAt.isAfter(cutoff))
    if (entries.size >= maxEntries) entries.clear()
  }
}

object BoardSnapshotCache {
  private final case class Entry(board: List[RespawnBoardEntry], expiresAt: Instant)

  /** Short enough to be invisible against a ten-second poll, long enough that
   *  every tab watching one guild is answered from a single read. */
  val DefaultTtl: Duration = Duration.ofSeconds(3)

  /** A row per guild being watched. Far above what this bot has, and bounded
   *  so it cannot grow without limit. */
  val MaxEntries: Int = 2000
}
