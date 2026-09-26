package com.tibiabot.observer

import com.sun.net.httpserver.HttpServer
import com.tibiabot.tracking.ApiCallMetrics
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/** Setting an account's rules against the API's cap, reading its feeds and
 *  renewing it, and counting every Observer request for the dashboard. The sidecar
 *  is a local stand-in serving canned answers, so these run without Config or the
 *  network. */
class ObserverRulesSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val answers = Map(
    "/ensure-rules" -> (200,
      """{"ok": true, "status_code": 200, "worlds": ["Victoris", "Ombra"], "skipped": ["Xyla"], "limit": 2}"""),
    "/mwc" -> (200,
      """{"ok": true, "status_code": 200, "miniWorldChanges": [
        |  {"world": "Victoris", "title": "Fury Gate", "body": "Near Venore."},
        |  {"worldName": "Ombra", "title": "Nomads", "body": "Camped."}
        |]}""".stripMargin),
    // What the sidecar answers when the API refuses the credential.
    "/raids" -> (401, """{"ok": false, "status": "unauthorised"}"""))

  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  private def answer(exchange: com.sun.net.httpserver.HttpExchange, code: Int, body: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.sendResponseHeaders(code, bytes.length.toLong)
    exchange.getResponseBody.write(bytes)
    exchange.close()
  }
  answers.foreach { case (path, (code, body)) => server.createContext(path, exchange => answer(exchange, code, body)) }
  // Raid rules answer by the credential: refused for "jwt", set for "explorer".
  server.createContext("/ensure-raid-rules", exchange => {
    val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8).parseJson.asJsObject
    body.fields.get("credential") match {
      case Some(JsString("explorer")) => answer(exchange, 200,
        """{"ok": true, "status_code": 200, "worlds": ["Victoris"], "skipped": [], "limit": 15,
          | "regions": {"Victoris": [3, 11, 23]}, "areaNames": {"3": "Carlin", "11": ""},
          | "explored": {"Victoris": [3, 11, 23], "Xyla": [7]},
          | "unchanged": true}""".stripMargin)
      case _ => answer(exchange, 200,
        """{"ok": false, "status_code": 400, "worlds": [], "skipped": [], "limit": 15, "error": "too many rules"}""")
    }
  })
  server.createContext("/explored-areas", exchange => {
    val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8).parseJson.asJsObject
    body.fields.get("credential") match {
      case Some(JsString("revoked")) => answer(exchange, 401, """{"ok": false, "status": "unauthorised"}""")
      case _ => answer(exchange, 200,
        """{"ok": true, "status_code": 200, "explored": {"Victoris": [3, 7]}, "areaNames": {"3": "Carlin", "7": "Edron"}}""")
    }
  })
  // A renewal answers by the credential it was asked to renew.
  server.createContext("/renew", exchange => {
    val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8).parseJson.asJsObject
    body.fields.get("credential") match {
      case Some(JsString("revoked")) => answer(exchange, 200, """{"ok": false, "status_code": 401, "credential": null}""")
      case Some(JsString("gated"))   => answer(exchange, 200, """{"ok": false, "status_code": 403, "credential": null}""")
      case _                         => answer(exchange, 200, """{"ok": true, "status_code": 200, "credential": "fresh"}""")
    }
  })

  override def beforeAll(): Unit = server.start()
  override def afterAll(): Unit = server.stop(0)

  private def client(metrics: ApiCallMetrics, url: String = s"http://127.0.0.1:${server.getAddress.getPort}") =
    new ObserverApiClient(url, sharedToken = "", deviceIdentification = "Violent Bot",
      clientVersion = "1.1.6", metrics = metrics)

  test("rules go only to the account's worlds, in the order they are wanted") {
    val account = List("Xyla", "Cantabra", "Victoris", "Ombra", "Honbra")
    ObserverService.ruleWorlds(account, List("victoris", "Antica", "OMBRA", "Victoris")) shouldBe
      List("Victoris", "Ombra")
  }

  test("no world wanted that the account has means no rules") {
    ObserverService.ruleWorlds(List("Victoris"), List("Antica")) shouldBe Nil
    ObserverService.ruleWorlds(Nil, List("Antica")) shouldBe Nil
    ObserverService.ruleWorlds(List("Victoris"), Nil) shouldBe Nil
  }

  test("setting rules says which worlds got one and which there was no room for") {
    val result = client(new ApiCallMetrics()).ensureRules("jwt", List("Victoris", "Ombra", "Xyla"))
    result shouldBe RulesResult(ok = true, List("Victoris", "Ombra"), List("Xyla"), Some(2), "status 200")
  }

  test("a refused store carries the API's status and its reason") {
    val result = client(new ApiCallMetrics()).ensureRaidRules("jwt", List("Victoris"))
    result.ok shouldBe false
    result.detail shouldBe "status 400: too many rules"
  }

  test("set raid rules say which areas each world's rule covers, and the names that came with them") {
    val result = client(new ApiCallMetrics()).ensureRaidRules("explorer", List("Victoris"))
    result.regions shouldBe Map("Victoris" -> List(3, 11, 23))
    // A blank name is no name.
    result.areaNames shouldBe Map(3 -> "Carlin")
    result.explored shouldBe Some(Map("Victoris" -> List(3, 11, 23), "Xyla" -> List(7)))
    result.unchanged shouldBe true
  }

  test("a refused store sends no explored areas, and isn't taken for rules already set") {
    val result = client(new ApiCallMetrics()).ensureRaidRules("jwt", List("Victoris"))
    result.explored shouldBe None
    result.unchanged shouldBe false
  }

  test("an account's explored areas are read with their names, and a refused credential is told apart") {
    val c = client(new ApiCallMetrics())
    c.exploredAreas("explorer") shouldBe
      FeedResult.Fetched(ExploredAreas(Map("Victoris" -> List(3, 7)), Map(3 -> "Carlin", 7 -> "Edron")))
    c.exploredAreas("revoked") shouldBe FeedResult.Unauthorised
  }

  test("asking for no MWC worlds sets nothing and makes no request") {
    val metrics = new ApiCallMetrics()
    client(metrics).ensureRules("jwt", Nil).ok shouldBe true
    metrics.snapshot().total shouldBe 0
  }

  test("a change is read whether the feed calls its world `world` or `worldName`") {
    client(new ApiCallMetrics()).mwc("jwt") match {
      case FeedResult.Fetched(changes) =>
        changes.map(c => c.world -> c.title) shouldBe List("Victoris" -> "Fury Gate", "Ombra" -> "Nomads")
      case other => fail(s"expected the changes, got $other")
    }
  }

  test("a refused credential is told apart from a failure") {
    client(new ApiCallMetrics()).raids("jwt") shouldBe FeedResult.Unauthorised
  }

  test("only the API's 401 on renewing is a rejection; its transport gate is a failure") {
    val c = client(new ApiCallMetrics())
    c.renew("jwt") shouldBe RenewResult.Renewed("fresh")
    c.renew("revoked") shouldBe RenewResult.Rejected
    c.renew("gated") shouldBe RenewResult.Failed
  }

  test("every request is counted by endpoint and by the status the API answered with") {
    val metrics = new ApiCallMetrics()
    val c = client(metrics)
    c.mwc("jwt")
    c.ensureRaidRules("jwt", Nil)
    val snap = metrics.snapshot()
    snap.total shouldBe 2
    snap.dimensions("endpoint").keySet shouldBe Set("/mwc", "/ensure-raid-rules")
    snap.dimensions("status").map { case (k, v) => k -> v.total } shouldBe Map("200" -> 1L, "400" -> 1L)
  }

  test("a sidecar that cannot be reached is counted as failed") {
    val metrics = new ApiCallMetrics()
    // Port 9 (discard) on localhost has nothing listening.
    client(metrics, "http://127.0.0.1:9").mwc("jwt") shouldBe FeedResult.Failed
    metrics.snapshot().dimensions("status").keySet shouldBe Set("failed")
  }
}
