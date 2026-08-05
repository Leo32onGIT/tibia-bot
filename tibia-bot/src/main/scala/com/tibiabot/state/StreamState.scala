package com.tibiabot.state

import com.tibiabot.domain.{PlayerCache, Players, Guilds, CustomSort, Discords, Worlds, WorldTransfer}

import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

/**
 * The per-guild working state mutated by BOTH the per-world streams and command
 * threads: activity tracking, the hunted/allied player lists, plus the
 * character-response freshness cache.
 *
 * Reads are lock-free on `@volatile` fields (so a running stream always sees the
 * latest committed map); every read-modify-write goes through the synchronized
 * `modify*` methods so a concurrent update to one guild's entry can never clobber
 * a concurrent update to another guild's.
 *
 * The character freshness cache is the one exception: it is written once per
 * character response — tens of thousands of times a minute across every world
 * stream at 32-way concurrency — so it is a ConcurrentHashMap with its own
 * per-entry striped locking rather than a copy-on-write immutable map behind
 * the single shared lock above. Under the old scheme every character response
 * rebuilt the whole map and blocked every unrelated guild-state write while
 * doing it.
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
  @volatile private var _worldTransfers: Map[String, List[WorldTransfer]] = Map.empty
  private val _characterCache = new ConcurrentHashMap[String, ZonedDateTime]()

  def activityData: Map[String, List[PlayerCache]] = _activity
  def worldTransfersData: Map[String, List[WorldTransfer]] = _worldTransfers
  def huntedPlayersData: Map[String, List[Players]] = _huntedPlayers
  def alliedPlayersData: Map[String, List[Players]] = _alliedPlayers
  def huntedGuildsData: Map[String, List[Guilds]] = _huntedGuilds
  def alliedGuildsData: Map[String, List[Guilds]] = _alliedGuilds
  def customSortData: Map[String, List[CustomSort]] = _customSort
  def discordsData: Map[String, List[Discords]] = _discords
  def worldsData: Map[String, List[Worlds]] = _worlds
  def activityCommandBlocker: Map[String, Boolean] = _activityBlocker

  /** Point read on the hot path — never materialises the whole map. */
  def characterSeenAt(name: String): Option[ZonedDateTime] = Option(_characterCache.get(name))

  /** Record when `name`'s character sheet was last seen. Hot path: one entry
   *  touched, no whole-map copy, no contention with unrelated guild state. */
  def recordCharacterSeen(name: String, at: ZonedDateTime): Unit = { _characterCache.put(name, at); () }

  /** Point-in-time copy, for the periodic Redis snapshot. Not on the hot path. */
  def characterCache: Map[String, ZonedDateTime] = _characterCache.asScala.toMap

  /** Seed from a snapshot without clobbering entries a live poll already wrote
   *  (existing, fresher entries win — the load-vs-first-poll race). */
  def warmCharacterCache(loaded: Map[String, ZonedDateTime]): Unit =
    loaded.foreach { case (name, at) => _characterCache.putIfAbsent(name, at) }

  /** Drop every entry not matching `keep` (the periodic age-based cleanup). */
  def pruneCharacterCache(keep: ZonedDateTime => Boolean): Unit =
    _characterCache.entrySet().removeIf(e => !keep(e.getValue))

  def modifyActivityData(f: Map[String, List[PlayerCache]] => Map[String, List[PlayerCache]]): Unit =
    lock.synchronized { _activity = f(_activity) }
  def modifyWorldTransfersData(f: Map[String, List[WorldTransfer]] => Map[String, List[WorldTransfer]]): Unit =
    lock.synchronized { _worldTransfers = f(_worldTransfers) }
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
}
