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
 *  `getWorld` as normal and additionally fire-and-forget publishes the raw
 *  result to Redis; a `Slave` reads that published result first, falling
 *  back to fetching it directly on a miss (primary hasn't polled this cycle
 *  yet, or is down). Every other method passes straight through to
 *  `underlying` unchanged.
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

  private def sharedKey(world: String): String = s"tibia:world-shared:${world.toLowerCase}"

  def getWorld(world: String): Future[Either[String, WorldResponse]] = role match {
    case Config.BotRole.Primary =>
      underlying.getWorld(world).map { result =>
        result.foreach { worldResponse =>
          cache.setEx(sharedKey(world), worldResponse.toJson.compactPrint, ttl).recover {
            case NonFatal(e) => logger.warn(s"Failed to publish shared world data for '$world': ${e.getMessage}")
          }
        }
        result
      }
    case Config.BotRole.Slave =>
      cache.get(sharedKey(world)).recover { case NonFatal(_) => None }.flatMap {
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

  def getWorlds(): Future[Either[String, WorldsResponse]] = underlying.getWorlds()
  def getBoostedBoss(): Future[Either[String, BoostedResponse]] = underlying.getBoostedBoss()
  def getBoostedCreature(): Future[Either[String, CreatureResponse]] = underlying.getBoostedCreature()
  def getHighscores(world: String, page: Int): Future[Either[String, HighscoresResponse]] = underlying.getHighscores(world, page)
  def getGuild(guild: String): Future[Either[String, GuildResponse]] = underlying.getGuild(guild)
  def getGuildWithInput(input: (String, String)): Future[(Either[String, GuildResponse], String, String)] = underlying.getGuildWithInput(input)
  def getCharacter(name: String): Future[Either[String, CharacterResponse]] = underlying.getCharacter(name)
  def getKillerFallback(name: String): Future[Either[String, CharacterResponse]] = underlying.getKillerFallback(name)
  def getCharacterV2(input: (String, Int)): Future[Either[String, CharacterResponse]] = underlying.getCharacterV2(input)
  def getCharacterWithInput(input: (String, String, String)): Future[(Either[String, CharacterResponse], String, String, String)] = underlying.getCharacterWithInput(input)
}
