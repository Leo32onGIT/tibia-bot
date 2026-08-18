package com.tibiabot.web

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.tibiabot.discord.{DiscordGateway, MemberAccess}
import net.dv8tion.jda.api.entities.{Guild, User}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

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
        CalendarSlot(Some(3L), "user-1", "Bubble", "violentbeams", "",
          from.plusHours(20), from.plusHours(22),
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
    def addSpawn(guildId: String, actorId: String, code: String, region: String,
                 name: String, creature: String): Future[ActionResult] = {
      moderatorCalls = moderatorCalls :+ s"add:$code:$region:$name:$creature"; result
    }
    def removeSpawn(guildId: String, actorId: String, code: String): Future[ActionResult] = {
      moderatorCalls = moderatorCalls :+ s"remove:$code"; result
    }
    def setSpawnMax(guildId: String, actorId: String, code: String,
                    minutes: Option[Int]): Future[ActionResult] = {
      // "clear" rather than an empty string, so a test can tell an override being
      // removed from one being set to nothing — which the endpoint refuses.
      moderatorCalls = moderatorCalls :+ s"spawnmax:$code:${minutes.fold("clear")(_.toString)}"
      result
    }
    def extendHolder(guildId: String, actorId: String, code: String,
                     extraMinutes: Int): Future[ActionResult] = {
      moderatorCalls = moderatorCalls :+ s"extend:$code:$extraMinutes"; result
    }
    def dropSlot(guildId: String, actorId: String, code: String,
                 startsAt: java.time.ZonedDateTime): Future[ActionResult] = {
      moderatorCalls = moderatorCalls :+ s"drop:$code:${startsAt.toInstant}"; result
    }
    def reassignSlot(guildId: String, actorId: String, code: String,
                     startsAt: java.time.ZonedDateTime, toUserId: String): Future[ActionResult] = {
      moderatorCalls = moderatorCalls :+ s"move:$code:${startsAt.toInstant}:$toUserId"; result
    }
  }

  private def auth = new DiscordAuth(
    clientId = "1234", clientSecret = "secret", sessionSecret = "session-secret",
    redirectUri = "https://example.test/dashboard/auth/callback",
    mountPath = "/dashboard", extraCookiePaths = List("/status"))(system, executor)

  private val ModRole = "role-mod"

  /** A member who holds the guild's moderator role. */
  private val moderator = Some(MemberAccess(false, Set(ModRole), Set(CategoryId)))

  /** Two people the guild's respawn system knows, for the stamina picker. */
  private val people = List(
    com.tibiabot.persistence.KnownMember("user-1", "violentbeams", "Violent Beams"),
    com.tibiabot.persistence.KnownMember("user-9", "someone", ""))

  private def routes(actions: RespawnActionPort,
                     member: Option[MemberAccess] = Some(MemberAccess(false, Set.empty, Set(CategoryId))),
                     moderatorRole: String = "0",
                     theirGuilds: Set[String] = Set("g1")) = {
    val a = auth
    // The guild list is seeded directly; a real one arrives at login.
    a.userGuilds.put("user-1", theirGuilds)
    val access = new DashboardAccessService(
      new FakeGateway(member),
      respawnConfigured = _ => true,
      worldsOf = _ => List(WorldChannel("Antica", CategoryId)),
      moderatorRoleOf = _ => moderatorRole)
    val cache = new CreatureSpriteCache(Files.createTempDirectory("action-route"), _ => Future.successful(None))(sameThread)
    pathPrefix("dashboard")(new RespawnDashboardRoute(a, access, cache, _ => Nil, (_, _) => None, _ => people, actions).routes)
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

  /* A guild another bot runs. This one is not in it — `guildById` answers null
   * for anything but g1 — so it has no way to read a member of it, and the write
   * goes to the bot that can rather than being refused for want of an answer.
   * That refusal is the bug: it turned "the other bot was half a second late"
   * into a permission error for somebody with every right to what they asked. */
  test("a write into a guild this bot is not in is carried, not decided here") {
    val actions = new RecordingActions
    Post("/dashboard/g/g2/claim", body("""{"code":"415"}""")) ~>
      signedIn ~> routes(actions, theirGuilds = Set("g1", "g2")) ~> check {
      status shouldBe StatusCodes.OK
    }
    actions.claims.map(_._1) shouldBe List("g2")
  }

  // Including the ones that act on other people. Whether they hold the role
  // there is a question only the bot in the guild can answer, and it does —
  // see RespawnCommandConsumer.
  test("a moderator write into a guild this bot is not in is carried too") {
    val actions = new RecordingActions
    Post("/dashboard/g/g2/force-leave", body("""{"code":"415"}""")) ~>
      signedIn ~> routes(actions, theirGuilds = Set("g1", "g2")) ~> check {
      status shouldBe StatusCodes.OK
    }
    actions.moderatorCalls shouldBe List("forceLeave:415")
  }

  // The one thing this end can still settle: their own Discord login says which
  // guilds they are in, and a guild they merely named is not one of them.
  test("a write into a guild the visitor is not in is refused, in words the page can read") {
    val actions = new RecordingActions
    Post("/dashboard/g/g9/claim", body("""{"code":"415"}""")) ~>
      signedIn ~> routes(actions, theirGuilds = Set("g1", "g2")) ~> check {
      status shouldBe StatusCodes.Forbidden
      // JSON, not a bare "Forbidden": the page reads every answer with
      // res.json(), and a plain-text refusal came out as "That did not go
      // through" whatever had actually happened.
      contentType shouldBe ContentTypes.`application/json`
      responseAs[String] should include(""""ok":false""")
    }
    actions.claims shouldBe empty
  }

  test("a plain member is refused every moderator tool, and nothing is written") {
    val actions = new RecordingActions
    val r = routes(actions)
    List(
      ("/dashboard/g/g1/force-leave", """{"code":"415"}"""),
      ("/dashboard/g/g1/reassign", """{"code":"415","toUserId":"u9"}"""),
      ("/dashboard/g/g1/grant-stamina", """{"userId":"u9","minutes":60}"""),
      ("/dashboard/g/g1/spawns", """{"code":"999","name":"Somewhere"}"""),
      ("/dashboard/g/g1/remove-spawn", """{"code":"999"}"""),
      ("/dashboard/g/g1/spawn-max", """{"code":"415","minutes":60}"""),
      ("/dashboard/g/g1/extend-holder", """{"code":"415","minutes":30}"""),
      ("/dashboard/g/g1/drop-slot", """{"code":"415","startsAt":"2026-08-13T11:00:00Z"}"""),
      ("/dashboard/g/g1/reassign-slot",
        """{"code":"415","startsAt":"2026-08-13T11:00:00Z","toUserId":"u9"}""")
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
    Post("/dashboard/g/g1/spawns",
      body("""{"code":"999","name":"Deep Cave","region":"Edron","creature":"Orc Warlord"}""")) ~>
      signedIn ~> r ~> check { status shouldBe StatusCodes.OK }
    Post("/dashboard/g/g1/remove-spawn", body("""{"code":"999"}""")) ~>
      signedIn ~> r ~> check { status shouldBe StatusCodes.OK }
    Post("/dashboard/g/g1/spawn-max", body("""{"code":"415","minutes":60}""")) ~>
      signedIn ~> r ~> check { status shouldBe StatusCodes.OK }
    Post("/dashboard/g/g1/extend-holder", body("""{"code":"415","minutes":30}""")) ~>
      signedIn ~> r ~> check { status shouldBe StatusCodes.OK }
    Post("/dashboard/g/g1/drop-slot",
      body("""{"code":"415","startsAt":"2026-08-13T11:00:00Z"}""")) ~>
      signedIn ~> r ~> check { status shouldBe StatusCodes.OK }
    Post("/dashboard/g/g1/reassign-slot",
      body("""{"code":"415","startsAt":"2026-08-13T11:00:00Z","toUserId":"u9"}""")) ~>
      signedIn ~> r ~> check { status shouldBe StatusCodes.OK }
    actions.moderatorCalls shouldBe List(
      "forceLeave:415", "reassign:415->u9", "grant:u9:60",
      "add:999:Edron:Deep Cave:Orc Warlord", "remove:999", "spawnmax:415:60", "extend:415:30",
      "drop:415:2026-08-13T11:00:00Z", "move:415:2026-08-13T11:00:00Z:u9")
  }

  test("an empty max claim clears the spawn's own ceiling rather than failing") {
    val actions = new RecordingActions
    val r = routes(actions, member = moderator, moderatorRole = ModRole)
    // Both spellings of "no value", because a page that clears a field sends the
    // empty string and one that drops it sends nothing at all.
    Post("/dashboard/g/g1/spawn-max", body("""{"code":"415","minutes":""}""")) ~>
      signedIn ~> r ~> check { status shouldBe StatusCodes.OK }
    Post("/dashboard/g/g1/spawn-max", body("""{"code":"415"}""")) ~>
      signedIn ~> r ~> check { status shouldBe StatusCodes.OK }
    actions.moderatorCalls shouldBe List("spawnmax:415:clear", "spawnmax:415:clear")
  }

  // The endpoint reads what was typed through ClaimDuration, so the dashboard and
  // the Discord form agree about what "2h" is. Its own table is in
  // ClaimDurationSpec; this only pins down that the route goes through it.
  test("a max claim is read the way it was typed") {
    val actions = new RecordingActions
    val r = routes(actions, member = moderator, moderatorRole = ModRole)
    Post("/dashboard/g/g1/spawn-max", body("""{"code":"415","minutes":"2h"}""")) ~>
      signedIn ~> r ~> check { status shouldBe StatusCodes.OK }
    Post("/dashboard/g/g1/spawn-max", body("""{"code":"415","minutes":"1h30"}""")) ~>
      signedIn ~> r ~> check { status shouldBe StatusCodes.OK }
    // A bare 2 is two hours, not two minutes — nobody caps a spawn at two
    // minutes, and the suffix is there for whoever means the short one.
    Post("/dashboard/g/g1/spawn-max", body("""{"code":"415","minutes":"2"}""")) ~>
      signedIn ~> r ~> check { status shouldBe StatusCodes.OK }
    actions.moderatorCalls shouldBe
      List("spawnmax:415:120", "spawnmax:415:90", "spawnmax:415:120")
  }

  // Refused rather than guessed at: reading "2 days" as two of anything is how a
  // ceiling ends up somewhere nobody chose.
  test("a max claim that cannot be read never reaches the service") {
    val actions = new RecordingActions
    val r = routes(actions, member = moderator, moderatorRole = ModRole)
    Post("/dashboard/g/g1/spawn-max", body("""{"code":"415","minutes":"2 days"}""")) ~>
      signedIn ~> r ~> check { status shouldBe StatusCodes.BadRequest }
    Post("/dashboard/g/g1/spawn-max", body("""{"minutes":"60"}""")) ~>
      signedIn ~> r ~> check { status shouldBe StatusCodes.BadRequest }
    actions.moderatorCalls shouldBe empty
  }

  // A slot is named by the moment it starts on, and getting that wrong means
  // taking away a booking that belongs to somebody else entirely — so anything
  // unreadable is refused rather than rounded to now.
  test("a slot tool with no day, or an unreadable one, never reaches the service") {
    val actions = new RecordingActions
    val r = routes(actions, member = moderator, moderatorRole = ModRole)
    List(
      ("/dashboard/g/g1/drop-slot", """{"code":"415"}"""),
      ("/dashboard/g/g1/drop-slot", """{"code":"415","startsAt":"tuesday evening"}"""),
      ("/dashboard/g/g1/drop-slot", """{"startsAt":"2026-08-13T11:00:00Z"}"""),
      ("/dashboard/g/g1/reassign-slot", """{"code":"415","startsAt":"2026-08-13T11:00:00Z"}"""),
      ("/dashboard/g/g1/reassign-slot", """{"code":"415","toUserId":"u9"}""")
    ).foreach { case (path, payload) =>
      Post(path, body(payload)) ~> signedIn ~> r ~> check {
        withClue(s"$path $payload: ")(status shouldBe StatusCodes.BadRequest)
      }
    }
    actions.moderatorCalls shouldBe empty
  }

  test("an extension of nothing, or of a negative, never reaches the service") {
    val actions = new RecordingActions
    val r = routes(actions, member = moderator, moderatorRole = ModRole)
    List("""{"code":"415"}""", """{"code":"415","minutes":0}""",
         """{"code":"415","minutes":-30}""", """{"minutes":30}""").foreach { payload =>
      Post("/dashboard/g/g1/extend-holder", body(payload)) ~> signedIn ~> r ~> check {
        withClue(s"$payload: ")(status shouldBe StatusCodes.BadRequest)
      }
    }
    actions.moderatorCalls shouldBe empty
  }

  // The list is everybody who has used the respawn system here, which is not
  // something an ordinary member should be able to enumerate off the board.
  test("only a moderator may read the list of people") {
    val actions = new RecordingActions
    Get("/dashboard/g/g1/people") ~> signedIn ~> routes(actions) ~> check {
      status shouldBe StatusCodes.Forbidden
    }
    Get("/dashboard/g/g1/people") ~> signedIn ~>
      routes(actions, member = moderator, moderatorRole = ModRole) ~> check {
      status shouldBe StatusCodes.OK
      val listed = responseAs[String].parseJson.asJsObject.fields("people")
        .asInstanceOf[JsArray].elements.map(_.asJsObject.fields)
      listed.map(_("id")) shouldBe Vector(JsString("user-1"), JsString("user-9"))
      // Both names travel: one is searchable and unique, the other is what
      // anybody would actually type.
      listed.head("name") shouldBe JsString("violentbeams")
      listed.head("nickname") shouldBe JsString("Violent Beams")
    }
  }

  test("a removal with no code is refused before it reaches the catalogue") {
    val actions = new RecordingActions
    val r = routes(actions, member = moderator, moderatorRole = ModRole)
    List("""{}""", """{"code":"   "}""").foreach { payload =>
      Post("/dashboard/g/g1/remove-spawn", body(payload)) ~> signedIn ~> r ~> check {
        withClue(s"$payload: ")(status shouldBe StatusCodes.BadRequest)
      }
    }
    actions.moderatorCalls shouldBe empty
  }

  test("a spawn with no code or no name is refused before it reaches the catalogue") {
    val actions = new RecordingActions
    val r = routes(actions, member = moderator, moderatorRole = ModRole)
    List("""{"name":"Deep Cave"}""", """{"code":"999"}""", """{"code":"  ","name":"Deep Cave"}""").foreach { payload =>
      Post("/dashboard/g/g1/spawns", body(payload)) ~> signedIn ~> r ~> check {
        withClue(s"$payload: ")(status shouldBe StatusCodes.BadRequest)
      }
    }
    actions.moderatorCalls shouldBe empty
  }

  test("a spawn may be added without a city or a creature") {
    // Both are genuinely optional in the bundled file too: no city groups it
    // under "Elsewhere", and no creature simply means no picture.
    val actions = new RecordingActions
    val r = routes(actions, member = moderator, moderatorRole = ModRole)
    Post("/dashboard/g/g1/spawns", body("""{"code":"999","name":"Deep Cave"}""")) ~>
      signedIn ~> r ~> check { status shouldBe StatusCodes.OK }
    actions.moderatorCalls shouldBe List("add:999::Deep Cave:")
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
