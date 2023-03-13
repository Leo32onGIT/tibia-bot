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
import scala.concurrent.duration._
import akka.http.scaladsl.model.HttpEntity.Strict
import scala.util.Random

import scala.concurrent.{ExecutionContextExecutor, Future}

class TibiaDataClient extends JsonSupport with StrictLogging {

  implicit private val system: ActorSystem = ActorSystem()
  implicit private val executionContext: ExecutionContextExecutor = system.dispatcher

  private val characterUrl = "https://api.tibiadata.com/v3/character/"
  private val guildUrl = "https://api.tibiadata.com/v3/guild/"

  def getWorld(world: String): Future[WorldResponse] = {
    val encodedName = URLEncoder.encode(world, "UTF-8")
    Http().singleRequest(HttpRequest(uri = s"https://api.tibiadata.com/v3/world/$encodedName"))
      .flatMap { response =>
        if (response.status.isSuccess()) {
          Unmarshal(decodeResponse(response).entity).to[WorldResponse]
        } else {
          response.discardEntityBytes() // discard the entity if the response status is not success
          Future.failed(new RuntimeException(s"Failed to get world $world with status ${response.status}"))
        }
      }
  }

  def getGuild(guild: String): Future[GuildResponse] = {
    val encodedName = URLEncoder.encode(guild, "UTF-8")
    Http().singleRequest(HttpRequest(uri = s"$guildUrl$encodedName"))
      .flatMap { response =>
        if (response.status.isSuccess()) {
          Unmarshal(decodeResponse(response).entity).to[GuildResponse]
        } else {
          response.discardEntityBytes() // discard the entity if the response status is not success
          Future.failed(new RuntimeException(s"Failed to get guild $guild with status ${response.status}"))
        }
      }
  }

  def getGuildWithInput(input: (String, String)): Future[(GuildResponse, String, String)] = {
    val name = input._1
    val reason = input._2
    val encodedName = URLEncoder.encode(name, "UTF-8")
    Http().singleRequest(HttpRequest(uri = s"$guildUrl$encodedName"))
      .flatMap { response =>
        if (response.status.isSuccess()) {
          Unmarshal(decodeResponse(response).entity).to[GuildResponse].map { guildResponse =>
            (guildResponse, name, reason)
          }
        } else {
          response.discardEntityBytes() // discard the entity if the response status is not success
          Future.failed(new RuntimeException(s"Failed to get guild $name with status ${response.status}"))
        }
      }
  }

  def getCharacter(name: String): Future[CharacterResponse] = {

    /*** PATCHED
    var obfsName = ""
    val rand = scala.util.Random
    name.toLowerCase.foreach { letter =>
      if (letter.isLetter && rand.nextBoolean() == true) {
        obfsName += s"${letter.toUpper}"
      } else {
        obfsName += s"$letter"
      }
    }
    ***/

    val encodedName = URLEncoder.encode(name, "UTF-8")

    Http().singleRequest(HttpRequest(uri = s"$characterUrl${encodedName}"))
      .flatMap { response =>
        if (response.status.isSuccess()) {
          Unmarshal(decodeResponse(response).entity).to[CharacterResponse]
        } else {
          response.discardEntityBytes() // discard the entity if the response status is not success
          Future.failed(new RuntimeException(s"Failed to get character $name with status ${response.status}"))
        }
      }
  }

  def getCharacterV2(input: (String, Int)): Future[CharacterResponse] = {
    val name = input._1
    val level = input._2
    val encodedName = URLEncoder.encode(name, "UTF-8")
    val bypassName = if (level >= 250) {
      val random = new Random()
      val numPluses = random.nextInt(4)
      val nameWithPluses = encodedName + ("+" * numPluses)
      nameWithPluses
    } else {
      encodedName
    }

    Http().singleRequest(HttpRequest(uri = s"$characterUrl${bypassName}"))
      .flatMap { response =>
        if (response.status.isSuccess()) {
          Unmarshal(decodeResponse(response).entity).to[CharacterResponse]
        } else {
          response.discardEntityBytes() // discard the entity if the response status is not success
          Future.failed(new RuntimeException(s"Failed to get character $name with status ${response.status}"))
        }
      }
  }

  def getCharacterWithInput(input: (String, String, String)): Future[(CharacterResponse, String, String, String)] = {
    val name = input._1
    val reason = input._2
    val reasonText = input._3
    val encodedName = URLEncoder.encode(name, "UTF-8")

    val request = HttpRequest(uri = s"$characterUrl${encodedName}")

    Http().singleRequest(request)
      .flatMap { response =>
        if (response.status.isSuccess()) {
          Unmarshal(decodeResponse(response).entity).to[CharacterResponse].map { unmarshalled =>
            (unmarshalled, name, reason, reasonText)
          }
        } else {
          response.discardEntityBytes()
          Future.failed(new RuntimeException(s"Failed to get character $name with status ${response.status}"))
        }
      }
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
