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
import scala.util.control.NonFatal
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import spray.json.DeserializationException
import akka.http.scaladsl.model.headers.{Age => AgeHeader, Date => DateHeader, `Retry-After`, RetryAfterDuration, RetryAfterDateTime}

/** `metrics` defaults to the process-wide TibiaData counter rather than being
 *  wired in at each call site: this class is constructed in three places
 *  (BotApp, TibiaBot, WorldManager) that all issue real traffic, and the
 *  dashboard wants one figure for the process, not three. Tests pass their own
 *  instance to keep assertions isolated. */
class TibiaDataClient(
  metrics: com.tibiabot.tracking.ApiCallMetrics = com.tibiabot.tracking.ApiMetrics.tibiaData,
  inFlight: InFlightLimit = InFlightLimit.tibiaData
)(implicit val system: ActorSystem) extends JsonSupport with StrictLogging with TibiaApi {

  implicit private val executionContext: ExecutionContextExecutor = system.dispatcher

  private val characterUrl = "https://api.tibiadata.com/v4/character/"
  private val guildUrl = "https://api.tibiadata.com/v4/guild/"

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

  /** Which slice of the upstream cache's life this response was served from,
   *  for the dashboard's cache-age breakdown.
   *
   *  `api.tibiadata.com` sits behind a Kong cache that stamps `Age` with how
   *  long the entry it just served has been sitting there — measured at a 300s
   *  TTL on `/v4/character`, and 60s on both `/v4/world` and `/v4/worlds`. That
   *  makes `Age` the one header that says whether a request actually learned
   *  anything: a response with `Age` 200 is byte-identical to the one the
   *  previous caller got, and the entry cannot change for another 100s.
   *  Bucketing it here is what tells us how much of the character firehose is
   *  re-fetching bytes we already had.
   *
   *  `fresh` is a 2xx with no `Age` at all — the entry was cold and this
   *  request is what refilled it from the origin. A self-hosted TibiaData
   *  instance has no cache in front of it at all, so everything it serves reads
   *  as `fresh` — correctly, since every one of those really did come from the
   *  origin. `error` keeps non-2xx out of the age picture: a 503 also carries no
   *  `Age`, and folding those into `fresh` would read as a healthy origin fetch
   *  when it is the opposite.
   *
   *  Buckets run past the observed 300s TTL deliberately — if some characters
   *  are cached longer than that, they show up as their own row rather than
   *  hiding inside a catch-all at exactly the boundary we assumed.
   *
   *  Recorded for every endpoint, not just characters, so the dimension still
   *  sums to the overall total (which is what the dashboard's share column is
   *  taken against). `/v4/character` is ~99% of the traffic, so in practice
   *  this reads as the character histogram anyway. */
  private def cacheAgeOf(response: HttpResponse, status: Int): String =
    if (status / 100 != 2) "error"
    else response.header[AgeHeader] match {
      case Some(age) => TibiaDataClient.cacheAgeBucket(age.deltaSeconds)
      case None      => "fresh"
    }

  /** The endpoint a request belongs to, for the dashboard's per-endpoint
   *  breakdown: the first two path segments, so `/v4/character/Bubble` and
   *  `/v4/world/Antica` collapse onto `/v4/character` and `/v4/world` rather
   *  than becoming one counter per character and world ever looked up. */
  private def endpointOf(request: HttpRequest): String = {
    val segments = request.uri.path.toString.split('/').filter(_.nonEmpty).take(2)
    if (segments.isEmpty) "/" else segments.mkString("/", "/", "")
  }

  /** Issue a GET, retrying only when [[RetryPolicy]] says it is worth it: a
   *  transient upstream failure (500/502/503/504) or a connection-level failure
   *  (timeout, reset). Anything else — a well-formed 200, a definitive 404, or
   *  a 429 telling us to send less — is returned as-is, since retrying an
   *  answer just gets the same answer, and retrying a rate limit makes it
   *  worse. A response the policy declines to retry degrades to the existing
   *  logged-Left behaviour untouched.
   *
   *  `callerRetriesSoon` turns the inline retry off entirely for callers that
   *  are already on a poll cycle — see [[RetryPolicy]] for why that is the
   *  better trade on the character firehose. */
  private def requestWithRetry(request: HttpRequest, attempt: Int = 0, callerRetriesSoon: Boolean = false): Future[HttpResponse] =
    inFlight(Http().singleRequest(request)).flatMap { response =>
      val status = response.status.intValue
      // Counted per attempt, not per logical fetch: a retried request really is
      // a second call on TibiaData, and hiding that would make the panel
      // understate our load exactly when an upstream wobble is causing it.
      metrics.record("endpoint" -> endpointOf(request), "status" -> status.toString, "cacheAge" -> cacheAgeOf(response, status))
      val retryAfter = retryAfterOf(response)
      retryPolicy.onResponse(status, retryAfter, attempt, callerRetriesSoon) match {
        case RetryDecision.RetryIn(delay) =>
          logger.warn(s"Got ${response.status} from '${request.uri}' (attempt ${attempt + 1}/${maxRetries + 1}), retrying in ${delay.toMillis}ms")
          response.discardEntityBytes()
          after(delay, system.scheduler)(requestWithRetry(request, attempt + 1, callerRetriesSoon))
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
        // A call that never got a status still left this process, so it counts;
        // "failed" keeps timeouts and resets visible on the panel instead of
        // silently shrinking the total.
        metrics.record("endpoint" -> endpointOf(request), "status" -> "failed")
        retryPolicy.onConnectionFailure(attempt, callerRetriesSoon) match {
          case RetryDecision.RetryIn(delay) =>
            logger.warn(s"Request to '${request.uri}' failed (attempt ${attempt + 1}/${maxRetries + 1}), retrying in ${delay.toMillis}ms: ${ex.getMessage}")
            after(delay, system.scheduler)(requestWithRetry(request, attempt + 1, callerRetriesSoon))
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

  /** The poll's character fetch — ~99% of this process's traffic against the
   *  API, and the one caller with its own retry: a failure here is picked up by
   *  the next poll a minute later, so it does not buy one inline. */
  def getCharacter(name: String): Future[Either[String, CharacterResponse]] = {
    val encodedName = URLEncoder.encode(name, "UTF-8").replaceAll("\\+", "%20")
    requestWithRetry(HttpRequest(uri = s"$characterUrl$encodedName"), callerRetriesSoon = true)
      .flatMap(unmarshalCharacter(_, encodedName))
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

object TibiaDataClient {
  /** Width of one cache-age bucket, and the age past which they stop being
   *  split. 60s buckets against a 300s TTL give five rows across an entry's
   *  life plus an overflow, which is enough to see the shape without turning
   *  the dashboard panel into a wall of rows. */
  private[tibiadata] val CacheAgeBucketSeconds = 60L
  private[tibiadata] val CacheAgeMaxBucket = 360L

  /** Label for the bucket `seconds` of upstream cache age falls in — e.g. 0
   *  -> "0-59s", 240 -> "240-299s", 400 -> "360s+". Pure, so the bucketing can
   *  be pinned by tests without going near HTTP. */
  private[tibiadata] def cacheAgeBucket(seconds: Long): String =
    if (seconds >= CacheAgeMaxBucket) s"${CacheAgeMaxBucket}s+"
    else {
      val floor = (math.max(0L, seconds) / CacheAgeBucketSeconds) * CacheAgeBucketSeconds
      s"$floor-${floor + CacheAgeBucketSeconds - 1}s"
    }
}
