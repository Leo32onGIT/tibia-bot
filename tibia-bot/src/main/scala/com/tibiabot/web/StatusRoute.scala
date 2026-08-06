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

  /** Dashboard-local static images (the BattlEye status icons — previously
   *  hotlinked from the Tibia Fandom wiki, now vendored into the repo so the
   *  dashboard doesn't depend on a third party staying up — plus the bot
   *  avatar, used for both the sidebar brand mark and the page favicon).
   *  Same filesystem-override-then-classpath lookup as `dashboardHtml`. An
   *  explicit allow-list, not a raw path segment read, so this can't be used
   *  to read arbitrary files from the image directory. */
  private val dashboardImages: Set[String] = Set("be-icon-green.gif", "be-icon-yellow.gif", "avatar.png")

  /** Content type for an allow-listed dashboard image. Only the extensions
   *  actually present in [[dashboardImages]] are mapped; gif is the fallback
   *  because the BattlEye icons came first and are still the common case. */
  private def imageContentType(filename: String): ContentType =
    if (filename.endsWith(".png")) ContentType(MediaTypes.`image/png`)
    else ContentType(MediaTypes.`image/gif`)

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
      HttpResponse(entity = HttpEntity(imageContentType(filename), bytes))
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

  private def apiCallStatsJson(stats: tracking.ApiCallStats): JsValue = JsObject(
    "total" -> JsNumber(stats.total),
    "perSecond" -> JsNumber(stats.perSecond),
    "perHour" -> JsNumber(stats.perHour)
  )

  /** One upstream's throughput for the dashboard's API panel. `observedSeconds`
   *  travels with it so the frontend can tell a genuine hourly rate from a
   *  partial hour shortly after a restart, rather than presenting "everything
   *  since boot" as if it were a rate. */
  private def apiThroughputJson(metrics: tracking.ApiCallMetrics): JsObject = {
    val snap = metrics.snapshot()
    JsObject(
      "total" -> JsNumber(snap.total),
      "perSecond" -> JsNumber(snap.perSecond),
      "perHour" -> JsNumber(snap.perHour),
      "observedSeconds" -> JsNumber(snap.observedSeconds),
      "history" -> JsArray(snap.history.map(v => JsNumber(v): JsValue)),
      "dimensions" -> JsObject(snap.dimensions.map { case (dimension, byValue) =>
        dimension -> (JsObject(byValue.map { case (value, stats) => value -> apiCallStatsJson(stats) }): JsValue)
      })
    )
  }

  private implicit val ec: ExecutionContext = ExecutionContext.global

  /** This process's own Discord identity — attached to every discords/guild
   *  entry this process reports (below), never to the world as a whole: a
   *  world can be served by guilds on more than one bot, but a guild always
   *  belongs to exactly one. avatarUrl/userId let the dashboard badge (and
   *  hover-identify) which bot serves a given guild on a merged view. */
  private def botIdentityJson: JsObject = JsObject(
    "name" -> JsString(discordGateway.selfUserName),
    "avatarUrl" -> JsString(discordGateway.selfUserAvatarUrl),
    "userId" -> JsString(discordGateway.selfUserId),
    "role" -> JsString(Config.BotRole.current match {
      case Config.BotRole.Primary => "primary"
      case Config.BotRole.Secondary => "secondary"
      case Config.BotRole.Disabled => "disabled"
    })
  )

  /** This process's own worlds — its JDA guild membership plus (as a
   *  shared-world-cycle primary) any extra worlds it polls on a secondary's
   *  behalf, per `BotApp.startBot`. Public: a secondary calls this directly
   *  to build the snapshot it publishes to Redis, reusing the exact same
   *  shape its own local dashboard would show if it ran one. */
  def buildWorldsJson(): JsArray = {
    val worldSnapshots = worldMetricsRegistry.snapshotAll()
    val streams = streamSupervisor.snapshot
    val bot = botIdentityJson

    val worldsJson = streams.keySet.union(worldSnapshots.keySet).toList.sorted.map { world =>
      val snap = worldSnapshots.getOrElse(world, tracking.WorldSnapshot(0, None, None, 0, 0, 0, battleyeGreen = true, pvpType = ""))
      val discordsJson = streams.get(world).map(_.usedBy).getOrElse(Nil).map { d =>
        val guild = discordGateway.guildById(d.id)
        val name = Option(guild).map(_.getName).getOrElse("Unknown")
        // getOwner is a cached lookup (no REST call) and can be null if the
        // owner isn't in JDA's member cache — same fallback used elsewhere
        // in this codebase (ChannelService.scala) for the same reason.
        val owner = Option(guild).flatMap(g => Option(g.getOwner)).map(_.getEffectiveName).getOrElse("Unknown")
        JsObject("id" -> JsString(d.id), "name" -> JsString(name), "owner" -> JsString(owner), "bot" -> bot)
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

  /** This process's full status snapshot — bot identity, when this was built,
   *  its own worlds, and its own rate-limit lanes. Public: this is exactly
   *  what a secondary publishes to Redis (see `secondaryStatusKeyPrefix`) so
   *  the primary can show a "which bots are connected" fleet view alongside
   *  the merged worlds, without a second Redis round-trip or a different
   *  shape to decode. */
  def buildBotStatusJson(): JsObject = JsObject(
    "bot" -> botIdentityJson,
    "publishedAt" -> JsString(java.time.Instant.now().toString),
    "startedAt" -> JsString(StatusRoute.startedAt.toString),
    "worlds" -> buildWorldsJson(),
    "rateLimitLanes" -> JsObject(
      "background" -> laneJson(outboundSender),
      "online-list" -> laneJson(onlineListSender, adaptiveRefresh = true)
    ),
    // Per-bot, and published by a secondary along with everything else here, so
    // the dashboard's throughput panel can show one subtree per connected bot
    // without a separate fetch or a second shape to decode.
    "apiThroughput" -> JsObject(
      "discord" -> apiThroughputJson(tracking.ApiMetrics.discord),
      "tibiadata" -> apiThroughputJson(tracking.ApiMetrics.tibiaData)
    )
  )

  /** Any shared-world-cycle secondary's published status, fetched raw — see
   *  `secondaryStatusKeyPrefix`. Only a Primary looks; a plain/secondary
   *  deployment gets an empty list back with no Redis round-trip at all.
   *  Uses `keysMatching` rather than a fixed secondary list so this supports
   *  however many secondaries are actually publishing, with zero config on
   *  the primary side when a new one joins. A secondary that's gone quiet
   *  (past its publish TTL) just stops appearing — no special-casing needed. */
  private def remoteSecondaryStatuses(): Future[Vector[JsObject]] =
    if (Config.BotRole.current != Config.BotRole.Primary) Future.successful(Vector.empty)
    else {
      persistence.RedisCacheProvider.cache.keysMatching(s"${StatusRoute.secondaryStatusKeyPrefix}*").flatMap { keys =>
        Future.traverse(keys)(persistence.RedisCacheProvider.cache.get).map { values =>
          values.flatten.flatMap { json =>
            try Some(json.parseJson.asJsObject)
            catch {
              case NonFatal(e) =>
                logger.warn(s"Failed to decode a secondary status snapshot, skipping it: ${e.getMessage}")
                None
            }
          }.toVector
        }
      }.recover {
        case NonFatal(e) =>
          logger.warn(s"Failed to fetch secondary status snapshots: ${e.getMessage}")
          Vector.empty
      }
    }

  private def buildStatusJson(): Future[JsObject] = {
    val ownStatus = buildBotStatusJson()
    remoteSecondaryStatuses().map { secondaryStatuses =>
      val ownWorlds = ownStatus.fields("worlds").asInstanceOf[JsArray].elements
      val remoteWorlds = secondaryStatuses.flatMap(s => s.fields.get("worlds").collect { case JsArray(el) => el }.getOrElse(Vector.empty))
      JsObject(
        "worlds" -> JsArray(StatusRoute.mergeWorlds(ownWorlds ++ remoteWorlds)),
        "bots" -> StatusRoute.buildBotsJson(ownStatus, secondaryStatuses),
        "rateLimitLanes" -> ownStatus.fields("rateLimitLanes"),
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
   *  patreonapi.PatreonApiClient) — the same snapshot the paywall gate reads,
   *  so a supporter's patronStatus here and their ability to `/setup` come
   *  from one source and can't disagree:
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
    // Single bulk read rather than one lookup per supporter below — see
    // PaywallService.effectiveSeatLimit for the same floor-at-0 arithmetic
    // applied per-request; kept in sync here rather than called directly so
    // this stays one query instead of N.
    val extraSeatsByUser = paywallService.allExtraSeats()
    def seatLimitFor(userId: String): Int = math.max(0, Config.Patreon.seatsPerUser + extraSeatsByUser.getOrElse(userId, 0))
    // Same bulk-read reasoning as extraSeatsByUser. `active` alone can't tell
    // the panel a subscription has lapsed any more: a lapsed seat stays fully
    // active through its grace period (see PaywallService.worldsInGrace), so
    // the dashboard would otherwise show it as fine right up until the pause
    // landed a week later.
    val inGrace = paywallService.worldsInGrace()

    val seatSupporters = bySupporter.toList.map { case (userId, seats) =>
      val seatsJson = seats.map { seat =>
        val guild = discordGateway.guildById(seat.guildId)
        val guildName = Option(guild).map(_.getName).getOrElse("Unknown")
        JsObject(
          "guildId" -> JsString(seat.guildId),
          "guildName" -> JsString(guildName),
          "world" -> JsString(seat.world),
          "created" -> JsString(seat.created.toString),
          "active" -> JsBoolean(paywallService.isActive(seat.guildId, seat.world)),
          "inGrace" -> JsBoolean(inGrace.contains((seat.guildId, seat.world)))
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
        "seatLimit" -> (JsNumber(seatLimitFor(userId)): JsValue),
        "extraSeats" -> (JsNumber(extraSeatsByUser.getOrElse(userId, 0)): JsValue),
        "seats" -> (JsArray(seatsJson.toVector): JsValue)
      )
      val enriched = patreonMember.map(patreonMemberFields).getOrElse(Map.empty)
      displayName -> JsObject(base ++ enriched)
    }

    val linkedNoSeat = patreonByDiscordId.toList.filterNot { case (userId, _) => bySupporter.contains(userId) }.map { case (userId, member) =>
      val entry = Map(
        "userId" -> (JsString(userId): JsValue),
        "userName" -> (JsString(member.fullName): JsValue),
        "seatLimit" -> (JsNumber(seatLimitFor(userId)): JsValue),
        "extraSeats" -> (JsNumber(extraSeatsByUser.getOrElse(userId, 0)): JsValue),
        "seats" -> (JsArray(Vector.empty): JsValue)
      ) ++ patreonMemberFields(member)
      member.fullName -> JsObject(entry)
    }

    // No linked Discord id, so no id to key a seat-count adjustment on —
    // always the flat base default.
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
  /** When this process came up, for the dashboard's uptime readout. Taken at
   *  class-load of this object, which happens during startup wiring — close
   *  enough to process start for a figure displayed to the minute, and it
   *  cannot drift the way a per-instance field would if a route were ever
   *  rebuilt. */
  val startedAt: java.time.Instant = java.time.Instant.now()

  /** Redis key prefix a shared-world-cycle secondary publishes its worlds
   *  snapshot under (full key: this prefix + its own Discord user id, a
   *  stable and always-unique suffix — see BotApp.publishSecondaryStatus),
   *  and that a primary scans for via `keysMatching` to discover however
   *  many secondaries are currently publishing. */
  val secondaryStatusKeyPrefix = "tibia:secondary-status:"

  /** Merges local + remote world lists by `name` — a world tracked by guilds
   *  on more than one bot must appear exactly once, not once per bot (a
   *  naive concatenation would double-count it in the KPI strip and only
   *  ever show one side's guilds in the detail view, since the frontend
   *  looks up a world by name). Guild/discord lists from every contributing
   *  entry are combined (each already tagged with its own bot); world-level
   *  stats (population, deaths/levels/edits, pvp/battleye) are taken from
   *  whichever entry polled most recently, since they describe the same
   *  real Tibia world and duplicating/summing them would be wrong.
   *  recentEvents are combined and re-capped at 50 (RecentEvents' own
   *  per-process capacity) so a merge never shows more history than a
   *  single bot's own dashboard would. A pure function of JsValues (no
   *  instance state), so it's directly unit-testable. */
  def mergeWorlds(entries: Vector[JsValue]): Vector[JsValue] = {
    def asObj(v: JsValue): JsObject = v.asJsObject
    def field(o: JsObject, key: String): Option[JsValue] = o.fields.get(key)
    def instant(o: JsObject): Option[java.time.Instant] =
      field(o, "lastPollAt").flatMap {
        case JsString(s) => try Some(java.time.Instant.parse(s)) catch { case NonFatal(_) => None }
        case _ => None
      }

    entries.map(asObj).groupBy(o => field(o, "name").collect { case JsString(n) => n }.getOrElse(""))
      .toVector.sortBy(_._1).map { case (name, group) =>
        if (group.size == 1) group.head
        else {
          // Most-recently-polled entry supplies every world-level stat field;
          // guild lists and recent events are the one thing genuinely safe
          // (and necessary) to combine across bots.
          val newest = group.maxBy(o => instant(o).map(_.toEpochMilli).getOrElse(Long.MinValue))
          val allDiscords = group.flatMap(o => field(o, "discords").collect { case JsArray(el) => el }.getOrElse(Vector.empty))
          val allEvents = group.flatMap(o => field(o, "recentEvents").collect { case JsArray(el) => el }.getOrElse(Vector.empty))
          val mergedEvents = allEvents.sortBy { ev =>
            asObj(ev).fields.get("at").collect { case JsString(s) => try java.time.Instant.parse(s).toEpochMilli catch { case NonFatal(_) => 0L } }.getOrElse(0L)
          }(Ordering[Long].reverse).take(50)
          JsObject(newest.fields ++ Map(
            "name" -> JsString(name),
            "discords" -> JsArray(allDiscords),
            "recentEvents" -> JsArray(mergedEvents)
          ))
        }
      }
  }

  /** One summary row per connected bot — this process itself plus every
   *  secondary currently publishing — for the dashboard's fleet panel. Each
   *  row is a self-contained snapshot (name/avatar/role, worlds served,
   *  population, its own rate-limit lanes, when it was last published) so
   *  the frontend can render "any X secondaries" without knowing bot names
   *  in advance. A pure function of JsObjects, directly unit-testable. */
  def buildBotsJson(own: JsObject, secondaries: Vector[JsObject]): JsArray = {
    def summarize(status: JsObject): JsObject = {
      val worlds = status.fields.get("worlds").collect { case JsArray(el) => el }.getOrElse(Vector.empty)
      val population = worlds.map { w =>
        w.asJsObject.fields.get("population").collect { case JsNumber(n) => n.toInt }.getOrElse(0)
      }.sum
      // Distinct, not summed: one guild commonly tracks several worlds, so a
      // naive count over each world's list would report a guild once per world
      // it watches. Counted over this bot's own worlds (every entry here is a
      // single bot's snapshot, pre-merge), so a guild is attributed to the bot
      // actually serving it.
      val discordCount = worlds.flatMap { w =>
        w.asJsObject.fields.get("discords").collect { case JsArray(ds) => ds }.getOrElse(Vector.empty)
          .flatMap(_.asJsObject.fields.get("id").collect { case JsString(id) => id })
      }.distinct.size
      JsObject(
        "bot" -> status.fields.getOrElse("bot", JsObject.empty),
        "publishedAt" -> status.fields.getOrElse("publishedAt", JsNull),
        "startedAt" -> status.fields.getOrElse("startedAt", JsNull),
        "worldCount" -> JsNumber(worlds.size),
        "discordCount" -> JsNumber(discordCount),
        "population" -> JsNumber(population),
        "rateLimitLanes" -> status.fields.getOrElse("rateLimitLanes", JsObject.empty),
        // Absent, not empty, for a secondary still running a build from before
        // these counters existed — the dashboard renders that as "no data"
        // rather than a confident 0/s that looks like a dead bot.
        "apiThroughput" -> status.fields.getOrElse("apiThroughput", JsNull)
      )
    }
    JsArray((summarize(own) +: secondaries.map(summarize)).toVector)
  }
}
