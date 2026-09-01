package com.tibiabot.web

import com.tibiabot.domain.{Respawn, RespawnClaim, RespawnSchedule}

import java.time.{Duration, Instant, ZonedDateTime}
import java.util.concurrent.ConcurrentHashMap

/** Every row a guild's calendar is drawn from, over a window, in one read. The
 *  grid is per spawn but the rows behind it are not: one query returns every
 *  reservation, another every rule, and surrendered days come back keyed by
 *  schedule either way. Grouping them here lets one read answer a panel that used
 *  to ask five questions per spawn per week. */
final case class CalendarRows(
  /** The guild's catalogue. Here because resolving the code the page asked about
   *  is a database read like any other, and was the one still paid per request: a
   *  card polling every ten seconds asked which spawn "415" is sixty times a
   *  minute. It belongs to the guild rather than the window, so a snapshot read
   *  for one window answers for any other. */
  respawns: List[Respawn],
  /** The hunt in progress on each spawn, where there is one. */
  active: Map[Long, RespawnClaim],
  /** Bookings that have not started, by spawn, soonest first. */
  reservations: Map[Long, List[RespawnClaim]],
  /** Live repeat rules, by spawn. */
  schedules: Map[Long, List[RespawnSchedule]],
  /** Days each rule has given up, keyed by schedule — see RespawnService. */
  givenUp: Map[Long, Set[Instant]],
  /** The window these rows were read for. A request reaching past either end
   *  cannot be served from them, and says so by falling through to its own
   *  read — see [[CalendarSnapshotCache.covers]]. */
  from: ZonedDateTime,
  to: ZonedDateTime
)

/** A guild's calendar rows, held for a few seconds and shared by every reader.
 *
 *  The same bargain [[BoardSnapshotCache]] makes, for the payload still paying
 *  full price: a board is one request per tab, a calendar one per spawn per week
 *  per tab, each going to the database for rows every other reader wanted at the
 *  same moment. Ten people browsing one guild could ask for the same fortnight a
 *  hundred times over.
 *
 *  In this process rather than Redis, for the reasons the board cache sets out.
 *
 *  ==The window==
 *  Read once for a horizon covering what the panel reaches in ordinary use — a
 *  week behind, six weeks ahead — and sliced per spawn and per request inside it.
 *  A request outside it is rare enough to be worth its own read, rather than a
 *  horizon wide enough to make every read expensive.
 *
 *  ==Staleness==
 *  Bounded as the board's is: this TTL, and a dashboard write clearing the guild
 *  so whoever booked something sees it at once. A booking made in Discord shows up
 *  within the TTL, inside the poll that would have noticed it anyway. */
final class CalendarSnapshotCache(
  read: (String, ZonedDateTime, ZonedDateTime) => CalendarRows,
  ttl: Duration = CalendarSnapshotCache.DefaultTtl,
  maxEntries: Int = CalendarSnapshotCache.MaxEntries,
  now: () => ZonedDateTime = () => ZonedDateTime.now()
) {

  import CalendarSnapshotCache.Entry

  private val entries = new ConcurrentHashMap[String, Entry]()

  /** The rows for one guild covering `from`..`to`, from memory where they are
   *  fresh and wide enough, and from the database where they are not.
   *
   *  A window the snapshot does not cover is read directly and *not* stored:
   *  caching it would either replace a horizon that serves everybody with one
   *  that serves the outlier, or need a second entry per shape of window. The
   *  outlier is somebody who has scrolled two months out, and they can pay for
   *  their own answer.
   */
  def rows(guildId: String, from: ZonedDateTime, to: ZonedDateTime): CalendarRows = {
    val at = now()
    Option(entries.get(guildId)) match {
      case Some(entry) if entry.expiresAt.isAfter(at.toInstant) && covers(entry.rows, from, to) =>
        entry.rows
      case Some(entry) if entry.expiresAt.isAfter(at.toInstant) =>
        read(guildId, from, to)
      case _ =>
        val horizonFrom = at.minusDays(CalendarSnapshotCache.DaysBehind)
        val horizonTo = at.plusDays(CalendarSnapshotCache.DaysAhead)
        val fresh = read(guildId, horizonFrom, horizonTo)
        if (entries.size >= maxEntries) sweep()
        entries.put(guildId, Entry(fresh, at.toInstant.plus(ttl)))
        if (covers(fresh, from, to)) fresh else read(guildId, from, to)
    }
  }

  private def covers(rows: CalendarRows, from: ZonedDateTime, to: ZonedDateTime): Boolean =
    !from.isBefore(rows.from) && !to.isAfter(rows.to)

  /** Forget a guild, because something just changed it. */
  def invalidate(guildId: String): Unit = { entries.remove(guildId); () }

  def size: Int = entries.size

  private def sweep(): Unit = {
    val cutoff = now().toInstant
    entries.entrySet().removeIf(e => !e.getValue.expiresAt.isAfter(cutoff))
    if (entries.size >= maxEntries) entries.clear()
  }
}

object CalendarSnapshotCache {
  private final case class Entry(rows: CalendarRows, expiresAt: Instant)

  /** Short enough to be invisible against a ten-second poll, long enough that
   *  every panel open on one guild is answered from a single read. */
  val DefaultTtl: Duration = Duration.ofSeconds(3)

  /** How far either side of now one read reaches. Yesterday is where the strip
   *  opens and a week behind covers scrolling back over what just happened;
   *  six weeks ahead is past where anybody books. */
  val DaysBehind: Long = 7
  val DaysAhead: Long = 42

  /** A row per guild being watched, bounded so it cannot grow without limit. */
  val MaxEntries: Int = 2000
}
