package com.tibiabot.state

import com.tibiabot.domain.{PlayerCache, Players}

/**
 * The per-guild working state mutated by BOTH the per-world streams and command
 * threads: activity tracking plus the hunted/allied player lists.
 *
 * Reads are lock-free on `@volatile` fields (so a running stream always sees the
 * latest committed map); every read-modify-write goes through the synchronized
 * `modify*` methods so a concurrent update to one guild's entry can never clobber
 * a concurrent update to another guild's.
 */
final class StreamState {
  private val lock = new Object()

  @volatile private var _activity: Map[String, List[PlayerCache]] = Map.empty
  @volatile private var _huntedPlayers: Map[String, List[Players]] = Map.empty
  @volatile private var _alliedPlayers: Map[String, List[Players]] = Map.empty

  def activityData: Map[String, List[PlayerCache]] = _activity
  def huntedPlayersData: Map[String, List[Players]] = _huntedPlayers
  def alliedPlayersData: Map[String, List[Players]] = _alliedPlayers

  def modifyActivityData(f: Map[String, List[PlayerCache]] => Map[String, List[PlayerCache]]): Unit =
    lock.synchronized { _activity = f(_activity) }
  def modifyHuntedPlayersData(f: Map[String, List[Players]] => Map[String, List[Players]]): Unit =
    lock.synchronized { _huntedPlayers = f(_huntedPlayers) }
  def modifyAlliedPlayersData(f: Map[String, List[Players]] => Map[String, List[Players]]): Unit =
    lock.synchronized { _alliedPlayers = f(_alliedPlayers) }
}
