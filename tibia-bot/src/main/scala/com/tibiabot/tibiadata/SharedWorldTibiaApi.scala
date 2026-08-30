package com.tibiabot.tibiadata

import com.tibiabot.Config
import com.tibiabot.persistence.RedisCache
import com.tibiabot.tibiadata.response._
import com.typesafe.scalalogging.StrictLogging
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Shared world-cycle decorator — see Config.BotRole. A `Primary` fetches
 *  `getWorld`/`getCharacter` as normal and additionally fire-and-forget
 *  publishes each successful result to Redis; a `Secondary` reads that
 *  published result first, falling back to fetching it directly on a miss
 *  (primary hasn't fetched this cycle yet, is down, or the value aged out).
 *  Every other method passes straight through to `underlying` unchanged.
 *
 *  Only a genuine `Right` is ever published. An error must not be shared as
 *  though it were data, and a Primary that failed simply leaves whatever is
 *  already in Redis to expire on its own.
 *
 *  '''A published character sheet is kept for exactly as long as the upstream
 *  copy it came from is still the current one''' — its origin timestamp plus
 *  that copy's lifetime, not a flat duration. A flat one is wrong in both
 *  directions now that [[AgeCachedTibiaApi]] sits in front. The Primary only
 *  reaches this class when a character comes due, roughly once per upstream
 *  lifetime, so a TTL shorter than that leaves the key missing for most of the
 *  cycle and every Secondary fetches directly anyway — which is what a flat 90
 *  seconds against a 300 second lifetime did. Overshooting is worse than
 *  useless rather than merely useless: a Secondary reads Redis and only falls
 *  through to a real fetch on a miss, so an entry outliving its copy is one a
 *  Secondary keeps being served after it stopped being current, and if the
 *  Primary died it would go on being served until the key expired. Tying the
 *  key's life to the copy's makes it vanish exactly when it stops being the
 *  current answer, at which point a Secondary either finds the newly published
 *  one or goes and fetches.
 *
 *  Correctness does not rest on that, though — it is about hit rate and blast
 *  radius. The payload carries its own `information.timestamp`, so a
 *  Secondary's own [[AgeCachedTibiaApi]] re-derives freshness from the sheet
 *  itself: handed an out-of-date one it records the old origin, stays due, and
 *  asks again next poll rather than settling for it.
 *
 *  `getWorld` keeps a flat TTL. Its upstream copy lives 60s and both bots poll
 *  every 60s, so the Primary republishes each cycle and the key is
 *  continuously present — the mismatch that broke the character path does not
 *  arise there, and nothing here changes what the online list sees.
 *
 *  Deliberately a separate decorator from CachingTibiaApi rather than an
 *  extension of it — that class's own doc explains why the character firehose
 *  is never cached there. Sits in front of CachingTibiaApi (which wraps
 *  TibiaDataClient), so a Primary's own fetch still benefits from whatever
 *  CachingTibiaApi caches on other endpoints. */
final class SharedWorldTibiaApi(
    underlying: TibiaApi,
    cache: RedisCache,
    role: Config.BotRole.Role,
    worldTtl: FiniteDuration = 90.seconds,
    characterTtl: FiniteDuration = 300.seconds,
    characterKeyPrefix: String = SharedWorldTibiaApi.TibiaDataCharacterKeyPrefix,
    // None keeps the original behaviour: a secondary that misses in Redis
    // fetches the character itself.
    primaryPresence: Option[PrimaryPresence] = None,
    now: () => java.time.Instant = () => java.time.Instant.now()
)(implicit ec: ExecutionContext)
    extends TibiaApi with JsonSupport with StrictLogging {

  /** How long this sheet is worth keeping: what is left of the upstream copy
   *  it came from. A response whose origin cannot be read has unknown
   *  freshness, so it gets the floor rather than a guess — barely shared, but
   *  the path still works if that field ever goes away. */
  private def characterPublishTtl(response: CharacterResponse): FiniteDuration = {
    val remaining = OriginTimestamp.of(response.information).map { origin =>
      java.time.Duration.between(now(), origin.plusSeconds(characterTtl.toSeconds)).getSeconds
    }.getOrElse(0L)
    math.max(SharedWorldTibiaApi.MinCharacterPublishTtl.toSeconds, remaining).seconds
  }

  private def sharedWorldKey(world: String): String = s"tibia:world-shared:${world.toLowerCase}"
  private def sharedCharacterKey(name: String): String = s"$characterKeyPrefix${name.toLowerCase}"

  def getWorld(world: String): Future[Either[String, WorldResponse]] = role match {
    case Config.BotRole.Primary =>
      underlying.getWorld(world).map { result =>
        result.foreach { worldResponse =>
          cache.setEx(sharedWorldKey(world), worldResponse.toJson.compactPrint, worldTtl).recover {
            case NonFatal(e) => logger.warn(s"Failed to publish shared world data for '$world': ${e.getMessage}")
          }
        }
        result
      }
    case Config.BotRole.Secondary =>
      cache.get(sharedWorldKey(world)).recover { case NonFatal(_) => None }.flatMap {
        case Some(json) =>
          try Future.successful(Right(json.parseJson.convertTo[WorldResponse]))
          catch {
            case NonFatal(e) =>
              logger.warn(s"Failed to decode shared world data for '$world', fetching directly: ${e.getMessage}")
              underlying.getWorld(world)
          }
        case None => underlying.getWorld(world)
      }
    case Config.BotRole.Disabled =>
      underlying.getWorld(world)
  }

  def getCharacter(name: String): Future[Either[String, CharacterResponse]] = role match {
    case Config.BotRole.Primary =>
      underlying.getCharacter(name).map { result =>
        // Only a genuine fetch is shareable — an error must never be published
        // as though it were data.
        result.foreach { characterResponse =>
          cache.setEx(sharedCharacterKey(name), characterResponse.toJson.compactPrint, characterPublishTtl(characterResponse)).recover {
            case NonFatal(e) => logger.warn(s"Failed to publish shared character data for '$name': ${e.getMessage}")
          }
        }
        result
      }
    case Config.BotRole.Secondary =>
      cache.get(sharedCharacterKey(name)).recover { case NonFatal(_) => None }.flatMap {
        case Some(json) =>
          try Future.successful(Right(json.parseJson.convertTo[CharacterResponse]))
          catch {
            case NonFatal(e) =>
              logger.warn(s"Failed to decode shared character data for '$name', fetching directly: ${e.getMessage}")
              underlying.getCharacter(name)
          }
        case None => onCharacterMiss(name)
      }
    case Config.BotRole.Disabled =>
      underlying.getCharacter(name)
  }

  /** What a secondary does when the primary has not published this character
   *  yet.
   *
   *  Fetching it directly is the original behaviour and still the fallback, but
   *  it is exactly what a consume-only deployment exists to prevent: it puts an
   *  address on the wire that the upstream may not have agreed to, one request
   *  per character the primary happens not to have reached yet.
   *
   *  While a primary is alive, the miss is answered with a Left instead. That
   *  costs nothing and loses nothing: [[AgeCachedTibiaApi]] above reads a Left
   *  as "stay due", so the character is simply asked for again on the next
   *  poll, by which time the primary will have published it. With no primary
   *  alive there is nobody to wait for, so it falls back to fetching. */
  private def onCharacterMiss(name: String): Future[Either[String, CharacterResponse]] =
    primaryPresence match {
      case Some(presence) if presence.isAlive =>
        Future.successful(Left(s"Waiting for the primary to publish '$name'"))
      case _ =>
        underlying.getCharacter(name)
    }

  def getWorlds(): Future[Either[String, WorldsResponse]] = underlying.getWorlds()
  def getBoostedBoss(): Future[Either[String, BoostedResponse]] = underlying.getBoostedBoss()
  def getBoostedCreature(): Future[Either[String, CreatureResponse]] = underlying.getBoostedCreature()
  def getGuild(guild: String): Future[Either[String, GuildResponse]] = underlying.getGuild(guild)
  def getGuildWithInput(input: (String, String)): Future[(Either[String, GuildResponse], String, String)] = underlying.getGuildWithInput(input)
  def getKillerFallback(name: String): Future[Either[String, CharacterResponse]] = underlying.getKillerFallback(name)
  def getCharacterWithInput(input: (String, String, String)): Future[(Either[String, CharacterResponse], String, String, String)] = underlying.getCharacterWithInput(input)
}

object SharedWorldTibiaApi {

  /** Redis namespace for sheets published from TibiaData. */
  val TibiaDataCharacterKeyPrefix: String = "tibia:character-shared:"

  /** ...and for sheets published from CipSoft's fansite API.
   *
   *  Each character upstream publishes under its own prefix rather than the two
   *  contending for one key. That is what lets a secondary reproduce the
   *  primary's choice instead of inheriting half of it: it reads both published
   *  sheets and races them locally, arriving at the same answer for the price
   *  of two Redis reads and no API calls at all. Sharing one key would publish
   *  whichever source happened to write last, which is not the same thing as
   *  the freshest. */
  val FansiteCharacterKeyPrefix: String = "fansite:character-shared:"

  /** Floor on a published sheet's life. A copy fetched right at its turnover
   *  has a full lifetime left, but one adopted late has little, and writing a
   *  key that expires before anybody could read it is just work. */
  val MinCharacterPublishTtl: FiniteDuration = 15.seconds
}
