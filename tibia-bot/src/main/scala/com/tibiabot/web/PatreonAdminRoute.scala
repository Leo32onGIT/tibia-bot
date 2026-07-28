package com.tibiabot.web

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.{Directive0, Route}
import akka.http.scaladsl.server.Directives._
import com.tibiabot.paywall
import com.typesafe.scalalogging.StrictLogging
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.ExecutionContextExecutor
import scala.util.{Failure, Success, Try}

/** Owner-only Patreon seat admin actions for the dashboard: release one seat,
 *  release every seat a user holds, or set a per-user seat-count adjustment
 *  (`extra-seats` below) — arbitrary overrides of the normal `/setup`-driven
 *  seat flow. Reuses [[DiscordAuth.authenticatedUser]] the same way
 *  [[StatusRoute]] does; mounted alongside it under the same `/dashboard`
 *  prefix in BotApp. */
final class PatreonAdminRoute(
  discordAuth: DiscordAuth,
  ownerId: String,
  paywallService: paywall.PaywallService
)(implicit ex: ExecutionContextExecutor) extends StrictLogging {

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
    path("patreon" / "users" / Segment / "extra-seats") { targetUserId =>
      put {
        discordAuth.authenticatedUser { callerId =>
          requireOwner(callerId) {
            entity(as[String]) { body =>
              Try(body.parseJson.asJsObject.fields("extraSeats").convertTo[Int]) match {
                case Success(extraSeats) =>
                  paywallService.setExtraSeats(targetUserId, extraSeats)
                  complete(ok)
                case Failure(_) => badRequest("Expected an integer extraSeats")
              }
            }
          }
        }
      }
    }
}
