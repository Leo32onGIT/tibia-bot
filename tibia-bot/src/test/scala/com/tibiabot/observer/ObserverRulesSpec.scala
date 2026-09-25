package com.tibiabot.observer

import com.sun.net.httpserver.HttpServer
import com.tibiabot.tracking.ApiCallMetrics
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/** Setting an account's rules against the API's cap, and counting every Observer
 *  request for the dashboard. The sidecar is a local stand-in serving canned
 *  answers, so these run without Config or the network. */
class ObserverRulesSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private val answers = Map(
    "/ensure-rules" ->
      """{"ok": true, "status_code": 200, "worlds": ["Victoris", "Ombra"], "skipped": ["Xyla"], "limit": 2}""",
    "/ensure-raid-rules" ->
      """{"ok": false, "status_code": 400, "worlds": [], "skipped": [], "limit": 15, "error": "too many rules"}""",
    "/mwc" ->
      """{"ok": true, "status_code": 200, "miniWorldChanges": [
        |  {"world": "Victoris", "title": "Fury Gate", "body": "Near Venore."},
        |  {"worldName": "Ombra", "title": "Nomads", "body": "Camped."}
        |]}""".stripMargin)

  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  answers.foreach { case (path, body) =>
    server.createContext(path, exchange => {
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      exchange.sendResponseHeaders(200, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
      exchange.close()
    })
  }

  override def beforeAll(): Unit = server.start()
  override def afterAll(): Unit = server.stop(0)

  private def client(metrics: ApiCallMetrics, url: String = s"http://127.0.0.1:${server.getAddress.getPort}") =
    new ObserverApiClient(url, sharedToken = "", deviceIdentification = "Violent Bot",
      clientVersion = "1.1.6", metrics = metrics)

  test("rules go only to worlds that are both the account's and set up in the guild, in the guild's order") {
    val account = List("Xyla", "Cantabra", "Victoris", "Ombra", "Honbra")
    ObserverService.ruleWorlds(account, List("victoris", "Antica", "OMBRA", "Victoris")) shouldBe
      List("Victoris", "Ombra")
  }

  test("no world in common means no rules") {
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

  test("asking for no MWC worlds sets nothing and makes no request") {
    val metrics = new ApiCallMetrics()
    client(metrics).ensureRules("jwt", Nil).ok shouldBe true
    metrics.snapshot().total shouldBe 0
  }

  test("a change is read whether the feed calls its world `world` or `worldName`") {
    client(new ApiCallMetrics()).mwcResult("jwt").map(_.map(c => c.world -> c.title)) shouldBe
      Some(List("Victoris" -> "Fury Gate", "Ombra" -> "Nomads"))
  }

  test("every request is counted by endpoint and by the status the API answered with") {
    val metrics = new ApiCallMetrics()
    val c = client(metrics)
    c.mwcResult("jwt")
    c.ensureRaidRules("jwt", Nil)
    val snap = metrics.snapshot()
    snap.total shouldBe 2
    snap.dimensions("endpoint").keySet shouldBe Set("/mwc", "/ensure-raid-rules")
    snap.dimensions("status").map { case (k, v) => k -> v.total } shouldBe Map("200" -> 1L, "400" -> 1L)
  }

  test("a sidecar that cannot be reached is counted as failed") {
    val metrics = new ApiCallMetrics()
    // Port 9 (discard) on localhost has nothing listening.
    client(metrics, "http://127.0.0.1:9").mwcResult("jwt") shouldBe None
    metrics.snapshot().dimensions("status").keySet shouldBe Set("failed")
  }
}
