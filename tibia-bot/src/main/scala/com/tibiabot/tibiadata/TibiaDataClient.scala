package com.tibiabot
package tibiadata

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.coding.Coders
import org.apache.pekko.http.scaladsl.model.headers.{HttpEncodingRange, HttpEncodings}
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.pattern.after
import com.tibiabot.tibiadata.response.{CharacterResponse, WorldResponse, WorldsResponse, GuildResponse, BoostedResponse, CreatureResponse, HighscoresResponse, Information}
import com.typesafe.scalalogging.StrictLogging
import spray.json.JsonParser.ParsingException
import java.net.URLEncoder
import java.time.Instant
import scala.util.control.NonFatal
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import spray.json.DeserializationException
import org.apache.pekko.http.scaladsl.model.headers.{`Accept-Encoding`, Date => DateHeader, `Retry-After`, RetryAfterDuration, RetryAfterDateTime}

/** The counters default to the process-wide ones so the dashboard sees one
 *  figure for the process, not one per construction site. Tests pass their own.
 *
 *  Two of them because this client talks to two upstreams: `metrics` counts
 *  api.tibiadata.com and `localMetrics` counts the instance we run ourselves.
 *  Which one a call lands in is decided per request from the host it actually
 *  went to, not from configuration — `TIBIADATA_HOST` points at the public API
 *  in local development, and a config-derived label would file every dev
 *  request as local traffic we are not in fact generating. */
