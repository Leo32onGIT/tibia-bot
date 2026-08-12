package com.tibiabot.web

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.tibiabot.discord.{DiscordGateway, MemberAccess}
import net.dv8tion.jda.api.entities.{Guild, User}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import scala.concurrent.{ExecutionContext, Future}

/** The write endpoints as a browser meets them: who is refused, what reaches
 *  the service, and what comes back. */
class ActionRouteSpec extends AnyFunSuite with Matchers with ScalatestRouteTest {

  override def testConfig: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.defaultReference()

  private val sameThread: ExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable): Unit = runnable.run()
    def reportFailure(cause: Throwable): Unit = throw cause
  }

  private val CategoryId = "cat-1"

  private def guildStub(id: String, name: String): Guild =
    java.lang.reflect.Proxy.newProxyInstance(
      classOf[Guild].getClassLoader, Array(classOf[Guild]),
      (_, method, _) => method.getName match {
        case "getId" => id
        case "getName" => name
        // Null is what JDA gives for a guild that never set an icon.
        case "getIconUrl" => null
        case other => throw new UnsupportedOperationException(other)
      }).asInstanceOf[Guild]

  private class FakeGateway(member: Option[MemberAccess]) extends DiscordGateway {
    def guilds: List[Guild] = List(guildStub("g1", "Violent"))
    def guildById(id: String): Guild = if (id == "g1") guildStub("g1", "Violent") else null
    def retrieveUser(id: String): User = null
    def memberAccess(guildId: String, userId: String, channelIds: List[String]): Option[MemberAccess] = member
    def selfUserId: String = "self"
    def selfUserName: String = "ViolentBot"
    def selfUserAvatarUrl: String = "https://example.test/a.png"
    def applicationOwnerId: String = "owner"
    def setWatchingActivity(text: String): Unit = ()
  }

  /** Records what the route passed through, so a test can show the request was
   *  parsed rather than merely accepted. */
  private class RecordingActions(outcome: ActionResult = ActionResult(ok = true, "done")) extends RespawnActionPort {
    private val result = Future.successful(outcome)
    var claims: List[(String, String, String, String, Option[Int])] = Nil
    var releases: List[(String, String, Option[String])] = Nil
    def claim(guildId: String, userId: String, characterName: String,
              code: String, minutes: Option[Int]): Future[ActionResult] = {
      claims = claims :+ (guildId, userId, characterName, code, minutes); result
    }
    def release(guildId: String, userId: String, code: Option[String]): Future[ActionResult] = {
      releases = releases :+ (guildId, userId, code); result
    }
    var moderatorCalls: List[String] = Nil
    def extend(guildId: String, userId: String, extraMinutes: Int): Future[ActionResult] = result
    def book(guildId: String, userId: String, characterName: String, code: String,
             firstStart: java.time.ZonedDateTime, durationMinutes: Int, daysOfWeek: Int): Future[ActionResult] = {
      books = books :+ (code, firstStart.toInstant.toString, durationMinutes, daysOfWeek); result
    }
    var books: List[(String, String, Int, Int)] = Nil
    def cancelBooking(guildId: String, userId: String, scheduleId: Long): Future[ActionResult] = result
    def bookings(guildId: String, userId: String): List[BookingView] = Nil
    def bookingsOn(guildId: String, code: String): List[BookingView] = Nil
    var calendarWindows: List[(String, String)] = Nil
    def calendar(guildId: String, code: String, from: java.time.ZonedDateTime,
                 to: java.time.ZonedDateTime): Option[CalendarView] = {
      calendarWindows = calendarWindows :+ (from.toInstant.toString, to.toInstant.toString)
      if (code == "415") Some(CalendarView("415", "Cult Orcs", "Orc", List(
        CalendarSlot(Some(3L), "user-1", "Bubble", from.plusHours(20), from.plusHours(22),
          "booked", repeats = false, daysOfWeek = 0, predicted = false))))
      else None
    }
    def forceLeave(guildId: String, actorId: String, code: String): Future[ActionResult] = {
      moderatorCalls = moderatorCalls :+ s"forceLeave:$code"; result
    }
    def reassign(guildId: String, actorId: String, code: String, toUserId: String): Future[ActionResult] = {
      moderatorCalls = moderatorCalls :+ s"reassign:$code->$toUserId"; result
    }
    def grantStamina(guildId: String, actorId: String, targetUserId: String, minutes: Int): Future[ActionResult] = {
      moderatorCalls = moderatorCalls :+ s"grant:$targetUserId:$minutes"; result
    }
  }

  private def auth = new DiscordAuth(
    clientId = "1234", clientSecret = "secret", sessionSecret = "session-secret",
    redirectUri = "https://example.test/dashboard/auth/callback",
    mountPath = "/dashboard", extraCookiePaths = List("/status"))(system, executor)

  private val ModRole = "role-mod"

  /** A member who holds the guild's moderator role. */
  private val moderator = Some(MemberAccess(false, Set(ModRole), Set(CategoryId)))

  private def routes(actions: RespawnActionPort,
                     member: Option[MemberAccess] = Some(MemberAccess(false, Set.empty, Set(CategoryId))),
                     moderatorRole: String = "0") = {
    val a = auth
    // The guild list is seeded directly; a real one arrives at login.
    a.userGuilds.put("user-1", Set("g1"))
    val access = new DashboardAccessService(
      new FakeGateway(member),
      respawnConfigured = _ => true,
      worldsOf = _ => List(WorldChannel("Antica", CategoryId)),
      moderatorRoleOf = _ => moderatorRole)
    val cache = new CreatureSpriteCache(Files.createTempDirectory("action-route"), _ => Future.successful(None))(sameThread)
    pathPrefix("dashboard")(new RespawnDashboardRoute(a, access, cache, _ => Nil, (_, _) => None, actions).routes)
  }

  private def signedIn = {
    val expiry = java.time.Instant.now().plusSeconds(3600).getEpochSecond
    val payload = s"user-1.$expiry"
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(new javax.crypto.spec.SecretKeySpec("session-secret".getBytes("UTF-8"), "HmacSHA256"))
    val sig = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(payload.getBytes("UTF-8")))
    akka.http.scaladsl.model.headers.Cookie("vb_session", s"$payload.$sig")
  }

  private def body(json: String) = HttpEntity(ContentTypes.`application/json`, json)

  test("a claim reaches the service with what was asked for") {
    val actions = new RecordingActions
    Post("/dashboard/g/g1/claim", body("""{"code":"415","minutes":180,"character":"Bubble"}""")) ~>
      signedIn ~> routes(actions) ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] should include("done")
    }
    actions.claims shouldBe List(("g1", "user-1", "Bubble", "415", Some(180)))
  }

  test("minutes may arrive as a string, since a form field is text") {
    val actions = new RecordingActions
    Post("/dashboard/g/g1/claim", body("""{"code":"415","minutes":"90"}""")) ~>
      signedIn ~> routes(actions) ~> check { status shouldBe StatusCodes.OK }
    actions.claims.head._5 shouldBe Some(90)
  }

  test("a claim with no duration lets the server pick the default") {
    val actions = new RecordingActions
    Post("/dashboard/g/g1/claim", body("""{"code":"415"}""")) ~>
      signedIn ~> routes(actions) ~> check { status shouldBe StatusCodes.OK }
    actions.claims.head._5 shouldBe None
  }

  test("a claim with no code is refused before reaching the service") {
    val actions = new RecordingActions
    Post("/dashboard/g/g1/claim", body("""{"minutes":60}""")) ~>
      signedIn ~> routes(actions) ~> check {
      status shouldBe StatusCodes.BadRequest
    }
    actions.claims shouldBe empty
  }

  test("a body that is not JSON is refused rather than throwing") {
    val actions = new RecordingActions
    Post("/dashboard/g/g1/claim", body("not json at all")) ~>
      signedIn ~> routes(actions) ~> check { status shouldBe StatusCodes.BadRequest }
    actions.claims shouldBe empty
  }

  test("a release with no code releases whatever the caller holds") {
    val actions = new RecordingActions
    Post("/dashboard/g/g1/release", body("{}")) ~>
      signedIn ~> routes(actions) ~> check { status shouldBe StatusCodes.OK }
    actions.releases shouldBe List(("g1", "user-1", None))
  }

  test("a refusal is still a 200 carrying ok=false, so the page can show why") {
    // The request was well-formed; the answer is simply no. A 4xx would make
    // the page treat a normal outcome as a failure.
    val actions = new RecordingActions(ActionResult(ok = false, "You already hold that."))
    Post("/dashboard/g/g1/release", body("{}")) ~> signedIn ~> routes(actions) ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] should include("\"ok\":false")
      responseAs[String] should include("already hold")
    }
  }

  test("somebody who cannot see any world is refused, and nothing is written") {
    val actions = new RecordingActions
    val blind = Some(MemberAccess(false, Set.empty, Set.empty))
    Post("/dashboard/g/g1/claim", body("""{"code":"415"}""")) ~>
      signedIn ~> routes(actions, member = blind) ~> check {
      status shouldBe StatusCodes.Forbidden
    }
    actions.claims shouldBe empty
  }

  // Knowing a guild id must get you nothing — the check is the authorization.
  test("a guild the caller has no access to is refused, and nothing is written") {
    val actions = new RecordingActions
    Post("/dashboard/g/g-other/claim", body("""{"code":"415"}""")) ~>
      signedIn ~> routes(actions) ~> check { status shouldBe StatusCodes.Forbidden }
    actions.claims shouldBe empty
  }

  test("an unauthenticated post is sent to login and writes nothing") {
    val actions = new RecordingActions
    Post("/dashboard/g/g1/claim", body("""{"code":"415"}""")) ~> routes(actions) ~> check {
      status shouldBe StatusCodes.Found
    }
    actions.claims shouldBe empty
  }

  // The page hides these from a plain member, but that is convenience — this is
  // the control, and it is what stops somebody posting to the endpoint directly.
  // Most polls find nothing has changed, and the answer to those should not
  // cross the network again.
  test("a board that has not changed comes back as 304 with no body") {
    val actions = new RecordingActions
    val r = routes(actions)
    var tag: akka.http.scaladsl.model.headers.EntityTag = null
    Get("/dashboard/g/g1/board") ~> signedIn ~> r ~> check {
      status shouldBe StatusCodes.OK
      tag = header[akka.http.scaladsl.model.headers.ETag]
        .map(_.etag).getOrElse(fail("no ETag on the board"))
    }
    Get("/dashboard/g/g1/board")
      .withHeaders(akka.http.scaladsl.model.headers.`If-None-Match`(tag)) ~> signedIn ~> r ~> check {
      status shouldBe StatusCodes.NotModified
      responseAs[String] shouldBe ""
    }
  }

  test("a stale ETag is answered in full rather than as unchanged") {
    val actions = new RecordingActions
    val stale = akka.http.scaladsl.model.headers.EntityTag("something-else")
    Get("/dashboard/g/g1/board")
      .withHeaders(akka.http.scaladsl.model.headers.`If-None-Match`(stale)) ~>
      signedIn ~> routes(actions) ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  // The catalogue is the half that does not change, so it may be reused for a
  // while without asking at all.
  test("the catalogue is cacheable and carries what a spawn is") {
    val actions = new RecordingActions
    Get("/dashboard/g/g1/catalogue") ~> signedIn ~> routes(actions) ~> check {
      status shouldBe StatusCodes.OK
      header("Cache-Control").map(_.value) should contain("private, max-age=120")
      header("ETag") should not be empty
    }
  }

  test("the calendar hands the asked-for window straight through") {
    val actions = new RecordingActions
    Get("/dashboard/g/g1/slots?code=415&from=2026-08-10T00:00:00Z&to=2026-08-17T00:00:00Z") ~>
      signedIn ~> routes(actions) ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] should include("\"code\":\"415\"")
      // Whose block it is has to survive the round trip: the page draws a
      // cancel button off exactly this.
      responseAs[String] should include("\"mine\":true")
    }
    actions.calendarWindows shouldBe List(("2026-08-10T00:00:00Z", "2026-08-17T00:00:00Z"))
  }

  // The window comes off the query string, so a hand-edited one has to be
  // refused rather than quietly turned into some other week.
  test("a calendar window that is nonsense is refused before the service sees it") {
    val actions = new RecordingActions
    val bad = List(
      "?code=415&from=whenever&to=2026-08-17T00:00:00Z",
      "?code=415&from=2026-08-17T00:00:00Z&to=2026-08-10T00:00:00Z",
      "?code=415&from=2026-08-10T00:00:00Z&to=2027-08-10T00:00:00Z")
    val r = routes(actions)
    bad.foreach { query =>
      Get(s"/dashboard/g/g1/slots$query") ~> signedIn ~> r ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
    actions.calendarWindows shouldBe empty
  }

  test("a calendar for a spawn that does not exist says so rather than drawing an empty week") {
    val actions = new RecordingActions
    Get("/dashboard/g/g1/slots?code=999&from=2026-08-10T00:00:00Z&to=2026-08-17T00:00:00Z") ~>
      signedIn ~> routes(actions) ~> check { status shouldBe StatusCodes.BadRequest }
  }

  test("a guild the visitor cannot see has no calendar either") {
    val actions = new RecordingActions
    val blind = Some(MemberAccess(false, Set.empty, Set.empty))
    Get("/dashboard/g/g1/slots?code=415&from=2026-08-10T00:00:00Z&to=2026-08-17T00:00:00Z") ~>
      signedIn ~> routes(actions, member = blind) ~> check {
      status shouldBe StatusCodes.Forbidden
    }
    actions.calendarWindows shouldBe empty
  }

  test("a plain member is refused every moderator tool, and nothing is written") {
    val actions = new RecordingActions
    val r = routes(actions)
    List(
      ("/dashboard/g/g1/force-leave", """{"code":"415"}"""),
      ("/dashboard/g/g1/reassign", """{"code":"415","toUserId":"u9"}"""),
      ("/dashboard/g/g1/grant-stamina", """{"userId":"u9","minutes":60}""")
    ).foreach { case (path, payload) =>
      Post(path, body(payload)) ~> signedIn ~> r ~> check {
        withClue(s"$path: ")(status shouldBe StatusCodes.Forbidden)
      }
    }
    actions.moderatorCalls shouldBe empty
  }

  test("a moderator may use them") {
    val actions = new RecordingActions
    val r = routes(actions, member = moderator, moderatorRole = ModRole)
    Post("/dashboard/g/g1/force-leave", body("""{"code":"415"}""")) ~> signedIn ~> r ~> check {
      status shouldBe StatusCodes.OK
    }
    Post("/dashboard/g/g1/reassign", body("""{"code":"415","toUserId":"u9"}""")) ~> signedIn ~> r ~> check {
      status shouldBe StatusCodes.OK
    }
    Post("/dashboard/g/g1/grant-stamina", body("""{"userId":"u9","minutes":60}""")) ~> signedIn ~> r ~> check {
      status shouldBe StatusCodes.OK
    }
    actions.moderatorCalls shouldBe List("forceLeave:415", "reassign:415->u9", "grant:u9:60")
  }

  test("a booking is parsed into an instant, a length and a weekday mask") {
    val actions = new RecordingActions
    Post("/dashboard/g/g1/book",
      body("""{"code":"415","startsAt":"2026-08-09T19:00:00Z","minutes":120,"days":5}""")) ~>
      signedIn ~> routes(actions) ~> check { status shouldBe StatusCodes.OK }
    actions.books shouldBe List(("415", "2026-08-09T19:00:00Z", 120, 5))
  }

  test("a booking with no days is a one-off rather than every day") {
    // Defaulting to EveryDay would silently turn a single evening into a
    // standing weekly commitment.
    val actions = new RecordingActions
    Post("/dashboard/g/g1/book",
      body("""{"code":"415","startsAt":"2026-08-09T19:00:00Z","minutes":120}""")) ~>
      signedIn ~> routes(actions) ~> check { status shouldBe StatusCodes.OK }
    actions.books.head._4 shouldBe com.tibiabot.domain.RespawnSchedule.OneOff
  }

  test("a booking with an unparseable start is refused before reaching the service") {
    val actions = new RecordingActions
    Post("/dashboard/g/g1/book",
      body("""{"code":"415","startsAt":"next tuesday","minutes":120}""")) ~>
      signedIn ~> routes(actions) ~> check { status shouldBe StatusCodes.BadRequest }
    actions.books shouldBe empty
  }

  test("a nonsense weekday mask is ignored rather than trusted") {
    val actions = new RecordingActions
    Post("/dashboard/g/g1/book",
      body("""{"code":"415","startsAt":"2026-08-09T19:00:00Z","minutes":120,"days":9999}""")) ~>
      signedIn ~> routes(actions) ~> check { status shouldBe StatusCodes.OK }
    actions.books.head._4 shouldBe com.tibiabot.domain.RespawnSchedule.OneOff
  }

  test("GET is not a way to perform a write") {
    val actions = new RecordingActions
    Get("/dashboard/g/g1/claim") ~> signedIn ~> routes(actions) ~> check {
      handled shouldBe false
    }
    actions.claims shouldBe empty
  }
}
