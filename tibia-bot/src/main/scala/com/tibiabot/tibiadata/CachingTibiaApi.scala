package com.tibiabot
package tibiadata

import com.tibiabot.persistence.RedisCache
import com.tibiabot.tibiadata.response.{BoostedResponse, CharacterResponse, CreatureResponse, GuildResponse, HighscoresResponse, WorldResponse, WorldsResponse}
import com.typesafe.scalalogging.StrictLogging
import spray.json._

import java.time.{LocalDate, ZoneId, ZonedDateTime}
import java.util.Locale
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Caching decorator over a TibiaApi.
 *
 *  Caches ONLY the freshness-tolerant, fan-out-heavy endpoints that hit the
 *  rate-limited local instance: boosted boss/creature and highscores. These
 *  change at most daily/hourly yet fan out per-guild at server-save, so caching
 *  collapses N identical calls into one.
 *
 *  The per-cycle character firehose and the lvl>=250 bypass are deliberately
 *  passed straight through: caching them would delay death detection, which is
 *  exactly what the bot exists to do quickly (see the local-bypass rationale).
 *
 *  The boosted boss/creature flip at the 10:00 Berlin server save, and the
 *  change-detection that fires the daily notification compares the API value to
 *  the DB-stored one — so a value cached just before the save must NOT survive
 *  past it. We therefore key those entries by the current "save day" (the date
 *  of the most recent 10:00 Berlin boundary): the key rolls the instant the save
 *  day rolls, turning any pre-save entry into a guaranteed miss regardless of
 *  remaining TTL. The TTL still applies, purely to self-evict stale day keys.
 *
 *  CAVEAT for future maintainers: JsonSupport.strFormat is asymmetric
 *  (unescape-on-read, plain-write), so a string carrying an HTML entity decodes
 *  differently on a cache hit vs a fresh fetch. Today's cached endpoints only
 *  carry fixed monster names / entity-free player names, so it cannot trigger;
 *  do NOT cache a free-form-text endpoint here without first making strFormat
 *  symmetric.
 *
 *  Cache misses, decode failures and cache errors all fall back to the
 *  underlying API, so Redis is strictly an optimisation. */
final class CachingTibiaApi(
    underlying: TibiaApi,
    cache: RedisCache,
    boostedTtl: FiniteDuration = 30.minutes,
    highscoresTtl: FiniteDuration = 30.minutes,
    berlinNow: () => ZonedDateTime = () => ZonedDateTime.now(ZoneId.of("Europe/Berlin"))
)(implicit ec: ExecutionContext)
    extends TibiaApi with JsonSupport with StrictLogging {

  /** Date of the most recent 10:00 Berlin server save — same rollover the bot
   *  uses for Rashid. Embedded in the boosted keys so a pre-save entry can never
   *  be read after the save. */
  private def saveDay: LocalDate = berlinNow().minusHours(10).toLocalDate

  /** Serve a decoded cache hit, else fetch from `underlying` and store a Right. */
  private def cached[T: RootJsonFormat](key: String, ttl: FiniteDuration)(fetch: => Future[Either[String, T]]): Future[Either[String, T]] =
    cache.get(key).recover { case NonFatal(_) => None }.flatMap {
      case Some(json) =>
        try Future.successful(Right(json.parseJson.convertTo[T]))
        catch {
          case NonFatal(e) =>
            logger.warn(s"redis decode failed for '$key', refetching: ${e.getMessage}")
            fetchAndStore(key, ttl)(fetch)
        }
      case None => fetchAndStore(key, ttl)(fetch)
    }

  private def fetchAndStore[T: RootJsonFormat](key: String, ttl: FiniteDuration)(fetch: => Future[Either[String, T]]): Future[Either[String, T]] =
    fetch.flatMap {
      case r @ Right(value) => cache.setEx(key, value.toJson.compactPrint, ttl).map(_ => r)
      case l                => Future.successful(l)
    }

  // --- cached endpoints (local instance, freshness-tolerant) ---

  def getBoostedBoss(): Future[Either[String, BoostedResponse]] =
    cached(s"tibia:boostedboss:$saveDay", boostedTtl)(underlying.getBoostedBoss())

  def getBoostedCreature(): Future[Either[String, CreatureResponse]] =
    cached(s"tibia:boostedcreature:$saveDay", boostedTtl)(underlying.getBoostedCreature())

  // page is forward-compat: production only fetches page 1 today, but the
  // underlying /v4/highscores endpoint is genuinely paged. toLowerCase(ROOT)
  // keeps the key locale-independent (and case-insensitive, matching Tibia).
  def getHighscores(world: String, page: Int): Future[Either[String, HighscoresResponse]] =
    cached(s"tibia:highscores:${world.toLowerCase(Locale.ROOT)}:$page", highscoresTtl)(underlying.getHighscores(world, page))

  // --- pass-through (deliberately uncached — see class doc) ---

  def getWorld(world: String): Future[Either[String, WorldResponse]] = underlying.getWorld(world)
  def getWorlds(): Future[Either[String, WorldsResponse]] = underlying.getWorlds()
  def getGuild(guild: String): Future[Either[String, GuildResponse]] = underlying.getGuild(guild)
  def getGuildWithInput(input: (String, String)): Future[(Either[String, GuildResponse], String, String)] = underlying.getGuildWithInput(input)
  def getCharacter(name: String): Future[Either[String, CharacterResponse]] = underlying.getCharacter(name)
  def getKillerFallback(name: String): Future[Either[String, CharacterResponse]] = underlying.getKillerFallback(name)
  def getCharacterV2(input: (String, Int)): Future[Either[String, CharacterResponse]] = underlying.getCharacterV2(input)
  def getCharacterWithInput(input: (String, String, String)): Future[(Either[String, CharacterResponse], String, String, String)] = underlying.getCharacterWithInput(input)
}
