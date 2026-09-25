package com.tibiabot.observer

import com.sun.net.httpserver.HttpServer
import com.tibiabot.domain.{ObserverStatus, ObserverToken}
import com.tibiabot.persistence.ObserverRepository
import com.tibiabot.tracking.ApiCallMetrics
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.collection.mutable.ListBuffer

/** Which worlds an account's rules are set on: only those that are both the
 *  account's and set up in the guild it was linked in, and none at all when no
 *  world is both. The sidecar is a local stand-in that records what it was asked,
 *  so these run without Config or the network. */
class ObserverRuleScopeSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  /** Every request the stand-in sidecar received: its path and the worlds it named. */
  private val calls = ListBuffer.empty[(String, List[String])]

  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  List("/ensure-rules", "/ensure-raid-rules", "/clear-rules").foreach { path =>
    server.createContext(path, exchange => {
      val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8).parseJson.asJsObject
      val worlds = body.fields.get("worlds").collect { case JsArray(ws) => ws.collect { case JsString(w) => w }.toList }
      calls.synchronized(calls += (path -> worlds.getOrElse(Nil)))
      val answer = s"""{"ok": true, "status_code": 200, "worlds": ${JsArray(worlds.getOrElse(Nil).map(JsString(_)): _*)}, "skipped": [], "limit": 15}"""
      val bytes = answer.getBytes(StandardCharsets.UTF_8)
      exchange.sendResponseHeaders(200, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
      exchange.close()
    })
  }

  override def beforeAll(): Unit = server.start()
  override def afterAll(): Unit = server.stop(0)

  private val crypto = TokenCrypto.fromSecret("test-secret")

  private def token(guildId: String, userId: String, worlds: String) =
    ObserverToken(1L, guildId, userId, Some(worlds), None, ObserverStatus.Linked, Instant.EPOCH, Instant.EPOCH)

  /** Just enough of the store for re-applying rules: the links and their credentials. */
  private final class Links(tokens: List[ObserverToken]) extends ObserverRepository {
    def all(): List[ObserverToken] = tokens
    def forUser(guildId: String, userId: String): Option[ObserverToken] =
      tokens.find(t => t.guildId == guildId && t.userId == userId)
    def upsert(guildId: String, userId: String, tokenEnc: String, status: ObserverStatus,
               accountLabel: Option[String], world: Option[String]): ObserverToken = ???
    def tokenEncFor(guildId: String, userId: String): Option[String] =
      forUser(guildId, userId).map(_ => crypto.encrypt(s"jwt-$userId"))
    def setStatus(id: Long, status: ObserverStatus, world: Option[String]): Unit = ???
    def delete(guildId: String, userId: String): Boolean = ???
    def deleteGuild(guildId: String): Unit = ???
    def deleteUser(guildId: String, userId: String): Unit = ???
  }

  private def service(tokens: List[ObserverToken], guildWorlds: String => List[String]) =
    new ObserverService(new Links(tokens), crypto,
      new ObserverApiClient(s"http://127.0.0.1:${server.getAddress.getPort}", sharedToken = "",
        deviceIdentification = "Violent Bot", clientVersion = "1.1.6", metrics = new ApiCallMetrics()),
      enabled = true, guildWorlds = guildWorlds)

  private def run(tokens: List[ObserverToken], guildWorlds: String => List[String], guildId: String): List[(String, List[String])] = {
    calls.synchronized(calls.clear())
    service(tokens, guildWorlds).reapplyRulesDirect(guildId)
    calls.synchronized(calls.toList)
  }

  test("rules go only to the worlds the account and the guild have in common, the guild's main world first") {
    val sent = run(List(token("g1", "u1", "Ombra, Victoris, Xyla")), _ => List("Victoris", "Antica", "Ombra"), "g1")
    sent shouldBe List("/ensure-rules" -> List("Victoris", "Ombra"), "/ensure-raid-rules" -> List("Victoris", "Ombra"))
  }

  test("an account with no world in common with the guild has the bot's rules taken off") {
    val sent = run(List(token("g1", "u1", "Victoris")), _ => List("Antica"), "g1")
    sent shouldBe List("/clear-rules" -> Nil)
  }

  test("rules are left as they are when the guild's worlds can't be read") {
    run(List(token("g1", "u1", "Victoris")), _ => throw new RuntimeException("database down"), "g1") shouldBe Nil
  }

  test("a guild's change touches only the accounts linked in that guild") {
    val tokens = List(token("g1", "u1", "Victoris"), token("g2", "u2", "Antica"))
    val sent = run(tokens, { case "g1" => List("Victoris"); case _ => List("Antica") }, "g1")
    sent.map(_._1) shouldBe List("/ensure-rules", "/ensure-raid-rules")
    sent.flatMap(_._2).distinct shouldBe List("Victoris")
  }
}
