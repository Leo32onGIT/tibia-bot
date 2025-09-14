package com.tibiabot
package tibiadata

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Coders
import akka.http.scaladsl.model.headers.HttpEncodings
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.tibiabot.tibiadata.response.{CharacterResponse, WorldResponse, WorldsResponse, CreaturesResponse, GuildResponse, BoostedResponse, CreatureResponse, RaceResponse, HighscoresResponse, NewsResponse, NewsTickerResponse}
import com.typesafe.scalalogging.StrictLogging
import spray.json.JsonParser.ParsingException
import java.net.URLEncoder
import scala.concurrent.duration._
import akka.http.scaladsl.model.HttpEntity.Strict
import scala.util.Random
import com.tibiabot.BotApp.characterCache
import scala.concurrent.{ExecutionContextExecutor, Future}
import spray.json.DeserializationException
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers.{Date => DateHeader}
import java.time.{ZonedDateTime, ZoneId}
import java.time.format.DateTimeFormatter

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

  def getWorlds(): Future[Either[String, WorldsResponse]] = {
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"https://api.tibiadata.com/v4/worlds"))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[WorldsResponse].map(Right(_))
        .recover {
          case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
            val errorMessage = s"Failed to get worlds with status: '${response.status}'"
            logger.warn(errorMessage)
            Left(errorMessage)
          case e @ (_: ParsingException | _: DeserializationException) =>
            val errorMessage = s"Failed to parse worlds response"
            logger.warn(errorMessage)
            Left(errorMessage)
        }
    } yield unmarshalled
  }

  def getCreatures(): Future[Either[String, CreaturesResponse]] = {
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"https://api.tibiadata.com/v4/creatures"))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[CreaturesResponse].map(Right(_))
        .recover {
          case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
            val errorMessage = s"Failed to get creatures with status: '${response.status}'"
            logger.warn(errorMessage)
            Left(errorMessage)
          case e @ (_: ParsingException | _: DeserializationException) =>
            val errorMessage = s"Failed to parse creatures response"
            logger.warn(errorMessage)
            Left(errorMessage)
        }
    } yield unmarshalled
  }

  def getBoostedBoss(): Future[Either[String, BoostedResponse]] = {
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"${Config.tibiadataApi}/v4/boostablebosses"))
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
      response <- Http().singleRequest(HttpRequest(uri = s"${Config.tibiadataApi}/v4/creatures"))
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

  def getHighscores(world: String, page: Int): Future[Either[String, HighscoresResponse]] = {
    val highscoresUri = s"${Config.tibiadataApi}/v4/highscores/${world}/experience/all/${page.toString}"
    for {
      response <- Http().singleRequest(HttpRequest(uri = highscoresUri))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[HighscoresResponse].map(Right(_))
        .recover {
          case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
            val errorMessage = s"Failed to get highscores with status: '${response.status}'"
            logger.warn(errorMessage)
            Left(errorMessage)
          case e @ (_: ParsingException | _: DeserializationException) =>
            val errorMessage = s"Failed to parse highscores"
            logger.warn(e.getMessage)
            Left(errorMessage)
        }
    } yield unmarshalled
  }

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
    val responseFuture = Http().singleRequest(HttpRequest(uri = s"$characterUrl$encodedName"))
    responseFuture.flatMap { response =>
      response.header[DateHeader] match {
        case Some(dateHeader) =>
          val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneId.of("GMT"))
          val responseDate = ZonedDateTime.parse(dateHeader.date.toString, formatter)
          characterCache.get(name) match {
            case Some(existingDate) if responseDate.isAfter(existingDate) =>
              characterCache += (name -> responseDate)
              val decoded = decodeResponse(response)
              Unmarshal(decoded).to[CharacterResponse].map(Right(_)).recover {
                case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
                  val errorMessage = s"Failed to get character: '${encodedName.replaceAll("%20", " ")}' with status: '${response.status}'"
                  logger.warn(errorMessage)
                  Left(errorMessage)
                case e @ (_: ParsingException | _: DeserializationException) =>
                  val errorMessage = s"Failed to parse character: '${encodedName.replaceAll("%20", " ")}'"
                  logger.warn(errorMessage)
                  Left(errorMessage)
              }
            case Some(_) =>
              response.discardEntityBytes()
              Future.successful(Left("Hit cache"))
            case None =>
              characterCache += (name -> responseDate)
              val decoded = decodeResponse(response)
              Unmarshal(decoded).to[CharacterResponse].map(Right(_)).recover {
                case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
                  val errorMessage = s"Failed to get character: '${encodedName.replaceAll("%20", " ")}' with status: '${response.status}'"
                  logger.warn(errorMessage)
                  Left(errorMessage)
                case e @ (_: ParsingException | _: DeserializationException) =>
                  val errorMessage = s"Failed to parse character: '${encodedName.replaceAll("%20", " ")}'"
                  logger.warn(errorMessage)
                  Left(errorMessage)
              }
          }
        case None =>
          response.discardEntityBytes()
          Future.successful(Left("No Date header in response"))
      }
    }
  }

  def getKillerFallback(name: String): Future[Either[String, CharacterResponse]] = {
    val encodedName = URLEncoder.encode(name, "UTF-8").replaceAll("\\+", "%20")
    val responseFuture = Http().singleRequest(HttpRequest(uri = s"$characterUrl$encodedName"))
    responseFuture.flatMap { response =>
      response.header[DateHeader] match {
        case Some(dateHeader) =>
          val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneId.of("GMT"))
          val responseDate = ZonedDateTime.parse(dateHeader.date.toString, formatter)
          val decoded = decodeResponse(response)
          Unmarshal(decoded).to[CharacterResponse].map(Right(_)).recover {
            case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
              val errorMessage = s"Failed to get character: '${encodedName.replaceAll("%20", " ")}' with status: '${response.status}'"
              logger.warn(errorMessage)
              Left(errorMessage)
            case e @ (_: ParsingException | _: DeserializationException) =>
              val errorMessage = s"Failed to parse character: '${encodedName.replaceAll("%20", " ")}'"
              logger.warn(errorMessage)
              Left(errorMessage)
          }
        case None =>
          response.discardEntityBytes()
          Future.successful(Left("No Date header in response"))
      }
    }
  }

  def getCharacterV2(input: (String, Int)): Future[Either[String, CharacterResponse]] = {
    val name = input._1
    val level = input._2
    val apiUrl = if (level >= 250) {
      s"${Config.tibiadataApi}/v4/character/"
    } else {
      characterUrl
    }
    val encodedName = URLEncoder.encode(name, "UTF-8").replaceAll("\\+", "%20")
    val bypassName: String = if (level >= 250) {
      val random = new Random()
      // Append randomly generated "+" characters to the last word, limited to a maximum length of 20
      val numPluses = math.min(random.nextInt(3), 20 - encodedName.length) // Randomly generate 0-6 "+" characters, limited to a max length of 20
      encodedName + ("+" * numPluses)
    } else {
      encodedName
    }
    val responseFuture = Http().singleRequest(HttpRequest(uri = s"$apiUrl$bypassName"))
    responseFuture.flatMap { response =>
      response.header[DateHeader] match {
        case Some(dateHeader) =>
          val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneId.of("GMT"))
          val responseDate = ZonedDateTime.parse(dateHeader.date.toString, formatter)
          characterCache.get(name) match {
            case Some(existingDate) if responseDate.isAfter(existingDate) =>
              characterCache += (name -> responseDate)
              val decoded = decodeResponse(response)
              Unmarshal(decoded).to[CharacterResponse].map(Right(_)).recover {
                case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
                  val errorMessage = s"Failed to get character: '${encodedName.replaceAll("%20", " ")}' with status: '${response.status}'"
                  logger.warn(errorMessage)
                  Left(errorMessage)
                case e @ (_: ParsingException | _: DeserializationException) =>
                  val errorMessage = s"Failed to parse character: '${encodedName.replaceAll("%20", " ")}'"
                  logger.warn(errorMessage)
                  Left(errorMessage)
              }
            case Some(_) =>
              response.discardEntityBytes()
              Future.successful(Left("Hit cache"))
            case None =>
              characterCache += (name -> responseDate)
              val decoded = decodeResponse(response)
              Unmarshal(decoded).to[CharacterResponse].map(Right(_)).recover {
                case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
                  val errorMessage = s"Failed to get character: '${encodedName.replaceAll("%20", " ")}' with status: '${response.status}'"
                  logger.warn(errorMessage)
                  Left(errorMessage)
                case e @ (_: ParsingException | _: DeserializationException) =>
                  val errorMessage = s"Failed to parse character: '${encodedName.replaceAll("%20", " ")}'"
                  logger.warn(errorMessage)
                  Left(errorMessage)
              }
          }
        case None =>
          response.discardEntityBytes()
          Future.successful(Left("No Date header in response"))
      }
    }
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

  def getLatestNews(): Future[Either[String, NewsResponse]] = {
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"${Config.tibiadataApi}/v4/news/latest"))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[NewsResponse].map(Right(_))
        .recover {
          case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
            val errorMessage = s"Failed to get latest news with status: '${response.status}'"
            logger.warn(errorMessage)
            Left(errorMessage)
          case e @ (_: ParsingException | _: DeserializationException) =>
            val errorMessage = s"Failed to parse latest news"
            logger.warn(errorMessage)
            Left(errorMessage)
        }
    } yield unmarshalled
  }

  def getNewsTicker(): Future[Either[String, NewsTickerResponse]] = {
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"${Config.tibiadataApi}/v4/news/newsticker"))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[NewsTickerResponse].map(Right(_))
        .recover {
          case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
            val errorMessage = s"Failed to get news ticker with status: '${response.status}'"
            logger.warn(errorMessage)
            Left(errorMessage)
          case e @ (_: ParsingException | _: DeserializationException) =>
            val errorMessage = s"Failed to parse news ticker"
            logger.warn(errorMessage)
            Left(errorMessage)
        }
    } yield unmarshalled
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
