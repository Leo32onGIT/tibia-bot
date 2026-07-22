package com.tibiabot.web

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.{Directive0, Route}
import akka.http.scaladsl.server.Directives._
import com.tibiabot.{app, discord, tracking}
import com.typesafe.scalalogging.StrictLogging
import spray.json._

/**
 * The monitoring dashboard: `/` (static HTML/JS shell) and `/status` (its JSON
 * data source), both restricted to the bot owner. Authentication (which
 * Discord user is this) is [[DiscordAuth]]'s job; this class only adds the
 * authorization on top — a future gated route would reuse [[DiscordAuth]]
 * unchanged and swap in its own guard instead of [[requireOwner]].
 */
final class StatusRoute(
  discordAuth: DiscordAuth,
  ownerId: String,
  streamSupervisor: app.StreamSupervisor,
  worldMetricsRegistry: tracking.WorldMetricsRegistry,
  recentEvents: tracking.RecentEvents,
  outboundSender: discord.RateLimitedSender,
  onlineListSender: discord.RateLimitedSender,
  discordGateway: discord.DiscordGateway
) extends StrictLogging {

  /** Read fresh on every request (not cached) so editing the file on disk —
   *  e.g. a volume-mounted `web/` directory in Docker — takes effect
   *  immediately, without rebuilding or restarting the bot. Prefers a
   *  filesystem copy at the relative path used by that mount; falls back to
   *  the copy baked into the jar (a plain `sbt run` with no mount, or the
   *  packaged default if nothing is mounted). */
  private def dashboardHtml(): String = {
    val overridePath = java.nio.file.Paths.get("web/dashboard.html")
    if (java.nio.file.Files.isReadable(overridePath)) {
      new String(java.nio.file.Files.readAllBytes(overridePath), "UTF-8")
    } else {
      val stream = getClass.getClassLoader.getResourceAsStream("web/dashboard.html")
      try scala.io.Source.fromInputStream(stream, "UTF-8").mkString
      finally stream.close()
    }
  }

  private def requireOwner(userId: String): Directive0 =
    if (userId == ownerId) pass else complete(StatusCodes.Forbidden -> "Forbidden")

  private def laneJson(sender: discord.RateLimitedSender): JsObject = JsObject(
    "queueDepth" -> JsNumber(sender.queueDepth),
    "totalDropped" -> JsNumber(sender.totalDropped),
    "totalSuperseded" -> JsNumber(sender.totalSuperseded),
    "labels" -> JsObject(sender.snapshot().map { case (label, stats) =>
      label -> JsObject("count" -> JsNumber(stats.count), "avgWaitMs" -> JsNumber(stats.avgWaitMs)): (String, JsValue)
    })
  )

  private def buildStatusJson(): JsObject = {
    val worldSnapshots = worldMetricsRegistry.snapshotAll()
    val streams = streamSupervisor.snapshot

    val worldsJson = streams.keySet.union(worldSnapshots.keySet).toList.sorted.map { world =>
      val snap = worldSnapshots.getOrElse(world, tracking.WorldSnapshot(0, None, None, 0, 0, 0))
      val discordsJson = streams.get(world).map(_.usedBy).getOrElse(Nil).map { d =>
        val guild = discordGateway.guildById(d.id)
        val name = Option(guild).map(_.getName).getOrElse("Unknown")
        JsObject("id" -> JsString(d.id), "name" -> JsString(name))
      }
      JsObject(
        "name" -> JsString(world),
        "population" -> JsNumber(snap.population),
        "lastPollAt" -> snap.lastPollAt.map(i => JsString(i.toString): JsValue).getOrElse(JsNull),
        "nextPollAt" -> snap.nextPollAt.map(i => JsString(i.toString): JsValue).getOrElse(JsNull),
        "deaths15m" -> JsNumber(snap.deaths),
        "levels15m" -> JsNumber(snap.levels),
        "edits15m" -> JsNumber(snap.edits),
        "discords" -> JsArray(discordsJson.toVector)
      )
    }

    JsObject(
      "worlds" -> JsArray(worldsJson.toVector),
      "rateLimitLanes" -> JsObject(
        "background" -> laneJson(outboundSender),
        "online-list" -> laneJson(onlineListSender)
      ),
      "recentEvents" -> JsArray(recentEvents.recent().map { ev =>
        JsObject(
          "at" -> JsString(ev.at.toString),
          "tag" -> JsString(ev.tag),
          "world" -> JsString(ev.world),
          "text" -> JsString(ev.text)
        ): JsValue
      }.toVector)
    )
  }

  val routes: Route =
    discordAuth.routes ~
    path("status") {
      get {
        discordAuth.authenticatedUser { userId =>
          requireOwner(userId) {
            complete(HttpEntity(ContentTypes.`application/json`, buildStatusJson().compactPrint))
          }
        }
      }
    } ~
    pathEndOrSingleSlash {
      get {
        discordAuth.authenticatedUser { userId =>
          requireOwner(userId) {
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, dashboardHtml()))
          }
        }
      }
    }
}
