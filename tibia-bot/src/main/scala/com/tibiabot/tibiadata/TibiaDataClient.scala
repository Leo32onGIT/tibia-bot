package com.tibiabot
package tibiadata

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Coders
import akka.http.scaladsl.model.headers.HttpEncodings
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.after
import com.tibiabot.tibiadata.response.{CharacterResponse, WorldResponse, WorldsResponse, GuildResponse, BoostedResponse, CreatureResponse}
import com.typesafe.scalalogging.StrictLogging
import spray.json.JsonParser.ParsingException
import java.net.URLEncoder
import scala.util.Random
import scala.util.control.NonFatal
import com.tibiabot.state.StreamState
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import spray.json.DeserializationException
import akka.http.scaladsl.model.headers.{Date => DateHeader, `Retry-After`, RetryAfterDuration, RetryAfterDateTime}
import java.time.{ZonedDateTime, ZoneId}
import java.time.format.DateTimeFormatter

class TibiaDataClient(streamState: StreamState)(implicit val system: ActorSystem) extends JsonSupport with StrictLogging with TibiaApi {

  implicit private val executionContext: ExecutionContextExecutor = system.dispatcher

  private val characterUrl = "https://api.tibiadata.com/v4/character/"
  private val guildUrl = "https://api.tibiadata.com/v4/guild/"

  // Built once: fetchCharacterCached runs on every character response (tens of
  // thousands a minute across all worlds), and DateTimeFormatter is immutable
  // and thread-safe, so there is no reason to rebuild it per response.
  private val dateHeaderFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneId.of("GMT"))

  private val retryPolicy = new RetryPolicy()
  private val maxRetries = 2

  /** The server's requested backoff, if it asked for one. `Retry-After` is
   *  legal as either delta-seconds or an HTTP-date; Cloudflare and Kong both
   *  send the former, but the date form is accepted rather than ignored.
   *  A date already in the past reads as "retry now". */
  private def retryAfterOf(response: HttpResponse): Option[FiniteDuration] =
    response.header[`Retry-After`].map { header =>
      header.delaySecondsOrDateTime match {
        case RetryAfterDuration(seconds) => seconds.seconds
        case RetryAfterDateTime(dateTime) => math.max(0L, dateTime.clicks - System.currentTimeMillis()).millis
      }
    }

  /** Issue a GET, retrying only when [[RetryPolicy]] says it is worth it: a
   *  transient upstream failure (500/502/503/504) or a connection-level failure
   *  (timeout, reset). Anything else — a well-formed 200, a definitive 404, or
   *  a 429 telling us to send less — is returned as-is, since retrying an
   *  answer just gets the same answer, and retrying a rate limit makes it
   *  worse. A response the policy declines to retry degrades to the existing
   *  logged-Left behaviour untouched. */
  private def requestWithRetry(request: HttpRequest, attempt: Int = 0): Future[HttpResponse] =
    Http().singleRequest(request).flatMap { response =>
      val status = response.status.intValue
      val retryAfter = retryAfterOf(response)
      retryPolicy.onResponse(status, retryAfter, attempt) match {
        case RetryDecision.RetryIn(delay) =>
          logger.warn(s"Got ${response.status} from '${request.uri}' (attempt ${attempt + 1}/${maxRetries + 1}), retrying in ${delay.toMillis}ms")
          response.discardEntityBytes()
          after(delay, system.scheduler)(requestWithRetry(request, attempt + 1))
        case RetryDecision.GiveUp =>
          // Worth its own line: being rate-limited is the one upstream response
          // that says something about our own behaviour rather than theirs.
          if (retryPolicy.isRateLimited(status))
            logger.warn(s"Rate limited (429) by '${request.uri}'${retryAfter.fold("")(d => s", asked to wait ${d.toSeconds}s")} — not retrying; the next poll cycle is the retry")
          else
            retryAfter.foreach(d => logger.warn(s"Got ${response.status} from '${request.uri}' asking for a ${d.toSeconds}s backoff — longer than a request is held open for, so not retrying"))
          Future.successful(response)
      }
    }.recoverWith {
      case NonFatal(ex) =>
        retryPolicy.onConnectionFailure(attempt) match {
          case RetryDecision.RetryIn(delay) =>
            logger.warn(s"Request to '${request.uri}' failed (attempt ${attempt + 1}/${maxRetries + 1}), retrying in ${delay.toMillis}ms: ${ex.getMessage}")
            after(delay, system.scheduler)(requestWithRetry(request, attempt + 1))
          case RetryDecision.GiveUp => Future.failed(ex)
        }
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
      response <- requestWithRetry(HttpRequest(uri = uri))
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
   *  between callers (plain vs the level>=1000 bypass), so it is built by the
   *  caller and passed in as `responseFuture`. */
  private def fetchCharacterCached(name: String, encodedName: String, responseFuture: Future[HttpResponse]): Future[Either[String, CharacterResponse]] =
    responseFuture.flatMap { response =>
      response.header[DateHeader] match {
        case Some(dateHeader) =>
          val responseDate = ZonedDateTime.parse(dateHeader.date.toString, dateHeaderFormatter)
          streamState.characterSeenAt(name) match {
            case Some(existingDate) if !responseDate.isAfter(existingDate) =>
              response.discardEntityBytes()
              Future.successful(Left("Hit cache"))
            case _ =>
              streamState.recordCharacterSeen(name, responseDate)
              unmarshalCharacter(response, encodedName)
          }
        case None =>
          response.discardEntityBytes()
          Future.successful(Left("No Date header in response"))
      }
    }

  def getCharacter(name: String): Future[Either[String, CharacterResponse]] = {
    val encodedName = URLEncoder.encode(name, "UTF-8").replaceAll("\\+", "%20")
    fetchCharacterCached(name, encodedName, requestWithRetry(HttpRequest(uri = s"$characterUrl$encodedName")))
  }

  def getKillerFallback(name: String): Future[Either[String, CharacterResponse]] = {
    val encodedName = URLEncoder.encode(name, "UTF-8").replaceAll("\\+", "%20")
    val responseFuture = requestWithRetry(HttpRequest(uri = s"$characterUrl$encodedName"))
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
    val apiUrl = if (level >= 1000) {
      s"${Config.tibiadataApi}/v4/character/"
    } else {
      characterUrl
    }
    val encodedName = URLEncoder.encode(name, "UTF-8").replaceAll("\\+", "%20")
    val bypassName: String = if (level >= 1000) {
          val randomizedName = encodedName.map { c =>
            if (c.isLetter)
              if (Random.nextBoolean()) c.toUpper else c.toLower
            else c
          }
          randomizedName
        } else encodedName
    fetchCharacterCached(name, encodedName, requestWithRetry(HttpRequest(uri = s"$apiUrl$bypassName")))
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
