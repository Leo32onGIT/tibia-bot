package com.tibiabot
package tibiadata

import com.tibiabot.tibiadata.response._
import com.typesafe.scalalogging.StrictLogging

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

/** How long a character sheet is reused before it is worth asking for again,
 *  and the guard rails around that. See [[AgeCachedTibiaApi]]. */
final case class AgeCacheSettings(
    ttl: FiniteDuration,
    pollInterval: FiniteDuration,
    maxStale: FiniteDuration,
    canaryFraction: Double,
    maxEntries: Int
)

/** Skips character fetches that provably cannot return anything new.
 *
 *  `api.tibiadata.com` serves `/v4/character` from a Kong cache with a 300s TTL
 *  and the bot polls every 60s, so four fetches in five return the same entry byte
 *  for byte. Every v4 response carries `information.timestamp` — when the origin
 *  generated the data, pinned for the life of a cached copy while the Date header
 *  moves on — so `timestamp + ttl` is when the upstream copy turns over, and the
 *  poll landing nearest that moment is the one worth spending (see `worthReusing`
 *  for why nearest rather than first-after).
 *
 *  Three things are load-bearing:
 *
 *  1. '''A hit returns the stored response, never a Left.''' TibiaBot's death scan
 *     drops a Left for that tick, so answering with one would silently stop
 *     level-ups, guild activity, renames and transfers for four ticks in five.
 *     Deaths dedup on `recentDeaths`, so replaying posts nothing twice.
 *
 *  2. '''Only a parsed success updates the schedule.''' A failure stores nothing,
 *     leaving the character due for the next 60s tick — the retry cadence from
 *     before this class existed. Roughly 45% of this API's responses are 503s, so
 *     pushing the next attempt out to a full TTL on failure would make a bad
 *     upstream far more expensive than it already is.
 *
 *  3. '''A failure is answered from the stored copy while one is fresh enough,'''
 *     so a blip costs a stale sheet rather than a hole. Past `maxStale` the Left
 *     is passed through rather than answering from something misleading.
 *
 *  `canaryFraction` of otherwise-skippable fetches are made anyway: gating on age
 *  makes the age histogram self-selecting, so acting on it would destroy the very
 *  measurement that would reveal a wrong `ttl`.
 *
 *  Characters skipped together come due together, deliberately unsmoothed. A
 *  copy's life is fixed when it is built, so asking early cannot move it and only
 *  asking *late* shifts phase — paying for smoothing in exactly the delay this
 *  exists to avoid. There is nothing to buy either: a poll where everything comes
 *  due sends what every poll sent before, a peak the bot already sustains, now
 *  reached a fifth as often. Player churn re-seeds phases anyway.
 *
 *  Every other endpoint passes straight through: the world poll already matches
 *  its own 60s upstream TTL, and the rest are neither hot nor cached upstream. */
