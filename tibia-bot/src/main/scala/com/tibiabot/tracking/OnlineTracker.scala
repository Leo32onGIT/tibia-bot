package com.tibiabot.tracking

import java.time.ZonedDateTime
import scala.collection.mutable

/** Online-presence state extracted from `TibiaBot.currentOnline`.
 *
 *  OPTIMIZED implementation: keyed by player name in an insertion-ordered map,
 *  so lookups/updates are O(1) instead of the original O(n) linear scans over a
 *  `mutable.Set`. The full-cycle rebuild is O(n) instead of O(n^2). Public API
 *  and observable behaviour are identical to the baseline — locked in by
 *  OnlineTrackerSpec.
 *
 *  Mirrors TibiaBot.scala:
 *    - updateFromOnline -> lines 94-105 (duration carry-over + clear/addAll)
 *    - find             -> lines 95, 204, 572, 646
 *    - setGuild         -> lines 204-208
 *    - setFlag          -> lines 646-648
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

  /** Exact, case-sensitive lookup by name (matches `.find(_.name == x)`). */
  def find(name: String): Option[OnlinePlayer] = state.get(name)

  /** Update a player's guild only if it actually changed (lines 204-208). */
  def setGuild(name: String, guildName: String): Unit =
    state.get(name).foreach { p =>
      if (p.guildName != guildName) state.update(name, p.copy(guildName = guildName))
    }

  /** Set a player's flag, e.g. the level-up marker (lines 646-648). */
  def setFlag(name: String, flag: String): Unit =
    state.get(name).foreach { p => state.update(name, p.copy(flag = flag)) }
}
