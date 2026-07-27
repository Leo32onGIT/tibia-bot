package com.tibiabot.web

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json._

/** StatusRoute.mergeWorlds is a pure JsValue transform (no instance state),
 *  so it's tested directly rather than through a full StatusRoute — that
 *  class needs JDA/OAuth fixtures to construct at all, which this doesn't. */
class StatusRouteMergeWorldsSpec extends AnyFunSuite with Matchers {

  private def bot(name: String, userId: String): JsObject =
    JsObject("name" -> JsString(name), "avatarUrl" -> JsString(s"https://cdn/$name.png"), "userId" -> JsString(userId))

  private def discord(id: String, botName: String, userId: String): JsValue =
    JsObject("id" -> JsString(id), "name" -> JsString("Guild " + id), "owner" -> JsString("Someone"), "bot" -> bot(botName, userId))

  private def event(at: String, tag: String): JsValue =
    JsObject("at" -> JsString(at), "tag" -> JsString(tag), "text" -> JsString(s"$tag happened"))

  private def world(
      name: String,
      lastPollAt: Option[String],
      discords: Vector[JsValue] = Vector.empty,
      recentEvents: Vector[JsValue] = Vector.empty,
      population: Int = 0
  ): JsValue = JsObject(
    "name" -> JsString(name),
    "population" -> JsNumber(population),
    "lastPollAt" -> lastPollAt.map(s => JsString(s): JsValue).getOrElse(JsNull),
    "nextPollAt" -> JsNull,
    "deaths15m" -> JsNumber(0),
    "levels15m" -> JsNumber(0),
    "edits15m" -> JsNumber(0),
    "battleyeGreen" -> JsBoolean(true),
    "pvpType" -> JsString("Optional PvP"),
    "discords" -> JsArray(discords),
    "recentEvents" -> JsArray(recentEvents)
  )

  private def worldsOf(merged: Vector[JsValue]): List[String] = merged.map(_.asJsObject.fields("name").asInstanceOf[JsString].value).toList

  test("a world present on only one bot passes through unchanged") {
    val onlyOne = world("Antica", Some("2026-01-01T00:00:00Z"), discords = Vector(discord("1", "Blue", "b1")))
    val merged = StatusRoute.mergeWorlds(Vector(onlyOne))
    merged shouldBe Vector(onlyOne)
  }

  test("a world present on both bots appears exactly once, not twice") {
    val fromBlue = world("Wintera", Some("2026-01-01T00:00:00Z"), discords = Vector(discord("1", "Blue", "b1")))
    val fromRed = world("Wintera", Some("2026-01-01T00:05:00Z"), discords = Vector(discord("2", "Red", "r1")))
    val merged = StatusRoute.mergeWorlds(Vector(fromBlue, fromRed))
    merged.size shouldBe 1
    worldsOf(merged) shouldBe List("Wintera")
  }

  test("merging combines discords from every contributing bot") {
    val fromBlue = world("Wintera", Some("2026-01-01T00:00:00Z"), discords = Vector(discord("1", "Blue", "b1")))
    val fromRed = world("Wintera", Some("2026-01-01T00:05:00Z"), discords = Vector(discord("2", "Red", "r1")))
    val merged = StatusRoute.mergeWorlds(Vector(fromBlue, fromRed))
    val discordIds = merged.head.asJsObject.fields("discords").asInstanceOf[JsArray].elements
      .map(_.asJsObject.fields("id").asInstanceOf[JsString].value)
    discordIds should contain theSameElementsAs List("1", "2")
  }

  test("world-level stats come from whichever entry polled most recently") {
    val stale = world("Wintera", Some("2026-01-01T00:00:00Z"), population = 10)
    val fresh = world("Wintera", Some("2026-01-01T00:05:00Z"), population = 99)
    val merged = StatusRoute.mergeWorlds(Vector(stale, fresh))
    merged.head.asJsObject.fields("population").asInstanceOf[JsNumber].value.toInt shouldBe 99
  }

  test("a null lastPollAt loses to any entry with a real timestamp") {
    val neverPolled = world("Wintera", None, population = 10)
    val polled = world("Wintera", Some("2026-01-01T00:00:00Z"), population = 5)
    val merged = StatusRoute.mergeWorlds(Vector(neverPolled, polled))
    merged.head.asJsObject.fields("population").asInstanceOf[JsNumber].value.toInt shouldBe 5
  }

  test("recentEvents from both entries are combined, newest first") {
    val fromBlue = world("Wintera", Some("2026-01-01T00:00:00Z"), recentEvents = Vector(event("2026-01-01T00:00:00Z", "death")))
    val fromRed = world("Wintera", Some("2026-01-01T00:05:00Z"), recentEvents = Vector(event("2026-01-01T00:05:00Z", "level-up")))
    val merged = StatusRoute.mergeWorlds(Vector(fromBlue, fromRed))
    val tags = merged.head.asJsObject.fields("recentEvents").asInstanceOf[JsArray].elements.map(_.asJsObject.fields("tag").asInstanceOf[JsString].value)
    tags shouldBe List("level-up", "death")
  }

