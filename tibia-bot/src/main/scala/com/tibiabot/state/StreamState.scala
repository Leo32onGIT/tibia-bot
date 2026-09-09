package com.tibiabot.state

import com.tibiabot.domain.{PlayerCache, Players, Guilds, CustomSort, Discords, Worlds, WorldTransfer}


/**
 * The per-guild working state mutated by BOTH the per-world streams and command
 * threads: activity tracking and the hunted/allied player lists.
 *
 * Reads are lock-free on `@volatile` fields (so a running stream always sees the
 * latest committed map); every read-modify-write goes through the synchronized
 * `modify*` methods so a concurrent update to one guild's entry can never clobber
 * a concurrent update to another guild's.
 *
 * Every map here is keyed by guild id except `_worldTransfers`, which is keyed by
 * world — the same locking argument holds either way, since what it protects is
 * one stream's update to its own key against another stream's to a different one.
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
  // The one map here keyed by world rather than by guild: an arrival is a fact
  // about the world, shared by every discord tracking it. See
  // WorldTransferRepository.
  @volatile private var _worldTransfers: Map[String, List[WorldTransfer]] = Map.empty

  def activityData: Map[String, List[PlayerCache]] = _activity
  /** Announced world transfers, keyed by world. */
  def worldTransfersData: Map[String, List[WorldTransfer]] = _worldTransfers
  def huntedPlayersData: Map[String, List[Players]] = _huntedPlayers
  def alliedPlayersData: Map[String, List[Players]] = _alliedPlayers
  def huntedGuildsData: Map[String, List[Guilds]] = _huntedGuilds
  def alliedGuildsData: Map[String, List[Guilds]] = _alliedGuilds
  def customSortData: Map[String, List[CustomSort]] = _customSort
  def discordsData: Map[String, List[Discords]] = _discords
  def worldsData: Map[String, List[Worlds]] = _worlds
  def activityCommandBlocker: Map[String, Boolean] = _activityBlocker

  /** Every hunted name any discord tracking `world` has asked about, lowercased
   *  — named outright, or reached through a hunted guild.
   *
   *  Unioned across discords rather than kept per discord because what reads it
   *  is per world: two discords watching the same world and hunting the same
   *  character are one character to fetch, not two.
   *
   *  Guild members come from `_activity` because nothing cheaper knows them:
   *  the online list carries no guild, and the character sheet that does is the
   *  thing being decided about. That map holds allied guilds too, so it is
   *  filtered by guild name rather than taken whole, and it makes membership
   *  lag by a poll — someone who joins a hunted guild appears here only once a
   *  fetch has noticed, and someone who left lingers until one notices that.
   *  Neither costs anything but freshness: the only reader is
   *  [[com.tibiabot.fansiteapi.FansiteRoster]], and a character missing from
   *  this set is still fetched from TibiaData on the poll's own schedule. */
  def huntedNamesForWorld(world: String): Set[String] = {
    val hunted = _huntedPlayers
    val guilds = _huntedGuilds
    val activity = _activity
    _worlds.iterator.collect {
      case (guildId, worlds) if worlds.exists(_.name.equalsIgnoreCase(world)) =>
        val guildNames = guilds.getOrElse(guildId, Nil).map(_.name.toLowerCase).toSet
        val members =
          if (guildNames.isEmpty) Iterator.empty[String]
          else activity.getOrElse(guildId, Nil).iterator.collect {
            case player if guildNames.contains(player.guild.toLowerCase) => player.name.toLowerCase
          }
        hunted.getOrElse(guildId, Nil).iterator.map(_.name.toLowerCase) ++ members
    }.flatten.toSet
  }

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
