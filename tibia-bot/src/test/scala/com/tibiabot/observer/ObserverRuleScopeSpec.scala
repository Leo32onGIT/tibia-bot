package com.tibiabot.observer

import com.sun.net.httpserver.HttpServer
import com.tibiabot.domain.{ObserverStatus, ObserverToken}
import com.tibiabot.persistence.{CoveredArea, ObserverCoverageRepository, ObserverRepository}
import com.tibiabot.tracking.ApiCallMetrics
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer

/** Which worlds an account's rules are set on: those it has characters on that
 *  some guild tracks, the guilds it is linked in first; the same from every link
 *  to one account; none at all when no guild tracks any. The sidecar is a local
 *  stand-in that records what it was asked, so these run without Config or the
 *  network. */
class ObserverRuleScopeSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  /** Every request the stand-in sidecar received: its path, the credential it was
   *  for, and the worlds it named. */
  private val calls = ListBuffer.empty[(String, String, List[String])]

  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  List("/ensure-rules", "/ensure-raid-rules", "/clear-rules").foreach { path =>
    server.createContext(path, exchange => {
      val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8).parseJson.asJsObject
      val credential = body.fields.get("credential").collect { case JsString(c) => c }.getOrElse("")
      val worlds = body.fields.get("worlds").collect { case JsArray(ws) => ws.collect { case JsString(w) => w }.toList }
      calls.synchronized(calls += ((path, credential, worlds.getOrElse(Nil))))
      // Every raid rule covers area 3 (Carlin); what the account has explored is
      // whatever `explored` holds for it.
      val regions = JsObject(worlds.getOrElse(Nil).map(w => w -> JsArray(JsNumber(3))).toMap)
      val answer = s"""{"ok": true, "status_code": 200, "worlds": ${JsArray(worlds.getOrElse(Nil).map(JsString(_)): _*)}, "skipped": [], "limit": 15, "regions": $regions, "explored": ${explored.getOrElse(credential, JsObject())}}"""
      val bytes = answer.getBytes(StandardCharsets.UTF_8)
      exchange.sendResponseHeaders(200, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
      exchange.close()
    })
  }

  /** What each credential's account has explored, as the API would answer. */
  private val explored = TrieMap.empty[String, JsObject]
  server.createContext("/explored-areas", exchange => {
    val credential = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8).parseJson.asJsObject
      .fields.get("credential").collect { case JsString(c) => c }.getOrElse("")
    calls.synchronized(calls += (("/explored-areas", credential, Nil)))
    val bytes = s"""{"ok": true, "status_code": 200, "explored": ${explored.getOrElse(credential, JsObject())}, "areaNames": {}}"""
      .getBytes(StandardCharsets.UTF_8)
    exchange.sendResponseHeaders(200, bytes.length.toLong)
    exchange.getResponseBody.write(bytes)
    exchange.close()
  })

  override def beforeAll(): Unit = server.start()
  override def afterAll(): Unit = server.stop(0)

  private val crypto = TokenCrypto.fromSecret("test-secret")

  private def token(guildId: String, userId: String, worlds: String, linkedDaysAgo: Int = 0) = {
    val at = Instant.parse("2026-09-26T12:00:00Z").minus(Duration.ofDays(linkedDaysAgo.toLong))
    ObserverToken(1L, guildId, userId, Some(worlds), None, ObserverStatus.Linked, at, at)
  }

  private def credential(guildId: String, userId: String) = s"jwt-$guildId-$userId"

  /** Just enough of the store for re-applying rules: the links and their credentials. */
  private final class Links(tokens: List[ObserverToken]) extends ObserverRepository {
    def all(): List[ObserverToken] = tokens
    def forUser(guildId: String, userId: String): Option[ObserverToken] =
      tokens.find(t => t.guildId == guildId && t.userId == userId)
    def upsert(guildId: String, userId: String, tokenEnc: String, status: ObserverStatus,
               accountLabel: Option[String], world: Option[String]): ObserverToken = ???
    def tokenEncFor(guildId: String, userId: String): Option[String] =
      forUser(guildId, userId).map(_ => crypto.encrypt(credential(guildId, userId)))
    def setStatus(id: Long, status: ObserverStatus, world: Option[String]): Unit = ???
    def delete(guildId: String, userId: String): Boolean = ???
    def deleteGuild(guildId: String): Unit = ???
    def deleteUser(guildId: String, userId: String): Unit = ???
  }

  /** What the service kept about each link's coverage. */
  private final class Coverage extends ObserverCoverageRepository {
    val areas = TrieMap.empty[(String, String), Map[String, List[Int]]]
    val cleared = ListBuffer.empty[(String, String)]
    def setAreas(guildId: String, userId: String, a: Map[String, List[Int]]): Unit = areas.put((guildId, userId), a)
    def clearLink(guildId: String, userId: String): Unit = cleared.synchronized(cleared += (guildId -> userId))
    def clearGuild(guildId: String): Unit = ()
    def liveAreas(worlds: List[String]): List[CoveredArea] = Nil
    def setNames(names: Map[Int, String]): Unit = ()
    def names(): Map[Int, String] = Map.empty
  }

  private def service(tokens: List[ObserverToken], guildWorlds: String => List[String],
                      tracked: () => List[String], coverage: ObserverCoverageRepository = ObserverCoverageRepository.None) =
    new ObserverService(new Links(tokens), crypto,
      new ObserverApiClient(s"http://127.0.0.1:${server.getAddress.getPort}", sharedToken = "",
        deviceIdentification = "Violent Bot", clientVersion = "1.1.6", metrics = new ApiCallMetrics()),
      enabled = true, guildWorlds = guildWorlds, trackedWorlds = tracked, coverage = coverage)

  private def recorded(work: => Unit): List[(String, String, List[String])] = {
    calls.synchronized(calls.clear())
    work
    calls.synchronized(calls.toList)
  }

  /** The requests made in the background, once `count` have arrived. */
  private def eventually(count: Int): List[(String, String, List[String])] = {
    val deadline = System.nanoTime() + 5_000_000_000L
    while (calls.synchronized(calls.size) < count && System.nanoTime() < deadline) Thread.sleep(10)
    calls.synchronized(calls.toList)
  }

  test("rules cover the linking guild's worlds first, its main world first, then those other guilds track") {
    val sent = recorded(service(List(token("g1", "u1", "Ombra, Victoris, Xyla, Honbra")),
      _ => List("Victoris", "Antica", "Ombra"), () => List("Xyla", "Antica", "Victoris")).reapplyRules())
    // Honbra has a character but no guild tracks it: nowhere to post.
    sent shouldBe List(
      ("/ensure-rules", credential("g1", "u1"), List("Victoris", "Ombra", "Xyla")),
      ("/ensure-raid-rules", credential("g1", "u1"), List("Victoris", "Ombra", "Xyla")))
  }

  test("an account none of whose worlds any guild tracks has the bot's rules taken off") {
    val sent = recorded(service(List(token("g1", "u1", "Victoris")), _ => List("Antica"), () => List("Antica")).reapplyRules())
    sent shouldBe List(("/clear-rules", credential("g1", "u1"), Nil))
  }

  test("the areas each world's raid rule covers are kept for the link") {
    val coverage = new Coverage
    recorded(service(List(token("g1", "u1", "Ombra, Victoris")), _ => List("Victoris"), () => List("Ombra", "Victoris"),
      coverage).reapplyRules())
    coverage.areas.toMap shouldBe Map(("g1", "u1") -> Map("Victoris" -> List(3), "Ombra" -> List(3)))
  }

  test("an account whose rules come off forgets what it covered") {
    val coverage = new Coverage
    recorded(service(List(token("g1", "u1", "Victoris")), _ => List("Antica"), () => List("Antica"), coverage).reapplyRules())
    coverage.cleared.toList shouldBe List("g1" -> "u1")
  }

  test("an account that explores something new has its rules set again at the next check, and only then") {
    val svc = service(List(token("g1", "u1", "Victoris")), _ => List("Victoris"), () => List("Victoris"))
    val cred = credential("g1", "u1")
    explored.put(cred, JsObject("Victoris" -> JsArray(JsNumber(3))))
    // Setting the rules notes what the account had explored.
    recorded(svc.reapplyRules())
    // Nothing new: one read, and no rules set.
    recorded(svc.refreshExplored()) shouldBe List(("/explored-areas", cred, Nil))
    explored.put(cred, JsObject("Victoris" -> JsArray(JsNumber(3), JsNumber(7))))
    recorded(svc.refreshExplored()).map(_._1) shouldBe List("/explored-areas", "/ensure-rules", "/ensure-raid-rules")
    // The new area is noted, so the check after sets nothing.
    recorded(svc.refreshExplored()).map(_._1) shouldBe List("/explored-areas")
  }

  test("the first check with nothing to compare against only notes the areas") {
    val svc = service(List(token("g2", "u2", "Victoris")), _ => List("Victoris"), () => List("Victoris"))
    explored.put(credential("g2", "u2"), JsObject("Victoris" -> JsArray(JsNumber(3))))
    recorded(svc.refreshExplored()).map(_._1) shouldBe List("/explored-areas")
  }

  test("rules are left as they are when the worlds can't be read") {
    recorded(service(List(token("g1", "u1", "Victoris")), _ => throw new RuntimeException("database down"),
      () => List("Victoris")).reapplyRules()) shouldBe Nil
    recorded(service(List(token("g1", "u1", "Victoris")), _ => List("Victoris"),
      () => throw new RuntimeException("database down")).reapplyRules()) shouldBe Nil
  }

  test("an account linked in two Discords gets the same rules from either, the older link's guild first") {
    val tokens = List(token("g1", "u1", "Antica, Victoris", linkedDaysAgo = 3), token("g2", "u1", "Antica, Victoris"))
    val guildWorlds: String => List[String] = { case "g1" => List("Victoris"); case _ => List("Antica") }
    val sent = recorded(service(tokens, guildWorlds, () => List("Antica", "Victoris")).reapplyRules())
    sent.map(_._1).distinct shouldBe List("/ensure-rules", "/ensure-raid-rules")
    sent.map(_._3).distinct shouldBe List(List("Victoris", "Antica"))
    sent.map(_._2).distinct.toSet shouldBe Set(credential("g1", "u1"), credential("g2", "u1"))
  }

  test("a world set up or removed touches the guild's links and every account with a character on it") {
    val tokens = List(token("g1", "u1", "Victoris"), token("g2", "u2", "Antica, Secura"), token("g3", "u3", "Secura"))
    val sent = recorded(service(tokens, _ => Nil, () => List("Victoris", "Antica", "Secura"))
      .reapplyRulesDirect("g1", Some("antica")))
    sent.map(_._2).distinct shouldBe List(credential("g1", "u1"), credential("g2", "u2"))
  }

  test("without a world, only the guild's links are touched") {
    val tokens = List(token("g1", "u1", "Victoris"), token("g2", "u2", "Antica"))
    val sent = recorded(service(tokens, _ => Nil, () => List("Victoris", "Antica")).reapplyRulesDirect("g1", None))
    sent.map(_._2).distinct shouldBe List(credential("g1", "u1"))
  }

  test("unlinking clears the account, then sets the rules again for the other links to the same account") {
    val tokens = List(token("g1", "u1", "Antica, Victoris"), token("g2", "u1", "Antica, Victoris"),
      token("g3", "u3", "Secura"))
    calls.synchronized(calls.clear())
    service(tokens, { case "g1" => List("Victoris"); case _ => List("Antica") }, () => List("Antica", "Victoris", "Secura"))
      .clearRulesDirect("g1", "u1") shouldBe true
    // The link in g2 is the only one left to that account, so its guild's world now comes first.
    eventually(3) shouldBe List(
      ("/clear-rules", credential("g1", "u1"), Nil),
      ("/ensure-rules", credential("g2", "u1"), List("Antica", "Victoris")),
      ("/ensure-raid-rules", credential("g2", "u1"), List("Antica", "Victoris")))
  }
}
