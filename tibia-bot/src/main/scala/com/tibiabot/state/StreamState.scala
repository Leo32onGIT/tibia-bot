package com.tibiabot.state

import com.tibiabot.domain.{PlayerCache, Players, Guilds, CustomSort, Discords, Worlds}

import java.time.ZonedDateTime

/**
 * The per-guild working state mutated by BOTH the per-world streams and command
 * threads: activity tracking, the hunted/allied player lists, plus the
 * character-response freshness cache.
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
  @volatile private var _huntedGuilds: Map[String, List[Guilds]] = Map.empty
  @volatile private var _alliedGuilds: Map[String, List[Guilds]] = Map.empty
  @volatile private var _customSort: Map[String, List[CustomSort]] = Map.empty
  @volatile private var _discords: Map[String, List[Discords]] = Map.empty
  @volatile private var _worlds: Map[String, List[Worlds]] = Map.empty
  @volatile private var _activityBlocker: Map[String, Boolean] = Map.empty
  @volatile private var _characterCache: Map[String, ZonedDateTime] = Map.empty

  def activityData: Map[String, List[PlayerCache]] = _activity
  def huntedPlayersData: Map[String, List[Players]] = _huntedPlayers
  def alliedPlayersData: Map[String, List[Players]] = _alliedPlayers
  def huntedGuildsData: Map[String, List[Guilds]] = _huntedGuilds
  def alliedGuildsData: Map[String, List[Guilds]] = _alliedGuilds
  def customSortData: Map[String, List[CustomSort]] = _customSort
  def discordsData: Map[String, List[Discords]] = _discords
  def worldsData: Map[String, List[Worlds]] = _worlds
  def activityCommandBlocker: Map[String, Boolean] = _activityBlocker
  def characterCache: Map[String, ZonedDateTime] = _characterCache

  def modifyActivityData(f: Map[String, List[PlayerCache]] => Map[String, List[PlayerCache]]): Unit =
    lock.synchronized { _activity = f(_activity) }
  def modifyHuntedPlayersData(f: Map[String, List[Players]] => Map[String, List[Players]]): Unit =
    lock.synchronized { _huntedPlayers = f(_huntedPlayers) }
  def modifyAlliedPlayersData(f: Map[String, List[Players]] => Map[String, List[Players]]): Unit =
    lock.synchronized { _alliedPlayers = f(_alliedPlayers) }
  def modifyHuntedGuildsData(f: Map[String, List[Guilds]] => Map[String, List[Guilds]]): Unit =
    lock.synchronized { _huntedGuilds = f(_huntedGuilds) }
  def modifyAlliedGuildsData(f: Map[String, List[Guilds]] => Map[String, List[Guilds]]): Unit =
    lock.synchronized { _alliedGuilds = f(_alliedGuilds) }
  def modifyCustomSortData(f: Map[String, List[CustomSort]] => Map[String, List[CustomSort]]): Unit =
    lock.synchronized { _customSort = f(_customSort) }
  def modifyDiscordsData(f: Map[String, List[Discords]] => Map[String, List[Discords]]): Unit =
    lock.synchronized { _discords = f(_discords) }
  def modifyWorldsData(f: Map[String, List[Worlds]] => Map[String, List[Worlds]]): Unit =
    lock.synchronized { _worlds = f(_worlds) }
  def modifyActivityCommandBlocker(f: Map[String, Boolean] => Map[String, Boolean]): Unit =
    lock.synchronized { _activityBlocker = f(_activityBlocker) }
  def modifyCharacterCache(f: Map[String, ZonedDateTime] => Map[String, ZonedDateTime]): Unit =
    lock.synchronized { _characterCache = f(_characterCache) }
}
