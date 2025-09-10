package com.tibiabot

import com.tibiabot.tibiadata.TibiaDataClient
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}
import java.time.ZonedDateTime

object WorldManager extends StrictLogging {
  
  implicit private val executionContext: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
  
  private val tibiaDataClient = new TibiaDataClient()
  private var cachedWorldList: Option[List[String]] = None
  private var lastFetchTime: Option[ZonedDateTime] = None
  private val cacheValidityHours = 24 // Cache worlds for 24 hours
  
  // Fallback static world list in case API fails
  private val fallbackWorldList = List(
    "Ambra", "Antica", "Astera", "Axera", "Belobra", "Bombra", "Bona", "Calmera", "Castela", 
    "Celebra", "Celesta", "Collabra", "Damora", "Descubra", "Dia", "Epoca", "Esmera", "Etebra", 
    "Ferobra", "Firmera", "Flamera", "Gentebra", "Gladera", "Gravitera", "Guerribra", "Harmonia", 
    "Havera", "Honbra", "Impulsa", "Inabra", "Issobra", "Jacabra", "Jadebra", "Jaguna", "Kalibra", 
    "Kardera", "Kendria", "Lobera", "Luminera", "Lutabra", "Menera", "Monza", "Mykera", "Nadora", 
    "Nefera", "Nevia", "Ombra", "Obscubra", "Ousabra", "Pacera", "Peloria", "Premia", "Pulsera", 
    "Quelibra", "Quintera", "Rasteibra", "Refugia", "Retalia", "Runera", "Secura", "Serdebra", 
    "Solidera", "Syrena", "Talera", "Thyria", "Tornabra", "Ustebra", "Utobra", "Venebra", "Vitera", 
    "Vunira", "Wadira", "Wildera", "Wintera", "Yonabra", "Yovera", "Zuna", "Zunera", "Victoris", 
    "Oceanis", "Stralis", "Yara", "Vandera", "Unebra", "Zephyra", "Ulera", "Yubra", "Divina", 
    "Temera", "Quebra", "Quidera", "Fibera", "Ourobra", "Gladibra", "Xyla", "Karmeya", "Malivora", 
    "Bravoria", "Aethera", "Cantabra", "Noctalia", "Ignitera", "Xybra", "Sonira", "Kalimera", "Luzibra"
  )
  
  def getWorldList(): List[String] = {
    if (isCacheValid()) {
      logger.debug("Using cached world list")
      cachedWorldList.getOrElse(fallbackWorldList)
    } else {
      logger.info("Cache expired or empty, fetching fresh world list from API")
      refreshWorldList()
    }
  }
  
  private def isCacheValid(): Boolean = {
    cachedWorldList.isDefined && lastFetchTime.exists { fetchTime =>
      ZonedDateTime.now().isBefore(fetchTime.plusHours(cacheValidityHours))
    }
  }
  
  private def refreshWorldList(): List[String] = {
    Try {
      val worldsResponse = Await.result(tibiaDataClient.getWorlds(), Duration(30, "seconds"))
      worldsResponse match {
        case Right(response) =>
          val worldNames = response.worlds.regular_worlds.map(_.name).sorted
          cachedWorldList = Some(worldNames)
          lastFetchTime = Some(ZonedDateTime.now())
          logger.info(s"Successfully fetched ${worldNames.length} worlds from TibiaData API")
          worldNames
        case Left(error) =>
          logger.warn(s"Failed to fetch worlds from API: $error, using fallback list")
          cachedWorldList.getOrElse(fallbackWorldList)
      }
    } match {
      case Success(worlds) => worlds
      case Failure(exception) =>
        logger.error(s"Exception while fetching worlds from API: ${exception.getMessage}, using fallback list")
        cachedWorldList.getOrElse(fallbackWorldList)
    }
  }
  
  def refreshWorldListAsync(): Future[List[String]] = {
    logger.info("Starting async refresh of world list")
    tibiaDataClient.getWorlds().map {
      case Right(response) =>
        val worldNames = response.worlds.regular_worlds.map(_.name).sorted
        cachedWorldList = Some(worldNames)
        lastFetchTime = Some(ZonedDateTime.now())
        logger.info(s"Successfully refreshed world list with ${worldNames.length} worlds")
        worldNames
      case Left(error) =>
        logger.warn(s"Failed to refresh worlds from API: $error, keeping current cache")
        cachedWorldList.getOrElse(fallbackWorldList)
    }.recover {
      case exception =>
        logger.error(s"Exception during async world refresh: ${exception.getMessage}, keeping current cache")
        cachedWorldList.getOrElse(fallbackWorldList)
    }
  }
}