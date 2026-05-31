package com.tibiabot
package tibiadata

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Coders
import akka.http.scaladsl.model.headers.HttpEncodings
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.tibiabot.tibiadata.response.{CharacterResponse, WorldResponse, WorldsResponse, GuildResponse, BoostedResponse, CreatureResponse, HighscoresResponse}
import com.typesafe.scalalogging.StrictLogging
import spray.json.JsonParser.ParsingException
import java.net.URLEncoder
import scala.util.Random
import com.tibiabot.BotApp.{characterCache, modifyCharacterCache}
import scala.concurrent.{ExecutionContextExecutor, Future}
import spray.json.DeserializationException
import akka.http.scaladsl.model.headers.{Date => DateHeader}
import java.time.{ZonedDateTime, ZoneId}
import java.time.format.DateTimeFormatter

class TibiaDataClient(implicit val system: ActorSystem) extends JsonSupport with StrictLogging with TibiaApi {

  implicit private val executionContext: ExecutionContextExecutor = system.dispatcher

  private val characterUrl = "https://api.tibiadata.com/v4/character/"
  private val guildUrl = "https://api.tibiadata.com/v4/guild/"

  // Start the shared latency/throughput probe (idempotent across the per-world clients).
  RequestMetrics.ensureReporting(system)

  /** Single chokepoint for every outbound request: issues it and records the
   *  round-trip latency (bucketed by host) into the shared RequestMetrics probe,
   *  without otherwise changing behaviour. */
  private def timedRequest(req: HttpRequest): Future[HttpResponse] = {
    val start = System.nanoTime()
    val response = Http().singleRequest(req)
    // Record only on success: a failed singleRequest (timeout, or a near-instant
    // max-open-requests pool rejection) would otherwise skew the latency
    // percentiles the probe exists to produce.
    response.onComplete {
      case scala.util.Success(_) => RequestMetrics.record(req.uri.authority.host.address(), (System.nanoTime() - start) / 1000000L)
      case _                     => ()
    }
    response
  }

  /** Shared recovery for an Unmarshal failure across every endpoint. On a
   *  non-JSON response (UnsupportedContentType) the spray-json unmarshaller
   *  rejects on the content-type check before reading the body, so the entity
   *  is unconsumed — drain it to free the akka-http pool connection. Parse
   *  failures already read the body, so they are not drained. Both log the
   *  friendly message plus the exception detail and yield Left; unmatched
   *  throwables propagate, exactly as the inline blocks did. */
  private def recoverUnmarshal[T](decoded: HttpResponse, contentTypeMessage: => String, parseMessage: => String): PartialFunction[Throwable, Either[String, T]] = {
    case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
      decoded.discardEntityBytes()
      val errorMessage = contentTypeMessage
      logger.warn(s"$errorMessage: ${e.getMessage}")
      Left(errorMessage)
    case e @ (_: ParsingException | _: DeserializationException) =>
      val errorMessage = parseMessage
      logger.warn(s"$errorMessage: ${e.getMessage}")
      Left(errorMessage)
  }

  /** Issue a GET, decode the (possibly gzipped) response and unmarshal its JSON
   *  body to T, recovering non-JSON / parse failures into a logged Left (draining
   *  the entity). The request/decode/unmarshal/recover shape shared by the
   *  parameter-free GET endpoints. `contentTypeMessage` receives the response so
   *  it can include the status. */
  private def fetch[T](uri: String, contentTypeMessage: HttpResponse => String, parseMessage: => String)
                      (implicit um: akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller[T]): Future[Either[String, T]] =
    for {
      response <- timedRequest(HttpRequest(uri = uri))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[T].map(Right(_))
        .recover(recoverUnmarshal(decoded, contentTypeMessage(response), parseMessage))
    } yield unmarshalled

  def getWorld(world: String): Future[Either[String, WorldResponse]] = {
    val encodedName = URLEncoder.encode(world, "UTF-8").replaceAll("\\+", "%20")
    fetch[WorldResponse](
      s"https://api.tibiadata.com/v4/world/$encodedName",
      resp => s"Failed to get world: '${encodedName.replaceAll("%20", " ")}' with status: '${resp.status}'",
      s"Failed to parse world: '${encodedName.replaceAll("%20", " ")}'")
  }

  def getWorlds(): Future[Either[String, WorldsResponse]] =
    fetch[WorldsResponse](
      s"https://api.tibiadata.com/v4/worlds",
      resp => s"Failed to get worlds with status: '${resp.status}'",
      s"Failed to parse worlds response")

  def getBoostedBoss(): Future[Either[String, BoostedResponse]] =
    fetch[BoostedResponse](
      s"${Config.tibiadataApi}/v4/boostablebosses",
      resp => s"Failed to get boosted boss with status: '${resp.status}'",
      s"Failed to parse boosted boss")

  def getBoostedCreature(): Future[Either[String, CreatureResponse]] =
    fetch[CreatureResponse](
      s"${Config.tibiadataApi}/v4/creatures",
      resp => s"Failed to get boosted creature with status: '${resp.status}'",
      s"Failed to parse boosted creature")

