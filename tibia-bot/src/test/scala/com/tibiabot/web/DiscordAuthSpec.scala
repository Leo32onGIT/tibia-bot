package com.tibiabot.web

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{Cookie, `Set-Cookie`}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Covers the login round-trip's cookie and redirect behaviour, which is all
 *  the browser actually reacts to. Everything here stops short of the token
 *  exchange, so no branch under test talks to Discord: the failure paths bail
 *  out before it, and the success path can't be reached without a real code. */
class DiscordAuthSpec extends AnyFunSuite with Matchers with ScalatestRouteTest {

  /** The app's own config is off-limits here: discord.conf substitutes in
   *  POSTGRES_HOST and friends from the environment and fails to resolve
   *  without them. Akka's reference defaults are all the test actor system
   *  needs. */
  override def testConfig: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.defaultReference()

  private val mountPath = "/dashboard"
  private val adminPath = "/status"

  private def auth = new DiscordAuth(
    clientId = "1234",
    clientSecret = "secret",
    sessionSecret = "session-secret",
    redirectUri = s"https://example.test$mountPath/auth/callback",
    mountPath = mountPath,
    extraCookiePaths = List(adminPath)
  )(system, executor)

  /** The same auth, told what to show a crawler. Separate so every existing
   *  test goes on proving the plain redirect is untouched. */
  private def previewing = new DiscordAuth(
    clientId = "1234",
    clientSecret = "secret",
    sessionSecret = "session-secret",
    redirectUri = s"https://example.test$mountPath/auth/callback",
    mountPath = mountPath,
    extraCookiePaths = List(adminPath),
    linkPreview = Some(LinkPreview.forPath("https://violentbot.xyz"))
  )(system, executor)

  /** How Discord's unfurler actually introduces itself: a browser-shaped string
   *  with its name buried in the middle, which is why the test for it is a
   *  substring search rather than a prefix. */
  private val DiscordUnfurler = "Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)"

