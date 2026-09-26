package com.tibiabot.observer

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import com.tibiabot.domain.{ObserverStatus, ObserverToken}
import com.tibiabot.persistence.ObserverRepository
import com.tibiabot.tracking.ApiCallMetrics
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer

/** The feeds pooled across every linked account when one of them stops working:
 *  a dead link is marked for relinking rather than holding up everyone else's
 *  mini world changes, and an account that cannot be fetched keeps its share
 *  until server save. The sidecar is a local stand-in answering per credential,
 *  so these run without Config or the network. */
class ObserverPoolSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {
  import ObserverPoolSpec._

  private val feeds = TrieMap.empty[String, Feed]
  private val renewals = TrieMap.empty[String, Renewal]
  /** Every feed request, as (path, credential). */
  private val asked = ListBuffer.empty[(String, String)]

  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)

  private def answer(exchange: HttpExchange, code: Int, body: JsValue): Unit = {
    val bytes = body.compactPrint.getBytes(StandardCharsets.UTF_8)
    exchange.sendResponseHeaders(code, bytes.length.toLong)
    exchange.getResponseBody.write(bytes)
    exchange.close()
  }

  private def field(exchange: HttpExchange, key: String): String =
    new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8).parseJson.asJsObject
      .fields.get(key).collect { case JsString(s) => s }.getOrElse("")

  private def serveFeed(path: String, key: String)(item: ((String, String)) => JsObject): Unit =
    server.createContext(path, exchange => {
      val credential = field(exchange, "bearerToken")
      asked.synchronized(asked += (path -> credential))
      feeds.getOrElse(credential, Down) match {
        case Answers(changes @ _*) =>
          answer(exchange, 200, JsObject("ok" -> JsTrue, "status_code" -> JsNumber(200),
            key -> JsArray(changes.map(item).toVector)))
        case Refuses => answer(exchange, 401, JsObject("ok" -> JsFalse, "status" -> JsString("unauthorised")))
        case Down    => answer(exchange, 502, JsObject("ok" -> JsFalse, "error" -> JsString("down")))
      }
    })

  serveFeed("/mwc", "miniWorldChanges") { case (world, title) =>
    JsObject("world" -> JsString(world), "title" -> JsString(title), "body" -> JsString(""))
  }
  serveFeed("/raids", "raids") { case (world, title) =>
    JsObject("raidId" -> JsString(s"$world-$title"), "worldName" -> JsString(world),
      "areaName" -> JsString(title), "category" -> JsString("areaRevealed"))
  }
  server.createContext("/renew", exchange => {
    renewals.get(field(exchange, "credential")) match {
      case Some(RenewsTo(fresh)) =>
        answer(exchange, 200, JsObject("ok" -> JsTrue, "status_code" -> JsNumber(200), "credential" -> JsString(fresh)))
      case Some(Rejects) => answer(exchange, 200, JsObject("ok" -> JsFalse, "status_code" -> JsNumber(401)))
      case None          => answer(exchange, 502, JsObject("ok" -> JsFalse, "error" -> JsString("down")))
    }
  })
  List("/ensure-rules", "/ensure-raid-rules", "/clear-rules").foreach { path =>
    server.createContext(path, exchange => {
      exchange.getRequestBody.readAllBytes()
      answer(exchange, 200, JsObject("ok" -> JsTrue, "status_code" -> JsNumber(200)))
    })
  }

  override def beforeAll(): Unit = server.start()
  override def afterAll(): Unit = server.stop(0)
  override def beforeEach(): Unit = {
    feeds.clear()
    renewals.clear()
    asked.synchronized(asked.clear())
  }

  private val crypto = TokenCrypto.fromSecret("test-secret")

  /** The links, as the shared table holds them. */
  private final class Store extends ObserverRepository {
    private val rows = TrieMap.empty[(String, String), (ObserverToken, String)]
    def add(userId: String, worlds: String, credential: String,
            status: ObserverStatus = ObserverStatus.Linked): Unit = {
      val t = ObserverToken(rows.size + 1L, "g1", userId, Some(worlds), None, status, Instant.EPOCH, Instant.EPOCH)
      rows.put(("g1", userId), t -> crypto.encrypt(credential))
    }
    def status(userId: String): ObserverStatus = rows(("g1", userId))._1.status
    def credential(userId: String): String = crypto.decrypt(rows(("g1", userId))._2)

    def all(): List[ObserverToken] = rows.values.map(_._1).toList.sortBy(_.id)
    def forUser(guildId: String, userId: String): Option[ObserverToken] = rows.get((guildId, userId)).map(_._1)
    def upsert(guildId: String, userId: String, tokenEnc: String, status: ObserverStatus,
               accountLabel: Option[String], world: Option[String]): ObserverToken = {
      val t = forUser(guildId, userId).get.copy(status = status, accountLabel = accountLabel, world = world)
      rows.put((guildId, userId), t -> tokenEnc)
      t
    }
    def tokenEncFor(guildId: String, userId: String): Option[String] = rows.get((guildId, userId)).map(_._2)
    def setStatus(id: Long, status: ObserverStatus, world: Option[String]): Unit =
      rows.collectFirst { case (key, (t, enc)) if t.id == id => key -> (t.copy(status = status, world = world) -> enc) }
        .foreach { case (key, row) => rows.put(key, row) }
    def delete(guildId: String, userId: String): Boolean = rows.remove((guildId, userId)).isDefined
    def deleteGuild(guildId: String): Unit = rows.keys.toList.filter(_._1 == guildId).foreach(rows.remove)
    def deleteUser(guildId: String, userId: String): Unit = { delete(guildId, userId); () }
  }

  private val firstSave = Instant.parse("2026-09-26T08:00:00Z")
  private val nextSave = Instant.parse("2026-09-27T08:00:00Z")

  private final class Harness {
    val store = new Store
    var clock: Instant = firstSave.plusSeconds(3600)
    val service = new ObserverService(store, crypto,
      new ObserverApiClient(s"http://127.0.0.1:${server.getAddress.getPort}", sharedToken = "",
        deviceIdentification = "Violent Bot", clientVersion = "1.1.6", metrics = new ApiCallMetrics()),
      enabled = true, now = () => clock,
      lastServerSave = at => if (at.isBefore(nextSave)) firstSave else nextSave)

    /** The pool's worlds and their titles, or None when it waits. */
    def mwc(): Option[Map[String, List[String]]] =
      service.fetchPooledMwc().map(_.view.mapValues(_.map(_.title)).toMap)
  }

  private def askedFor(credential: String): List[String] = asked.synchronized(asked.toList).collect { case (p, `credential`) => p }

  test("a dead link is marked for relinking and no longer holds up everyone else's changes") {
    val h = new Harness
    h.store.add("u1", "Victoris", "a")
    h.store.add("u2", "Antica", "b")
    feeds.put("a", Answers("Victoris" -> "Fury Gate"))
    feeds.put("b", Refuses)
    renewals.put("b", Rejects)

    h.mwc() shouldBe Some(Map("victoris" -> List("Fury Gate")))
    h.store.status("u2") shouldBe ObserverStatus.NeedsRelink

    asked.synchronized(asked.clear())
    h.mwc() shouldBe Some(Map("victoris" -> List("Fury Gate")))
    askedFor("b") shouldBe Nil
  }

  test("a dead link's changes last until server save, and not past it") {
    val h = new Harness
    h.store.add("u1", "Victoris", "a")
    h.store.add("u2", "Antica", "b")
    feeds.put("a", Answers("Victoris" -> "Fury Gate"))
    feeds.put("b", Answers("Antica" -> "Nomads"))
    h.mwc() shouldBe Some(Map("victoris" -> List("Fury Gate"), "antica" -> List("Nomads")))

    feeds.put("b", Refuses)
    renewals.put("b", Rejects)
    h.mwc() shouldBe Some(Map("victoris" -> List("Fury Gate"), "antica" -> List("Nomads")))
    h.store.status("u2") shouldBe ObserverStatus.NeedsRelink
    h.mwc() shouldBe Some(Map("victoris" -> List("Fury Gate"), "antica" -> List("Nomads")))

    h.clock = nextSave.plusSeconds(60)
    h.mwc() shouldBe Some(Map("victoris" -> List("Fury Gate")))
  }

  test("a failed fetch falls back on the account's changes since server save, and the pool waits without them") {
    val h = new Harness
    h.store.add("u1", "Victoris", "a")
    h.store.add("u2", "Antica", "b")
    feeds.put("a", Answers("Victoris" -> "Fury Gate"))
    feeds.put("b", Answers("Antica" -> "Nomads"))
    h.mwc() shouldBe Some(Map("victoris" -> List("Fury Gate"), "antica" -> List("Nomads")))

    feeds.put("b", Down)
    h.mwc() shouldBe Some(Map("victoris" -> List("Fury Gate"), "antica" -> List("Nomads")))
    h.store.status("u2") shouldBe ObserverStatus.Linked

    h.clock = nextSave.plusSeconds(60)
    h.mwc() shouldBe None
  }

  test("a refused credential that renews is asked again with the fresh one, which is kept") {
    val h = new Harness
    h.store.add("u1", "Antica", "b")
    feeds.put("b", Refuses)
    renewals.put("b", RenewsTo("b2"))
    feeds.put("b2", Answers("Antica" -> "Nomads"))

    h.mwc() shouldBe Some(Map("antica" -> List("Nomads")))
    h.store.credential("u1") shouldBe "b2"
    h.store.status("u1") shouldBe ObserverStatus.Linked
  }

  test("the API refusing every link at once is its own trouble: none is marked, and the pool waits") {
    val h = new Harness
    h.store.add("u1", "Victoris", "a")
    h.store.add("u2", "Antica", "b")
    List("a", "b").foreach { c => feeds.put(c, Refuses); renewals.put(c, Rejects) }

    h.mwc() shouldBe None
    h.store.status("u1") shouldBe ObserverStatus.Linked
    h.store.status("u2") shouldBe ObserverStatus.Linked
  }

  test("a dead link's raids are left out and the rest are pooled") {
    val h = new Harness
    h.store.add("u1", "Victoris", "a")
    h.store.add("u2", "Antica", "b")
    feeds.put("a", Answers("Victoris" -> "Carlin"))
    feeds.put("b", Refuses)
    renewals.put("b", Rejects)

    h.service.fetchPooledRaids().keySet shouldBe Set("Victoris")
    h.store.status("u2") shouldBe ObserverStatus.NeedsRelink
  }

  test("the renewal sweep links a lapsed link again when its credential works, and marks one it can't renew") {
    val h = new Harness
    h.store.add("u1", "Victoris", "a")
    h.store.add("u2", "Antica", "b", ObserverStatus.NeedsRelink)
    h.store.add("u3", "Secura", "c")
    renewals.put("a", Rejects)
    renewals.put("b", RenewsTo("b2"))
    renewals.put("c", RenewsTo("c2"))

    h.service.renewAll()
    h.store.status("u1") shouldBe ObserverStatus.NeedsRelink
    h.store.status("u2") shouldBe ObserverStatus.Linked
    h.store.credential("u2") shouldBe "b2"
    h.store.status("u3") shouldBe ObserverStatus.Linked
    h.store.credential("u3") shouldBe "c2"
  }

  test("a member's panel shows the link as stored, so a secondary sees it marked") {
    val h = new Harness
    h.store.add("u1", "Victoris", "a")
    h.service.load()
    h.store.setStatus(1L, ObserverStatus.NeedsRelink, Some("Victoris"))
    h.service.statusFor("g1", "u1").map(_.status) shouldBe Some(ObserverStatus.NeedsRelink)
  }
}

object ObserverPoolSpec {

  /** How the stand-in API answers a credential's feeds. */
  private sealed trait Feed
  /** Its active changes (and a raid on each world), as (world, title). */
  private final case class Answers(changes: (String, String)*) extends Feed
  private case object Refuses extends Feed
  private case object Down extends Feed

  /** How the stand-in API answers renewing a credential. */
  private sealed trait Renewal
  private final case class RenewsTo(fresh: String) extends Renewal
  private case object Rejects extends Renewal
}
