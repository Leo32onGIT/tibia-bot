package com.tibiabot
package fansiteapi

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Coders
import akka.http.scaladsl.model.headers.{
  Authorization, HttpEncodingRange, HttpEncodings, OAuth2BearerToken, RetryAfterDateTime, RetryAfterDuration,
  `Accept-Encoding`, `Last-Modified`, `Retry-After`, `User-Agent`
}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.after
import com.tibiabot.fansiteapi.response.FansiteCharacterResponse
import com.tibiabot.tibiadata.response._
import com.tibiabot.tibiadata.{InFlightLimit, RetryDecision, RetryPolicy, TibiaApi}
import com.typesafe.scalalogging.StrictLogging
import spray.json.DeserializationException
import spray.json.JsonParser.ParsingException

import java.net.URLEncoder
import java.time.Instant
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.control.NonFatal

/** Character sheets from CipSoft's official fansite API, presented as a
 *  [[com.tibiabot.tibiadata.TibiaApi]] so the rest of the bot cannot tell which
 *  upstream answered.
 *
 *  Only the three character methods are served here — this API has exactly two
 *  endpoints, `status` and `GetCharacter`, so worlds, guilds and the boosted
 *  pair have no equivalent and pass through to `delegate` unchanged. That makes
 *  this a split of the character firehose away from TibiaData, not a
 *  replacement of it: the online-list poll still goes where it always went.
 *
 *  '''Two request headers are load-bearing, not cosmetic.''' The API sits
 *  behind Cloudflare, which answers 403 — not 401, not 429 — to a request
 *  missing either an `Accept-Encoding` header or a plausible `User-Agent`.
 *  Both were confirmed to fail independently. `br` is deliberately absent from
 *  the offered encodings: akka-http's `Coders` implements only gzip and
 *  deflate, so advertising brotli would earn a body this client cannot decode.
 *
 *  '''The request shape must stay byte-stable per character.''' The upstream
 *  caches each distinct URL as its own entry with its own 300s window, so
 *  varying the `include` set between polls would fragment one character across
 *  several independently-phased copies and defeat the age cache in front of
 *  this. `include=characterDeathsData` is therefore fixed here rather than
 *  passed in: it is the narrowest request carrying everything the bot reads,
 *  and it more than halves the payload against asking for every section. */
