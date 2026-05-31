package com.tibiabot

import com.tibiabot.tibiadata.TibiaDataClient
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.{Failure, Success, Try}
import java.time.{Duration, ZonedDateTime}

object WorldManager extends StrictLogging {

  implicit private val system: akka.actor.ActorSystem = akka.actor.ActorSystem()

  private val tibiaDataClient: tibiadata.TibiaApi =
    new tibiadata.CachingTibiaApi(new TibiaDataClient(), persistence.RedisCacheProvider.cache)(scala.concurrent.ExecutionContext.global)

  // The world list changes only at major game updates, so cache it (default 1h)
  // instead of making a blocking API call on every getWorldList() (e.g. once per
  // /leaderboards). Falls back to the last good value, then the static list.
  // TTL is centralised with the other cache TTLs in Config.Cache (discord.conf cache {}).
  private val cacheTtl = Duration.ofMillis(Config.Cache.worldListTtl.toMillis)

  // Fallback static world list in case API fails
  private val fallbackWorldList = List(
    "Antica", "Astera", "Axera", "Belobra", "Bombra", "Bona", "Calmera", "Castela",
    "Celebra", "Celesta", "Collabra", "Damora", "Descubra", "Dia", "Epoca", "Etebra",
    "Ferobra", "Firmera", "Gentebra", "Gladera", "Guerribra", "Harmonia",
    "Havera", "Honbra", "Impulsa", "Inabra", "Issobra", "Jadebra", "Kalibra",
    "Kardera", "Kendria", "Lobera", "Luminera", "Lutabra", "Menera", "Monza", "Mykera", "Nadora",
    "Nefera", "Nevia", "Ombra", "Ousabra", "Pacera", "Peloria", "Premia", "Pulsera",
    "Quelibra", "Quintera", "Rasteibra", "Refugia", "Retalia", "Secura", "Serdebra",
    "Solidera", "Syrena", "Talera", "Thyria", "Tornabra", "Ustebra", "Utobra", "Venebra",
    "Vunira", "Wintera", "Yonabra", "Yovera", "Zuna", "Zunera", "Victoris",
    "Oceanis", "Stralis", "Unebra", "Yubra",
    "Quidera", "Ourobra", "Gladibra", "Xyla", "Karmeya",
    "Bravoria", "Aethera", "Cantabra", "Noctalia", "Ignitera", "Xybra", "Sonira", "Kalimera", "Luzibra",
    "Idyllia", "Hostera", "Dracobra", "Xymera", "Blumera", "Monstera", "Tempestera", "Terribra", "Sombra", "Eclipta", "Kalanta", "Citra", "Kanda", "Opulera", "Ignibra", "Maligna", "Junera", "Floribra"
  )

  private val worldListCache = new CachedList[String](
    fetch = () => fetchWorldNames(),
    fallback = fallbackWorldList,
    ttl = cacheTtl,
    now = () => ZonedDateTime.now()
  )

  def getWorldList(): List[String] = worldListCache.get()

  /** One blocking fetch of the sorted regular-world names, as an Either so the
   *  cache can decide whether to keep the previous value on failure. */
  private def fetchWorldNames(): Either[String, List[String]] = {
    logger.info("Fetching world list from TibiaData API...")
    Try(Await.result(tibiaDataClient.getWorlds(), 30.seconds)) match {
      case Success(Right(response)) =>
        val worldNames = response.worlds.regular_worlds.map(_.name).sorted
        logger.info(s"Successfully fetched ${worldNames.length} worlds from TibiaData API")
        Right(worldNames)
      case Success(Left(error)) =>
        logger.warn(s"Failed to fetch worlds from API: $error, using last good / fallback list")
        Left(error)
      case Failure(exception) =>
        logger.error(s"Exception while fetching worlds from API: ${exception.getMessage}, using last good / fallback list")
        Left(exception.getMessage)
    }
  }
}
