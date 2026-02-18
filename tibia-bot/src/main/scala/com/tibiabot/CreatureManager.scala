package com.tibiabot

import com.tibiabot.tibiadata.TibiaDataClient
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}
import java.time.ZonedDateTime

object CreatureManager extends StrictLogging {

  implicit private val executionContext: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  private val tibiaDataClient = new TibiaDataClient()
  private var cachedCreatureList: Option[List[String]] = None
  private var lastFetchTime: Option[ZonedDateTime] = None

  // Creatures endpoint on TibiaData Api uses pluralization, race is unconventional name
  // Can't be used yet, needs work

  // Fallback static creature list in case API fails (truncated for brevity)
  private val fallbackCreatureList = List(
    "abyssal calamary", "acid blob"
  )

  def getCreaturesList(): List[String] = {
    logger.info("Cache expired or empty, fetching fresh creature list from API")
    refreshCreatureList()
  }

  private def refreshCreatureList(): List[String] = {
    Try {
      val creaturesResponse = Await.result(tibiaDataClient.getCreatures(), Duration(30, "seconds"))
      creaturesResponse match {
        case Right(response) =>
          // Convert creature names to lowercase and extract from the creature_list
          val creatureNames = response.creatures.creature_list.map(_.name.toLowerCase).sorted
          cachedCreatureList = Some(creatureNames)
          lastFetchTime = Some(ZonedDateTime.now())
          logger.info(s"Successfully fetched ${creatureNames.length} creatures from TibiaData API")
          creatureNames
        case Left(error) =>
          logger.warn(s"Failed to fetch creatures from API: $error, using fallback list")
          cachedCreatureList.getOrElse(fallbackCreatureList)
      }
    } match {
      case Success(creatures) => creatures
      case Failure(exception) =>
        logger.error(s"Exception while fetching creatures from API: ${exception.getMessage}, using fallback list")
        cachedCreatureList.getOrElse(fallbackCreatureList)
    }
  }
}
