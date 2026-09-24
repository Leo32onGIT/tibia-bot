package com.tibiabot.observer

import com.tibiabot.{Config, tracking}
import com.tibiabot.domain.{MiniWorldChange, RaidAnnouncement}
import com.typesafe.scalalogging.StrictLogging
import spray.json._

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration, Instant, OffsetDateTime}

/** Outcome of exchanging a 5-char access token for a durable link. */
sealed trait LinkResult
object LinkResult {
  /** Linked. `credential` is the durable ~90-day JWT bearer to store (renewable via
   *  the sidecar's /renew before it lapses); `worlds` are the account's
   *  character-worlds, which the bot sets MWC rules for. */
  final case class Linked(credential: String, accountLabel: Option[String], worlds: List[String]) extends LinkResult
  /** The token was wrong/expired/spent — the user must add a fresh one. */
  case object InvalidToken extends LinkResult
  /** The sidecar or upstream failed; not the user's fault. */
  final case class Failed(reason: String) extends LinkResult
}

/** What setting one kind of rule on an account came to. The API caps how many
 *  rules an account holds, so `applied` can be fewer worlds than were asked for;
 *  `skipped` are the ones left without a rule, and `limit` the cap. `detail` says
 *  why it failed, when it did. */
final case class RulesResult(ok: Boolean, applied: List[String], skipped: List[String],
                             limit: Option[Int], detail: String)

/** Talks to the local Observer sidecar (see `observer-sidecar/`), which owns the
 *  browser-TLS Cloudflare pass and the CipSoft request shapes. Everything here is a
 *  plain localhost HTTP call; the bot keeps all durable state itself. */
