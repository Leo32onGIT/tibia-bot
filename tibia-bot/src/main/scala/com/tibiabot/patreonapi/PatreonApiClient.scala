package com.tibiabot.patreonapi

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.model.{FormData, HttpMethods, HttpRequest}
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.tibiabot.Config
import com.tibiabot.domain.PatreonMember
import com.typesafe.scalalogging.StrictLogging
import spray.json._
import spray.json.DefaultJsonProtocol._

import java.net.URLEncoder
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/** Pure JSON:API response parsing for PatreonApiClient, split out so it's
 *  testable with fixture JSON and no ActorSystem/HTTP involved — mirrors the
 *  `private[paywall]`-pure-function split already used by PaywallService.
 *
 *  Parsed by hand (matching DiscordAuth's manual field-access style for
 *  Discord's OAuth responses) rather than case-class auto-derivation — the
 *  envelope's data/included/meta shape doesn't map onto one fixed case class
 *  the way TibiaData's endpoints do.
 *
 *  Caveat: the `social_connections.discord` nested shape used below (an
 *  object with a `user_id` field, or absent/null when unlinked) is inferred
 *  from Patreon's v1 API and ecosystem convention, not confirmed against a
 *  live v2 response — Patreon's own v2 docs don't spell it out. Verify once
 *  real credentials and a patron with Discord linked exist, and adjust
 *  `parseDiscordUserId` if the real shape differs. */
object PatreonApiClient {

  private[patreonapi] def parseDiscordUserId(userObj: Option[JsObject]): Option[String] =
    for {
      user <- userObj
      attrsField <- user.fields.get("attributes")
      attrs <- Try(attrsField.asJsObject).toOption
      socialField <- attrs.fields.get("social_connections")
      social <- Try(socialField.asJsObject).toOption
      discordField <- social.fields.get("discord")
      if discordField != JsNull
      discord <- Try(discordField.asJsObject).toOption
      userIdField <- discord.fields.get("user_id")
      userId <- Try(userIdField.convertTo[String]).toOption
    } yield userId

  /** Parses one page of the campaign members response into the members it
   *  carries plus the next page's cursor, if any. */
  private[patreonapi] def parsePage(json: JsObject): (List[PatreonMember], Option[String]) = {
    val included = json.fields.get("included") match {
      case Some(JsArray(elements)) => elements.toList.map(_.asJsObject)
      case _ => Nil
    }
    val usersById = included
      .filter(_.fields.get("type").contains(JsString("user")))
      .map(u => u.fields("id").convertTo[String] -> u)
      .toMap

    val dataElements = json.fields.get("data") match {
      case Some(JsArray(elements)) => elements.toList.map(_.asJsObject)
      case _ => Nil
    }
    val members = dataElements.flatMap { member =>
      Try {
        val id = member.fields("id").convertTo[String]
        val attrs = member.fields("attributes").asJsObject
        val fullName = attrs.fields.get("full_name").flatMap {
          case JsString(s) => Some(s)
          case _ => None
        }.getOrElse("")
        val patronStatus = attrs.fields.get("patron_status").flatMap {
          case JsString(s) => Some(s)
          case _ => None
        }
        val pledgeCents = attrs.fields.get("currently_entitled_amount_cents").flatMap {
          case JsNumber(n) => Some(n.toInt)
          case _ => None
        }.getOrElse(0)
        val linkedUserId = Try(
          member.fields("relationships").asJsObject.fields("user").asJsObject.fields("data").asJsObject.fields("id").convertTo[String]
        ).toOption
        val userObj = linkedUserId.flatMap(usersById.get)
        val discordUserId = parseDiscordUserId(userObj)
        PatreonMember(id, fullName, patronStatus, pledgeCents, discordUserId)
      }.toOption
    }

    val nextCursor = for {
      meta <- json.fields.get("meta")
      pagination <- Try(meta.asJsObject).toOption
      cursorsField <- pagination.fields.get("pagination")
      pageInfo <- Try(cursorsField.asJsObject).toOption
      cursors <- pageInfo.fields.get("cursors")
      cursorsObj <- Try(cursors.asJsObject).toOption
      nextField <- cursorsObj.fields.get("next")
      if nextField != JsNull
      next <- Try(nextField.convertTo[String]).toOption
    } yield next

    (members, nextCursor)
  }
}

