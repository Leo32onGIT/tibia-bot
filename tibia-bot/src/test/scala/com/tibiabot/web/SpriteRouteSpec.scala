package com.tibiabot.web

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.`Cache-Control`
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.concurrent.{ExecutionContext, Future}

/** The sprite endpoint end to end: what a browser actually gets back, which is
 *  where the pieces meeting each other can go wrong even though each is tested
 *  on its own. */
class SpriteRouteSpec extends AnyFunSuite with Matchers with ScalatestRouteTest with BeforeAndAfterEach {

  override def testConfig: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.defaultReference()

  private var dir: Path = _
  override def beforeEach(): Unit = dir = Files.createTempDirectory("sprite-route-spec")
  override def afterEach(): Unit =
    if (dir != null && Files.exists(dir))
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))

  private val gif = Array[Byte](71, 73, 70, 56, 57, 97)

  private def auth = new DiscordAuth(
    clientId = "1234", clientSecret = "secret", sessionSecret = "session-secret",
    redirectUri = "https://example.test/dashboard/auth/callback",
    mountPath = "/dashboard", extraCookiePaths = List("/status")
  )(system, executor)

  /** Runs the cache's background fetch on the calling thread. Without this the
   *  fetch really is asynchronous, and "404 now, served next time" becomes a
   *  race against akka's dispatcher — which passed alone and failed under the
   *  full suite's load. */
  private val sameThread: ExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable): Unit = runnable.run()
    def reportFailure(cause: Throwable): Unit = throw cause
  }

  private def routes(fetch: String => Future[Option[Array[Byte]]]) = {
    val a = auth
    val cache = new CreatureSpriteCache(dir, fetch)(sameThread)
    val access = new DashboardAccessService(
      new FakeGateway, _ => false, _ => Nil, _ => "0")
    pathPrefix("dashboard")(new RespawnDashboardRoute(a, access, cache, _ => Nil, (_, _) => None, _ => Nil, NoActions).routes)
  }

  /** No test here posts anything, so the write seam refuses everything. */
  private object NoActions extends RespawnActionPort {
    private val no = Future.successful(RespawnActionPort.Unavailable)
    def claim(guildId: String, userId: String, characterName: String,
              code: String, minutes: Option[Int]): Future[ActionResult] = no
    def release(guildId: String, userId: String, code: Option[String]): Future[ActionResult] = no
    def extend(guildId: String, userId: String, extraMinutes: Int): Future[ActionResult] = no
    def book(guildId: String, userId: String, characterName: String, code: String,
             firstStart: java.time.ZonedDateTime, durationMinutes: Int, daysOfWeek: Int): Future[ActionResult] = no
    def cancelBooking(guildId: String, userId: String, scheduleId: Long): Future[ActionResult] = no
    def bookings(guildId: String, userId: String): List[BookingView] = Nil
    def bookingsOn(guildId: String, code: String): List[BookingView] = Nil
    def calendar(guildId: String, code: String, from: java.time.ZonedDateTime,
                 to: java.time.ZonedDateTime): Option[CalendarView] = None
    def forceLeave(guildId: String, actorId: String, code: String): Future[ActionResult] = no
    def reassign(guildId: String, actorId: String, code: String, toUserId: String): Future[ActionResult] = no
    def grantStamina(guildId: String, actorId: String, targetUserId: String, minutes: Int): Future[ActionResult] = no
    def addSpawn(guildId: String, actorId: String, code: String, region: String,
                 name: String, creature: String): Future[ActionResult] = no
    def removeSpawn(guildId: String, actorId: String, code: String): Future[ActionResult] = no
    def setSpawnMax(guildId: String, actorId: String, code: String,
                    minutes: Option[Int]): Future[ActionResult] = no
    def extendHolder(guildId: String, actorId: String, code: String,
                     extraMinutes: Int): Future[ActionResult] = no
    def dropSlot(guildId: String, actorId: String, code: String,
                 startsAt: java.time.ZonedDateTime): Future[ActionResult] = no
    def reassignSlot(guildId: String, actorId: String, code: String,
                     startsAt: java.time.ZonedDateTime, toUserId: String): Future[ActionResult] = no
    def editSlot(guildId: String, actorId: String, code: String,
                 startsAt: java.time.ZonedDateTime, minutes: Int): Future[ActionResult] = no
  }

  /** Enough gateway to construct the access service; no test here reaches it. */
  private class FakeGateway extends com.tibiabot.discord.DiscordGateway {
    def guildById(id: String): net.dv8tion.jda.api.entities.Guild = null
    def guilds: List[net.dv8tion.jda.api.entities.Guild] = Nil
    def retrieveUser(id: String): net.dv8tion.jda.api.entities.User = null
    def memberAccess(guildId: String, userId: String, channelIds: List[String]) = None
    def selfUserId: String = "self"
    def selfUserName: String = "ViolentBot"
    def selfUserAvatarUrl: String = "https://example.test/a.png"
    def applicationOwnerId: String = "owner"
    def setWatchingActivity(text: String): Unit = ()
  }

  /** A signed session for the routes that need one. Built the same way the real
   *  callback does, so nothing here depends on a login round-trip. */
  private def signedIn = {
    val expiry = java.time.Instant.now().plusSeconds(3600).getEpochSecond
    val payload = s"user-1.$expiry"
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(new javax.crypto.spec.SecretKeySpec("session-secret".getBytes("UTF-8"), "HmacSHA256"))
    val sig = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(payload.getBytes("UTF-8")))
    akka.http.scaladsl.model.headers.Cookie("vb_session", s"$payload.$sig")
  }

  test("a cached sprite is served as a gif") {
    Files.write(dir.resolve("Dragon.gif"), gif)
    Get("/dashboard/sprites/Dragon.gif") ~> signedIn ~> routes(_ => Future.successful(None)) ~> check {
      status shouldBe StatusCodes.OK
      contentType.mediaType shouldBe akka.http.scaladsl.model.MediaTypes.`image/gif`
      responseAs[Array[Byte]].toList shouldBe gif.toList
    }
  }

  test("a served sprite is cacheable, so a board does not re-request dozens per poll") {
    Files.write(dir.resolve("Dragon.gif"), gif)
    Get("/dashboard/sprites/Dragon.gif") ~> signedIn ~> routes(_ => Future.successful(None)) ~> check {
      header[`Cache-Control`].map(_.value()) should contain("public, max-age=2592000")
    }
  }

  test("an uncached sprite 404s now and is fetched for next time") {
    var asked = List.empty[String]
    val fetch = (name: String) => { asked = asked :+ name; Future.successful(Some(gif)) }
    val r = routes(fetch)
    Get("/dashboard/sprites/Dragon.gif") ~> signedIn ~> r ~> check {
      status shouldBe StatusCodes.NotFound
    }
    asked shouldBe List("Dragon.gif")
    // Having been fetched, the next request is served locally.
    Get("/dashboard/sprites/Dragon.gif") ~> signedIn ~> r ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  test("a traversal attempt is refused and never reaches the fetcher") {
    var asked = List.empty[String]
    val fetch = (name: String) => { asked = asked :+ name; Future.successful(Some(gif)) }
    Get("/dashboard/sprites/..%2F..%2Fsecret.gif") ~> signedIn ~> routes(fetch) ~> check {
      status shouldBe StatusCodes.NotFound
    }
    asked shouldBe empty
  }

  test("a sprite is not served to somebody who is not signed in") {
    Files.write(dir.resolve("Dragon.gif"), gif)
    Get("/dashboard/sprites/Dragon.gif") ~> routes(_ => Future.successful(None)) ~> check {
      status shouldBe StatusCodes.Found
      header("Location").get.value() should include("/auth/login")
    }
  }
}
