package com.tibiabot.web

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes, Uri}
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** Fetches a creature sprite from the wiki, for [[CreatureSpriteCache]] to
 *  store. Runs on the bot's host, which is the whole point: it can reach the
 *  wiki even where the people looking at the dashboard cannot.
 */
final class WikiSpriteFetcher(baseUrl: String = WikiSpriteFetcher.DefaultBaseUrl)
                             (implicit system: ActorSystem, ec: ExecutionContext) extends StrictLogging {

  /** `Special:Redirect/file/X.gif` answers with a 302 to the real file, and
   *  akka-http does not follow redirects on its own — left unhandled this would
   *  quietly cache nothing at all, since a 302 is not a success and carries no
   *  image. Bounded so a redirect loop cannot spin. */
  private val MaxRedirects = 4

  /** Sprites are a few KB; anything remotely this large is not one, and the cap
   *  is what stops a bad or hostile response filling the disk. */
  private val MaxBytes = 2L * 1024 * 1024

  private val Timeout = 15.seconds

  def fetch(fileName: String): Future[Option[Array[Byte]]] =
    follow(Uri(s"$baseUrl$fileName"), MaxRedirects)

  private def follow(uri: Uri, redirectsLeft: Int): Future[Option[Array[Byte]]] =
    Http().singleRequest(HttpRequest(uri = uri)).flatMap { response =>
      if (response.status.isRedirection()) {
        response.header[akka.http.scaladsl.model.headers.Location] match {
          case Some(location) if redirectsLeft > 0 =>
            response.discardEntityBytes()
            // Resolved against the current uri, so a relative Location works.
            follow(location.uri.resolvedAgainst(uri), redirectsLeft - 1)
          case Some(_) =>
            response.discardEntityBytes()
            logger.warn(s"Gave up on '$uri': too many redirects")
            Future.successful(None)
          case None =>
            response.discardEntityBytes()
            logger.warn(s"Redirect from '$uri' carried no Location")
            Future.successful(None)
        }
      } else if (response.status == StatusCodes.NotFound) {
        // An ordinary answer, not a fault: plenty of catalogue entries name a
        // creature the wiki has no file for. The cache remembers it so this is
        // not asked again on every page view.
        response.discardEntityBytes()
        Future.successful(None)
      } else if (response.status.isSuccess()) {
        response.entity.withSizeLimit(MaxBytes).toStrict(Timeout)
          .map(strict => Some(strict.getData().toArray))
      } else {
        response.discardEntityBytes()
        logger.warn(s"Sprite fetch for '$uri' returned ${response.status}")
        Future.successful(None)
      }
    }.recoverWith {
      // Failed rather than empty, so the cache treats it as transient and will
      // try again — a network blip must not blank a sprite until a restart.
      case NonFatal(e) =>
        logger.warn(s"Sprite fetch for '$uri' failed: ${e.getMessage}")
        Future.failed(e)
    }
}

object WikiSpriteFetcher {
  /** The same host and path `Urls.creatureImageUrl` already builds for Discord,
   *  so the dashboard caches exactly the files the embeds show. */
  val DefaultBaseUrl: String = "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/"
}
