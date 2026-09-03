package com.tibiabot
package fansiteapi

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** Which characters are worth spending a fansite request on.
 *
 *  The paced lane passes roughly one poll interval's worth of requests per poll
 *  interval — around 800 against a 75ms gap — and a saturated fleet has tens of
 *  thousands of characters online. Something has to choose, and leaving it to
 *  arrival order would mean the choice is made by whichever world's stream
 *  happened to tick first, which is no choice at all.
 *
 *  '''Hunted only.''' A fansite sheet buys nothing but freshness: both sources
 *  are raced and the newer answer wins, so the prize is seeing an event a couple
 *  of minutes sooner. That is worth paying for on the characters somebody
 *  deliberately asked to watch and nowhere else — a neutral player's death is
 *  announced on the next poll either way.
 *
 *  '''Ranked by level, fleet-wide.''' Hunted lists are long, so most of what is
 *  eligible still cannot be afforded, and level is the honest proxy for which
 *  deaths people care about. Ranking across the whole fleet rather than per
 *  world is deliberate: the budget is one shared IP's worth of requests, so a
 *  quiet world's level 200 should not hold a slot a busy world's level 900 would
 *  use better.
 *
 *  '''The ranking chooses; the pacer bounds.''' Admitting at most `budget` names
 *  keeps the two agreeing, so the pacer rarely has to refuse anything. That
 *  matters because a pacer refusal is arbitrary — it falls on whoever asked last
 *  — and would quietly undo the ordering this class exists to impose.
 *
 *  Being left out costs a character nothing but the freshness: it is still
 *  fetched from TibiaData on the poll's own schedule, and
 *  [[DualCharacterApi]] simply never opens a second source for it. */
final class FansiteRoster(
    budget: Int,
    staleAfter: FiniteDuration,
    now: () => Instant = () => Instant.now()
) {
  require(budget >= 1, s"budget must be at least 1, got $budget")
  require(staleAfter > Duration.Zero, s"staleAfter must be positive, got $staleAfter")

  import FansiteRoster.Published

  private val byWorld = new ConcurrentHashMap[String, Published]()
  @volatile private var admitted: Set[String] = Set.empty

  /** Replace what `world` is offering: its hunted characters currently online,
   *  with the level the online list just reported for each. */
  def publish(world: String, candidates: Iterable[(String, Int)]): Unit = {
    val levels = candidates.map { case (name, level) => name.toLowerCase -> level }.toMap
    byWorld.put(world, Published(levels, now()))
    recompute()
  }

  /** Whether a fansite fetch for `name` is worth its place in the budget. */
  def admits(name: String): Boolean = admitted.contains(name.toLowerCase)

  /** How many characters currently hold a slot. Diagnostic. */
  def admittedCount: Int = admitted.size

  /** A world whose stream has stopped keeps its last offering in the map, and
   *  would otherwise hold budget for characters nobody is polling any more. Its
   *  entry is ignored once it stops being refreshed rather than deleted: there
   *  is one per world at most, so nothing grows, and a world that comes back
   *  simply publishes over the top. */
  private def recompute(): Unit = {
    val cutoff = now().minusSeconds(staleAfter.toSeconds)
    val live = byWorld.asScala.values.filter(_.at.isAfter(cutoff))
    admitted = live
      .flatMap(_.candidates)
      .toList
      .sortBy { case (_, level) => -level }
      .take(budget)
      .map { case (name, _) => name }
      .toSet
  }
}

object FansiteRoster {

  /** One world's last offering, and when it made it. */
  private final case class Published(candidates: Map[String, Int], at: Instant)

  /** One roster for the process, because the budget it rations is one IP's.
   *
   *  `budget` is derived rather than configured: it is exactly what the pacer
   *  will let through in a poll interval, so the two cannot drift apart and
   *  there is no second number to keep honest. */
  // Lazy so that merely touching this object -- which publishing does, for the
  // Published type above -- does not force Config to resolve.
  lazy val shared: FansiteRoster = new FansiteRoster(
    budget = math.max(1, (TibiaBot.PollInterval.toMillis / Config.FansiteApi.minRequestGap.toMillis).toInt),
    staleAfter = TibiaBot.PollInterval * 3L
  )
}
