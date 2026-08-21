package com.tibiabot.web

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.HttpCookie
import akka.http.scaladsl.server.{Directive, Directive0, StandardRoute}
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
final class DiscordAuth(clientId: String, clientSecret: String, sessionSecret: String, redirectUri: String,
                        mountPath: String, extraCookiePaths: List[String] = Nil,
                        /** Whether the session and state cookies are marked
                         *  `Secure`. True everywhere this is deployed; false
                         *  only for a local run over a plain-HTTP port forward,
                         *  where a `Secure` cookie is dropped by the browser and
                         *  the login loops. Decided from the origin rather than
                         *  set by hand — see `Config.Web.secureCookies`. */
                        secureCookies: Boolean = true,
                        /** A page to answer a link crawler with, rather than
                         *  bouncing it to Discord's OAuth screen and letting it
                         *  describe this link as Discord — see [[LinkPreview]].
                         *
                         *  A function of the path that was asked for, not a
                         *  fixed page: the areas behind this auth are different
                         *  things to whoever is shown the card, and only the
                         *  caller knows what each of them is called. None keeps
                         *  the old behaviour for every caller. */
                        linkPreview: Option[String => String] = None)
  (implicit system: ActorSystem, ex: ExecutionContextExecutor) extends StrictLogging {

  /** Every area this session is good for. `mountPath` is where the auth routes
   *  themselves live and where a bare login lands; the rest are other gated
   *  areas on the same domain that should not demand a second sign-in.
   *
   *  They get one cookie each rather than one cookie at `/`, which would be the
   *  obvious shortcut and is the one thing that must not happen here: this
   *  domain also proxies an unrelated landing page to GitHub Pages, so a
   *  root-scoped session cookie would be handed to a third party on every visit
   *  to the front page. A response may set the same cookie under several paths,
   *  so one login still covers them all. */
  private val cookiePaths: List[String] = (mountPath :: extraCookiePaths).distinct

  private val cookieName = "vb_session"
  private val sessionTtl = 7.days
  private val loginPath = s"$mountPath/auth/login"

  /** `guilds` is asked for on top of `identify` so the respawn dashboard can
   *  narrow the bot's several hundred guilds down to the handful this visitor
   *  is actually in. The bot cannot answer that itself: resolving it through
   *  JDA would need the privileged GUILD_MEMBERS intent, which this bot
   *  deliberately avoids (see PaywallService's note on Discord's verification
   *  threshold past 100 guilds), and checking membership guild by guild would
   *  be one REST call per guild per sign-in.
   *
   *  Widening the scope invalidates consent, so every existing session has to
   *  sign in again once — including the owner's. */
  private val scope = "identify guilds"

  /** Guild ids from the login, kept only long enough to save asking again on
   *  every request. Not a permission — see [[UserGuildCache]]. */
  val userGuilds: UserGuildCache = new UserGuildCache(sessionTtl)

  /** Short-lived companion to the session cookie, holding the OAuth `state`
   *  nonce for exactly as long as one login round-trip: set when we send the
   *  visitor to Discord, compared against the `state` echoed back on the
   *  callback, then deleted. Without it the callback would accept an
   *  authorization code from anywhere, letting an attacker land their own
   *  Discord identity in a victim's browser session (login CSRF). Ten minutes
   *  is long enough to read a consent screen and short enough that an
   *  abandoned attempt doesn't linger. */
  private val stateCookieName = "vb_oauth_state"
  private val stateTtl = 10.minutes
  private val secureRandom = new java.security.SecureRandom()

  /** How much of a deep link may ride through the sign-in round trip. Ample for
   *  anything the bot itself builds, and a bound on what a hand-made login link
   *  can put into the `state` we hand Discord. */
  private val MaxResumeLength = 512

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

  private def authorizeUrl(state: String): String = {
    val encodedRedirect = URLEncoder.encode(redirectUri, "UTF-8")
    val encodedState = URLEncoder.encode(state, "UTF-8")
    val encodedScope = URLEncoder.encode(scope, "UTF-8")
    s"https://discord.com/api/oauth2/authorize?client_id=$clientId&redirect_uri=$encodedRedirect&response_type=code&scope=$encodedScope&state=$encodedState"
  }

  private def newState(): String = {
    val bytes = new Array[Byte](32)
    secureRandom.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

  private def stateMatches(fromCookie: Option[String], fromQuery: Option[String]): Boolean =
    (fromCookie, fromQuery) match {
      case (Some(expected), Some(actual)) =>
        java.security.MessageDigest.isEqual(expected.getBytes("UTF-8"), actual.getBytes("UTF-8"))
      case _ => false
    }

  private def sessionCookie(value: String, path: String): HttpCookie = HttpCookie(
    name = cookieName,
    value = value,
    path = Some(path),
    httpOnly = true,
    secure = secureCookies,
    maxAge = Some(sessionTtl.toSeconds),
    // Lax, not Strict: the request right after the callback is the browser
    // following our redirect to `mountPath`, still part of the redirect chain
    // that began on discord.com. Browsers treat every hop of a cross-site
    // chain as cross-site, so a Strict cookie would be withheld there — the
    // dashboard would see no session, bounce back to login, and loop through
    // Discord forever. Lax is sent on top-level GET navigations, which is
    // exactly that hop, and still withholds the cookie from cross-site POSTs
    // (the seat-admin endpoints).
    extension = Some("SameSite=Lax")
  )

  /** Same Lax reasoning as the session cookie, and for the same reason it is
   *  load-bearing here: the callback that reads this one *is* the cross-site
   *  hop from discord.com, so under Strict there would be nothing to compare
   *  the echoed `state` against and every login would fail. */
  private def stateCookie(value: String): HttpCookie = HttpCookie(
    name = stateCookieName,
    value = value,
    path = Some(mountPath),
    httpOnly = true,
    secure = secureCookies,
    maxAge = Some(stateTtl.toSeconds),
    extension = Some("SameSite=Lax")
  )

  /** A dead end the visitor can act on rather than a bare status line: every
   *  way a login can fail short of a server fault (they cancelled, the attempt
   *  went stale, Discord refused) ends here, with the one useful next step.
   *  `message` is ours, never echoed from the query string — an attacker-supplied
   *  `error` would otherwise be HTML injection.
   *
   *  Self-contained rather than served through the dashboard's shell, because
   *  this class is mounted by more than one area and cannot assume the respawn
   *  page's stylesheet is there. The card is therefore a deliberate copy of it
   *  — same palette, same mono heading, same blurple sign-in control — so a
   *  login that failed does not look like it belongs to a different product than
   *  the page the visitor was heading for. Change one and change the other. */
  private def loginProblem(status: StatusCode, message: String): StandardRoute =
    complete(status, HttpEntity(ContentTypes.`text/html(UTF-8)`,
      s"""<!doctype html>
         |<html lang="en"><head><meta charset="utf-8">
         |<meta name="viewport" content="width=device-width, initial-scale=1">
         |<title>Violent Bot - Sign in</title>
         |<link rel="icon" type="image/png" href="/dashboard/images/avatar.png">
         |<style>
         |  * { box-sizing: border-box; }
         |  body {
         |    margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
         |    padding: 24px; background: #0b0d12; color: #d7dce3; font-size: 14px;
         |    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
         |    background-image:
         |      linear-gradient(rgba(255,255,255,0.014) 1px, transparent 1px),
         |      linear-gradient(90deg, rgba(255,255,255,0.014) 1px, transparent 1px);
         |    background-size: 46px 46px;
         |  }
         |  .card {
         |    background: #12151c; border: 1px solid #1f2430; border-radius: 10px;
         |    padding: 30px 32px; width: 100%; max-width: 27rem; text-align: center;
         |  }
         |  .face { width: 46px; height: 46px; border-radius: 12px; margin-bottom: 20px; }
         |  h1 {
         |    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
         |    font-size: 20px; font-weight: 600; letter-spacing: -0.01em; color: #e8ebf0; margin: 0 0 10px;
         |  }
         |  p { color: #7c8698; font-size: 13.5px; line-height: 1.65; margin: 0; }
         |  a.btn {
         |    display: flex; align-items: center; justify-content: center; gap: 9px;
         |    margin-top: 20px; padding: 10px 18px; border-radius: 7px;
         |    background: #5865f2; color: #fff; font-size: 13px; font-weight: 600; text-decoration: none;
         |  }
         |  a.btn:hover { background: #6b77f5; }
         |  a.btn:focus-visible { outline: 2px solid #5b8cff; outline-offset: 2px; }
         |</style></head>
         |<body>
         |  <div class="card">
         |    <img class="face" src="/dashboard/images/avatar.png" alt="">
         |    <h1>That didn't go through</h1>
         |    <p>$message</p>
         |    <a class="btn" href="$loginPath">Continue with Discord</a>
         |  </div>
         |</body></html>""".stripMargin))

  /** The visitor's guild ids, read once with the token we already hold.
   *
   *  Never fails the login: somebody who signs in but whose guild list could
   *  not be fetched is still authenticated, and an empty set simply means the
   *  dashboard shows them nothing until they sign in again. Refusing the whole
   *  login over it would lock the owner out of the admin dashboard, which does
   *  not use guilds at all. */
  private def fetchGuildIds(accessToken: String): Future[Set[String]] = {
    val request = HttpRequest(
      uri = "https://discord.com/api/users/@me/guilds",
      headers = List(akka.http.scaladsl.model.headers.Authorization(
        akka.http.scaladsl.model.headers.OAuth2BearerToken(accessToken)))
    )
    Http().singleRequest(request).flatMap { response =>
      if (response.status.isSuccess()) {
        Unmarshal(response.entity).to[String].map { body =>
          Try(body.parseJson.convertTo[List[JsValue]].flatMap(
            _.asJsObject.fields.get("id").collect { case JsString(id) => id }).toSet)
            .getOrElse {
              logger.warn("Discord guild list was not the expected shape")
              Set.empty[String]
            }
        }
      } else {
        response.discardEntityBytes()
        logger.warn(s"Failed to read the visitor's guild list: ${response.status}")
        Future.successful(Set.empty[String])
      }
    }.recover {
      case ex: Throwable =>
        logger.warn(s"Failed to read the visitor's guild list: ${ex.getMessage}")
        Set.empty[String]
    }
  }

  /** Exchanges an OAuth `code` for the authenticated Discord user's id and the
   *  guilds they belong to, via the token endpoint then `/users/@me` and
   *  `/users/@me/guilds`. None on any failure of the first two (bad code,
   *  network, malformed response) — the callback route treats that as a failed
   *  login. The access token is used here and then dropped: nothing stores it,
   *  because nothing needs it after this point. */
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
                  Unmarshal(userResponse.entity).to[String].flatMap { userBody =>
                    Try(userBody.parseJson.asJsObject.fields("id").convertTo[String]).toOption match {
                      case Some(userId) =>
                        // Remembered against the id we just resolved, while the
                        // token is still in scope — it is discarded with this
                        // closure and never persisted.
                        fetchGuildIds(accessToken).map { guildIds =>
                          userGuilds.put(userId, guildIds)
                          Some(userId)
                        }
                      case None => Future.successful(None)
                    }
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
        case None =>
          // A link unfurler gets a page rather than the redirect. It cannot hold
          // a session, so following the bounce only lands it on discord.com,
          // whose tags it then reports as this link's — which is why a dashboard
          // link embedded as an advert for Discord. Answered before the redirect
          // and only for something that says it is a crawler; everybody else
          // takes the branch below exactly as before.
          optionalHeaderValueByName("User-Agent") { agent =>
            // Which area was being asked for, wanted by both branches: the
            // crawler is told what is behind *this* link rather than what the
            // site is, and somebody who opens the admin dashboard cold is
            // returned there rather than dropped on the member one. Matched
            // against the known paths on the way back, never used as given.
            extractMatchedPath { matched =>
              linkPreview.filter(_ => agent.exists(LinkPreview.isCrawler)) match {
                case Some(preview) =>
                  complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, preview(matched.toString)))
                case None =>
                  // The whole request, not just the path it matched: a link from
                  // Discord names its spawn in the query, and the area alone
                  // would come back having forgotten which one.
                  extractUri(uri => redirect(loginUrlFor(uri), StatusCodes.Found))
              }
            }
          }
      }
    }
  }

  /** Sets the session for every gated area in one response, so a single login
   *  covers them all without a root-scoped cookie. */
  private def setSession(value: String): Directive0 =
    cookiePaths.map(p => sessionCookie(value, p)) match {
      case first :: rest => setCookie(first, rest: _*)
      case Nil           => pass
    }

  /** Which area to land on after signing in, as an index into [[cookiePaths]].
   *
   *  An index rather than the path itself: it rides through the OAuth
   *  round-trip inside the `state` value, and a bare number cannot be bent into
   *  an off-site redirect however it comes back. Anything unrecognised falls
   *  back to the primary mount, so a stale or hand-edited link lands somewhere
   *  real instead of erroring. */
  private def landingIndex(next: Option[String]): Int =
    next.map(cookiePaths.indexOf).filter(_ >= 0).getOrElse(0)

  private def areaAt(index: String): String = Try(cookiePaths(index.toInt)).getOrElse(mountPath)

  /** Where to land after signing in: the exact page that was asked for when the
   *  state carries one, the area's front door otherwise.
   *
   *  The index above answers "which area", which is all a login needed while
   *  every gated area had one page worth landing on. A link from Discord names a
   *  guild and a spawn, and an area is not a useful answer to it — somebody
   *  pressing Dashboard on a spawn's panel and being handed the server picker
   *  has been given a chore instead of a page.
   */
  private[web] def landingPath(state: String): String =
    state.split('.') match {
      case Array(_, index, resume) => decodeResume(resume).getOrElse(areaAt(index))
      case Array(_, index)         => areaAt(index)
      case _                       => mountPath
    }

  /** The sign-in to send somebody to, carrying where they were going.
   *
   *  Used by [[authenticatedUser]]'s own bounce, and offered to callers because
   *  a page can find a session wanting for reasons this class cannot see. The
   *  respawn dashboard's board page is the one that does: the guild list behind
   *  a session lives in memory and does not survive a restart, while the cookie
   *  it belongs to lasts a week — so a visitor can arrive perfectly signed in
   *  and still resolve to nothing. Signing in again is the whole fix, and going
   *  through here is what stops the spawn they were opening being the price of
   *  it.
   */
  def loginUrlFor(uri: Uri): String = {
    val area = cookiePaths.find(p => uri.path.toString.startsWith(p))
    val params =
      area.map(p => s"next=${URLEncoder.encode(p, "UTF-8")}").toList ++
        resumeTarget(uri).map(target => s"to=${encodeResume(target)}")
    if (params.isEmpty) loginPath else s"$loginPath?${params.mkString("&")}"
  }

  /** The page an unauthenticated visitor was actually asking for, when it is one
   *  worth coming back to. Path and query, relative — never a whole URL. */
  private def resumeTarget(uri: Uri): Option[String] = {
    val target = uri.path.toString + uri.rawQueryString.fold("")("?" + _)
    Some(target).filter(t => t.length <= MaxResumeLength && isOurs(t))
  }

  /** Whether a string is a relative path into one of this auth's own areas, and
   *  so somewhere a redirect of ours may send a browser.
   *
   *  This is the whole of the open-redirect guard, so it is a whitelist and not
   *  a hunt for the ways out. A login URL's query is public — anybody can hand
   *  anybody a `?to=`, and the value rides the round trip in a cookie we set
   *  ourselves — which makes the check on the way *back* the one that matters.
   *  Hence [[decodeResume]] applying this again to what it decodes rather than
   *  trusting that it was checked on the way in.
   *
   *  A value that passes starts with a known mount and continues at a boundary,
   *  so `/dashboardish` is not `/dashboard`; and holds nothing that turns a
   *  relative path into an absolute one — `//host` is a protocol-relative URL, a
   *  backslash is read as one by some browsers, and a control character can end
   *  the `Location` header early and start another.
   */
  private def isOurs(target: String): Boolean =
    !target.contains("//") && !target.contains('\\') && !target.exists(_.isControl) &&
      cookiePaths.exists(p => target == p || target.startsWith(s"$p/") || target.startsWith(s"$p?"))

  /** Base64url, so the destination survives inside a dot-separated `state` and
   *  needs no further escaping in a query string of its own — the alphabet is
   *  `A-Za-z0-9-_` and carries neither a dot nor anything a URL minds. */
  private def encodeResume(target: String): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(target.getBytes("UTF-8"))

  /** The reverse, refusing anything that is not one of ours — including
   *  something that is not Base64 at all, which a truncated or hand-edited state
   *  will produce. None everywhere, so the caller falls back to the area. */
  private def decodeResume(encoded: String): Option[String] =
    Try(new String(Base64.getUrlDecoder.decode(encoded), "UTF-8")).toOption.filter(isOurs)

  /** Exposed for [[DiscordAuthSpec]]: the guard above is the security-critical
   *  half of this class and is otherwise reachable only through a round trip
   *  that ends at Discord. */
  private[web] def resumableForTest(target: String): Boolean = isOurs(target)

  val routes: akka.http.scaladsl.server.Route =
    path("auth" / "login") {
      get {
        parameter("next".optional, "to".optional) { (next, to) =>
          // The nonce and the destination travel together, so the comparison on
          // the way back still covers both and neither can be swapped alone.
          //
          // `to` is checked here as well as on the way back, so a login link
          // carrying somewhere it should not go is refused before it reaches
          // Discord rather than after — the visitor is bounced to the area's
          // front door, which is where they would have landed anyway.
          val resume = to.flatMap(decodeResume).map(encodeResume)
          val state = s"${newState()}.${landingIndex(next)}${resume.fold("")("." + _)}"
          setCookie(stateCookie(state)) {
            redirect(authorizeUrl(state), StatusCodes.Found)
          }
        }
      }
    } ~
    path("auth" / "callback") {
      get {
        // The nonce is spent the moment we look at it, whatever the outcome —
        // clearing it on every branch keeps a stale one from being replayed and
        // stops a failed attempt from poisoning the next login.
        deleteCookie(stateCookieName, path = mountPath) {
          optionalCookie(stateCookieName) { stateCookieOpt =>
            parameterMap { params =>
              val stateOk = stateMatches(stateCookieOpt.map(_.value), params.get("state"))
              (params.get("error"), params.get("code")) match {
                // Discord reports a refusal in the query string rather than by
                // withholding the callback, so this is the ordinary "user hit
                // Cancel" path, not an error worth logging loudly.
                case (Some("access_denied"), _) =>
                  loginProblem(StatusCodes.Forbidden, "You cancelled the Discord sign-in.")
                case (Some(error), _) =>
                  logger.warn(s"Discord OAuth returned an error: $error")
                  loginProblem(StatusCodes.BadRequest, "Discord turned down the sign-in request.")
                case (None, Some(_)) if !stateOk =>
                  // Either a genuinely forged callback or, far more often, a
                  // stale tab whose nonce cookie has since expired. The visitor
                  // can't tell the difference and neither can we, so say the
                  // benign thing and let them start over.
                  logger.warn("Discord OAuth callback rejected: state parameter did not match the login cookie")
                  loginProblem(StatusCodes.BadRequest, "That sign-in link has expired. Please start again.")
                case (None, Some(code)) =>
                  onComplete(resolveUserId(code)) {
                    case Success(Some(userId)) =>
                      val expiry = Instant.now().plusSeconds(sessionTtl.toSeconds).getEpochSecond
                      // Back to whichever page sent them, which the state
                      // carried and which has just been verified against the
                      // cookie — and which is checked against `isOurs` once
                      // more as it is decoded, so it can only ever be one of
                      // ours however it got here.
                      val landing: String = stateCookieOpt.map(c => landingPath(c.value)).getOrElse(mountPath)
                      setSession(signSession(userId, expiry)) {
                        redirect(landing, StatusCodes.Found)
                      }
                    case Success(None) =>
                      loginProblem(StatusCodes.Unauthorized, "Discord sign-in failed.")
                    case Failure(ex) =>
                      logger.error(s"Discord OAuth callback failed: ${ex.getMessage}")
                      loginProblem(StatusCodes.InternalServerError, "Something went wrong signing you in.")
                  }
                case (None, None) =>
                  loginProblem(StatusCodes.BadRequest, "That sign-in link is incomplete. Please start again.")
              }
            }
          }
        }
      }
    }
}