  /** A session cookie signed the way the class signs its own, so a test can be
   *  authenticated without going through Discord. */
  private def signedSession(userId: String): String = {
    val payload = s"$userId.${java.time.Instant.now().plusSeconds(3600).getEpochSecond}"
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(new javax.crypto.spec.SecretKeySpec("session-secret".getBytes("UTF-8"), "HmacSHA256"))
    s"$payload.${java.util.Base64.getUrlEncoder.withoutPadding
      .encodeToString(mac.doFinal(payload.getBytes("UTF-8")))}"
  }

  private def routes = pathPrefix("dashboard")(auth.routes)

  /** The nonce the login redirect just minted, read back out of the two places
   *  it has to agree — the authorize URL's `state` and the cookie. */
  private def loginState(): (String, String) = {
    var pair: (String, String) = null
    Get(s"$mountPath/auth/login") ~> routes ~> check {
      status shouldBe StatusCodes.Found
      val location = header("Location").get.value()
      val fromUrl = akka.http.scaladsl.model.Uri(location).query().get("state").get
      val fromCookie = header[`Set-Cookie`].get.cookie
      fromCookie.name shouldBe "vb_oauth_state"
      pair = (fromUrl, fromCookie.value)
    }
    pair
  }

  test("login sends the visitor to Discord with a state nonce matching its cookie") {
    val (fromUrl, fromCookie) = loginState()
    fromUrl shouldBe fromCookie
    fromUrl should not be empty
  }

  test("login mints a fresh nonce each time") {
    loginState()._1 should not be loginState()._1
  }

  test("session and state cookies are SameSite=Lax so they survive the hop back from Discord") {
    // Strict would be withheld on every request in a redirect chain that
    // started cross-site, which is exactly the chain a login is.
    Get(s"$mountPath/auth/login") ~> routes ~> check {
      header[`Set-Cookie`].get.cookie.extension shouldBe Some("SameSite=Lax")
    }
  }

  test("cancelling at Discord's consent screen explains itself instead of erroring") {
    Get(s"$mountPath/auth/callback?error=access_denied") ~> routes ~> check {
      status shouldBe StatusCodes.Forbidden
      responseAs[String] should include("cancelled")
      responseAs[String] should include(s"$mountPath/auth/login")
    }
  }

  test("any other Discord-reported error offers a retry rather than a bare failure") {
    Get(s"$mountPath/auth/callback?error=invalid_scope") ~> routes ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[String] should include(s"$mountPath/auth/login")
    }
  }

  test("a callback whose state does not match the cookie is refused") {
    val (state, _) = loginState()
    Get(s"$mountPath/auth/callback?code=abc&state=$state-tampered") ~>
      Cookie("vb_oauth_state" -> state) ~> routes ~> check {
      status shouldBe StatusCodes.BadRequest
      responseAs[String] should include("expired")
    }
  }

  test("a callback with no state cookie at all is refused") {
    Get(s"$mountPath/auth/callback?code=abc&state=anything") ~> routes ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }

  test("a callback carrying neither code nor error is refused") {
    Get(s"$mountPath/auth/callback") ~> routes ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }

  test("the callback spends the state nonce whatever the outcome") {
    Get(s"$mountPath/auth/callback?error=access_denied") ~>
      Cookie("vb_oauth_state" -> "stale") ~> routes ~> check {
      val cleared = headers.collect { case `Set-Cookie`(c) if c.name == "vb_oauth_state" => c }
      cleared should have size 1
      cleared.head.value shouldBe "deleted"
    }
  }

  test("an unauthenticated visitor to a guarded route is sent to login, and back afterwards") {
    // The return path rides along so somebody who opens a gated area cold is
    // returned there rather than dropped on whichever one happens to be primary.
    val guarded = pathPrefix("dashboard")(path("thing")(auth.authenticatedUser(_ => complete("ok"))))
    Get(s"$mountPath/thing") ~> guarded ~> check {
      status shouldBe StatusCodes.Found
      header("Location").get.value() shouldBe s"$mountPath/auth/login?next=%2Fdashboard"
    }
  }

  // A crawler follows the redirect to Discord's OAuth screen and reports what it
  // finds there — which is how a link to this dashboard came to unfurl as an
  // advert for Discord.
  test("a link crawler is given a page to read instead of the sign-in redirect") {
    val guarded = pathPrefix("dashboard")(path("thing")(previewing.authenticatedUser(_ => complete("ok"))))
    Get(s"$mountPath/thing") ~> addHeader("User-Agent", DiscordUnfurler) ~> guarded ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] should include("""content="Respawn Claims /dashboard" property="og:title"""")
      responseAs[String] should include("this allows you to book much further in advance")
    }
  }

  // The whole point of describing the areas separately: a link to one of them
  // has to unfurl as that one, and the path is the only thing that says which.
  test("the crawler's page is the one for the area it asked about") {
    val guarded = pathPrefix("status")(path("thing")(previewing.authenticatedUser(_ => complete("ok"))))
    Get(s"$adminPath/thing") ~> addHeader("User-Agent", DiscordUnfurler) ~> guarded ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] should include("""content="Violent Bot /status" property="og:title"""")
      responseAs[String] should not include "Respawn Claims"
    }
  }

  test("everybody else still gets the redirect, crawler page or not") {
    val guarded = pathPrefix("dashboard")(path("thing")(previewing.authenticatedUser(_ => complete("ok"))))
    val browser = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126 Safari/537.36"
    List(Some(browser), None).foreach { agent =>
      val request = agent.fold(Get(s"$mountPath/thing"))(a => Get(s"$mountPath/thing") ~> addHeader("User-Agent", a))
      request ~> guarded ~> check {
        withClue(s"$agent: ")(status shouldBe StatusCodes.Found)
      }
    }
  }

  // The preview replaces only the bounce to Discord. Somebody who is signed in
  // must still get the page they asked for, whatever they claim to be.
  test("a signed-in request is served even when it looks like a crawler") {
    val guarded = pathPrefix("dashboard")(path("thing")(previewing.authenticatedUser(id => complete(id))))
    Get(s"$mountPath/thing") ~> addHeader("User-Agent", DiscordUnfurler) ~>
      Cookie("vb_session" -> signedSession("user-1")) ~> guarded ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] shouldBe "user-1"
    }
  }

  test("a visitor to the admin area is sent back there, not to the member one") {
    val guarded = pathPrefix("status")(path("thing")(auth.authenticatedUser(_ => complete("ok"))))
    Get(s"$adminPath/thing") ~> guarded ~> check {
      status shouldBe StatusCodes.Found
      header("Location").get.value() shouldBe s"$mountPath/auth/login?next=%2Fstatus"
    }
  }

  test("login carries the destination inside the state, not as its own parameter") {
    // It has to survive the round-trip through Discord, and it has to be
    // covered by the same comparison the nonce is — otherwise the destination
    // could be swapped without invalidating the nonce.
    Get(s"$mountPath/auth/login?next=%2Fstatus") ~> routes ~> check {
      status shouldBe StatusCodes.Found
      val state = header("Location").get.value().split("state=").last
      state should endWith(".1")
      val cookie = header[`Set-Cookie`].get.cookie
      cookie.value shouldBe state
    }
  }

  // An index into the known paths, so nothing that comes back can be bent into
  // a redirect off our own domain.
  test("an off-site destination is ignored rather than honoured") {
    Get(s"$mountPath/auth/login?next=https%3A%2F%2Fevil.test") ~> routes ~> check {
      status shouldBe StatusCodes.Found
      header("Location").get.value().split("state=").last should endWith(".0")
    }
  }

  test("an unknown destination falls back to the primary mount") {
    Get(s"$mountPath/auth/login?next=%2Fnowhere") ~> routes ~> check {
      header("Location").get.value().split("state=").last should endWith(".0")
    }
  }
}