final class FansiteApiClient(
    delegate: TibiaApi,
    token: String,
    baseUrl: String = Config.FansiteApi.baseUrl,
    userAgent: String = Config.FansiteApi.userAgent,
    metrics: tracking.ApiCallMetrics = tracking.ApiMetrics.fansiteApi,
    inFlight: InFlightLimit = InFlightLimit.fansiteApi
)(implicit val system: ActorSystem)
    extends FansiteJsonSupport with StrictLogging with TibiaApi {

  implicit private val executionContext: ExecutionContextExecutor = system.dispatcher

  private val retryPolicy = new RetryPolicy()
  private val maxRetries = 2

  /** Sent on every request. The encoding and agent headers are the Cloudflare
   *  gates described in the class doc; the bearer token is the API's own auth. */
  private val requestHeaders = List(
    Authorization(OAuth2BearerToken(token)),
    `Accept-Encoding`(HttpEncodingRange(HttpEncodings.gzip), HttpEncodingRange(HttpEncodings.deflate)),
    `User-Agent`(userAgent)
  )

  private def characterUri(name: String): String = {
    val encodedName = URLEncoder.encode(name, "UTF-8").replaceAll("\\+", "%20")
    s"$baseUrl/api/v1/CharacterData/GetCharacter/$encodedName?include=characterDeathsData"
  }

  private def request(name: String): HttpRequest =
    HttpRequest(uri = characterUri(name)).withHeaders(requestHeaders)

  private def retryAfterOf(response: HttpResponse): Option[FiniteDuration] =
    response.header[`Retry-After`].map { header =>
      header.delaySecondsOrDateTime match {
        case RetryAfterDuration(seconds)  => seconds.seconds
        case RetryAfterDateTime(dateTime) => math.max(0L, dateTime.clicks - System.currentTimeMillis()).millis
      }
    }

  /** When the upstream built the copy this response was served from.
   *
   *  `Last-Modified` is pinned to that moment for the whole life of a cached
   *  copy while `Date` advances — measured holding still across 299s and
   *  rolling on the first request after expiry. That makes it this API's
   *  equivalent of TibiaData's `information.timestamp`, and mapping it into
   *  that field is what lets the existing cache decorators schedule this
   *  source without modification. */
  private def originOf(response: HttpResponse): Option[Instant] =
    response.header[`Last-Modified`].map(header => Instant.ofEpochMilli(header.date.clicks))

  /** Issue a GET, retrying only when [[RetryPolicy]] says it is worth it.
   *  Mirrors TibiaDataClient's choke point so both upstreams are counted and
   *  bounded the same way, but against this API's own metrics and in-flight
   *  limit — the two must not share a budget, or a stall on one would throttle
   *  the other. */
  private def requestWithRetry(name: String, attempt: Int = 0, callerRetriesSoon: Boolean = false): Future[HttpResponse] =
    inFlight(Http().singleRequest(request(name))).flatMap { response =>
      val status = response.status.intValue
      metrics.record("endpoint" -> "/CharacterData/GetCharacter", "status" -> status.toString)
      val retryAfter = retryAfterOf(response)
      retryPolicy.onResponse(status, retryAfter, attempt, callerRetriesSoon) match {
        case RetryDecision.RetryIn(delay) =>
          logger.warn(s"Got ${response.status} from the fansite API for '$name' (attempt ${attempt + 1}/${maxRetries + 1}), retrying in ${delay.toMillis}ms")
          response.discardEntityBytes()
          after(delay, system.scheduler)(requestWithRetry(name, attempt + 1, callerRetriesSoon))
        case RetryDecision.GiveUp =>
          if (retryPolicy.isRateLimited(status))
            logger.warn(s"Rate limited (429) by the fansite API${retryAfter.fold("")(d => s", asked to wait ${d.toSeconds}s")} — not retrying; the next poll cycle is the retry")
          Future.successful(response)
      }
    }.recoverWith {
      case NonFatal(ex) =>
        metrics.record("endpoint" -> "/CharacterData/GetCharacter", "status" -> "failed")
        retryPolicy.onConnectionFailure(attempt, callerRetriesSoon) match {
          case RetryDecision.RetryIn(delay) =>
            logger.warn(s"Fansite API request for '$name' failed (attempt ${attempt + 1}/${maxRetries + 1}), retrying in ${delay.toMillis}ms: ${ex.getMessage}")
            after(delay, system.scheduler)(requestWithRetry(name, attempt + 1, callerRetriesSoon))
          case RetryDecision.GiveUp => Future.failed(ex)
        }
    }

  private def decodeResponse(response: HttpResponse): HttpResponse = {
    val decoder = response.encoding match {
      case HttpEncodings.gzip     => Coders.Gzip
      case HttpEncodings.deflate  => Coders.Deflate
      case HttpEncodings.identity => Coders.NoCoding
      case other =>
        logger.warn(s"Unknown encoding [$other] from the fansite API, not decoding")
        Coders.NoCoding
    }
    decoder.decodeMessage(response)
  }

  /** Decode one response into the bot's own character model.
   *
   *  A 404 is an answer, not a failure: this API says plainly that a character
   *  does not exist, where TibiaData returns a 502 HTML page for the same
   *  question. It becomes a Left without the body being touched, and
   *  [[RetryPolicy]] already declines to retry it.
   *
   *  Any other non-2xx is drained and reported, so a pool connection is never
   *  left holding a body nobody will read. */
  private def unmarshalCharacter(response: HttpResponse, name: String): Future[Either[String, CharacterResponse]] =
    if (response.status == StatusCodes.NotFound) {
      response.discardEntityBytes()
      Future.successful(Left(s"Character '$name' does not exist"))
    } else if (response.status.isFailure()) {
      response.discardEntityBytes()
      Future.successful(Left(s"Failed to get character: '$name' with status: '${response.status}'"))
    } else {
      val origin = originOf(response)
      val decoded = decodeResponse(response)
      Unmarshal(decoded).to[FansiteCharacterResponse]
        .map(payload => Right(CharacterMapping.toCharacterResponse(payload, origin)))
        .recover {
          case e: akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
            decoded.discardEntityBytes()
            val message = s"Failed to get character: '$name' with status: '${response.status}'"
            logger.warn(s"$message: ${e.getMessage}")
            Left(message)
          case e @ (_: ParsingException | _: DeserializationException) =>
            val message = s"Failed to parse character: '$name'"
            logger.warn(s"$message: ${e.getMessage}")
            Left(message)
        }
    }

  /** The poll's character fetch. `callerRetriesSoon` for the same reason
   *  TibiaDataClient sets it: a miss here is picked up by the next poll a
   *  minute away, so an inline retry buys almost nothing and costs a request
   *  during whatever is already going wrong. */
  def getCharacter(name: String): Future[Either[String, CharacterResponse]] =
    requestWithRetry(name, callerRetriesSoon = true).flatMap(unmarshalCharacter(_, name))

  /** A one-shot lookup with nobody polling behind it, so it keeps the inline
   *  retry — there is no "next cycle" to defer to. */
  def getKillerFallback(name: String): Future[Either[String, CharacterResponse]] =
    requestWithRetry(name).flatMap(unmarshalCharacter(_, name))

  def getCharacterWithInput(input: (String, String, String)): Future[(Either[String, CharacterResponse], String, String, String)] = {
    val (name, reason, reasonText) = input
    requestWithRetry(name).flatMap(unmarshalCharacter(_, name)).map((_, name, reason, reasonText))
  }

  // --- no equivalent on this API (see class doc) ---

  def getWorld(world: String): Future[Either[String, WorldResponse]] = delegate.getWorld(world)
  def getWorlds(): Future[Either[String, WorldsResponse]] = delegate.getWorlds()
  def getBoostedBoss(): Future[Either[String, BoostedResponse]] = delegate.getBoostedBoss()
  def getBoostedCreature(): Future[Either[String, CreatureResponse]] = delegate.getBoostedCreature()
  def getGuild(guild: String): Future[Either[String, GuildResponse]] = delegate.getGuild(guild)
  def getGuildWithInput(input: (String, String)): Future[(Either[String, GuildResponse], String, String)] = delegate.getGuildWithInput(input)
}
