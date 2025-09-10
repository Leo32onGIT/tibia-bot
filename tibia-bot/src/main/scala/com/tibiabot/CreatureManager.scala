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
  private val cacheValidityHours = 24 // Cache creatures for 24 hours (they don't change frequently)
  
  // Fallback static creature list in case API fails (truncated for brevity)
  private val fallbackCreatureList = List(
    "abyssal calamary", "acid blob", "acolyte of darkness", "acolyte of the cult", "adept of the cult",
    "adult goanna", "adventurer", "afflicted strider", "aggressive chicken", "agrestic chicken",
    "albino dragon", "amazon", "ancient lion knight", "ancient scarab", "ancient ugly monster",
    "angry adventurer", "angry demon", "angry plant", "animated clomp", "animated cyclops",
    "animated feather", "animated guzzlemaw", "animated moohtant", "animated mummy",
    "animated ogre brute", "animated ogre savage", "animated ogre shaman", "animated rotworm",
    "animated skunk", "animated snowman", "animated sword", "arachnophobica", "arctic faun",
    "armadile", "askarak demon", "askarak lord", "askarak prince", "assassin", "azure frog",
    "badger", "baleful bunny", "bandit", "bane bringer", "bane of light", "banshee",
    "barbarian bloodwalker", "barbarian brutetamer", "barbarian headsplitter", "barbarian skullhunter",
    "barkless devotee", "barkless fanatic", "bashmu", "bat", "bear", "behemoth", "bellicose orger",
    "berrypest", "berserker chicken", "betrayed wraith", "biting book", "black cobra", "black sheep",
    "black sphinx acolyte", "blazing fire elemental", "blemished spawn", "blightwalker",
    "blistering fire elemental", "bloated man-maggot", "blood beast", "blood crab", "blood hand",
    "blood priest", "bloom of doom", "blue djinn", "boar man", "boar", "bog frog", "bog raider",
    "bonebeast", "bonelord", "bonny bunny", "bony sea devil", "boogy", "bound astral power",
    "brachiodemon", "brain squid", "braindeath", "branchy crawler", "breach brood", "bride of night"
  )
  
  def getCreaturesList(): List[String] = {
    if (isCacheValid()) {
      logger.debug("Using cached creature list")
      cachedCreatureList.getOrElse(fallbackCreatureList)
    } else {
      logger.info("Cache expired or empty, fetching fresh creature list from API")
      refreshCreatureList()
    }
  }
  
  private def isCacheValid(): Boolean = {
    cachedCreatureList.isDefined && lastFetchTime.exists { fetchTime =>
      ZonedDateTime.now().isBefore(fetchTime.plusHours(cacheValidityHours))
    }
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
  
  def refreshCreatureListAsync(): Future[List[String]] = {
    logger.info("Starting async refresh of creature list")
    tibiaDataClient.getCreatures().map {
      case Right(response) =>
        val creatureNames = response.creatures.creature_list.map(_.name.toLowerCase).sorted
        cachedCreatureList = Some(creatureNames)
        lastFetchTime = Some(ZonedDateTime.now())
        logger.info(s"Successfully refreshed creature list with ${creatureNames.length} creatures")
        creatureNames
      case Left(error) =>
        logger.warn(s"Failed to refresh creatures from API: $error, keeping current cache")
        cachedCreatureList.getOrElse(fallbackCreatureList)
    }.recover {
      case exception =>
        logger.error(s"Exception during async creature refresh: ${exception.getMessage}, keeping current cache")
        cachedCreatureList.getOrElse(fallbackCreatureList)
    }
  }
}