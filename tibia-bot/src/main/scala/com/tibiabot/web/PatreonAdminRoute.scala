package com.tibiabot.web

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.{Directive0, Route}
import akka.http.scaladsl.server.Directives._
import com.tibiabot.paywall
import com.typesafe.scalalogging.StrictLogging
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.util.{Failure, Success, Try}

/** Owner-only Patreon seat admin actions for the dashboard: release one seat,
 *  release every seat a user holds, or set a per-user seat-count adjustment
 *  (`extra-seats` below, taking a Discord username, raw id or pasted mention —
 *  see [[paywall.PaywallService.resolveUserId]]). A *positive* adjustment also
 *  fully bypasses the Patreon subscription check for that person (see
 *  [[paywall.PaywallService.callerIsSubscribed]]), so this doubles as "give
 *  this specific person bot access without a real subscription" — an arbitrary
 *  override of the normal `/setup`-driven seat flow, deliberately not gated on
 *  the target being in the support server or anywhere else. Reuses [[DiscordAuth.authenticatedUser]] the same way
 *  [[StatusRoute]] does; mounted alongside it under the same `/dashboard`
 *  prefix in BotApp. */
final class PatreonAdminRoute(
  discordAuth: DiscordAuth,
  ownerId: String,
  paywallService: paywall.PaywallService
) extends StrictLogging {

  private def requireOwner(userId: String): Directive0 =
    if (userId == ownerId) pass else complete(StatusCodes.Forbidden -> "Forbidden")

  private val ok = HttpEntity(ContentTypes.`application/json`, """{"ok":true}""")

  private def badRequest(message: String) =
    complete(StatusCodes.BadRequest -> HttpEntity(ContentTypes.`application/json`, JsObject("error" -> JsString(message)).compactPrint))

  val routes: Route =
    path("patreon" / "seats") {
      delete {
        discordAuth.authenticatedUser { callerId =>
          requireOwner(callerId) {
            entity(as[String]) { body =>
              Try(body.parseJson.asJsObject.fields) match {
                case Success(fields) if fields.contains("guildId") && fields.contains("world") =>
                  paywallService.releaseSeat(fields("guildId").convertTo[String], fields("world").convertTo[String])
                  complete(ok)
                case _ => badRequest("Expected guildId and world")
              }
            }
          }
        }
      }
    } ~
    path("patreon" / "users" / Segment / "seats") { targetUserId =>
      delete {
        discordAuth.authenticatedUser { callerId =>
          requireOwner(callerId) {
            paywallService.releaseAllSeats(targetUserId)
            complete(ok)
          }
        }
      }
    } ~
    path("patreon" / "extra-seats") {
      put {
        discordAuth.authenticatedUser { callerId =>
          requireOwner(callerId) {
            entity(as[String]) { body =>
              Try(body.parseJson.asJsObject.fields) match {
                case Success(fields) if fields.contains("username") && fields.contains("extraSeats") =>
                  val username = fields("username").convertTo[String]
                  Try(fields("extraSeats").convertTo[Int]) match {
                    case Success(extraSeats) =>
                      paywallService.resolveUserId(username) match {
                        case Some(targetUserId) =>
                          paywallService.setExtraSeats(targetUserId, extraSeats)
                          complete(ok)
                        case None =>
                          badRequest(s"Couldn't resolve '$username' — paste their Discord user ID if they aren't in the support server")
                      }
                    case Failure(_) => badRequest("Expected an integer extraSeats")
                  }
                case _ => badRequest("Expected username and extraSeats")
              }
            }
          }
        }
      }
    }
}
