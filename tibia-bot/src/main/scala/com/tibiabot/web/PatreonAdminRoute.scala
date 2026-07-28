package com.tibiabot.web

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.{Directive0, Route}
import akka.http.scaladsl.server.Directives._
import com.tibiabot.{discord, paywall}
import com.typesafe.scalalogging.StrictLogging
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

/** Owner-only Patreon seat admin actions for the dashboard: add, release one,
 *  or release every seat a user holds — arbitrary overrides of the normal
 *  `/setup`-driven seat flow, bypassing [[paywall.PaywallService.canAssignSeat]]'s
 *  limit entirely (this is the one place that's meant to happen). Also a
 *  per-user seat-count adjustment (see `extra-seats` below), a separate
 *  admin lever from seat assignment — it raises or lowers a user's overall
 *  limit rather than force-claiming one specific (guild, world) pair. Reuses
 *  [[DiscordAuth.authenticatedUser]] the same way [[StatusRoute]] does; mounted
 *  alongside it under the same `/dashboard` prefix in BotApp. */
final class PatreonAdminRoute(
  discordAuth: DiscordAuth,
  ownerId: String,
  paywallService: paywall.PaywallService,
  discordGateway: discord.DiscordGateway
)(implicit ex: ExecutionContextExecutor) extends StrictLogging {

  private def requireOwner(userId: String): Directive0 =
    if (userId == ownerId) pass else complete(StatusCodes.Forbidden -> "Forbidden")

  private val ok = HttpEntity(ContentTypes.`application/json`, """{"ok":true}""")

  private def badRequest(message: String) =
    complete(StatusCodes.BadRequest -> HttpEntity(ContentTypes.`application/json`, JsObject("error" -> JsString(message)).compactPrint))

  val routes: Route =
    path("patreon" / "seats") {
      post {
        discordAuth.authenticatedUser { callerId =>
          requireOwner(callerId) {
            entity(as[String]) { body =>
              Try(body.parseJson.asJsObject.fields) match {
                case Success(fields) if fields.contains("userId") && fields.contains("guildId") && fields.contains("world") =>
                  val targetUserId = fields("userId").convertTo[String]
                  val guildId = fields("guildId").convertTo[String]
                  val world = fields("world").convertTo[String]
                  // retrieveUser is a blocking JDA REST call — run it off the
                  // routing dispatcher, unlike guildById elsewhere in the
                  // dashboard, which only reads JDA's in-memory guild cache.
                  onComplete(Future(Option(discordGateway.retrieveUser(targetUserId)).map(_.getName).getOrElse(""))) {
                    case Success(userName) =>
                      paywallService.assignSeat(targetUserId, userName, guildId, world)
                      complete(ok)
                    case Failure(ex) =>
                      logger.warn(s"Failed to resolve username for Discord user id '$targetUserId' while assigning a seat", ex)
                      paywallService.assignSeat(targetUserId, "", guildId, world)
                      complete(ok)
                  }
                case _ => badRequest("Expected userId, guildId and world")
              }
            }
          }
        }
      } ~
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
