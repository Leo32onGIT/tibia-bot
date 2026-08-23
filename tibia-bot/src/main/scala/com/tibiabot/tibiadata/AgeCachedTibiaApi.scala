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
 *  `api.tibiadata.com` serves `/v4/character` out of a Kong cache with a 300s
 *  TTL, and the bot polls every 60s. Four fetches in five therefore return a
 *  copy of the same bytes the previous one already got. Not "probably the
 *  same" — the same entry, byte for byte, until it expires. Skipping those
 *  loses nothing.
 *
 *  Knowing when an entry expires needs to know when it was built, and every v4
 *  response says so in `information.timestamp`: the moment the origin generated
 *  the data, which stays pinned across the whole life of a cached copy while
 *  the Date header moves on. So `timestamp + ttl` is when the upstream copy
 *  turns over, and the poll that lands nearest that moment is the one worth
 *  spending — see `worthReusing` for why nearest, and not simply the first
 *  poll after it.
 *
 *  Three things about this are load-bearing:
 *
 *  1. '''A hit returns the stored response, never a Left.''' TibiaBot's death
 *     scan drops a Left for that character on that tick, so answering with one
 *     would silently stop level-ups, guild activity, name changes and world
 *     transfers for the four ticks in five that hit. Replaying the body
 *     instead keeps every one of those paths running on exactly the bytes a
 *     real fetch would have returned. Deaths already dedup on `recentDeaths`,
 *     so replaying them posts nothing twice.
 *
 *  2. '''Only a parsed success updates the schedule.''' A failure stores
 *     nothing, which leaves the character due, which means the next 60s tick
 *     retries it — the same retry cadence as before this class existed. That
 *     matters more than it sounds: roughly 45% of responses from this API are
 *     503s, and a design that pushed the next attempt out to a full TTL on a
 *     failed one would have made a bad upstream far more expensive than it
 *     already is. During an outage this degrades back to polling every 60s,
 *     which is the right thing to do when there is no data.
 *
 *  3. '''A failure is answered from the stored copy while one is fresh
 *     enough.''' A blip then costs a stale sheet rather than a hole, so
 *     level-ups still fire off the fresh world poll. Past `maxStale` the
 *     failure is passed through as the Left it is, rather than answering from
 *     something old enough to mislead.
 *
 *  `canaryFraction` of otherwise-skippable fetches are made anyway. Gating on
 *  age makes the age histogram self-selecting — once only near-expiry fetches
 *  are issued, the panel can only ever show near-expiry ages, and the very
 *  measurement that would reveal a wrong `ttl` is destroyed by acting on it.
 *  The canary keeps a small unbiased sample flowing so that number stays
 *  honest and a wrong TTL shows up in prod rather than staying silent.
 *
 *  Characters skipped together come due together, and nothing here spreads
 *  them out. That is deliberate. A copy's life is fixed by when it was built,
 *  so asking early cannot move it — the same copy comes back and the entry is
 *  still due. Only asking *late* shifts a character's phase, which means any
 *  smoothing is paid for in exactly the delay this exists to avoid. And there
 *  is nothing to buy with it: a poll on which every character comes due sends
 *  what every poll sent before this class existed, so the peak is the one the
 *  bot already sustains, now reached a fifth as often. Player churn re-seeds
 *  phases continuously anyway, so the lockstep case needs a cold start to
 *  arise and decays on its own.
 *
 *  Every other endpoint passes straight through: the world poll is already
 *  matched to its own 60s upstream TTL, and the rest are neither hot nor
 *  cached upstream.
 */
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
        logger.info(s"Character age-cache over ${settings.maxEntries} entries, dropped the $excess oldest")
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
