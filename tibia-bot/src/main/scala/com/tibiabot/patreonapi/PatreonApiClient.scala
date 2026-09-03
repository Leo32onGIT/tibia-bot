package com.tibiabot.patreonapi

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import org.apache.pekko.http.scaladsl.model.{FormData, HttpMethods, HttpRequest}
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import com.tibiabot.Config
import com.tibiabot.domain.PatreonMember
import com.typesafe.scalalogging.StrictLogging
import spray.json._
import spray.json.DefaultJsonProtocol._

import java.net.URLEncoder
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/** Pure JSON:API response parsing for PatreonApiClient, split out so it is
 *  testable with fixture JSON and no ActorSystem or HTTP.
 *
 *  Parsed by hand rather than by case-class derivation: the envelope's
 *  data/included/meta shape does not map onto one fixed class the way TibiaData's
 *  endpoints do.
 *
 *  The `social_connections.discord` shape below — an object with a `user_id`, or
 *  absent when unlinked — is '''not documented''' in Patreon's v2 docs. It was
 *  inferred from v1 and confirmed against the live campaign. Worth knowing if
 *  Patreon ever changes it: `parseDiscordUserId` yields None rather than failing,
 *  so an unlinked patron and a changed response format look identical from here —
 *  and this is what the paywall matches supporters on. */
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
 *  synced by BotApp into persistence.PatreonMemberRepository, which backs
 *  both the dashboard's supporters panel and the paywall gate itself (see
 *  paywall.PaywallService.callerIsSubscribed). Read-only, but no longer
 *  merely informational: what this fetches decides who can `/setup`, which
 *  is why `fetchAllMembers` reports failure rather than papering over it.
 *  Response parsing lives in the companion object above, so it's testable
 *  without an ActorSystem. */
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

  /** One page, then the rest recursively. Every failure — a bad status, an
   *  unparseable body, a dropped connection — fails the Future rather than
   *  degrading to an empty or partial list. That matters because the caller
   *  replaces the stored snapshot wholesale and that snapshot is what the
   *  paywall gate reads: a page that quietly returned `Nil` mid-pagination
   *  would drop every member after it from the snapshot, and those people
   *  would stop being recognised as patrons. Half a member list is worse
   *  than none, so callers get all of it or an explicit failure. */
  private def fetchPage(cursor: Option[String], retriedAfterRefresh: Boolean = false): Future[List[PatreonMember]] =
    Http().singleRequest(membersRequest(cursor)).flatMap { response =>
      if (response.status.intValue == 401 && !retriedAfterRefresh) {
        response.discardEntityBytes()
        refreshAccessToken().flatMap(_ => fetchPage(cursor, retriedAfterRefresh = true))
      } else if (!response.status.isSuccess()) {
        val status = response.status
        response.discardEntityBytes()
        Future.failed(new RuntimeException(s"Patreon members fetch failed: $status"))
      } else {
        Unmarshal(response.entity).to[String].flatMap { body =>
          Try(PatreonApiClient.parsePage(body.parseJson.asJsObject)) match {
            case Success((members, Some(next))) => fetchPage(Some(next)).map(members ++ _)
            case Success((members, None)) => Future.successful(members)
            case Failure(ex) => Future.failed(new RuntimeException(s"Failed to parse Patreon members response: ${ex.getMessage}", ex))
          }
        }
      }
    }

  /** Every current campaign member (active, declined, and former patrons —
   *  Patreon returns all of them, not just active ones), paginated to
   *  exhaustion. `None` means the fetch failed and nothing about the campaign
   *  should be inferred from it — explicitly not an empty list, which the
   *  caller would otherwise be entitled to read as "the campaign has no
   *  members" and act on. Still never fails the caller's Future: this backs a
   *  periodic background sync, and a transient error should leave the last
   *  good snapshot standing rather than throw. */
  def fetchAllMembers(): Future[Option[List[PatreonMember]]] =
    fetchPage(None).map(Option(_)).recover {
      case NonFatal(ex) =>
        logger.warn(s"Patreon members fetch failed: ${ex.getMessage}")
        None
    }
}