class TibiaDataClient(
  metrics: com.tibiabot.tracking.ApiCallMetrics = com.tibiabot.tracking.ApiMetrics.tibiaData,
  localMetrics: com.tibiabot.tracking.ApiCallMetrics = com.tibiabot.tracking.ApiMetrics.tibiaDataLocal,
  inFlight: InFlightLimit = InFlightLimit.tibiaData
)(implicit val system: ActorSystem) extends JsonSupport with StrictLogging with TibiaApi with HighscoresApi {

  implicit private val executionContext: ExecutionContextExecutor = system.dispatcher

  private val publicApi = s"https://${TibiaDataClient.PublicHost}"
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

  /** How old the data in a parsed response actually was, for the dashboard's
   *  cache-age breakdown.
   *
   *  Read from `information.timestamp` in the body rather than the `Age` header.
   *  `Age` used to be the answer, and when present it still is — measured equal
   *  to `Date - information.timestamp` in every one of 200 samples. But TibiaData
   *  moved endpoint caching to Cloudflare in September 2026 and `Age` did not
   *  survive the move for the traffic this bot actually sends: probing 16
   *  characters taken from a live online list, every one came back
   *  `cf-cache-status: HIT` carrying data 15-292s old and not one carried an
   *  `Age`. It lingers only on keys being hammered often enough to stay in the
   *  older layer, which is nothing like the poll's spread over a world's
   *  population. Reading `None` as "refilled from the origin", as this did, would
   *  now file essentially the whole character poll as fresh.
   *
   *  The remaining headers are no better: `x-cache-status` is frozen at `Miss` on
   *  every response, hit or not, and `last-modified` is restamped per response
   *  (`Date - Last-Modified` was 0 on every sample, including hits of 191s-old
   *  data). `information.timestamp` is the one value still pinned to when the
   *  origin built the copy, which is why [[AgeCachedTibiaApi]] already schedules
   *  against it.
   *
   *  Buckets deliberately run past the 300s TTL. That is the point of the
   *  breakdown: a copy can be observed at any age up to the TTL, so anything
   *  landing at 300s+ says the real TTL is longer than `character-cache.ttl`
   *  assumes, which is the one thing this measurement exists to catch. */
  private def dataAgeOf(response: HttpResponse, information: Information): String =
    TibiaDataClient.dataAgeBucket(
      response.header[DateHeader].map(_.date.clicks / 1000L),
      OriginTimestamp.of(information))

  /** The first two path segments, so `/v4/character/Bubble` collapses onto
   *  `/v4/character` instead of becoming one counter per character. */
  private def endpointOf(request: HttpRequest): String = {
    val segments = request.uri.path.toString.split('/').filter(_.nonEmpty).take(2)
    if (segments.isEmpty) "/" else segments.mkString("/", "/", "")
  }

  /** The counter this request belongs to, by the host it is going to.
   *
   *  Host rather than endpoint, because `/v4/highscores` is the one path served
   *  by both instances — the vocation-filtered lists go to ours and the rest to
   *  the public API, and [[endpointOf]] collapses them onto the same label. */
  private def metricsFor(request: HttpRequest): com.tibiabot.tracking.ApiCallMetrics =
    if (TibiaDataClient.isPublicHost(request.uri)) metrics else localMetrics

  /** Every request this client makes, built the one way.
   *
   *  [[decodeResponse]] has always been able to gunzip a reply, but nothing ever
   *  asked for one — pekko does not add `Accept-Encoding` itself, so the server
   *  kept sending identity and the decoder kept having nothing to do. Measured on
   *  a character sheet: 1627 bytes uncompressed against 648 asking for gzip, on
   *  the endpoint that is ~99% of this process's requests. `FansiteApiClient`
   *  already sends the header; this is the same one line. */
  private def get(uri: String): HttpRequest =
    HttpRequest(uri = uri).withHeaders(
      `Accept-Encoding`(HttpEncodingRange(HttpEncodings.gzip), HttpEncodingRange(HttpEncodings.deflate)))

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
      metricsFor(request).record("endpoint" -> endpointOf(request), "status" -> status.toString)
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
        metricsFor(request).record("endpoint" -> endpointOf(request), "status" -> "failed")
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
      response <- requestWithRetry(get(uri))
      decoded = decodeResponse(response)
      unmarshalled <- Unmarshal(decoded).to[T].map(Right(_))
        .recover(recoverUnmarshal(decoded, contentTypeMessage(response), parseMessage))
    } yield unmarshalled

  def getWorld(world: String): Future[Either[String, WorldResponse]] = {
    val encodedName = URLEncoder.encode(world, "UTF-8").replaceAll("\\+", "%20")
    fetch[WorldResponse](
      s"$publicApi/v4/world/$encodedName",
      resp => s"Failed to get world: '${encodedName.replaceAll("%20", " ")}' with status: '${resp.status}'",
      s"Failed to parse world: '${encodedName.replaceAll("%20", " ")}'")
  }

  def getWorlds(): Future[Either[String, WorldsResponse]] =
    fetch[WorldsResponse](
      s"$publicApi/v4/worlds",
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

  /** Decode + unmarshal a character response, recovering failures to a logged Left.
   *
   *  A parse that succeeds also files the sheet's age under `cacheAge` — see
   *  [[dataAgeOf]] for why that has to happen here rather than at the request
   *  choke point. `recordDimension` rather than `record`, because the call was
   *  already counted there and counting it again would inflate every total on
   *  the panel. The dimension therefore sums to the character sheets actually
   *  parsed, not to all TibiaData traffic.
   *
   *  This covers `getCharacter` — the poll, ~99% of this process's requests and
   *  the only caller [[AgeCachedTibiaApi]] gates — and `getKillerFallback`. The
   *  slash-command path goes through `fetch` and is deliberately left out: it is
   *  a handful of calls whose timing is a user typing, and mixing them in would
   *  bias the very histogram the poll's canary keeps unbiased. */
  private def unmarshalCharacter(response: HttpResponse, encodedName: String): Future[Either[String, CharacterResponse]] = {
    val decoded = decodeResponse(response)
    Unmarshal(decoded).to[CharacterResponse].map { parsed =>
      metrics.recordDimension("cacheAge", dataAgeOf(response, parsed.information))
      Right(parsed)
    }.recover(recoverUnmarshal(
      decoded,
      s"Failed to get character: '${encodedName.replaceAll("%20", " ")}' with status: '${response.status}'",
      s"Failed to parse character: '${encodedName.replaceAll("%20", " ")}'"))
  }

  /** The poll's character fetch — ~99% of this process's API traffic, and the one
   *  caller with its own retry: the next poll is a minute away, so no inline retry. */
  def getCharacter(name: String): Future[Either[String, CharacterResponse]] = {
    val encodedName = URLEncoder.encode(name, "UTF-8").replaceAll("\\+", "%20")
    requestWithRetry(get(s"$characterUrl$encodedName"), callerRetriesSoon = true)
      .flatMap(unmarshalCharacter(_, encodedName))
  }

  def getKillerFallback(name: String): Future[Either[String, CharacterResponse]] = {
    val encodedName = URLEncoder.encode(name, "UTF-8").replaceAll("\\+", "%20")
    val responseFuture = requestWithRetry(get(s"$characterUrl$encodedName"))
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
  /** The shared, Kong-cached instance. Every other host this client is pointed
   *  at is one we run, so this single name is the whole of the split. */
  val PublicHost = "api.tibiadata.com"

  /** Whether `uri` is going to the public API rather than to our own instance.
   *
   *  A missing host reads as public: a relative URI is not something this
   *  client builds, and guessing "ours" for one would inflate the figure whose
   *  only value is being literally true. */
  def isPublicHost(uri: org.apache.pekko.http.scaladsl.model.Uri): Boolean = {
    val host = uri.authority.host.address()
    host.isEmpty || host.equalsIgnoreCase(PublicHost)
  }

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

  /** How old the data was when it reached us, from the two clocks in the
   *  response: `servedAt` is its `Date` header in epoch seconds, `builtAt` the
   *  `information.timestamp` the origin stamped into the body.
   *
   *  Both sides come from the server, so a skewed or wrong local clock cannot
   *  move this figure — which matters for a measurement whose whole job is to
   *  say whether the upstream TTL is what we think it is.
   *
   *  Either one missing yields "unknown" rather than a guess: a sheet that
   *  cannot say when it was built is not evidence about the TTL in either
   *  direction, and quietly filing it as young would be the same mistake the
   *  `Age` header's absence already caused once. */
  private[tibiadata] def dataAgeBucket(servedAt: Option[Long], builtAt: Option[Instant]): String =
    (servedAt, builtAt) match {
      case (Some(served), Some(built)) => cacheAgeBucket(served - built.getEpochSecond)
      case _                           => "unknown"
    }
}
