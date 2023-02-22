package com.tibiabot
package tibiadata

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Coders
import akka.http.scaladsl.model.headers.HttpEncodings
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.tibiabot.tibiadata.response.{CharacterResponse, WorldResponse, GuildResponse}
import com.typesafe.scalalogging.StrictLogging
import java.net.URLEncoder

import scala.concurrent.{ExecutionContextExecutor, Future}

class TibiaDataClient extends JsonSupport with StrictLogging {

  implicit private val system: ActorSystem = ActorSystem()
  implicit private val executionContext: ExecutionContextExecutor = system.dispatcher

  private val characterUrl = "https://api.tibiadata.com/v3/character/"
  private val guildUrl = "https://api.tibiadata.com/v3/guild/"

  def getWorld(world: String): Future[WorldResponse] = {
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"https://api.tibiadata.com/v3/world/$world"))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[WorldResponse]
    } yield unmarshalled
  }

  def getGuild(guild: String): Future[GuildResponse] = {
    val encodedName = URLEncoder.encode(guild, "UTF-8")
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"$guildUrl${encodedName}"))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[GuildResponse]
    } yield unmarshalled
  }

  def getGuildWithInput(input: (String, String)): Future[(GuildResponse, String, String)] = {
    val name = input._1
    val reason = input._2
    val encodedName = URLEncoder.encode(name, "UTF-8")
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"$guildUrl${encodedName}"))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[GuildResponse]
    } yield (unmarshalled, name, reason)
  }

  def getCharacter(name: String): Future[CharacterResponse] = {

    // yeehaw
    var obfsName = ""
    val rand = scala.util.Random
    name.toLowerCase.foreach { letter =>
      if (letter.isLetter && rand.nextBoolean() == true) {
        obfsName += s"${letter.toUpper}"
      } else {
        obfsName += s"$letter"
      }
    }

    val encodedName = URLEncoder.encode(obfsName, "UTF-8")

    for {
      response <- Http().singleRequest(HttpRequest(uri = s"$characterUrl${encodedName}"))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[CharacterResponse]
    } yield unmarshalled
  }

  def getCharacterWithInput(input: (String, String, String)): Future[(CharacterResponse, String, String, String)] = {
    val name = input._1
    val reason = input._2
    val reasonText = input._3
    val encodedName = URLEncoder.encode(name, "UTF-8")
    for {
      response <- Http().singleRequest(HttpRequest(uri = s"$characterUrl${encodedName}"))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[CharacterResponse]
    } yield (unmarshalled, name, reason, reasonText)
  }

  private def decodeResponse(response: HttpResponse): HttpResponse = {
    val decoder = response.encoding match {
      case HttpEncodings.gzip =>
        Coders.Gzip
      case HttpEncodings.deflate =>
        Coders.Deflate
      case HttpEncodings.identity =>
        Coders.NoCoding
      case other =>
        logger.warn(s"Unknown encoding [$other], not decoding")
        Coders.NoCoding
    }

    decoder.decodeMessage(response)
  }

}
