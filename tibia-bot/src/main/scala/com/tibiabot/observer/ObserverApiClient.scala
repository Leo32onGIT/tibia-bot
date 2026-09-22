package com.tibiabot.observer

import com.tibiabot.Config
import com.typesafe.scalalogging.StrictLogging
import spray.json._

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

/** Outcome of exchanging a 5-char access token for a durable link. */
sealed trait LinkResult
object LinkResult {
  /** Linked. `refresh` is the durable credential to store; `bearer`/`expires` are
   *  the current access token (cached in memory, not persisted). */
  final case class Linked(refresh: String, bearer: Option[String], expires: Option[String],
                          accountLabel: Option[String]) extends LinkResult
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
          str(o, "refresh") match {
            case Some(refresh) =>
              LinkResult.Linked(refresh, str(o, "bearerToken"), str(o, "expires"), str(o, "accountLabel"))
            case None =>
              // Linked upstream but the sidecar could not find the durable token in
              // the response — a shape change to fix in the sidecar, not here.
              LinkResult.Failed("linked, but no refresh token was returned")
          }
        else if (status == "invalidAccessToken") LinkResult.InvalidToken
        else LinkResult.Failed(if (status.nonEmpty) status else "unknown sidecar response")
    }
}