/** Direct client for Patreon API v2 (see Config.PatreonApi) — periodically
 *  synced by BotApp into persistence.PatreonMemberRepository for the
 *  dashboard's supporters panel. Purely additive/read-only: never touches
 *  the paywall's own Discord-role gate. Response parsing lives in the
 *  companion object above, so it's testable without an ActorSystem. */
final class PatreonApiClient()(implicit system: ActorSystem, ec: ExecutionContextExecutor) extends StrictLogging {

  private val apiBase = "https://www.patreon.com/api/oauth2/v2"
  private val tokenUrl = "https://www.patreon.com/api/oauth2/token"

  // Refreshed in place on a 401; not persisted — a fresh refresh on next boot
  // is cheap and avoids needing to keep the DB in sync with token rotation.
  @volatile private var accessToken: String = Config.PatreonApi.accessToken

  private def refreshAccessToken(): Future[Unit] = {
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = tokenUrl,
      entity = FormData(Map(
        "grant_type" -> "refresh_token",
        "refresh_token" -> Config.PatreonApi.refreshToken,
        "client_id" -> Config.PatreonApi.clientId,
        "client_secret" -> Config.PatreonApi.clientSecret
      )).toEntity
    )
    Http().singleRequest(request).flatMap { response =>
      Unmarshal(response.entity).to[String].map { body =>
        if (response.status.isSuccess()) {
          Try(body.parseJson.asJsObject.fields("access_token").convertTo[String]) match {
            case Success(token) => accessToken = token
            case Failure(ex) => logger.warn(s"Patreon token refresh response was not the expected shape: ${ex.getMessage}")
          }
        } else {
          logger.warn(s"Patreon token refresh failed: ${response.status}")
        }
      }
    }.recover {
      case NonFatal(ex) => logger.error(s"Patreon token refresh failed: ${ex.getMessage}")
    }
  }

  private def membersRequest(cursor: Option[String]): HttpRequest = {
    val campaignId = URLEncoder.encode(Config.PatreonApi.campaignId, "UTF-8")
    val base = s"$apiBase/campaigns/$campaignId/members" +
      "?include=user" +
      "&fields%5Bmember%5D=full_name,patron_status,currently_entitled_amount_cents" +
      "&fields%5Buser%5D=social_connections" +
      "&page%5Bcount%5D=1000"
    val uri = cursor.map(c => s"$base&page%5Bcursor%5D=${URLEncoder.encode(c, "UTF-8")}").getOrElse(base)
    HttpRequest(uri = uri, headers = List(Authorization(OAuth2BearerToken(accessToken))))
  }

  private def fetchPage(cursor: Option[String], retriedAfterRefresh: Boolean = false): Future[List[PatreonMember]] =
    Http().singleRequest(membersRequest(cursor)).flatMap { response =>
      if (response.status.intValue == 401 && !retriedAfterRefresh) {
        response.discardEntityBytes()
        refreshAccessToken().flatMap(_ => fetchPage(cursor, retriedAfterRefresh = true))
      } else if (!response.status.isSuccess()) {
        val status = response.status
        response.discardEntityBytes()
        logger.warn(s"Patreon members fetch failed: $status")
        Future.successful(Nil)
      } else {
        Unmarshal(response.entity).to[String].flatMap { body =>
          Try(PatreonApiClient.parsePage(body.parseJson.asJsObject)) match {
            case Success((members, Some(next))) => fetchPage(Some(next)).map(members ++ _)
            case Success((members, None)) => Future.successful(members)
            case Failure(ex) =>
              logger.warn(s"Failed to parse Patreon members response: ${ex.getMessage}")
              Future.successful(Nil)
          }
        }
      }
    }.recover {
      case NonFatal(ex) =>
        logger.warn(s"Patreon members fetch failed: ${ex.getMessage}")
        Nil
    }

  /** Every current campaign member (active, declined, and former patrons —
   *  Patreon returns all of them, not just active ones), paginated to
   *  exhaustion. Best-effort: never fails the caller's Future, since this
   *  backs a periodic background sync rather than a user-facing request —
   *  a transient error just yields whatever was fetched before it hit. */
  def fetchAllMembers(): Future[List[PatreonMember]] = fetchPage(None)
}
