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
import com.tibiabot.tibiadata.{InFlightLimit, RequestPacer, RetryDecision, RetryPolicy, TibiaApi}
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
 *  Only the three character methods are served here — this API has two endpoints,
 *  `status` and `GetCharacter` — so worlds, guilds and boosted pass through to
 *  `delegate`. This splits the character firehose away from TibiaData rather than
 *  replacing it; the online-list poll goes where it always went.
 *
 *  '''Two request headers are load-bearing.''' Cloudflare answers 403 — not 401,
 *  not 429 — to a request missing either `Accept-Encoding` or a plausible
 *  `User-Agent`, confirmed to fail independently. `br` is deliberately absent:
 *  akka-http's `Coders` implements only gzip and deflate, so advertising brotli
 *  would earn a body this client cannot decode.
 *
 *  '''The request shape must stay byte-stable per character.''' The upstream
 *  caches each distinct URL as its own entry with its own 300s window, so varying
 *  the `include` set would fragment one character across several
 *  independently-phased copies and defeat the age cache in front of this.
 *  `include=characterDeathsData` is fixed here: the narrowest request carrying
 *  everything the bot reads, and less than half the payload of asking for all. */
final class FansiteApiClient(
    delegate: TibiaApi,
    token: String,
    baseUrl: String = Config.FansiteApi.baseUrl,
    userAgent: String = Config.FansiteApi.userAgent,
    metrics: tracking.ApiCallMetrics = tracking.ApiMetrics.fansiteApi,
    inFlight: InFlightLimit = InFlightLimit.fansiteApi,
    pacer: RequestPacer = RequestPacer.fansiteApi,
    maxQueueDelay: FiniteDuration = Config.FansiteApi.maxQueueDelay,
    breaker: FansiteCircuitBreaker = FansiteCircuitBreaker.shared,
    refusals: tracking.ApiCallMetrics = tracking.ApiMetrics.fansiteRefused
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
  /** A refusal that costs no request. Shaped like any other failure so callers
   *  need no special case — [[DualCharacterApi]] already falls back to
   *  TibiaData on a Left. */
  private val circuitOpenLeft: Future[Either[String, CharacterResponse]] =
    Future.successful(Left("Fansite API circuit is open — not sending"))

  /** A fetch refused before it was sent, because the pacer's queue already
   *  reaches past the point where an answer would still be worth having. */
  private val pacerSaturatedLeft: Future[Either[String, CharacterResponse]] =
    Future.successful(Left("Fansite API pacer is saturated — not sending"))

  /** Counted, because a refusal is invisible otherwise: it becomes a Left that
   *  [[DualCharacterApi]] quietly covers with TibiaData, so the bot looks
   *  perfectly healthy while the second source contributes nothing. Kept out of
   *  the request counter on purpose -- see [[com.tibiabot.tracking.ApiMetrics]]. */
  private def blockedResponse: Future[Either[String, CharacterResponse]] = {
    refusals.record("reason" -> "circuit-open")
    circuitOpenLeft
  }

  private def pacedOutResponse: Future[Either[String, CharacterResponse]] = {
    refusals.record("reason" -> "paced-out")
    pacerSaturatedLeft
  }

  /** Wait for this lane's turn, then fetch.
   *
   *  Admission sits out here rather than inside [[requestWithRetry]] because
   *  this is where a refusal can still be said in the caller's own language: a
   *  Left, which every caller already answers by falling back to TibiaData. A
   *  rejection deeper down would have to be a failed Future, and would then be
   *  counted as a request that failed rather than one never sent.
   *
   *  Retries are not paced, and do not need to be. The poll's fetch declines
   *  them outright, and the one caller that keeps them is a rare one-shot whose
   *  own jittered backoff already starts well above this floor. */
  private def paced(name: String, callerRetriesSoon: Boolean): Future[Either[String, CharacterResponse]] =
    pacer.tryReserve(maxQueueDelay) match {
      case None => pacedOutResponse
      case Some(wait) =>
        after(wait, system.scheduler)(requestWithRetry(name, callerRetriesSoon = callerRetriesSoon))
          .flatMap(unmarshalCharacter(_, name))
    }

  private def requestWithRetry(name: String, attempt: Int = 0, callerRetriesSoon: Boolean = false): Future[HttpResponse] =
    inFlight(Http().singleRequest(request(name))).flatMap { response =>
      val status = response.status.intValue
      metrics.record("endpoint" -> "/CharacterData/GetCharacter", "status" -> status.toString)
      // Before the retry policy sees it: a 403 here is the edge refusing the
      // whole IP, which no amount of retrying improves and every further
      // request makes worse.
      if (breaker.blocks(status)) breaker.recordBlocked(status)
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
    if (breaker.isOpen) blockedResponse
    else paced(name, callerRetriesSoon = true)

  /** A one-shot lookup with nobody polling behind it, so it keeps the inline
   *  retry — there is no "next cycle" to defer to. */
  def getKillerFallback(name: String): Future[Either[String, CharacterResponse]] =
    if (breaker.isOpen) blockedResponse
    else paced(name, callerRetriesSoon = false)

  def getCharacterWithInput(input: (String, String, String)): Future[(Either[String, CharacterResponse], String, String, String)] = {
    val (name, reason, reasonText) = input
    val result = if (breaker.isOpen) blockedResponse else paced(name, callerRetriesSoon = false)
    result.map((_, name, reason, reasonText))
  }

  // --- no equivalent on this API (see class doc) ---

  def getWorld(world: String): Future[Either[String, WorldResponse]] = delegate.getWorld(world)
  def getWorlds(): Future[Either[String, WorldsResponse]] = delegate.getWorlds()
  def getBoostedBoss(): Future[Either[String, BoostedResponse]] = delegate.getBoostedBoss()
  def getBoostedCreature(): Future[Either[String, CreatureResponse]] = delegate.getBoostedCreature()
  def getGuild(guild: String): Future[Either[String, GuildResponse]] = delegate.getGuild(guild)
  def getGuildWithInput(input: (String, String)): Future[(Either[String, GuildResponse], String, String)] = delegate.getGuildWithInput(input)
}