  test("merged recentEvents are capped at 50, even combined across bots") {
    val manyBlue = (1 to 40).map(i => event(f"2026-01-01T00:$i%02d:00Z", s"blue-$i")).toVector
    val manyRed = (1 to 40).map(i => event(f"2026-01-01T01:$i%02d:00Z", s"red-$i")).toVector
    val fromBlue = world("Wintera", Some("2026-01-01T00:40:00Z"), recentEvents = manyBlue)
    val fromRed = world("Wintera", Some("2026-01-01T01:40:00Z"), recentEvents = manyRed)
    val merged = StatusRoute.mergeWorlds(Vector(fromBlue, fromRed))
    merged.head.asJsObject.fields("recentEvents").asInstanceOf[JsArray].elements.size shouldBe 50
  }

  test("results are sorted by world name") {
    val b = world("Bravoria", Some("2026-01-01T00:00:00Z"))
    val a = world("Antica", Some("2026-01-01T00:00:00Z"))
    val merged = StatusRoute.mergeWorlds(Vector(b, a))
    worldsOf(merged) shouldBe List("Antica", "Bravoria")
  }

  test("an empty input yields an empty result") {
    StatusRoute.mergeWorlds(Vector.empty) shouldBe Vector.empty
  }

  private def lanes(bgDepth: Int, olDepth: Int): JsObject = JsObject(
    "background" -> JsObject("queueDepth" -> JsNumber(bgDepth), "totalDropped" -> JsNumber(0), "totalSuperseded" -> JsNumber(0), "labels" -> JsObject.empty),
    "online-list" -> JsObject("queueDepth" -> JsNumber(olDepth), "totalDropped" -> JsNumber(0), "totalSuperseded" -> JsNumber(0), "labels" -> JsObject.empty)
  )

  private def botStatus(name: String, userId: String, worlds: Vector[JsValue], publishedAt: String = "2026-01-01T00:00:00Z"): JsObject = JsObject(
    "bot" -> bot(name, userId),
    "publishedAt" -> JsString(publishedAt),
    "worlds" -> JsArray(worlds),
    "rateLimitLanes" -> lanes(0, 0)
  )

  test("buildBotsJson always includes the primary/self entry first, even with no secondaries") {
    val own = botStatus("Blue", "b1", Vector(world("Antica", Some("2026-01-01T00:00:00Z"))))
    val bots = StatusRoute.buildBotsJson(own, Vector.empty)
    bots.elements.size shouldBe 1
    bots.elements.head.asJsObject.fields("bot").asJsObject.fields("name").asInstanceOf[JsString].value shouldBe "Blue"
  }

  test("buildBotsJson includes one entry per connected secondary, after self") {
    val own = botStatus("Blue", "b1", Vector.empty)
    val red = botStatus("Red", "r1", Vector.empty)
    val green = botStatus("Green", "g1", Vector.empty)
    val bots = StatusRoute.buildBotsJson(own, Vector(red, green))
    val names = bots.elements.map(_.asJsObject.fields("bot").asJsObject.fields("name").asInstanceOf[JsString].value)
    names shouldBe List("Blue", "Red", "Green")
  }

  test("buildBotsJson derives worldCount and population from each bot's own worlds") {
    val own = botStatus("Blue", "b1", Vector(
      world("Antica", Some("2026-01-01T00:00:00Z"), population = 100),
      world("Wintera", Some("2026-01-01T00:00:00Z"), population = 50)
    ))
    val bots = StatusRoute.buildBotsJson(own, Vector.empty)
    val entry = bots.elements.head.asJsObject
    entry.fields("worldCount").asInstanceOf[JsNumber].value.toInt shouldBe 2
    entry.fields("population").asInstanceOf[JsNumber].value.toInt shouldBe 150
  }

  test("buildBotsJson carries each bot's own rate-limit lanes through unchanged") {
    val own = JsObject(
      "bot" -> bot("Blue", "b1"),
      "publishedAt" -> JsString("2026-01-01T00:00:00Z"),
      "worlds" -> JsArray(Vector.empty),
      "rateLimitLanes" -> lanes(7, 3)
    )
    val bots = StatusRoute.buildBotsJson(own, Vector.empty)
    val entryLanes = bots.elements.head.asJsObject.fields("rateLimitLanes").asJsObject
    entryLanes.fields("background").asJsObject.fields("queueDepth").asInstanceOf[JsNumber].value.toInt shouldBe 7
    entryLanes.fields("online-list").asJsObject.fields("queueDepth").asInstanceOf[JsNumber].value.toInt shouldBe 3
  }
}
