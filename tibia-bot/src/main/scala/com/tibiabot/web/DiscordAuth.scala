package com.tibiabot.web

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.HttpCookie
import akka.http.scaladsl.server.Directive
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.typesafe.scalalogging.StrictLogging
import spray.json._
import spray.json.DefaultJsonProtocol._

import java.net.URLEncoder
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Generic Discord OAuth2 login/callback pipeline plus a signed session cookie
 * identifying *who* authenticated. Deliberately has no opinion on *what* that
 * user is allowed to do — that's each route's own concern, layered on top of
 * [[authenticatedUser]] (e.g. [[StatusRoute]]'s owner-only guard) — so this
 * class can be reused unchanged by a future gated route (a paywall) mounted at
 * its own `mountPath` that only needs "which Discord user is this", not "is
 * this the bot owner".
 *
 * `mountPath` (e.g. "/dashboard") is where the caller nests [[routes]] via
 * `pathPrefix` — passed in separately here (rather than derived from the
 * request) because it's also needed for two things a route match can't tell
 * us: the absolute redirect target for an unauthenticated visitor, and the
 * cookie's scope, kept to this one gated area rather than the whole domain
 * (this app's domain also serves an unrelated, unauthenticated landing page).
 */
final class DiscordAuth(clientId: String, clientSecret: String, sessionSecret: String, redirectUri: String, mountPath: String)
  (implicit system: ActorSystem, ex: ExecutionContextExecutor) extends StrictLogging {

  private val cookieName = "vb_session"
  private val sessionTtl = 7.days
  private val loginPath = s"$mountPath/auth/login"

  private def hmac(data: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(sessionSecret.getBytes("UTF-8"), "HmacSHA256"))
    Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(data.getBytes("UTF-8")))
  }

  private def signSession(userId: String, expiryEpochSeconds: Long): String = {
    val payload = s"$userId.$expiryEpochSeconds"
    s"$payload.${hmac(payload)}"
  }

  /** None if the cookie is missing, malformed, mis-signed, or expired. */
  private def verifySession(cookieValue: String): Option[String] = cookieValue.split("\\.", 3) match {
    case Array(userId, expiryStr, signature) =>
      val payload = s"$userId.$expiryStr"
      for {
        expiry <- Try(expiryStr.toLong).toOption
        if hmac(payload) == signature
        if expiry > Instant.now().getEpochSecond
      } yield userId
    case _ => None
  }

  private def authorizeUrl: String = {
    val encodedRedirect = URLEncoder.encode(redirectUri, "UTF-8")
    s"https://discord.com/api/oauth2/authorize?client_id=$clientId&redirect_uri=$encodedRedirect&response_type=code&scope=identify"
  }

  /** Exchanges an OAuth `code` for the authenticated Discord user's id, via the
   *  token endpoint then `/users/@me`. None on any failure (bad code, network,
   *  malformed response) — the callback route treats that as a failed login. */
  private def resolveUserId(code: String): Future[Option[String]] = {
    val tokenRequest = HttpRequest(
      method = HttpMethods.POST,
      uri = "https://discord.com/api/oauth2/token",
      entity = FormData(Map(
        "client_id" -> clientId,
        "client_secret" -> clientSecret,
        "grant_type" -> "authorization_code",
        "code" -> code,
        "redirect_uri" -> redirectUri
      )).toEntity
    )
    Http().singleRequest(tokenRequest).flatMap { tokenResponse =>
      if (tokenResponse.status.isSuccess()) {
        Unmarshal(tokenResponse.entity).to[String].flatMap { body =>
          Try(body.parseJson.asJsObject.fields("access_token").convertTo[String]) match {
            case Success(accessToken) =>
              val userRequest = HttpRequest(
                uri = "https://discord.com/api/users/@me",
                headers = List(akka.http.scaladsl.model.headers.Authorization(
                  akka.http.scaladsl.model.headers.OAuth2BearerToken(accessToken)))
              )
              Http().singleRequest(userRequest).flatMap { userResponse =>
                if (userResponse.status.isSuccess()) {
                  Unmarshal(userResponse.entity).to[String].map { userBody =>
                    Try(userBody.parseJson.asJsObject.fields("id").convertTo[String]).toOption
                  }
                } else {
                  userResponse.discardEntityBytes()
                  Future.successful(None)
                }
              }
            case Failure(ex) =>
              logger.warn(s"Discord token response was not the expected shape: ${ex.getMessage}")
              Future.successful(None)
          }
        }
      } else {
        tokenResponse.discardEntityBytes()
        logger.warn(s"Discord token exchange failed: ${tokenResponse.status}")
        Future.successful(None)
      }
    }.recover {
      case ex: Throwable =>
        logger.error(s"Discord OAuth exchange failed: ${ex.getMessage}")
        None
    }
  }

  /** The authenticated Discord user id, extracted from a valid session cookie.
   *  Redirects to the login path if the cookie is missing, invalid, or expired —
   *  callers only need to handle the authenticated case. */
  val authenticatedUser: Directive[Tuple1[String]] = Directive[Tuple1[String]] { inner =>
    optionalCookie(cookieName) { cookieOpt =>
      cookieOpt.flatMap(c => verifySession(c.value)) match {
        case Some(userId) => inner(Tuple1(userId))
        case None => redirect(loginPath, StatusCodes.Found)
      }
    }
  }

  val routes: akka.http.scaladsl.server.Route =
    path("auth" / "login") {
      get {
        redirect(authorizeUrl, StatusCodes.Found)
      }
    } ~
    path("auth" / "callback") {
      get {
        parameter("code") { code =>
          onComplete(resolveUserId(code)) {
            case Success(Some(userId)) =>
              val expiry = Instant.now().plusSeconds(sessionTtl.toSeconds).getEpochSecond
              setCookie(HttpCookie(
                name = cookieName,
                value = signSession(userId, expiry),
                path = Some(mountPath),
                httpOnly = true,
                secure = true,
                maxAge = Some(sessionTtl.toSeconds),
                extension = Some("SameSite=Strict")
              )) {
                redirect(mountPath, StatusCodes.Found)
              }
            case Success(None) =>
              complete(StatusCodes.Unauthorized -> "Discord login failed")
            case Failure(ex) =>
              logger.error(s"Discord OAuth callback failed: ${ex.getMessage}")
              complete(StatusCodes.InternalServerError -> "Discord login failed")
          }
        }
      }
    }
}
