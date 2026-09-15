package com.tibiabot.state

import com.tibiabot.domain.{ActivityIndex, PlayerCache, Players, Guilds, CustomSort, Discords, Worlds, WorldTransfer}

import java.util.concurrent.ConcurrentHashMap


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

  // Derived from _huntedPlayers and _alliedPlayers, not state of its own: the
  // flattened set of every listed name, lowercased. See `listedNames`.
  @volatile private var _listedNames: Set[String] = Set.empty
  @volatile private var _listedNamesStale: Boolean = false

  /** `_activity`, per guild, as a name lookup — memoised against the very list
   *  it was built from, so a guild whose rows have not been replaced is indexed
   *  once and then read for free.
   *
   *  Keyed on list identity rather than on an invalidation flag because
   *  `modifyActivityData` takes an opaque function and so cannot say which
   *  guilds it touched: comparing the cached list reference to the live one
   *  asks the map itself, and is right for every write path there will ever be.
   *  Rebuilding a guild nobody wrote to would be the only cost of getting that
   *  wrong, and in practice nothing rebuilds — across all 121 guilds on the
   *  primary bot, fewer than ten activity rows are written in ten minutes.
   *
   *  A ConcurrentHashMap rather than another `@volatile var`: this is derived
   *  data that several world streams fill in concurrently, and publishing it a
   *  whole map at a time would let one stream's rebuild drop another's.
   */
  private val _activityIndex = new ConcurrentHashMap[String, (List[PlayerCache], ActivityIndex)]()

  def activityData: Map[String, List[PlayerCache]] = _activity

  /** One guild's activity rows as a name lookup. Equivalent to searching
   *  `activityData.getOrElse(guildId, Nil)` with `equalsIgnoreCase`, which is
   *  what the death scan used to do at three call sites per character per
   *  discord — up to four passes over the list, since renameFromFormerNames
   *  makes two of its own.
   *
   *  The snapshot it answers from is the one live when it was called, exactly
   *  as reading `activityData` is — a row written afterwards is not in it. Both
   *  are read the same way by the scan, which decides against a snapshot and
   *  then re-reads inside the lock before writing.
   */
  def activityIndex(guildId: String): ActivityIndex = {
    val rows = _activity.getOrElse(guildId, Nil)
    if (rows.isEmpty) ActivityIndex.empty
    else {
      val cached = _activityIndex.get(guildId)
      if (cached != null && (cached._1 eq rows)) cached._2
      else {
        val built = ActivityIndex(rows)
        _activityIndex.put(guildId, (rows, built))
        built
      }
    }
  }

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

  /** Every name on any discord's hunted or allied list, lowercased and flattened
   *  across guilds — the index behind [[com.tibiabot.BotApp.isOnAnyList]].
   *
   *  Kept because the read side and the write side are nothing alike. The death
   *  poll asks "does anybody list this player?" once per online character per
   *  world per minute, tens of thousands of times; the lists themselves change
   *  when somebody runs a command, or when the poll notices a rename or a guild
   *  swap — hundreds of times a day at the very most. Answering each read by
   *  walking every guild's list made that question cost the whole set of listed
   *  names, and on a fleet carrying twenty-five thousand of them it was the
   *  single largest consumer of CPU on the box.
   *
   *  Rebuilt on the next read after a change rather than inside the change
   *  itself, because the writes arrive in bursts: startup loads every guild's
   *  lists one guild at a time, so rebuilding eagerly would rebuild once per
   *  guild and throw all but the last away. A burst of any size costs one
   *  rebuild, on whoever reads next.
   *
   *  The rebuild takes the same lock the writers do, so it cannot observe a map
   *  half-updated, and clearing the flag inside that lock means a write landing
   *  during a rebuild leaves the flag set rather than being lost. Reads with
   *  nothing pending never take the lock at all — the `@volatile` read is the
   *  same lock-free path every other accessor here uses. */
  def listedNames: Set[String] = {
    if (_listedNamesStale) lock.synchronized {
      if (_listedNamesStale) {
        val builder = Set.newBuilder[String]
        _huntedPlayers.valuesIterator.foreach(_.foreach(player => builder += player.name.toLowerCase))
        _alliedPlayers.valuesIterator.foreach(_.foreach(player => builder += player.name.toLowerCase))
        _listedNames = builder.result()
        _listedNamesStale = false
      }
    }
    _listedNames
  }

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
    lock.synchronized {
      _activity = f(_activity)
      // A guild that has gone entirely — a discord removed, or /clear run —
      // would otherwise keep its rows and their index alive in the cache for
      // the life of the process, and the largest guild's is a couple of
      // megabytes. Guilds still present are left alone: their entry is
      // validated on read against the list it was built from.
      _activityIndex.keySet().removeIf(guildId => !_activity.contains(guildId))
    }
  def modifyWorldTransfersData(f: Map[String, List[WorldTransfer]] => Map[String, List[WorldTransfer]]): Unit =
    lock.synchronized { _worldTransfers = f(_worldTransfers) }
  // Both of these invalidate `listedNames`, which is derived from them.
  def modifyHuntedPlayersData(f: Map[String, List[Players]] => Map[String, List[Players]]): Unit =
    lock.synchronized { _huntedPlayers = f(_huntedPlayers); _listedNamesStale = true }
  def modifyAlliedPlayersData(f: Map[String, List[Players]] => Map[String, List[Players]]): Unit =
    lock.synchronized { _alliedPlayers = f(_alliedPlayers); _listedNamesStale = true }
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
