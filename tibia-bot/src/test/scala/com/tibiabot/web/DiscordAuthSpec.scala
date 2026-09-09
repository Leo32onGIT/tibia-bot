package com.tibiabot.web

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.{Cookie, `Set-Cookie`}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Covers the login round-trip's cookie and redirect behaviour, which is all
 *  the browser actually reacts to. Everything here stops short of the token
 *  exchange, so no branch under test talks to Discord: the failure paths bail
 *  out before it, and the success path can't be reached without a real code. */
class DiscordAuthSpec extends AnyFunSuite with Matchers with ScalatestRouteTest {

  /** The app's own config is off-limits here: discord.conf substitutes in
   *  POSTGRES_HOST and friends from the environment and fails to resolve
   *  without them. Pekko's reference defaults are all the test actor system
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

  /** Enough Redis to be a place things survive a restart in — which is the
   *  whole of what the guild store asks of one. */
  private final class SpecRedis extends com.tibiabot.persistence.RedisCache {
    private val store = scala.collection.concurrent.TrieMap.empty[String, String]
    def get(key: String): scala.concurrent.Future[Option[String]] =
      scala.concurrent.Future.successful(store.get(key))
    def setEx(key: String, value: String, ttl: scala.concurrent.duration.FiniteDuration)
      : scala.concurrent.Future[Unit] = scala.concurrent.Future.successful { store.put(key, value); () }
    def setIfAbsent(key: String, value: String, ttl: scala.concurrent.duration.FiniteDuration)
      : scala.concurrent.Future[Boolean] =
      scala.concurrent.Future.successful(store.putIfAbsent(key, value).isEmpty)
    def delete(key: String): scala.concurrent.Future[Unit] =
      scala.concurrent.Future.successful { store.remove(key); () }
    def keysMatching(pattern: String): scala.concurrent.Future[List[String]] =
      scala.concurrent.Future.successful(Nil)
    def close(): Unit = ()
  }

  /** An auth whose guild list outlives it, as every deployed one has. */
  private def authOn(redis: com.tibiabot.persistence.RedisCache) = new DiscordAuth(
    clientId = "1234",
    clientSecret = "secret",
    sessionSecret = "session-secret",
    redirectUri = s"https://example.test$mountPath/auth/callback",
    mountPath = mountPath,
    extraCookiePaths = List(adminPath),
    userGuildStore = redis
  )(system, executor)

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

  /** The `to` parameter as the bounce writes it, and as a caller crafting a
   *  login link by hand would have to. */
  private def encodeTo(target: String): String =
    java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(target.getBytes("UTF-8"))

  /** The destination read back out of a login redirect. */
  private def decoded(location: String): String =
    new String(java.util.Base64.getUrlDecoder.decode(
      org.apache.pekko.http.scaladsl.model.Uri(location).query().get("to").get), "UTF-8")

