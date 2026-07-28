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
 *  Every other method passes straight through to `underlying` unchanged —
 *  `getCharacterV2` deliberately included: it exists solely to defeat
 *  TibiaData's own upstream caching for Noctera (name-case-randomised on
 *  every call), so sharing on top of it would reintroduce exactly the
 *  staleness it's designed to avoid.
 *
 *  `getCharacter` has a correctness trap worth documenting: the underlying
 *  TibiaDataClient already does its own process-local dedup (comparing the
 *  response's Date header against what it last saw) and can return
 *  `Left("Hit cache")` with no character data at all. That signal reflects
 *  only the calling process's own fetch history — it says nothing about
 *  whether another process has seen this data — so it must never be
 *  published; only a genuine `Right` result is shared. A `Left` from a
 *  Primary (cache-hit-locally or a real error) is simply not published this
 *  cycle, leaving whatever's already in Redis (if anything) to age normally.
 *
 *  Deliberately a separate decorator from CachingTibiaApi rather than an
 *  extension of it — that class's own doc explains why the world/character
 *  firehose is never cached there (it would delay death detection); the TTL
 *  here is short enough (well under the ~60s poll interval) to not have that
 *  effect, but the concerns are different enough to keep them apart. Sits in
 *  front of CachingTibiaApi (which wraps TibiaDataClient), so a Primary's own
 *  fetch still benefits from whatever CachingTibiaApi caches on other
 *  endpoints. */
final class SharedWorldTibiaApi(
    underlying: TibiaApi,
    cache: RedisCache,
    role: Config.BotRole.Role,
    ttl: FiniteDuration = 90.seconds
)(implicit ec: ExecutionContext)
    extends TibiaApi with JsonSupport with StrictLogging {

  private def sharedWorldKey(world: String): String = s"tibia:world-shared:${world.toLowerCase}"
  private def sharedCharacterKey(name: String): String = s"tibia:character-shared:${name.toLowerCase}"

  def getWorld(world: String): Future[Either[String, WorldResponse]] = role match {
    case Config.BotRole.Primary =>
      underlying.getWorld(world).map { result =>
        result.foreach { worldResponse =>
          cache.setEx(sharedWorldKey(world), worldResponse.toJson.compactPrint, ttl).recover {
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
        // Only a genuine fresh fetch is shareable — see the class doc for why
        // a Left (including the underlying client's own "Hit cache" signal)
        // must never be published.
        result.foreach { characterResponse =>
          cache.setEx(sharedCharacterKey(name), characterResponse.toJson.compactPrint, ttl).recover {
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
        case None => underlying.getCharacter(name)
      }
    case Config.BotRole.Disabled =>
      underlying.getCharacter(name)
  }

  def getWorlds(): Future[Either[String, WorldsResponse]] = underlying.getWorlds()
  def getBoostedBoss(): Future[Either[String, BoostedResponse]] = underlying.getBoostedBoss()
  def getBoostedCreature(): Future[Either[String, CreatureResponse]] = underlying.getBoostedCreature()
  def getGuild(guild: String): Future[Either[String, GuildResponse]] = underlying.getGuild(guild)
  def getGuildWithInput(input: (String, String)): Future[(Either[String, GuildResponse], String, String)] = underlying.getGuildWithInput(input)
  def getKillerFallback(name: String): Future[Either[String, CharacterResponse]] = underlying.getKillerFallback(name)
  def getCharacterV2(input: (String, Int)): Future[Either[String, CharacterResponse]] = underlying.getCharacterV2(input)
  def getCharacterWithInput(input: (String, String, String)): Future[(Either[String, CharacterResponse], String, String, String)] = underlying.getCharacterWithInput(input)
}