  def getHighscores(world: String, page: Int): Future[Either[String, HighscoresResponse]] =
    fetch[HighscoresResponse](
      s"${Config.tibiadataApi}/v4/highscores/${world}/experience/all/${page.toString}",
      resp => s"Failed to get highscores with status: '${resp.status}'",
      s"Failed to parse highscores")

  def getGuild(guild: String): Future[Either[String, GuildResponse]] = {
    val encodedName = URLEncoder.encode(guild, "UTF-8").replaceAll("\\+", "%20")
    fetch[GuildResponse](
      s"$guildUrl$encodedName",
      resp => s"Failed to get guild: '${encodedName.replaceAll("%20", " ")}' with status: '${resp.status}'",
      s"Failed to parse guild: '${encodedName.replaceAll("%20", " ")}'")
  }

  def getGuildWithInput(input: (String, String)): Future[(Either[String, GuildResponse], String, String)] = {
    val guild = input._1
    val reason = input._2
    val encodedName = URLEncoder.encode(guild, "UTF-8").replaceAll("\\+", "%20")
    fetch[GuildResponse](
      s"$guildUrl$encodedName",
      resp => s"Failed to get guild: '${encodedName.replaceAll("%20", " ")}' with status: '${resp.status}'",
      s"Failed to parse guild: '${encodedName.replaceAll("%20", " ")}'")
      .map(unmarshalled => (unmarshalled, guild, reason))
  }

  /** Decode + unmarshal a character response, recovering failures to a logged
   *  Left (draining on the non-JSON path). Shared by the character endpoints. */
  private def unmarshalCharacter(response: HttpResponse, encodedName: String): Future[Either[String, CharacterResponse]] = {
    val decoded = decodeResponse(response)
    Unmarshal(decoded).to[CharacterResponse].map(Right(_)).recover(recoverUnmarshal(
      decoded,
      s"Failed to get character: '${encodedName.replaceAll("%20", " ")}' with status: '${response.status}'",
      s"Failed to parse character: '${encodedName.replaceAll("%20", " ")}'"))
  }

  /** The Date-header-gated character cache shared by getCharacter and
   *  getCharacterV2: when the response carries a Date no newer than the cached
   *  timestamp for `name`, skip unmarshalling (drain + report a cache hit);
   *  otherwise record the timestamp and unmarshal. The request URL differs
   *  between callers (plain vs the level>=250 bypass), so it is built by the
   *  caller and passed in as `responseFuture`. */
  private def fetchCharacterCached(name: String, responseFuture: Future[HttpResponse]): Future[Either[String, CharacterResponse]] = {
    val encodedName = URLEncoder.encode(name, "UTF-8").replaceAll("\\+", "%20")
    responseFuture.flatMap { response =>
      response.header[DateHeader] match {
        case Some(dateHeader) =>
          val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneId.of("GMT"))
          val responseDate = ZonedDateTime.parse(dateHeader.date.toString, formatter)
          characterCache.get(name) match {
            case Some(existingDate) if responseDate.isAfter(existingDate) =>
              modifyCharacterCache(_ + (name -> responseDate))
              unmarshalCharacter(response, encodedName)
            case Some(_) =>
              response.discardEntityBytes()
              Future.successful(Left("Hit cache"))
            case None =>
              modifyCharacterCache(_ + (name -> responseDate))
              unmarshalCharacter(response, encodedName)
          }
        case None =>
          response.discardEntityBytes()
          Future.successful(Left("No Date header in response"))
      }
    }
  }

  def getCharacter(name: String): Future[Either[String, CharacterResponse]] = {
    val encodedName = URLEncoder.encode(name, "UTF-8").replaceAll("\\+", "%20")
    fetchCharacterCached(name, timedRequest(HttpRequest(uri = s"$characterUrl$encodedName")))
  }

  def getKillerFallback(name: String): Future[Either[String, CharacterResponse]] = {
    val encodedName = URLEncoder.encode(name, "UTF-8").replaceAll("\\+", "%20")
    val responseFuture = timedRequest(HttpRequest(uri = s"$characterUrl$encodedName"))
    responseFuture.flatMap { response =>
      response.header[DateHeader] match {
        case Some(_) =>
          unmarshalCharacter(response, encodedName)
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
    fetchCharacterCached(name, timedRequest(HttpRequest(uri = s"$apiUrl$bypassName")))
  }

  def getCharacterWithInput(input: (String, String, String)): Future[(Either[String, CharacterResponse], String, String, String)] = {
    val name = input._1
    val reason = input._2
    val reasonText = input._3
    val encodedName = URLEncoder.encode(name, "UTF-8").replaceAll("\\+", "%20")
    fetch[CharacterResponse](
      s"$characterUrl${encodedName}",
      resp => s"Failed to get character: '${encodedName.replaceAll("%20", " ")}' with status: '${resp.status}'",
      s"Failed to parse character: '${encodedName.replaceAll("%20", " ")}'")
      .map(unmarshalled => (unmarshalled, name, reason, reasonText))
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
