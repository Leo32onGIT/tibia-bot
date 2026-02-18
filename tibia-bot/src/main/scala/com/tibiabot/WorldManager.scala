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
    "Idyllia", "Hostera", "Dracobra", "Xymera", "Blumera", "Monstera", "Tempestera", "Terribra", "Sombra", "Eclipta", "Kalanta", "Citra", "Kanda", "Opulera", "Ignibra"
  )

  def getWorldList(): List[String] = {
    logger.info("Fetching world list from TibiaData API...")
    refreshWorldList()
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
}
