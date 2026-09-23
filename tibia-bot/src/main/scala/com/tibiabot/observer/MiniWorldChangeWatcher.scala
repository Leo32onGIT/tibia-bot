package com.tibiabot.observer

import com.tibiabot.domain.MiniWorldChange
import com.tibiabot.scheduler.ServerSaveSchedule
import com.typesafe.scalalogging.StrictLogging

import java.time.{Duration, Instant, ZonedDateTime}

/** Watches the pooled mini world changes and amends the server-save message when a
 *  world's set changes.
 *
 *  The notifications message posts once TibiaData reports the new boosted boss and
 *  creature, but the Observer feed may roll over to the new day's changes at a
 *  different moment — possibly only once the game server is back up, several
 *  minutes after 10:00. So rather than trust whatever the feed said at posting time,
 *  this polls it (every `FastInterval` through the server-save window, every
 *  `SlowInterval` otherwise) and hands `amend` the worlds whose changes differ from
 *  the previous poll. Each update is logged with its offset from server save, which
 *  is what shows when the feed actually rolls over.
 *
 *  The first poll only records what is active: there is nothing to compare it with,
 *  and the message it would amend was built from the same feed. A poll that failed
 *  (`fetch` returns `None`) is skipped without forgetting the last good set, so a
 *  sidecar hiccup never reads as every change ending.
 *
 *  `tick` is meant to run once a minute; it decides for itself when a poll is due. */
final class MiniWorldChangeWatcher(
  fetch: () => Option[Map[String, List[MiniWorldChange]]],
  amend: Set[String] => Unit,
  now: () => ZonedDateTime
) extends StrictLogging {
  import MiniWorldChangeWatcher._

  @volatile private var seen: Option[Map[String, Set[(String, String)]]] = None
  @volatile private var lastPoll: Instant = Instant.EPOCH

  def tick(): Unit =
    try {
      val at = now()
      val interval = if (ServerSaveSchedule.isServerSaveWindow(at.toLocalTime)) FastInterval else SlowInterval
      if (!lastPoll.plus(interval).isAfter(at.toInstant)) {
        lastPoll = at.toInstant
        poll(at)
      }
    } catch {
      case ex: Throwable => logger.warn("Mini world change poll failed", ex)
    }

  private def poll(at: ZonedDateTime): Unit =
    fetch().foreach { byWorld =>
      val current = signatures(byWorld)
      seen.foreach { previous =>
        val worlds = changedWorlds(previous, current)
        if (worlds.nonEmpty) {
          val sinceSave = Duration.between(ServerSaveSchedule.lastServerSave(at), at).toMinutes
          logger.info(s"Mini world changes updated ${sinceSave}m after server save on ${worlds.size} world(s): " +
            worlds.toList.sorted.mkString(", "))
          amend(worlds)
        }
      }
      seen = Some(current)
    }
}

object MiniWorldChangeWatcher {

  /** How often to poll between 10:00 and 10:45, while the feed may be rolling over. */
  val FastInterval: Duration = Duration.ofMinutes(2)

  /** How often to poll the rest of the day, in case a change starts or ends mid-day. */
  val SlowInterval: Duration = Duration.ofMinutes(15)

  /** What identifies a world's active changes: each one's title and description. */
  def signatures(byWorld: Map[String, List[MiniWorldChange]]): Map[String, Set[(String, String)]] =
    byWorld.view.mapValues(_.map(c => (c.title.toLowerCase, c.body)).toSet).filter(_._2.nonEmpty).toMap

  /** Worlds whose changes differ between two polls — including a world whose last
   *  change ended, so its embed is taken back out. */
  def changedWorlds(previous: Map[String, Set[(String, String)]],
                    current: Map[String, Set[(String, String)]]): Set[String] =
    (previous.keySet ++ current.keySet).filter(w => previous.get(w) != current.get(w))
}
