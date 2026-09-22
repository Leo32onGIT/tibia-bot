package com.tibiabot.observer

import com.tibiabot.Config
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

/** Talks to the local Observer sidecar (see `observer-sidecar/`), which owns the
 *  browser-TLS Cloudflare pass and the CipSoft request shapes. Everything here is a
 *  plain localhost HTTP call; the bot keeps all durable state itself. */
final class ObserverApiClient(
  sidecarUrl: String = Config.Observer.sidecarUrl,
  sharedToken: String = Config.Observer.sidecarToken,
  deviceIdentification: String = Config.Observer.deviceIdentification,
  clientVersion: String = Config.Observer.clientVersion
) extends StrictLogging {

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  private def post(path: String, body: JsObject): Either[String, JsObject] = {
    val builder = HttpRequest.newBuilder(URI.create(s"$sidecarUrl$path"))
      .timeout(Duration.ofSeconds(25))
      .header("Content-Type", "application/json")
    if (sharedToken.nonEmpty) builder.header("X-Sidecar-Token", sharedToken)
    val request = builder.POST(HttpRequest.BodyPublishers.ofString(body.compactPrint)).build()
    try {
      val response = http.send(request, HttpResponse.BodyHandlers.ofString())
      Right(response.body.parseJson.asJsObject)
    } catch {
      case ex: Throwable =>
        logger.warn(s"Observer sidecar call to $path failed", ex)
        Left(Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName))
    }
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

  /** Ensure enabled MWC rules exist for these worlds on the linked account. */
  def ensureRules(credential: String, worlds: List[String]): Boolean =
    worlds.nonEmpty && (post("/ensure-rules", JsObject(
      "credential" -> JsString(credential),
      "deviceIdentification" -> JsString(deviceIdentification),
      "worlds" -> JsArray(worlds.map(JsString(_)).toVector)
    )) match {
      case Right(o) => bool(o, "ok")
      case Left(_)  => false
    })

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
  def mwc(credential: String): List[MiniWorldChange] =
    post("/mwc", JsObject("bearerToken" -> JsString(credential))) match {
      case Left(_) => Nil
      case Right(o) =>
        o.fields.get("miniWorldChanges").collect { case JsArray(items) =>
          items.collect { case item: JsObject =>
            MiniWorldChange(
              str(item, "world").getOrElse(""),
              str(item, "title").getOrElse(""),
              str(item, "body").getOrElse(""))
          }.toList
        }.getOrElse(Nil)
    }

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

  /** Ensure enabled raid rules for every world the account has explored (regions
   *  derived from ExploredAreas by the sidecar). */
  def ensureRaidRules(credential: String): Boolean =
    post("/ensure-raid-rules", JsObject(
      "credential" -> JsString(credential),
      "deviceIdentification" -> JsString(deviceIdentification)
    )) match {
      case Right(o) => bool(o, "ok")
      case Left(_)  => false
    }

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