final class AgeCachedTibiaApi(
    underlying: TibiaApi,
    settings: AgeCacheSettings,
    now: () => Instant = () => Instant.now(),
    random: () => Double = () => java.util.concurrent.ThreadLocalRandom.current().nextDouble()
)(implicit ec: ExecutionContext)
    extends TibiaApi with StrictLogging {

  import AgeCachedTibiaApi.Entry

  private val entries = new ConcurrentHashMap[String, Entry]()
  @volatile private var lastPruneAt: Instant = Instant.EPOCH

  private def key(name: String): String = name.toLowerCase

  /** When the upstream copy built at `origin` turns over. */
  private def refetchAt(origin: Instant): Instant =
    origin.plusSeconds(settings.ttl.toSeconds)

  /** Whether `entry` is worth reusing at `at` rather than asking again.
   *
   *  Asks whether this poll or the next one lands closer to the moment the
   *  upstream copy turns over, rather than whether that moment has already
   *  passed. The difference is not academic: fetches only happen on the poll
   *  tick, the TTL is an exact multiple of it, so an entry created by one poll
   *  expires exactly on a later one. Asking "has it expired yet" then turns on
   *  a fraction of a second — the request latency between the poll firing and
   *  the origin stamping the copy is enough to make the answer no — and losing
   *  that coin flip costs a whole poll interval of death-detection latency.
   *
   *  Rounding to the nearest tick instead lands on the expiry poll with half an
   *  interval of slack either side, which is far more than request latency,
   *  tick drift and the whole-second rounding of the timestamp combined. Being
   *  early costs nothing worse than one wasted request that returns the same
   *  copy and leaves the entry due again. */
  private def worthReusing(entry: Entry, at: Instant): Boolean =
    refetchAt(entry.origin).isAfter(at.plusSeconds(settings.pollInterval.toSeconds / 2))

  /** The origin time to actually store, never later than the moment we saw it.
   *
   *  A timestamp ahead of our own clock — skew, or a nonsense value — would
   *  otherwise describe a copy that is not due to turn over yet and never will
   *  be, since anything measured forward from it stays in the future as our
   *  own clock advances. Pinning it to now makes such a response behave like a
   *  copy built this instant: reused for one TTL at most, then re-fetched. */
  private def storedOrigin(origin: Instant, at: Instant): Instant =
    if (origin.isAfter(at)) at else origin

  private def isStale(entry: Entry, at: Instant): Boolean =
    entry.origin.plusSeconds(settings.maxStale.toSeconds).isBefore(at)

  /** Drop entries nothing can serve from any more. Runs at most once per
   *  `maxStale`, off the back of a call rather than on a timer: the map is per
   *  world and only grows when that world's poll does, so there is nothing to
   *  clean while nothing is being fetched. */
  private def pruneIfDue(at: Instant): Unit =
    if (at.isAfter(lastPruneAt.plusSeconds(settings.maxStale.toSeconds))) {
      lastPruneAt = at
      entries.entrySet().removeIf(e => isStale(e.getValue, at))
      // A cap on top of the age sweep, in case a world somehow sees far more
      // distinct names than it has players. Oldest go first.
      val excess = entries.size() - settings.maxEntries
      if (excess > 0) {
        entries.asScala.toVector
          .sortBy(_._2.origin)(Ordering.by[Instant, Long](_.toEpochMilli))
          .take(excess)
          .foreach { case (name, _) => entries.remove(name) }
        logger.debug(s"Character age-cache over ${settings.maxEntries} entries, dropped the $excess oldest")
      }
    }

  def getCharacter(name: String): Future[Either[String, CharacterResponse]] = {
    val at = now()
    pruneIfDue(at)
    val cacheKey = key(name)
    val cached = Option(entries.get(cacheKey))
    val reusable = cached.filter(worthReusing(_, at))
    reusable match {
      case Some(entry) if random() >= settings.canaryFraction =>
        Future.successful(Right(entry.response))
      case _ =>
        underlying.getCharacter(name).map {
          case right @ Right(response) =>
            // No origin means unknown freshness, so it is not cached at all and
            // this character keeps being fetched every cycle exactly as before.
            OriginTimestamp.of(response.information)
              .foreach(origin => entries.put(cacheKey, Entry(response, storedOrigin(origin, at))))
            right
          case left =>
            // Nothing stored, so this character stays due and the next tick
            // retries it. Answer from the stored copy meanwhile, while one is
            // still fresh enough to be worth more than the error.
            cached.filterNot(isStale(_, at)) match {
              case Some(entry) => Right(entry.response)
              case None        => left
            }
        }
    }
  }

  def getWorld(world: String): Future[Either[String, WorldResponse]] = underlying.getWorld(world)
  def getWorlds(): Future[Either[String, WorldsResponse]] = underlying.getWorlds()
  def getBoostedBoss(): Future[Either[String, BoostedResponse]] = underlying.getBoostedBoss()
  def getBoostedCreature(): Future[Either[String, CreatureResponse]] = underlying.getBoostedCreature()
  def getGuild(guild: String): Future[Either[String, GuildResponse]] = underlying.getGuild(guild)
  def getGuildWithInput(input: (String, String)): Future[(Either[String, GuildResponse], String, String)] = underlying.getGuildWithInput(input)
  def getKillerFallback(name: String): Future[Either[String, CharacterResponse]] = underlying.getKillerFallback(name)
  def getCharacterWithInput(input: (String, String, String)): Future[(Either[String, CharacterResponse], String, String, String)] = underlying.getCharacterWithInput(input)
}

private[tibiadata] object AgeCachedTibiaApi {
  /** One stored character sheet. `origin` is the upstream's own generation
   *  time, not when we stored it — so an entry adopted from a copy that was
   *  already half expired ages out on the upstream's schedule rather than
   *  ours.
   *
   *  Lives here rather than inside the class so that matching on it does not
   *  drag an unverifiable outer reference into the type test. */
  final case class Entry(response: CharacterResponse, origin: Instant)
}