  /** The nonce the login redirect just minted, read back out of the two places
   *  it has to agree — the authorize URL's `state` and the cookie. */
  private def loginState(): (String, String) = {
    var pair: (String, String) = null
    Get(s"$mountPath/auth/login") ~> routes ~> check {
      status shouldBe StatusCodes.Found
      val location = header("Location").get.value()
      val fromUrl = org.apache.pekko.http.scaladsl.model.Uri(location).query().get("state").get
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

  test("a restart does not cost a signed-in visitor their sign-in") {
    // The bug this fixes: the cookie is signed rather than stored and survives a
    // deploy, while the guild list behind it lived in this process and did not
    // — so everybody came back authenticated, resolving to no servers, and was
    // bounced through Discord to say so. A new instance sharing the store is
    // exactly what a restarted bot is.
    val redis = new SpecRedis
    val before = authOn(redis)
    before.userGuilds.put("user-1", Set("g1", "g2"))

    val after = authOn(redis)
    after.userGuilds.get("user-1") shouldBe None

    val guarded = pathPrefix("dashboard")(path("thing")(after.authenticatedUser(_ => complete("ok"))))
    Get(s"$mountPath/thing") ~> Cookie("vb_session" -> signedSession("user-1")) ~> guarded ~> check {
      status shouldBe StatusCodes.OK
      // Read back before the route ran, so what it goes on to ask about their
      // servers is answered rather than empty.
      after.userGuilds.get("user-1") shouldBe Some(Set("g1", "g2"))
    }
  }

  test("a visitor the store has never heard of is still sent to sign in") {
    // The store makes a miss rarer, not impossible — an entry does expire, and
    // what happens then has to be the sign-in it always was.
    val after = authOn(new SpecRedis)
    val guarded = pathPrefix("dashboard")(path("thing")(after.authenticatedUser(_ => complete("ok"))))
    Get(s"$mountPath/thing") ~> Cookie("vb_session" -> signedSession("user-2")) ~> guarded ~> check {
      // Authenticated is still authenticated: this directive only answers who,
      // and it is the dashboard that turns an empty list into a login.
      status shouldBe StatusCodes.OK
      after.userGuilds.get("user-2") shouldBe None
    }
  }

  test("an unauthenticated visitor to a guarded route is sent to login, and back afterwards") {
    // Both halves ride along: the area, so somebody who opens a gated area cold
    // is returned there rather than dropped on whichever one happens to be
    // primary — and the page itself, so they are returned to the one they asked
    // for rather than to its front door.
    val guarded = pathPrefix("dashboard")(path("thing")(auth.authenticatedUser(_ => complete("ok"))))
    Get(s"$mountPath/thing") ~> guarded ~> check {
      status shouldBe StatusCodes.Found
      val location = header("Location").get.value()
      location should startWith(s"$mountPath/auth/login?next=%2Fdashboard&to=")
      decoded(location) shouldBe s"$mountPath/thing"
    }
  }

  test("the page a deep link named comes back with its query intact") {
    // The whole point of carrying more than the area: a link from Discord names
    // a guild in the path and a spawn in the query, and either one lost leaves
    // the reader somewhere they have to start again from.
    val guarded = pathPrefix("dashboard")(path("g" / Segment)(_ =>
      auth.authenticatedUser(_ => complete("ok"))))
    Get(s"$mountPath/g/12345?spawn=415") ~> guarded ~> check {
      decoded(header("Location").get.value()) shouldBe s"$mountPath/g/12345?spawn=415"
    }
  }

  test("login carries a checked destination through as a third part of the state") {
    Get(s"$mountPath/auth/login?next=%2Fdashboard&to=${encodeTo(s"$mountPath/g/1?spawn=415")}") ~>
      routes ~> check {
      val state = org.apache.pekko.http.scaladsl.model.Uri(header("Location").get.value()).query().get("state").get
      state.split('.') should have length 3
      new String(java.util.Base64.getUrlDecoder.decode(state.split('.')(2)), "UTF-8") shouldBe
        s"$mountPath/g/1?spawn=415"
    }
  }

  // A login URL's query is public — anyone can hand anyone a `?to=` — so the
  // destination is checked on the way in as well as on the way back, and a bad
  // one leaves the visitor heading for the area's front door as before.
  test("a destination that leaves our own areas is dropped before Discord sees it") {
    val refused = List(
      "https://evil.test/steal",      // an absolute URL
      "//evil.test/steal",            // protocol-relative, which a browser follows off-site
      "/dashboardish/g/1",            // a prefix that only looks like ours
      "/nowhere",                     // real path, not a gated area
      "\\evil.test",              // read as a slash pair by some browsers
      mountPath + "/g/1\nLocation: https://evil.test" // a header split
    )
    refused.foreach { target =>
      withClue(s"$target: ") {
        Get(s"$mountPath/auth/login?to=${encodeTo(target)}") ~> routes ~> check {
          val state = org.apache.pekko.http.scaladsl.model.Uri(header("Location").get.value()).query().get("state").get
          state.split('.') should have length 2
        }
      }
    }
  }

  test("a destination that is not even Base64 is dropped rather than erroring") {
    Get(s"$mountPath/auth/login?to=not-base-64-at-all!!") ~> routes ~> check {
      status shouldBe StatusCodes.Found
      val state = org.apache.pekko.http.scaladsl.model.Uri(header("Location").get.value()).query().get("state").get
      state.split('.') should have length 2
    }
  }

  // The far side of the round trip, which no route test can reach: completing a
  // callback means exchanging a real code with Discord. This is the step that
  // turns the state we minted back into somewhere to send the browser.
  test("the state a login minted comes back as the page it named") {
    val target = s"$mountPath/g/1082484147492237515?spawn=415"
    auth.landingPath(s"nonce.0.${encodeTo(target)}") shouldBe target
  }

  test("a state with no destination in it still lands on its area") {
    auth.landingPath("nonce.1") shouldBe adminPath
    auth.landingPath("nonce.0") shouldBe mountPath
  }

  test("a destination that no longer passes the guard falls back to the area") {
    // Belt and braces: this value cannot arrive here, since it is checked on the
    // way in and covered by the cookie comparison. Checked anyway, because it is
    // the last thing standing between a tampered cookie and an off-site redirect.
    auth.landingPath(s"nonce.1.${encodeTo("https://evil.test")}") shouldBe adminPath
    auth.landingPath("nonce.0.not-base-64!!") shouldBe mountPath
    auth.landingPath("nonsense") shouldBe mountPath
  }

  test("the guard admits our own areas and nothing that reaches outside them") {
    auth.resumableForTest(mountPath) shouldBe true
    auth.resumableForTest(s"$mountPath/g/1?spawn=415") shouldBe true
    // The other mount is gated by the same session, so it is just as much ours.
    auth.resumableForTest(s"$adminPath/whatever") shouldBe true
    auth.resumableForTest(s"$mountPath?x=1") shouldBe true
    auth.resumableForTest(s"${mountPath}ish") shouldBe false
    auth.resumableForTest("//evil.test") shouldBe false
    auth.resumableForTest("https://evil.test") shouldBe false
    auth.resumableForTest("") shouldBe false
  }

  // A crawler follows the redirect to Discord's OAuth screen and reports what it
  // finds there — which is how a link to this dashboard came to unfurl as an
  // advert for Discord.
  test("a link crawler is given a page to read instead of the sign-in redirect") {
    val guarded = pathPrefix("dashboard")(path("thing")(previewing.authenticatedUser(_ => complete("ok"))))
    Get(s"$mountPath/thing") ~> addHeader("User-Agent", DiscordUnfurler) ~> guarded ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] should include("""content="Respawn Claims" property="og:title"""")
      responseAs[String] should include("book much further in advance")
    }
  }

  // The whole point of describing the areas separately: a link to one of them
  // has to unfurl as that one, and the path is the only thing that says which.
  test("the crawler's page is the one for the area it asked about") {
    val guarded = pathPrefix("status")(path("thing")(previewing.authenticatedUser(_ => complete("ok"))))
    Get(s"$adminPath/thing") ~> addHeader("User-Agent", DiscordUnfurler) ~> guarded ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] should include("""content="Admin Panel" property="og:title"""")
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
      val location = header("Location").get.value()
      location should startWith(s"$mountPath/auth/login?next=%2Fstatus&to=")
      // And to the page within it, since the admin area is gated by this same
      // session and so is just as resumable as the member one.
      decoded(location) shouldBe s"$adminPath/thing"
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
