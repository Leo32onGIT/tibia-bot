package com.tibiabot.persistence

/** One area a live link's raid rule covers on a world. */
final case class CoveredArea(guildId: String, userId: String, world: String, areaId: Int)

/** Persistence for what the members' Observer links cover — the areas each link's
 *  raid rules take in, per world, and the names Observer gives those areas. Both
 *  live in `bot_cache`, so every bot can show any guild's coverage. */
trait ObserverCoverageRepository {
  /** Replace the areas a link's raid rules cover, per world. */
  def setAreas(guildId: String, userId: String, areas: Map[String, List[Int]]): Unit
  /** Forget what a link covers: its rules came off, or it was removed. */
  def clearLink(guildId: String, userId: String): Unit
  def clearGuild(guildId: String): Unit
  /** The areas every working link covers on these worlds, from any guild. */
  def liveAreas(worlds: List[String]): List[CoveredArea]
  /** Record names for area ids, keeping any already known. */
  def setNames(names: Map[Int, String]): Unit
  def names(): Map[Int, String]
}

object ObserverCoverageRepository {
  /** Stores nothing: for a bot, or a test, that has no coverage to keep. */
  object None extends ObserverCoverageRepository {
    def setAreas(guildId: String, userId: String, areas: Map[String, List[Int]]): Unit = ()
    def clearLink(guildId: String, userId: String): Unit = ()
    def clearGuild(guildId: String): Unit = ()
    def liveAreas(worlds: List[String]): List[CoveredArea] = Nil
    def setNames(names: Map[Int, String]): Unit = ()
    def names(): Map[Int, String] = Map.empty
  }
}
