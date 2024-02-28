package com.tibiabot
package tibiadata

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Coders
import akka.http.scaladsl.model.headers.HttpEncodings
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.tibiabot.tibiadata.response.{CharacterResponse, WorldResponse, GuildResponse, BoostedResponse, CreatureResponse, RaceResponse}
import com.typesafe.scalalogging.StrictLogging
import spray.json.JsonParser.ParsingException
import java.net.URLEncoder
import scala.concurrent.duration._
import akka.http.scaladsl.model.HttpEntity.Strict
import scala.util.Random

import scala.concurrent.{ExecutionContextExecutor, Future}
import spray.json.DeserializationException

class TibiaDataClient extends JsonSupport with StrictLogging {

  implicit private val system: ActorSystem = ActorSystem()
  implicit private val executionContext: ExecutionContextExecutor = system.dispatcher

  private val characterUrl = "https://api.tibiadata.com/v4/character/"
  private val guildUrl = "https://api.tibiadata.com/v4/guild/"

  def getWorld(world: String): Future[Either[String, WorldResponse]] = {
    val encodedName = URLEncoder.encode(world, "UTF-8").replaceAll("\\+", "%20")
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"https://api.tibiadata.com/v4/world/$encodedName"))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[WorldResponse].map(Right(_))
        .recover {
          case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
            val errorMessage = s"Failed to get world: '${encodedName.replaceAll("%20", " ")}' with status: '${response.status}'"
            logger.warn(errorMessage)
            Left(errorMessage)
          case e @ (_: ParsingException | _: DeserializationException) =>
            val errorMessage = s"Failed to parse world: '${encodedName.replaceAll("%20", " ")}'"
            logger.warn(errorMessage)
            Left(errorMessage)
        }
    } yield unmarshalled
  }

  def getBoostedBoss(): Future[Either[String, BoostedResponse]] = {
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"https://api.tibiadata.com/v4/boostablebosses"))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[BoostedResponse].map(Right(_))
        .recover {
          case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
            val errorMessage = s"Failed to get boosted boss with status: '${response.status}'"
            logger.warn(errorMessage)
            Left(errorMessage)
          case e @ (_: ParsingException | _: DeserializationException) =>
            val errorMessage = s"Failed to parse boosted boss"
            logger.warn(e.getMessage)
            Left(errorMessage)
        }
    } yield unmarshalled
  }

  def getBoostedCreature(): Future[Either[String, CreatureResponse]] = {
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"https://api.tibiadata.com/v4/creatures"))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[CreatureResponse].map(Right(_))
        .recover {
          case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
            val errorMessage = s"Failed to get boosted creature with status: '${response.status}'"
            logger.warn(errorMessage)
            Left(errorMessage)
          case e @ (_: ParsingException | _: DeserializationException) =>
            val errorMessage = s"Failed to parse boosted creature"
            logger.warn(e.getMessage)
            Left(errorMessage)
        }
    } yield unmarshalled
  }

  /**
  // unused
  def getCreature(creature: String): Future[Either[String, RaceResponse]] = {
    val encodedCreature = URLEncoder.encode(creature, "UTF-8").replaceAll("\\+", "%20")
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"https://api.tibiadata.com/v4/creature/$encodedCreature"))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[RaceResponse].map(Right(_))
        .recover {
          case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
            val errorMessage = s"Failed to get creature check with status: '${response.status}'"
            logger.warn(errorMessage)
            Left(errorMessage)
          case e @ (_: ParsingException | _: DeserializationException) =>
            val errorMessage = s"Failed to parse creature check"
            logger.warn(e.getMessage)
            Left(errorMessage)
        }
    } yield unmarshalled
  }
  **/

  def getGuild(guild: String): Future[Either[String, GuildResponse]] = {
    val encodedName = URLEncoder.encode(guild, "UTF-8").replaceAll("\\+", "%20")
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"$guildUrl$encodedName"))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[GuildResponse].map(Right(_))
        .recover {
          case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
            val errorMessage = s"Failed to get guild: '${encodedName.replaceAll("%20", " ")}' with status: '${response.status}'"
            logger.warn(errorMessage)
            Left(errorMessage)
          case e @ (_: ParsingException | _: DeserializationException) =>
            val errorMessage = s"Failed to parse guild: '${encodedName.replaceAll("%20", " ")}'"
            logger.warn(errorMessage)
            Left(errorMessage)
        }
    } yield unmarshalled
  }

  def getGuildWithInput(input: (String, String)): Future[(Either[String, GuildResponse], String, String)] = {
    val guild = input._1
    val reason = input._2
    val encodedName = URLEncoder.encode(guild, "UTF-8").replaceAll("\\+", "%20")
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"$guildUrl$encodedName"))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[GuildResponse].map(Right(_))
        .recover {
          case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
            val errorMessage = s"Failed to get guild: '${encodedName.replaceAll("%20", " ")}' with status: '${response.status}'"
            logger.warn(errorMessage)
            Left(errorMessage)
          case e @ (_: ParsingException | _: DeserializationException) =>
            val errorMessage = s"Failed to parse guild: '${encodedName.replaceAll("%20", " ")}'"
            logger.warn(errorMessage)
            Left(errorMessage)
        }
    } yield (unmarshalled, guild, reason)
  }

  def getCharacter(name: String): Future[Either[String, CharacterResponse]] = {
    val encodedName = URLEncoder.encode(name, "UTF-8").replaceAll("\\+", "%20")
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"$characterUrl${encodedName}"))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[CharacterResponse].map(Right(_))
        .recover {
          case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
            val errorMessage = s"Failed to get character: '${encodedName.replaceAll("%20", " ")}' with status: '${response.status}'"
            logger.warn(errorMessage)
            Left(errorMessage)
          case e @ (_: ParsingException | _: DeserializationException) =>
            val errorMessage = s"Failed to parse character: '${encodedName.replaceAll("%20", " ")}'"
            logger.warn(errorMessage)
            Left(errorMessage)
        }
    } yield unmarshalled
  }

  def getCharacterV2(input: (String, Int)): Future[Either[String, CharacterResponse]] = {
    val name = input._1
    val level = input._2
    val encodedName = URLEncoder.encode(name, "UTF-8").replaceAll("\\+", "%20")
    val bypassName: String = if (level >= 400) {
      val random = new Random()
      // Append randomly generated "+" characters to the last word, limited to a maximum length of 20
      val numPluses = math.min(random.nextInt(7), 20 - encodedName.length) // Randomly generate 0-6 "+" characters, limited to a max length of 20
      encodedName + ("+" * numPluses)
    } else {
      encodedName
    }
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"$characterUrl${bypassName}"))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[CharacterResponse].map(Right(_))
        .recover {
          case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
            val errorMessage = s"Failed to get character: '${bypassName.replaceAll("%20", " ")}' with status: '${response.status}'"
            logger.warn(errorMessage)
            Left(errorMessage)
          case e @ (_: ParsingException | _: DeserializationException) =>
            val errorMessage = s"Failed to parse character: '${bypassName.replaceAll("%20", " ")}'"
            logger.warn(errorMessage)
            Left(errorMessage)
        }
    } yield unmarshalled
  }

  def getCharacterWithInput(input: (String, String, String)): Future[(Either[String, CharacterResponse], String, String, String)] = {
    val name = input._1
    val reason = input._2
    val reasonText = input._3
    val encodedName = URLEncoder.encode(name, "UTF-8").replaceAll("\\+", "%20")
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"$characterUrl${encodedName}"))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[CharacterResponse].map(Right(_))
        .recover {
          case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
            val errorMessage = s"Failed to get character: '${encodedName.replaceAll("%20", " ")}' with status: '${response.status}'"
            logger.warn(errorMessage)
            Left(errorMessage)
          case e @ (_: ParsingException | _: DeserializationException) =>
            val errorMessage = s"Failed to parse character: '${encodedName.replaceAll("%20", " ")}'"
            logger.warn(errorMessage)
            Left(errorMessage)
        }
    } yield (unmarshalled, name, reason, reasonText)
  }

  private def decodeResponse(response: HttpResponse): HttpResponse = {
    val decoder = response.encoding match {
      case HttpEncodings.gzip => Coders.Gzip
      case HttpEncodings.deflate => Coders.Deflate
      case HttpEncodings.identity => Coders.NoCoding
      case other =>
        logger.warn(s"Unknown encoding [$other], not decoding")
        Coders.NoCoding
    }

    decoder.decodeMessage(response)
  }
}
