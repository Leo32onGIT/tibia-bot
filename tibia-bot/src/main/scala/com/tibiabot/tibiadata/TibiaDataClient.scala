package com.tibiabot
package tibiadata

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.coding.Coders
import org.apache.pekko.http.scaladsl.model.headers.HttpEncodings
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.pattern.after
import com.tibiabot.tibiadata.response.{CharacterResponse, WorldResponse, WorldsResponse, GuildResponse, BoostedResponse, CreatureResponse, HighscoresResponse}
import com.typesafe.scalalogging.StrictLogging
import spray.json.JsonParser.ParsingException
import java.net.URLEncoder
import scala.util.control.NonFatal
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import spray.json.DeserializationException
import org.apache.pekko.http.scaladsl.model.headers.{Age => AgeHeader, Date => DateHeader, `Retry-After`, RetryAfterDuration, RetryAfterDateTime}

/** `metrics` defaults to the process-wide counter so the dashboard sees one
 *  figure for the process, not one per construction site. Tests pass their own. */
class TibiaDataClient(
  metrics: com.tibiabot.tracking.ApiCallMetrics = com.tibiabot.tracking.ApiMetrics.tibiaData,
  inFlight: InFlightLimit = InFlightLimit.tibiaData
)(implicit val system: ActorSystem) extends JsonSupport with StrictLogging with TibiaApi with HighscoresApi {

  implicit private val executionContext: ExecutionContextExecutor = system.dispatcher

  private val publicApi = "https://api.tibiadata.com"
  private val characterUrl = s"$publicApi/v4/character/"
  private val guildUrl = s"$publicApi/v4/guild/"

  private val retryPolicy = new RetryPolicy()
  private val maxRetries = 2

  /** The server's requested backoff, if any. `Retry-After` is legal as either
   *  delta-seconds or an HTTP-date; both are accepted, and a past date reads as
   *  "retry now". */
  private def retryAfterOf(response: HttpResponse): Option[FiniteDuration] =
    response.header[`Retry-After`].map { header =>
      header.delaySecondsOrDateTime match {
        case RetryAfterDuration(seconds) => seconds.seconds
        case RetryAfterDateTime(dateTime) => math.max(0L, dateTime.clicks - System.currentTimeMillis()).millis
      }
    }

  /** Which slice of the upstream cache's life this response came from, for the
   *  dashboard's cache-age breakdown. Kong stamps `Age` with how long the entry
   *  has sat there (300s TTL on `/v4/character`, 60s on the world endpoints), so
   *  it is the one header saying whether a request learned anything.
   *
   *  `fresh` is a 2xx with no `Age` — a cold entry this request refilled from the
   *  origin, which is also everything a self-hosted instance serves. `error`
   *  keeps non-2xx out of the picture, since a 503 carries no `Age` either.
   *  Buckets deliberately run past the 300s TTL so longer-cached entries get
   *  their own row instead of hiding in a catch-all. */
  private def cacheAgeOf(response: HttpResponse, status: Int): String =
    if (status / 100 != 2) "error"
    else response.header[AgeHeader] match {
      case Some(age) => TibiaDataClient.cacheAgeBucket(age.deltaSeconds)
      case None      => "fresh"
    }

  /** The first two path segments, so `/v4/character/Bubble` collapses onto
   *  `/v4/character` instead of becoming one counter per character. */
  private def endpointOf(request: HttpRequest): String = {
    val segments = request.uri.path.toString.split('/').filter(_.nonEmpty).take(2)
    if (segments.isEmpty) "/" else segments.mkString("/", "/", "")
  }

  /** Issue a GET, retrying only when [[RetryPolicy]] says it is worth it: a
   *  transient upstream failure (500/502/503/504) or a connection-level one.
   *  Anything else is returned as-is and degrades to the logged-Left path.
   *
   *  `callerRetriesSoon` disables the inline retry for callers already on a poll
   *  cycle — see [[RetryPolicy]] for why. */
  private def requestWithRetry(request: HttpRequest, attempt: Int = 0, callerRetriesSoon: Boolean = false): Future[HttpResponse] =
    inFlight(Http().singleRequest(request)).flatMap { response =>
      val status = response.status.intValue
      // Per attempt, not per logical fetch — a retry really is a second call,
      // and hiding it would understate our load during an upstream wobble.
      metrics.record("endpoint" -> endpointOf(request), "status" -> status.toString, "cacheAge" -> cacheAgeOf(response, status))
      val retryAfter = retryAfterOf(response)
      retryPolicy.onResponse(status, retryAfter, attempt, callerRetriesSoon) match {
        case RetryDecision.RetryIn(delay) =>
          logger.warn(s"Got ${response.status} from '${request.uri}' (attempt ${attempt + 1}/${maxRetries + 1}), retrying in ${delay.toMillis}ms")
          response.discardEntityBytes()
          after(delay, system.scheduler)(requestWithRetry(request, attempt + 1, callerRetriesSoon))
        case RetryDecision.GiveUp =>
          // Worth its own line: 429 is the one response that says something
          // about our behaviour rather than theirs.
          if (retryPolicy.isRateLimited(status))
            logger.warn(s"Rate limited (429) by '${request.uri}'${retryAfter.fold("")(d => s", asked to wait ${d.toSeconds}s")} — not retrying; the next poll cycle is the retry")
          else
            retryAfter.foreach(d => logger.warn(s"Got ${response.status} from '${request.uri}' asking for a ${d.toSeconds}s backoff — longer than a request is held open for, so not retrying"))
          Future.successful(response)
      }
    }.recoverWith {
      case NonFatal(ex) =>
        // A call that never got a status still left this process, so it counts;
        // "failed" keeps timeouts and resets visible instead of shrinking the total.
        metrics.record("endpoint" -> endpointOf(request), "status" -> "failed")
        retryPolicy.onConnectionFailure(attempt, callerRetriesSoon) match {
          case RetryDecision.RetryIn(delay) =>
            logger.warn(s"Request to '${request.uri}' failed (attempt ${attempt + 1}/${maxRetries + 1}), retrying in ${delay.toMillis}ms: ${ex.getMessage}")
            after(delay, system.scheduler)(requestWithRetry(request, attempt + 1, callerRetriesSoon))
          case RetryDecision.GiveUp => Future.failed(ex)
        }
    }

  /** Shared recovery for an Unmarshal failure. A non-JSON response is rejected on
   *  the content-type check before the body is read, so the entity is drained to
   *  free the pool connection; parse failures already read it. Both log and yield
   *  Left; unmatched throwables propagate. */
  private def recoverUnmarshal[T](decoded: HttpResponse, contentTypeMessage: => String, parseMessage: => String): PartialFunction[Throwable, Either[String, T]] = {
    case e: org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException =>
      decoded.discardEntityBytes()
      val errorMessage = contentTypeMessage
      logger.warn(s"$errorMessage: ${e.getMessage}")
      Left(errorMessage)
    case e @ (_: ParsingException | _: DeserializationException) =>
      val errorMessage = parseMessage
      logger.warn(s"$errorMessage: ${e.getMessage}")
      Left(errorMessage)
  }

  /** GET, decode (possibly gzipped) and unmarshal to T, recovering failures into a
   *  logged Left. `contentTypeMessage` receives the response so it can name the status. */
  private def fetch[T](uri: String, contentTypeMessage: HttpResponse => String, parseMessage: => String)
                      (implicit um: org.apache.pekko.http.scaladsl.unmarshalling.FromEntityUnmarshaller[T]): Future[Either[String, T]] =
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

  /** Decode + unmarshal a character response, recovering failures to a logged Left. */
  private def unmarshalCharacter(response: HttpResponse, encodedName: String): Future[Either[String, CharacterResponse]] = {
    val decoded = decodeResponse(response)
    Unmarshal(decoded).to[CharacterResponse].map(Right(_)).recover(recoverUnmarshal(
      decoded,
      s"Failed to get character: '${encodedName.replaceAll("%20", " ")}' with status: '${response.status}'",
      s"Failed to parse character: '${encodedName.replaceAll("%20", " ")}'"))
  }

  /** The poll's character fetch — ~99% of this process's API traffic, and the one
   *  caller with its own retry: the next poll is a minute away, so no inline retry. */
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

  /** One page of one highscore list.
   *
   *  The host is the list's own choice, not this method's: only a
   *  vocation-filtered list needs our instance, because the public API refuses
   *  any vocation but `all` with a 400. Everything else goes to the public
   *  endpoint, where it is Kong-cached and costs tibia.com nothing extra.
   *
   *  A 400 here parses as a Left rather than a `HighscoresResponse` — the error
   *  body carries no `highscores` object at all — so the message names the list
   *  and page, since the likeliest cause is our instance coming back up with
   *  restriction mode on and refusing the vocation filter. */
  def getHighscores(world: String, list: HighscoreList, page: Int): Future[Either[String, HighscoresResponse]] = {
    val host = list.source match {
      case HighscoreSource.Public => publicApi
      case HighscoreSource.Local  => Config.tibiadataApi.stripSuffix("/")
    }
    val what = s"'$list' page $page for '$world'"
    fetch[HighscoresResponse](
      s"$host${list.path(world, page)}",
      resp => s"Failed to get highscores $what with status: '${resp.status}'",
      s"Failed to parse highscores $what")
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
  /** Bucket width, and the age past which buckets stop splitting. 60s against a
   *  300s TTL gives five rows plus an overflow — the shape, without a wall of rows. */
  private[tibiadata] val CacheAgeBucketSeconds = 60L
  private[tibiadata] val CacheAgeMaxBucket = 360L

  /** Label for the bucket `seconds` falls in — 0 -> "0-59s", 400 -> "360s+". */
  private[tibiadata] def cacheAgeBucket(seconds: Long): String =
    if (seconds >= CacheAgeMaxBucket) s"${CacheAgeMaxBucket}s+"
    else {
      val floor = (math.max(0L, seconds) / CacheAgeBucketSeconds) * CacheAgeBucketSeconds
      s"$floor-${floor + CacheAgeBucketSeconds - 1}s"
    }
}