final class ObserverApiClient(
  sidecarUrl: String = Config.Observer.sidecarUrl,
  sharedToken: String = Config.Observer.sidecarToken,
  deviceIdentification: String = Config.Observer.deviceIdentification,
  clientVersion: String = Config.Observer.clientVersion,
  metrics: tracking.ApiCallMetrics = tracking.ApiMetrics.observer
) extends StrictLogging {

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  /** Every Observer request goes through here, so this is where it is counted for
   *  the dashboard: by sidecar endpoint, and by the status the Observer API itself
   *  answered with where the sidecar passes it on (the sidecar's own otherwise). */
  private def post(path: String, body: JsObject): Either[String, JsObject] = {
    val builder = HttpRequest.newBuilder(URI.create(s"$sidecarUrl$path"))
      .timeout(Duration.ofSeconds(25))
      .header("Content-Type", "application/json")
    if (sharedToken.nonEmpty) builder.header("X-Sidecar-Token", sharedToken)
    val request = builder.POST(HttpRequest.BodyPublishers.ofString(body.compactPrint)).build()
    try {
      val response = http.send(request, HttpResponse.BodyHandlers.ofString())
      val parsed = response.body.parseJson.asJsObject
      val status = parsed.fields.get("status_code").collect { case JsNumber(n) => n.toInt }.getOrElse(response.statusCode)
      metrics.record("endpoint" -> path, "status" -> status.toString)
      Right(parsed)
    } catch {
      case ex: Throwable =>
        metrics.record("endpoint" -> path, "status" -> "failed")
        logger.warn(s"Observer sidecar call to $path failed", ex)
        Left(Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName))
    }
  }

  private def rulesResult(answer: Either[String, JsObject]): RulesResult = answer match {
    case Left(err) => RulesResult(ok = false, Nil, Nil, None, err)
    case Right(o) =>
      val code = o.fields.get("status_code").collect { case JsNumber(n) => s"status ${n.toInt}" }
      RulesResult(bool(o, "ok"), strings(o, "worlds"), strings(o, "skipped"),
        o.fields.get("limit").collect { case JsNumber(n) => n.toInt },
        (code.toList ++ str(o, "error")).mkString(": "))
  }

  private def str(o: JsObject, key: String): Option[String] =
    o.fields.get(key).collect { case JsString(s) if s.nonEmpty => s }

  private def bool(o: JsObject, key: String): Boolean =
    o.fields.get(key).collect { case JsBoolean(b) => b }.getOrElse(false)

  private def strings(o: JsObject, key: String): List[String] =
    o.fields.get(key).collect { case JsArray(xs) => xs.collect { case JsString(s) => s }.toList }.getOrElse(Nil)

  /** Exchange a 5-char access token (case-sensitive, single-use) for a durable link. */
  def link(accessToken: String): LinkResult =
    post("/link", JsObject(
      "accessToken" -> JsString(accessToken),
      "deviceIdentification" -> JsString(deviceIdentification),
      "clientVersion" -> JsString(clientVersion)
    )) match {
      case Left(err) => LinkResult.Failed(err)
      case Right(o) =>
        val status = str(o, "status").getOrElse("")
        if (bool(o, "ok") && status == "success")
          str(o, "credential") match {
            case Some(credential) =>
              LinkResult.Linked(credential, str(o, "accountLabel"), strings(o, "worlds"))
            case None =>
              // Linked upstream but the sidecar could not find the credential in the
              // response — a shape change to fix in the sidecar, not here.
              LinkResult.Failed("linked, but no credential was returned")
          }
        else if (status == "invalidAccessToken") LinkResult.InvalidToken
        else LinkResult.Failed(if (status.nonEmpty) status else "unknown sidecar response")
    }

  /** Ensure enabled MWC rules exist on the linked account for these worlds, most
   *  wanted first: the account has room for only a few (see [[RulesResult]]). */
  def ensureRules(credential: String, worlds: List[String]): RulesResult =
    if (worlds.isEmpty) RulesResult(ok = true, Nil, Nil, None, "")
    else rulesResult(post("/ensure-rules", JsObject(
      "credential" -> JsString(credential),
      "deviceIdentification" -> JsString(deviceIdentification),
      "worlds" -> JsArray(worlds.map(JsString(_)).toVector)
    )))

  /** Remove the bot's MWC rules from the account (on unlink). */
  def clearRules(credential: String): Boolean =
    post("/clear-rules", JsObject(
      "credential" -> JsString(credential),
      "deviceIdentification" -> JsString(deviceIdentification)
    )) match {
      case Right(o) => bool(o, "ok")
      case Left(_)  => false
    }

  /** The currently-active mini world changes for this credential's enabled rules. */
  def mwc(credential: String): List[MiniWorldChange] = mwcResult(credential).getOrElse(Nil)

  /** As [[mwc]], but `None` when the fetch failed — so a caller watching for changes
   *  can tell a failure apart from nothing being active. */
  def mwcResult(credential: String): Option[List[MiniWorldChange]] =
    post("/mwc", JsObject("bearerToken" -> JsString(credential))) match {
      case Left(_) => None
      case Right(o) =>
        o.fields.get("miniWorldChanges").collect { case JsArray(items) =>
          items.collect { case item: JsObject =>
            // `world` is what the feed was seen to send; the raids feed calls the
            // same thing `worldName`, so either is taken.
            val change = MiniWorldChange(
              str(item, "world").orElse(str(item, "worldName")).getOrElse(""),
              str(item, "title").getOrElse(""),
              str(item, "body").getOrElse(""))
            if ((change.world.isEmpty || change.title.isEmpty) && !reportedShape) {
              reportedShape = true
              logger.warn("A mini world change arrived without a world or title, so it is dropped; " +
                s"its fields were: ${item.fields.keys.toList.sorted.mkString(", ")}")
            }
            change
          }.toList
        }
    }

  /** Whether a change the pool cannot use has been reported, so a feed whose shape
   *  moved says so once rather than on every poll. */
  @volatile private var reportedShape = false

  private def intOf(o: JsObject, key: String): Int =
    o.fields.get(key).collect { case JsNumber(n) => n.toInt }.getOrElse(0)

  private def parseInstant(s: String): Option[Instant] =
    try Some(OffsetDateTime.parse(s).toInstant) catch { case _: Throwable => None }

  /** Renew the durable credential (mint a fresh ~90-day JWT). `None` on failure. */
  def renew(credential: String): Option[String] =
    post("/renew", JsObject(
      "credential" -> JsString(credential),
      "deviceIdentification" -> JsString(deviceIdentification)
    )) match {
      case Right(o) if bool(o, "ok") => str(o, "credential")
      case _                         => None
    }

  /** Ensure enabled raid rules for the worlds the account has explored (regions
   *  derived from ExploredAreas by the sidecar), taken in the order of `priority`
   *  while the account has room. */
  def ensureRaidRules(credential: String, priority: List[String]): RulesResult =
    rulesResult(post("/ensure-raid-rules", JsObject(
      "credential" -> JsString(credential),
      "deviceIdentification" -> JsString(deviceIdentification),
      "worlds" -> JsArray(priority.map(JsString(_)).toVector)
    )))

  /** The currently-announced/active raids for this credential's enabled rules. */
  def raids(credential: String): List[RaidAnnouncement] =
    post("/raids", JsObject("bearerToken" -> JsString(credential))) match {
      case Left(_) => Nil
      case Right(o) =>
        o.fields.get("raids").collect { case JsArray(items) =>
          items.collect { case item: JsObject =>
            RaidAnnouncement(
              str(item, "raidId").getOrElse(""),
              str(item, "worldName").getOrElse(""),
              str(item, "areaName").getOrElse(""),
              str(item, "subareaName"),
              str(item, "category").getOrElse(""),
              str(item, "startDate").flatMap(parseInstant),
              intOf(item, "raidTypeId"))
          }.toList
        }.getOrElse(Nil)
    }
}
