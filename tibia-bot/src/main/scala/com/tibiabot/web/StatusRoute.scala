package com.tibiabot.web

import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpEntity, HttpResponse, MediaTypes, StatusCodes}
import akka.http.scaladsl.server.{Directive0, Route}
import akka.http.scaladsl.server.Directives._
import com.tibiabot.domain.PatreonMember
import com.tibiabot.{app, discord, paywall, persistence, tracking, Config}
import com.typesafe.scalalogging.StrictLogging
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * The monitoring dashboard: `/` (static HTML/JS shell) and `/status` (its JSON
 * data source), both restricted to the bot owner. Authentication (which
 * Discord user is this) is [[DiscordAuth]]'s job; this class only adds the
 * authorization on top — a future gated route would reuse [[DiscordAuth]]
 * unchanged and swap in its own guard instead of `requireOwner`.
 */
final class StatusRoute(
  discordAuth: DiscordAuth,
  ownerId: String,
  streamSupervisor: app.StreamSupervisor,
  worldMetricsRegistry: tracking.WorldMetricsRegistry,
  recentEventsRegistry: tracking.RecentEventsRegistry,
  outboundSender: discord.RateLimitedSender,
  onlineListSender: discord.RateLimitedSender,
  discordGateway: discord.DiscordGateway,
  logCapture: LogCapture,
  paywallService: paywall.PaywallService,
  patreonMemberRepository: persistence.PatreonMemberRepository
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

  /** Dashboard-local static images (currently just the BattlEye status icons —
   *  previously hotlinked from the Tibia Fandom wiki, now vendored into the
   *  repo so the dashboard doesn't depend on a third party staying up).
   *  Same filesystem-override-then-classpath lookup as `dashboardHtml`. An
   *  explicit allow-list, not a raw path segment read, so this can't be used
   *  to read arbitrary files from the image directory. */
  private val dashboardImages: Set[String] = Set("be-icon-green.gif", "be-icon-yellow.gif")

  /** InputStream.readAllBytes() is Java 9+; this project targets Java 8. */
  private def readAllBytes(stream: java.io.InputStream): Array[Byte] = {
    val buffer = new java.io.ByteArrayOutputStream()
    val chunk = new Array[Byte](4096)
    var n = stream.read(chunk)
    while (n != -1) {
      buffer.write(chunk, 0, n)
      n = stream.read(chunk)
    }
    buffer.toByteArray
  }

  private def dashboardImage(filename: String): HttpResponse =
    if (!dashboardImages.contains(filename)) {
      HttpResponse(StatusCodes.NotFound)
    } else {
      val overridePath = java.nio.file.Paths.get(s"web/images/$filename")
      val bytes =
        if (java.nio.file.Files.isReadable(overridePath)) {
          java.nio.file.Files.readAllBytes(overridePath)
        } else {
          val stream = getClass.getClassLoader.getResourceAsStream(s"web/images/$filename")
          try readAllBytes(stream)
          finally stream.close()
        }
      HttpResponse(entity = HttpEntity(ContentType(MediaTypes.`image/gif`), bytes))
    }

  private def requireOwner(userId: String): Directive0 =
    if (userId == ownerId) pass else complete(StatusCodes.Forbidden -> "Forbidden")

  private def laneJson(sender: discord.RateLimitedSender, adaptiveRefresh: Boolean = false): JsObject = JsObject(
    Map(
      "queueDepth" -> JsNumber(sender.queueDepth),
      "totalDropped" -> JsNumber(sender.totalDropped),
      "totalSuperseded" -> JsNumber(sender.totalSuperseded),
      "labels" -> JsObject(sender.snapshot().map { case (label, stats) =>
        label -> JsObject("count" -> JsNumber(stats.count), "avgWaitMs" -> JsNumber(stats.avgWaitMs)): (String, JsValue)
      })
    ) ++ (if (adaptiveRefresh) Map("refreshIntervalSeconds" -> JsNumber(discord.AdaptiveRefreshInterval.intervalSeconds(sender.queueDepth))) else Map.empty)
  )

  private implicit val ec: ExecutionContext = ExecutionContext.global

  /** This process's own worlds — its JDA guild membership plus (as a
   *  shared-world-cycle primary) any extra worlds it polls on a slave's
   *  behalf, per `BotApp.startBot`. Each entry is tagged with `bot` (see
   *  Config.BotRole.name) so a merged dashboard (below) can show which
   *  Discord bot actually serves it. Public: a slave calls this directly to
   *  build the snapshot it publishes to Redis, reusing the exact same shape
   *  its own local dashboard would show if it ran one. */
  def buildWorldsJson(): JsArray = {
    val worldSnapshots = worldMetricsRegistry.snapshotAll()
    val streams = streamSupervisor.snapshot

    val worldsJson = streams.keySet.union(worldSnapshots.keySet).toList.sorted.map { world =>
      val snap = worldSnapshots.getOrElse(world, tracking.WorldSnapshot(0, None, None, 0, 0, 0, battleyeGreen = true, pvpType = ""))
      val discordsJson = streams.get(world).map(_.usedBy).getOrElse(Nil).map { d =>
        val guild = discordGateway.guildById(d.id)
        val name = Option(guild).map(_.getName).getOrElse("Unknown")
        // getOwner is a cached lookup (no REST call) and can be null if the
        // owner isn't in JDA's member cache — same fallback used elsewhere
        // in this codebase (ChannelService.scala) for the same reason.
        val owner = Option(guild).flatMap(g => Option(g.getOwner)).map(_.getEffectiveName).getOrElse("Unknown")
        JsObject("id" -> JsString(d.id), "name" -> JsString(name), "owner" -> JsString(owner))
      }
      val recentEventsJson = recentEventsRegistry.forWorld(world).recent().map { ev =>
        JsObject(
          "at" -> JsString(ev.at.toString),
          "tag" -> JsString(ev.tag),
          "text" -> JsString(ev.text)
        ): JsValue
      }
      JsObject(
        "name" -> JsString(world),
        "bot" -> JsString(Config.BotRole.name),
        "population" -> JsNumber(snap.population),
        "lastPollAt" -> snap.lastPollAt.map(i => JsString(i.toString): JsValue).getOrElse(JsNull),
        "nextPollAt" -> snap.nextPollAt.map(i => JsString(i.toString): JsValue).getOrElse(JsNull),
        "deaths15m" -> JsNumber(snap.deaths),
        "levels15m" -> JsNumber(snap.levels),
        "edits15m" -> JsNumber(snap.edits),
        "battleyeGreen" -> JsBoolean(snap.battleyeGreen),
        "pvpType" -> JsString(snap.pvpType),
        "discords" -> JsArray(discordsJson.toVector),
        "recentEvents" -> JsArray(recentEventsJson.toVector)
      )
    }
    JsArray(worldsJson.toVector)
  }

  /** Any shared-world-cycle slave's published worlds, merged in — see
   *  `slaveStatusKeyPrefix`. Only a Primary looks; a plain/slave deployment
   *  gets an empty list back with no Redis round-trip at all. Uses
   *  `keysMatching` rather than a fixed slave list so this supports however
   *  many slaves are actually publishing, with zero config on the primary
   *  side when a new one joins. A slave that's gone quiet (past its
   *  publish TTL) just stops appearing — no special-casing needed. */
  private def remoteSlaveWorldsJson(): Future[Vector[JsValue]] =
    if (Config.BotRole.current != Config.BotRole.Primary) Future.successful(Vector.empty)
    else {
      persistence.RedisCacheProvider.cache.keysMatching(s"${StatusRoute.slaveStatusKeyPrefix}*").flatMap { keys =>
        Future.traverse(keys)(persistence.RedisCacheProvider.cache.get).map { values =>
          values.flatten.flatMap { json =>
            try json.parseJson.asInstanceOf[JsArray].elements
            catch {
              case NonFatal(e) =>
                logger.warn(s"Failed to decode a slave status snapshot, skipping it: ${e.getMessage}")
                Vector.empty
            }
          }.toVector
        }
      }.recover {
        case NonFatal(e) =>
          logger.warn(s"Failed to fetch slave status snapshots: ${e.getMessage}")
          Vector.empty
      }
    }

  private def buildStatusJson(): Future[JsObject] = {
    val ownWorlds = buildWorldsJson()
    remoteSlaveWorldsJson().map { remoteWorlds =>
      JsObject(
        "worlds" -> JsArray(ownWorlds.elements ++ remoteWorlds),
        "rateLimitLanes" -> JsObject(
          "background" -> laneJson(outboundSender),
          "online-list" -> laneJson(onlineListSender, adaptiveRefresh = true)
        ),
        "logAlerts" -> buildLogAlertsJson(),
        "patreon" -> buildPatreonJson()
      )
    }
  }

  /** A synced Patreon member's status/pledge, spliced onto a supporter entry
   *  when a cross-reference exists — absent (empty map) for seat-only
   *  supporters the Patreon sync hasn't (or can't) match. */
  private def patreonMemberFields(member: PatreonMember): Map[String, JsValue] = Map(
    "patreonMemberId" -> JsString(member.patreonMemberId),
    "patronStatus" -> member.patronStatus.map(s => JsString(s): JsValue).getOrElse(JsNull),
    "pledgeCents" -> JsNumber(member.pledgeCents),
    "discordUsername" -> member.discordUsername.map(s => JsString(s): JsValue).getOrElse(JsNull)
  )

  /** One entry per supporter (not per seat), each carrying their seats — the
   *  dashboard's Option-B grouped view. Guild names are resolved via
   *  `guildById` (an in-memory JDA cache read, not a REST call — same as
   *  `buildStatusJson`'s per-world discord names above) so this stays cheap
   *  on the 10s poll; `userName` uses the stored snapshot rather than a live
   *  `retrieveUser` REST lookup for the same reason — a live lookup only
   *  happens once, in PatreonAdminRoute, when a seat is actually assigned.
   *
   *  Additively merges in patreonMemberRepository's synced snapshot (see
   *  patreonapi.PatreonApiClient) — purely informational, never affects
   *  paywallService's own Discord-role gate:
   *   - a seat-holding supporter whose Discord id matches a synced member
   *     gets that member's patronStatus/pledgeCents spliced on, and their
   *     Patreon fullName supersedes the seat's own one-time stored name;
   *   - a synced member with a linked Discord id but no seat becomes its own
   *     entry (userId set, empty seats — the existing add/remove-seat flow
   *     still targets a real Discord id, just starting from zero seats);
   *   - a synced member never linked to Discord at all also becomes its own
   *     entry, but with `userId: null` — the dashboard has no Discord id to
   *     act on, so it renders informational-only, no seat-management
   *     buttons.
   *  Members with no patron_status at all (Patreon's own null state — never
   *  completed becoming a patron, distinct from a real active/former/declined
   *  status) are dropped before any of this, seat-holders included. */
  private def buildPatreonJson(): JsArray = {
    val bySupporter = paywallService.allSeats().groupBy(_.userId)
    // A null patron_status (never completed becoming a patron, or a similar
    // Patreon-side edge state) isn't worth surfacing on the dashboard.
    val patreonMembers = patreonMemberRepository.snapshot().filter(_.patronStatus.isDefined)
    val patreonByDiscordId = patreonMembers.flatMap(m => m.discordUserId.map(_ -> m)).toMap
    val unlinkedMembers = patreonMembers.filter(_.discordUserId.isEmpty)

    val seatSupporters = bySupporter.toList.map { case (userId, seats) =>
      val seatsJson = seats.map { seat =>
        val guild = discordGateway.guildById(seat.guildId)
        val guildName = Option(guild).map(_.getName).getOrElse("Unknown")
        JsObject(
          "guildId" -> JsString(seat.guildId),
          "guildName" -> JsString(guildName),
          "world" -> JsString(seat.world),
          "created" -> JsString(seat.created.toString),
          "active" -> JsBoolean(paywallService.isActive(seat.guildId, seat.world))
        ): JsValue
      }
      // A confirmed Patreon cross-reference's fullName supersedes the seat's
      // own stored name, which is just a one-time Discord lookup taken when
      // the seat was assigned (see the class doc above) and can go stale or
      // was never a real name to begin with.
      val patreonMember = patreonByDiscordId.get(userId)
      val displayName = patreonMember.map(_.fullName).getOrElse(seats.headOption.map(_.userName).getOrElse(""))
      val base = Map(
        "userId" -> (JsString(userId): JsValue),
        "userName" -> (JsString(displayName): JsValue),
        "seatLimit" -> (JsNumber(Config.Patreon.seatsPerUser): JsValue),
        "seats" -> (JsArray(seatsJson.toVector): JsValue)
      )
      val enriched = patreonMember.map(patreonMemberFields).getOrElse(Map.empty)
      displayName -> JsObject(base ++ enriched)
    }

    val linkedNoSeat = patreonByDiscordId.toList.filterNot { case (userId, _) => bySupporter.contains(userId) }.map { case (userId, member) =>
      val entry = Map(
        "userId" -> (JsString(userId): JsValue),
        "userName" -> (JsString(member.fullName): JsValue),
        "seatLimit" -> (JsNumber(Config.Patreon.seatsPerUser): JsValue),
        "seats" -> (JsArray(Vector.empty): JsValue)
      ) ++ patreonMemberFields(member)
      member.fullName -> JsObject(entry)
    }

    val unlinked = unlinkedMembers.map { member =>
      val entry = Map(
        "userId" -> (JsNull: JsValue),
        "userName" -> (JsString(member.fullName): JsValue),
        "seatLimit" -> (JsNumber(Config.Patreon.seatsPerUser): JsValue),
        "seats" -> (JsArray(Vector.empty): JsValue)
      ) ++ patreonMemberFields(member)
      member.fullName -> JsObject(entry)
    }

    val all = (seatSupporters ++ linkedNoSeat ++ unlinked).sortBy(_._1).map(_._2)
    JsArray(all.toVector)
  }

  private def logEventJson(ev: LogEvent): JsValue = JsObject(
    "at" -> JsString(ev.at.toString),
    "level" -> JsString(ev.level),
    "logger" -> JsString(ev.logger),
    "message" -> JsString(ev.message),
    "count" -> JsNumber(ev.count)
  )

  private def buildLogAlertsJson(): JsObject = {
    val errors = logCapture.recentErrors()
    val warnings = logCapture.recentWarnings()
    // Total occurrences (summing repeat counts), not distinct rows — a
    // repeating warning collapsed to one row below shouldn't make the
    // header undercount how often it's actually firing.
    JsObject(
      "errorCount" -> JsNumber(errors.map(_.count).sum),
      "warnCount" -> JsNumber(warnings.map(_.count).sum),
      "errors" -> JsArray(errors.map(logEventJson).toVector),
      "warnings" -> JsArray(warnings.map(logEventJson).toVector)
    )
  }

  val routes: Route =
    discordAuth.routes ~
    path("status") {
      get {
        discordAuth.authenticatedUser { userId =>
          requireOwner(userId) {
            complete(buildStatusJson().map(json => HttpEntity(ContentTypes.`application/json`, json.compactPrint)))
          }
        }
      }
    } ~
    path("images" / Segment) { filename =>
      get {
        discordAuth.authenticatedUser { userId =>
          requireOwner(userId) {
            complete(dashboardImage(filename))
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

object StatusRoute {
  /** Redis key prefix a shared-world-cycle slave publishes its worlds
   *  snapshot under (full key: this prefix + Config.BotRole.name), and that
   *  a primary scans for via `keysMatching` to discover however many slaves
   *  are currently publishing. */
  val slaveStatusKeyPrefix = "tibia:slave-status:"
}
