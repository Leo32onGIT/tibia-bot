package com.tibiabot.tracking

import java.time.ZonedDateTime
import scala.collection.mutable

/** Online-presence state used by TibiaBot each tracking cycle: who's online,
 *  their guild/flag, and how long they've been online. Keyed by player name
 *  in an insertion-ordered map for O(1) lookups/updates; behaviour is pinned
 *  by OnlineTrackerSpec.
 */
final case class OnlinePlayer(
  name: String,
  level: Int,
  vocation: String,
  guildName: String,
  time: ZonedDateTime,
  duration: Long = 0L,
  flag: String = ""
)

final class OnlineTracker {
  // keyed by name; LinkedHashMap keeps a stable order for snapshots (order is
  // irrelevant downstream since onlineList re-sorts, but it keeps behaviour
  // predictable and tests deterministic).
  private val state = mutable.LinkedHashMap.empty[String, OnlinePlayer]

  def size: Int = state.size
  def snapshot: List[OnlinePlayer] = state.values.toList

  /** Replace presence from a fresh online list, carrying over guildName /
   *  duration / flag for players already present. Players absent from `online`
   *  are dropped (they logged off). Incoming `level` is already parsed to Int,
   *  exactly as `player.level.toInt` in the flow. */
  def updateFromOnline(online: Seq[(String, Int, String)], now: ZonedDateTime): Unit = {
    // build the next state reading from the *current* one, then swap in.
    val rebuilt = mutable.LinkedHashMap.empty[String, OnlinePlayer]
    online.foreach { case (name, level, vocation) =>
      val updated = state.get(name) match {
        case Some(existing) =>
          val delta = now.toEpochSecond - existing.time.toEpochSecond
          OnlinePlayer(name, level, vocation, existing.guildName, now, existing.duration + delta, existing.flag)
        case None =>
          OnlinePlayer(name, level, vocation, "", now, 0L, "")
      }
      rebuilt.put(name, updated)
    }
    state.clear()
    state ++= rebuilt
  }

  /** Exact, case-sensitive lookup by name. */
  def find(name: String): Option[OnlinePlayer] = state.get(name)

  /** Seed state from a pre-restart snapshot without clobbering any player a
   *  live poll already updated (existing wins — guards the load-vs-first-poll
   *  race, since the snapshot loads asynchronously). Each restored entry's
   *  `time` is stamped to `restoreTime`, not its original value, so the next
   *  real `updateFromOnline` delta reflects only time actually elapsed since
   *  restart, not the whole downtime gap. */
  def restore(entries: Iterable[OnlinePlayer], restoreTime: ZonedDateTime): Unit =
    entries.foreach { p =>
      if (!state.contains(p.name)) state.put(p.name, p.copy(time = restoreTime))
    }

  /** Update a player's guild only if it actually changed. */
  def setGuild(name: String, guildName: String): Unit =
    state.get(name).foreach { p =>
      if (p.guildName != guildName) state.update(name, p.copy(guildName = guildName))
    }

  /** Set a player's flag, e.g. the level-up marker. */
  def setFlag(name: String, flag: String): Unit =
    state.get(name).foreach { p => state.update(name, p.copy(flag = flag)) }
}
