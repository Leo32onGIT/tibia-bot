package com.tibiabot.observer

import com.tibiabot.domain.MiniWorldChange
import com.tibiabot.scheduler.ServerSaveSchedule
import com.typesafe.scalalogging.StrictLogging

import java.time.{Duration, Instant, ZonedDateTime}

/** Watches the pooled mini world changes and amends the server-save message when a
 *  world's set changes.
 *
 *  The notifications message posts once TibiaData reports the new boosted boss and
 *  creature, but the Observer feed rolls over to the new day's changes at a
 *  different moment — several minutes after 10:00. Until it does, the feed holds a
 *  world's changes back (see ObserverFeed.todays), so the message goes out without
 *  them. This polls it (every `FastInterval` through the server-save window, every
 *  `SlowInterval` otherwise, or only when needed — see `quietOutsideWindow` below)
 *  and hands `amend` the worlds whose changes differ from
 *  the previous poll, which is what adds them. Each update is logged with its
 *  offset from server save, which is what shows when the feed actually rolls over.
 *
 *  The first poll only records what is active: there is nothing to compare it with,
 *  and the message it would amend was built from the same feed. A poll after a
 *  server save is compared with nothing rather than the poll before it: yesterday's
 *  changes going is the save, not a change, and today's message never had them. A
 *  poll that failed (`fetch` returns `None`) is skipped without forgetting the last
 *  good set, so a sidecar hiccup never reads as every change ending.
 *
 *  A message can also go out while there are no changes to be had at all — right
 *  after server save, before a fresh pool is in, or while the feed is down.
 *  `answeredWithout` says so (see ObserverFeed.takeAnsweredWithout), and the next
 *  good poll amends every world it has changes for, not only those that moved on:
 *  a world whose changes stayed the same would otherwise never get them.
 *
 *  `tick` is meant to run once a minute; it decides for itself when a poll is due.
 *  A bot reading the primary's published copy rather than the API can pass shorter
 *  intervals: its polls are Redis reads, and the primary's own cadence already
 *  bounds how often the copy changes.
 *
 *  A bot that asks the API itself passes `quietOutsideWindow`: mini world changes
 *  only change at server save, so outside the server-save window it polls only when
 *  it has no good poll since the day's changes settled — `SettledAfter` past the
 *  save, the one poll just after the window, or the first since boot or after a
 *  window whose polls all failed — or when `rulesChanged` says an account's rules
 *  just changed, which can add worlds to the pool. Those polls are `fastInterval`
 *  apart until one gets through. */
final class MiniWorldChangeWatcher(
  fetch: () => Option[Map[String, List[MiniWorldChange]]],
  amend: Set[String] => Unit,
  now: () => ZonedDateTime,
  fastInterval: Duration = MiniWorldChangeWatcher.FastInterval,
  slowInterval: Duration = MiniWorldChangeWatcher.SlowInterval,
  answeredWithout: () => Boolean = () => false,
  quietOutsideWindow: Boolean = false,
  rulesChanged: () => Boolean = () => false
) extends StrictLogging {
  import MiniWorldChangeWatcher._

  /** The last good poll: when it ran and what was active then. */
  @volatile private var seen: Option[(Instant, Map[String, Set[(String, String)]])] = None
  @volatile private var lastPoll: Instant = Instant.EPOCH

  def tick(): Unit =
    try {
      val at = now()
      val inWindow = ServerSaveSchedule.isServerSaveWindow(at.toLocalTime)
      val quiet = quietOutsideWindow && !inWindow
      val interval = if (inWindow || quiet) fastInterval else slowInterval
      // `rulesChanged` is only asked when a poll is due, so a change noted between
      // polls isn't lost.
      if (!lastPoll.plus(interval).isAfter(at.toInstant) && (!quiet || unsettled(at) || rulesChanged())) {
        lastPoll = at.toInstant
        poll(at)
      }
    } catch {
      case ex: Throwable => logger.warn("Mini world change poll failed", ex)
    }

  /** No good poll since the day's changes settled, `SettledAfter` past the latest
   *  server save. */
  private def unsettled(at: ZonedDateTime): Boolean = {
    val settled = ServerSaveSchedule.lastServerSave(at).toInstant.plus(SettledAfter)
    seen.forall { case (polledAt, _) => polledAt.isBefore(settled) }
  }

  private def poll(at: ZonedDateTime): Unit =
    fetch().foreach { byWorld =>
      val current = signatures(byWorld)
      val save = ServerSaveSchedule.lastServerSave(at)
      val moved = seen.map { case (polledAt, previous) =>
        changedWorlds(if (polledAt.isBefore(save.toInstant)) Map.empty else previous, current)
      }.getOrElse(Set.empty)
      if (moved.nonEmpty) {
        val sinceSave = Duration.between(save, at).toMinutes
        logger.info(s"Mini world changes updated ${sinceSave}m after server save on ${moved.size} world(s): " +
          moved.toList.sorted.mkString(", "))
      }
      val missed = if (answeredWithout()) current.keySet -- moved else Set.empty[String]
      if (missed.nonEmpty)
        logger.info(s"Adding mini world changes to messages posted without them on ${missed.size} world(s)")
      if (moved.nonEmpty || missed.nonEmpty) amend(moved ++ missed)
      seen = Some(at.toInstant -> current)
    }
}

object MiniWorldChangeWatcher {

  /** How often to poll between 10:00 and 10:45, while the feed may be rolling over. */
  val FastInterval: Duration = Duration.ofMinutes(2)

  /** How often to poll the rest of the day, for a bot that isn't quiet outside the
   *  window (one reading the primary's copy). */
  val SlowInterval: Duration = Duration.ofMinutes(15)

  /** How long after server save the day's changes can be taken as settled: the end
   *  of the server-save window, when ObserverFeed also stops holding back a world
   *  whose changes are the same as yesterday's (`SameAsYesterdayAfter`). */
  val SettledAfter: Duration = ObserverFeed.SameAsYesterdayAfter

  /** What identifies a world's active changes: each one's title and description. */
  def signatures(byWorld: Map[String, List[MiniWorldChange]]): Map[String, Set[(String, String)]] =
    byWorld.view.mapValues(_.map(c => (c.title.toLowerCase, c.body)).toSet).filter(_._2.nonEmpty).toMap

  /** Worlds whose changes differ between two polls — including a world whose last
   *  change ended, so its embed is taken back out. */
  def changedWorlds(previous: Map[String, Set[(String, String)]],
                    current: Map[String, Set[(String, String)]]): Set[String] =
    (previous.keySet ++ current.keySet).filter(w => previous.get(w) != current.get(w))
}
