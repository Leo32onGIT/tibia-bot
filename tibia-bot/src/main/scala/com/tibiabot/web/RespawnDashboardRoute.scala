package com.tibiabot.web

import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpEntity, MediaTypes, StatusCodes}
import akka.http.scaladsl.model.headers.CacheDirectives.{`max-age`, `public`}
import akka.http.scaladsl.model.headers.`Cache-Control`
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import com.typesafe.scalalogging.StrictLogging
import spray.json._

/** The member-facing respawn dashboard, mounted at `/dashboard`.
 *
 *  The counterpart to [[StatusRoute]], which is the owner's monitoring
 *  dashboard at `/status`. Both sit behind the same [[DiscordAuth]] session,
 *  but they gate on completely different things: that one asks "are you the
 *  owner", this one asks "which of the bot's guilds can you see a tracked world
 *  in, and what may you do there" — see [[DashboardAccessService]].
 *
 *  The owner is not special here. They land on the member dashboard like
 *  anybody else, and reach the monitoring one by going to `/status`; a session
 *  is good for both. Making this route branch on identity would mean the owner
 *  could never see what their own members see, which is the surest way to ship
 *  a broken member experience.
 */
final class RespawnDashboardRoute(
  discordAuth: DiscordAuth,
  accessService: DashboardAccessService,
  spriteCache: CreatureSpriteCache,
  boardOf: String => List[com.tibiabot.respawn.RespawnBoardEntry],
  limitsOf: (String, String) => Option[BoardLimits],
  actions: RespawnActionPort
)(implicit blocking: scala.concurrent.ExecutionContext) extends StrictLogging {

  /** Runs blocking work somewhere it cannot hurt.
   *
   *  Everything this route reads is blocking — Discord REST calls to resolve
   *  access, database round trips for a board — and akka's HTTP dispatcher is a
   *  small pool shared with every other request. One slow Discord call on that
   *  pool does not just slow its own request; it takes a server thread out of
   *  circulation for everybody. So reads go to the same pool the writes already
   *  use, and the request simply is not completed until they answer. */
  private def read[A](work: => A)(inner: A => Route): Route =
    onSuccess(scala.concurrent.Future(work)(blocking))(inner)

  /** Same filesystem-override-then-classpath lookup as StatusRoute's, and for
   *  the same reason: editing the file in a volume-mounted `web/` takes effect
   *  without a rebuild. */
  private def page(name: String): String = {
    val overridePath = java.nio.file.Paths.get(s"web/$name")
    if (java.nio.file.Files.isReadable(overridePath)) {
      new String(java.nio.file.Files.readAllBytes(overridePath), "UTF-8")
    } else {
      val stream = getClass.getClassLoader.getResourceAsStream(s"web/$name")
      try scala.io.Source.fromInputStream(stream, "UTF-8").mkString
      finally stream.close()
    }
  }

  /** Guild ids from the visitor's login. Empty when the cache has aged out or
   *  the process restarted — which resolves to no access rather than an error,
   *  and the page offers a sign-in to refresh it. */
  private def guildIdsOf(userId: String): Set[String] =
    discordAuth.userGuilds.get(userId).getOrElse(Set.empty)

  /** Runs `inner` only if this visitor genuinely has access to `guildId` right
   *  now. Re-resolved on every request rather than trusting the id in the URL,
   *  which is the whole of the authorization for this area — a guild id is
   *  guessable, so reaching one has to depend on the check, not on knowing it. */
  private def withAccess(guildId: String)(inner: GuildAccess => Route): Route =
    withAccessAs(guildId)((_, access) => inner(access))

  /** As [[withAccess]], but also hands over who is asking — which every write
   *  needs and no read does.
   *
   *  Answered from the last few seconds where it can be. Resolving this costs a
   *  Discord REST call per candidate guild, and an open board asks six times a
   *  minute — paying that on every poll is what made the dashboard feel slow.
   *  Acting on somebody else's claim goes through [[withModerator]], which
   *  never reads from that memory. */
  private def withAccessAs(guildId: String)(inner: (String, GuildAccess) => Route): Route =
    discordAuth.authenticatedUser { userId =>
      read(accessService.rememberedAccessFor(userId, guildIdsOf(userId))) { granted =>
        granted.find(_.guildId == guildId) match {
          case Some(access) => inner(userId, access)
          case None         => complete(StatusCodes.Forbidden -> "Forbidden")
        }
      }
    }

  /** As [[withAccessAs]], but also requires the moderator tier in *this* guild.
   *
   *  Separate from the page's own hiding of these tools: that is convenience,
   *  and this is the control. Resolved per request, so somebody who lost the
   *  role a minute ago is refused on their next action rather than whenever a
   *  cache happens to expire. */
  private def withModerator(guildId: String)(inner: (String, GuildAccess) => Route): Route =
    discordAuth.authenticatedUser { userId =>
      // Deliberately not the remembered answer. These act on other people's
      // claims, and somebody who lost the role a minute ago must be refused
      // now rather than whenever a cache happens to expire.
      read(accessService.accessFor(userId, guildIdsOf(userId))) { granted =>
        granted.find(_.guildId == guildId) match {
          case Some(access) if access.tier.atLeast(AccessTier.Moderator) => inner(userId, access)
          case _ => complete(StatusCodes.Forbidden -> "Forbidden")
        }
      }
    }

  private def json(value: JsValue) =
    complete(HttpEntity(ContentTypes.`application/json`, value.compactPrint))

  /** Completes once the action has actually been performed — which for a guild
   *  another bot runs means once that process has answered. Nothing is parked
   *  waiting: the request simply is not completed until the Future is. */
  private def actionResult(result: scala.concurrent.Future[ActionResult]): Route =
    onSuccess(result) { answer =>
      json(JsObject("ok" -> JsBoolean(answer.ok), "message" -> JsString(answer.message)))
    }

  private def badRequest(message: String) =
    complete(StatusCodes.BadRequest ->
      HttpEntity(ContentTypes.`application/json`,
        JsObject("ok" -> JsBoolean(false), "message" -> JsString(message)).compactPrint))

  private def html(body: String) =
    complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, body))

  /** Escapes text that came from Discord — a guild name is chosen by somebody
   *  else and lands in our markup, so it is never interpolated raw. */
  private def esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
      .replace("\"", "&quot;").replace("'", "&#39;")

  private def shell(title: String, inner: String): String =
    page("respawn.html").replace("<!--TITLE-->", esc(title)).replace("<!--BODY-->", inner)

  private def nowhere: String = shell("Nothing here yet",
    s"""<div class="empty">
       |  <i class="ti ti-mood-empty" aria-hidden="true"></i>
       |  <h1>Nothing to show yet</h1>
       |  <p>The respawn dashboard needs a Discord server where Violent Bot is set up
       |     <em>and</em> where you can see at least one tracked world's channels.</p>
       |  <p class="hint">If you have just joined a server or been given access, sign in again to refresh.</p>
       |  <a class="btn" href="/dashboard/auth/login">Refresh sign-in</a>
       |</div>""".stripMargin)

  private def picker(options: List[GuildAccess]): String = shell("Choose a server",
    s"""<div class="picker">
       |  <h1>Choose a server</h1>
       |  <p>You can use the respawn board on more than one.</p>
       |  <div class="choices">
       |    ${options.map(choice).mkString("\n")}
       |  </div>
       |</div>""".stripMargin)

  private def choice(a: GuildAccess): String =
    s"""<a class="choice" href="/dashboard/g/${esc(a.guildId)}">
       |  <span class="choice-name">${esc(a.guildName)}</span>
       |  <span class="choice-meta">${a.worlds.size} world${if (a.worlds.size == 1) "" else "s"} &middot; ${esc(a.worlds.take(3).map(esc).mkString(", "))}</span>
       |  <span class="tier tier-${a.tier.name}">${a.tier.name}</span>
       |</a>""".stripMargin

  /** The board for one guild. Its own page rather than the shared shell,
   *  because it carries a script and fetches its own data.
   *
   *  Only the guild id, name and tier are baked in — everything that changes
   *  arrives from `/board`, so the page is served once and then polls. The tier
   *  is presentation only: it decides which tools are drawn, never whether they
   *  are permitted, which every route re-checks for itself. */
  private def board(a: GuildAccess): String =
    page("board.html")
      .replace("<!--TITLE-->", esc(a.guildName))
      .replace("<!--GUILD_NAME-->", esc(a.guildName))
      .replace("<!--TIER-->", esc(a.tier.name))
      // The clock a booking's weekdays and the stamina reset are read in. The
      // page draws a local axis and has to say where the server's day falls on
      // it, which it has no way to work out for itself.
      .replace("<!--SERVER_TZ-->", com.tibiabot.domain.time.Clock.Berlin.getId)
      .replace("<!--SERVER_SAVE_HOUR-->",
        com.tibiabot.scheduler.ServerSaveSchedule.serverSaveTime.getHour.toString)
      // Escaped for a JS string literal, not for markup: it lands inside quotes
      // in a script, where an apostrophe or backslash would break out.
      .replace("<!--GUILD_ID-->", a.guildId.replace("\\", "\\\\").replace("'", "\\'"))

  val routes: Route =
    pathEndOrSingleSlash {
      get {
        discordAuth.authenticatedUser { userId =>
          read(accessService.entryFor(userId, guildIdsOf(userId))) {
            case DashboardEntry.Nowhere            => html(nowhere)
            case DashboardEntry.Straight(access)   => html(board(access))
            case DashboardEntry.Choose(options)    => html(picker(options))
          }
        }
      }
    } ~
    path("g" / Segment) { guildId =>
      get {
        withAccess(guildId)(access => html(board(access)))
      }
    } ~
    path("g" / Segment / "board") { guildId =>
      get {
        withAccessAs(guildId) { (userId, access) =>
          read((boardOf(guildId), limitsOf(guildId, userId))) { case (entries, limits) =>
            complete(HttpEntity(ContentTypes.`application/json`,
              RespawnDashboardRoute.boardJson(entries, access.tier, userId, limits).compactPrint))
          }
        }
      }
    } ~
    path("g" / Segment / "claim") { guildId =>
      post {
        withAccessAs(guildId) { (userId, _) =>
          entity(as[String]) { body =>
            val fields = RespawnDashboardRoute.parseBody(body)
            fields.get("code").map(_.trim).filter(_.nonEmpty) match {
              case None => badRequest("Which spawn?")
              case Some(code) =>
                val minutes = fields.get("minutes").flatMap(m => scala.util.Try(m.toInt).toOption)
                actionResult(actions.claim(
                  guildId, userId, fields.getOrElse("character", "").trim, code, minutes))
            }
          }
        }
      }
    } ~
    path("g" / Segment / "release") { guildId =>
      post {
        withAccessAs(guildId) { (userId, _) =>
          entity(as[String]) { body =>
            val code = RespawnDashboardRoute.parseBody(body).get("code").map(_.trim).filter(_.nonEmpty)
            actionResult(actions.release(guildId, userId, code))
          }
        }
      }
    } ~
    path("g" / Segment / "extend") { guildId =>
      post {
        withAccessAs(guildId) { (userId, _) =>
          entity(as[String]) { body =>
            RespawnDashboardRoute.parseBody(body).get("minutes").flatMap(m => scala.util.Try(m.toInt).toOption) match {
              case Some(extra) if extra > 0 => actionResult(actions.extend(guildId, userId, extra))
              case _ => badRequest("How much longer?")
            }
          }
        }
      }
    } ~
    path("g" / Segment / "book") { guildId =>
      post {
        withAccessAs(guildId) { (userId, _) =>
          entity(as[String]) { body =>
            val fields = RespawnDashboardRoute.parseBody(body)
            val parsed = for {
              code <- fields.get("code").map(_.trim).filter(_.nonEmpty)
              // An absolute instant, so the page can pick a time in the reader's
              // own zone without either side agreeing a timezone.
              start <- fields.get("startsAt").flatMap(s =>
                scala.util.Try(java.time.Instant.parse(s).atZone(java.time.ZoneOffset.UTC)).toOption)
              minutes <- fields.get("minutes").flatMap(m => scala.util.Try(m.toInt).toOption).filter(_ > 0)
            } yield (code, start, minutes)
            parsed match {
              case None => badRequest("A booking needs a spawn, a start time and a length.")
              case Some((code, start, minutes)) =>
                val days = fields.get("days").flatMap(d => scala.util.Try(d.toInt).toOption)
                  .filter(d => d >= 0 && d <= com.tibiabot.domain.RespawnSchedule.EveryDay)
                  .getOrElse(com.tibiabot.domain.RespawnSchedule.OneOff)
                actionResult(actions.book(
                  guildId, userId, fields.getOrElse("character", "").trim, code, start, minutes, days))
            }
          }
        }
      }
    } ~
    path("g" / Segment / "bookings") { guildId =>
      get {
        withAccessAs(guildId) { (userId, _) =>
          parameter("code".optional) { code =>
            // Without a code, the caller's own bookings across every spawn;
            // with one, everybody's on that spawn, which is what the calendar
            // draws behind the picker.
            read(code.fold(actions.bookings(guildId, userId))(actions.bookingsOn(guildId, _))) { views =>
              json(JsObject(
                "bookings" -> JsArray(views.map(RespawnDashboardRoute.bookingJson(_, userId)).toVector)))
            }
          }
        }
      }
    } ~
    // One spawn's week. It has no page of its own: the calendar lives inside the
    // spawn window on the board, because choosing when to hunt something and
    // deciding whether to take it now are the same decision.
    path("g" / Segment / "slots") { guildId =>
      get {
        withAccessAs(guildId) { (userId, _) =>
          parameters("code", "from", "to") { (code, from, to) =>
            RespawnDashboardRoute.window(from, to) match {
              case None => badRequest("That is not a week I can show.")
              case Some((start, end)) =>
                read(actions.calendar(guildId, code, start, end)) {
                  case None       => badRequest(s"No spawn matches '$code'.")
                  case Some(view) => json(RespawnDashboardRoute.calendarJson(view, userId))
                }
            }
          }
        }
      }
    } ~
    path("g" / Segment / "cancel-booking") { guildId =>
      post {
        withAccessAs(guildId) { (userId, _) =>
          entity(as[String]) { body =>
            RespawnDashboardRoute.parseBody(body).get("scheduleId").flatMap(s => scala.util.Try(s.toLong).toOption) match {
              case Some(id) => actionResult(actions.cancelBooking(guildId, userId, id))
              case None     => badRequest("Which booking?")
            }
          }
        }
      }
    } ~
    // Moderator tools. The tier is re-resolved per request like everything
    // else, so hiding these on the page is convenience and this is the control.
    path("g" / Segment / "force-leave") { guildId =>
      post {
        withModerator(guildId) { (userId, _) =>
          entity(as[String]) { body =>
            RespawnDashboardRoute.parseBody(body).get("code").map(_.trim).filter(_.nonEmpty) match {
              case Some(code) => actionResult(actions.forceLeave(guildId, userId, code))
              case None       => badRequest("Which spawn?")
            }
          }
        }
      }
    } ~
    path("g" / Segment / "reassign") { guildId =>
      post {
        withModerator(guildId) { (userId, _) =>
          entity(as[String]) { body =>
            val fields = RespawnDashboardRoute.parseBody(body)
            (fields.get("code").map(_.trim).filter(_.nonEmpty),
             fields.get("toUserId").map(_.trim).filter(_.nonEmpty)) match {
              case (Some(code), Some(to)) => actionResult(actions.reassign(guildId, userId, code, to))
              case _ => badRequest("A reassignment needs a spawn and somebody to give it to.")
            }
          }
        }
      }
    } ~
    path("g" / Segment / "grant-stamina") { guildId =>
      post {
        withModerator(guildId) { (userId, _) =>
          entity(as[String]) { body =>
            val fields = RespawnDashboardRoute.parseBody(body)
            (fields.get("userId").map(_.trim).filter(_.nonEmpty),
             fields.get("minutes").flatMap(m => scala.util.Try(m.toInt).toOption)) match {
              case (Some(target), Some(minutes)) =>
                actionResult(actions.grantStamina(guildId, userId, target, minutes))
              case _ => badRequest("A grant needs somebody and an amount.")
            }
          }
        }
      }
    } ~
    path("images" / Segment) { filename =>
      get {
        discordAuth.authenticatedUser { _ =>
          complete(StatusRoute.dashboardImage(filename))
        }
      }
    } ~
    path("sprites" / Segment) { segment =>
      get {
        discordAuth.authenticatedUser { _ =>
          CreatureSprites.wikiNameOf(segment).flatMap(spriteCache.get) match {
            case Some(bytes) =>
              // Creature art never changes, so this is worth caching hard in the
              // browser: a board is dozens of sprites and they should be asked
              // for once, not on every poll.
              respondWithHeader(`Cache-Control`(`public`, `max-age`(RespawnDashboardRoute.SpriteMaxAge.toSeconds))) {
                complete(HttpEntity(ContentType(MediaTypes.`image/gif`), bytes))
              }
            // Either not ours, or not fetched yet — the fetch has just been
            // started, and the page falls back to the placeholder meanwhile.
            case None => complete(StatusCodes.NotFound)
          }
        }
      }
    }
}

object RespawnDashboardRoute {
  /** A month. The file behind a given creature name is immutable in practice,
   *  and a stale sprite is the mildest possible wrongness. */
  val SpriteMaxAge: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.Duration(30, java.util.concurrent.TimeUnit.DAYS)

  /** A request body's string fields, flat.
   *
   *  Tolerant on purpose: a body that isn't JSON, or isn't an object, yields
   *  nothing rather than throwing, and the route then answers "which spawn?"
   *  like any other missing field. Numbers are read as their own text so the
   *  page may send `minutes` either way. */
  private[web] def parseBody(body: String): Map[String, String] =
    scala.util.Try(body.parseJson.asJsObject.fields).getOrElse(Map.empty).collect {
      case (key, JsString(value)) => key -> value
      case (key, JsNumber(value)) => key -> value.toBigInt.toString
    }

  /** How much calendar one request may ask for. Generous enough for a month
   *  view and mean enough that a hand-edited `to` cannot ask the schedule walker
   *  for a decade of Tuesdays. */
  private[web] val MaxWindowDays: Long = 45

  /** A window from two instants off the query string, or nothing.
   *
   *  Rejects rather than clamps a silly range: a clamped window would quietly
   *  draw a different week from the one the page asked for, and the page would
   *  render it as though it were the right one. */
  private[web] def window(from: String, to: String): Option[(java.time.ZonedDateTime, java.time.ZonedDateTime)] =
    for {
      start <- scala.util.Try(java.time.Instant.parse(from).atZone(java.time.ZoneOffset.UTC)).toOption
      end   <- scala.util.Try(java.time.Instant.parse(to).atZone(java.time.ZoneOffset.UTC)).toOption
      if end.isAfter(start) && java.time.Duration.between(start, end).toDays <= MaxWindowDays
    } yield (start, end)

  /** Somebody on a board, as the page needs them: an id to act on and a name to
   *  show. Never one without the other — a name alone cannot be acted on, and an
   *  id alone cannot be read. */
  private def person(claim: com.tibiabot.domain.RespawnClaim): JsValue = JsObject(
    "id" -> JsString(claim.userId),
    "name" -> JsString(if (claim.characterName.nonEmpty) claim.characterName else claim.userName))

  private def instant(when: java.time.ZonedDateTime): JsValue = JsString(when.toInstant.toString)

  /** One spawn's window for the grid. Every block carries both ends as instants,
   *  so the page places it on a local axis without knowing anything about how
   *  the booking recurs. */
  private[web] def calendarJson(view: CalendarView, viewerId: String): JsObject = JsObject(
    Map[String, JsValue](
      "code" -> JsString(view.code),
      "name" -> JsString(view.name),
      "now" -> JsString(java.time.Instant.now().toString),
      "slots" -> JsArray(view.slots.map(slotJson(_, viewerId)).toVector)
    ) ++ CreatureSprites.urlFor(view.creature).map(url => "sprite" -> (JsString(url): JsValue))
  )

  private def slotJson(slot: CalendarSlot, viewerId: String): JsValue = JsObject(
    Map[String, JsValue](
      "owner" -> JsString(slot.owner),
      // As on the board, whose block it is turns on the account and never on the
      // name: two people can hunt on characters called the same thing.
      "mine" -> JsBoolean(slot.ownerId == viewerId),
      "startsAt" -> instant(slot.startsAt),
      "endsAt" -> instant(slot.endsAt),
      "state" -> JsString(slot.state),
      "repeats" -> JsBoolean(slot.repeats),
      "days" -> JsNumber(slot.daysOfWeek),
      // Drawn fainter, and never offered a cancel button: there is no row to
      // cancel yet, only the rule that will make one.
      "predicted" -> JsBoolean(slot.predicted)
    ) ++ slot.scheduleId.map(id => "scheduleId" -> (JsNumber(id): JsValue))
  )

  /** One booking for the calendar. `mine` decides whether it is drawn as the
   *  reader's own or as somebody else's, which is the difference between a slot
   *  they can cancel and one they can only ask for. */
  private[web] def bookingJson(booking: BookingView, viewerId: String): JsValue = JsObject(
    "scheduleId" -> JsNumber(booking.scheduleId),
    "code" -> JsString(booking.code),
    "name" -> JsString(booking.spawnName),
    "owner" -> JsString(booking.owner),
    "mine" -> JsBoolean(booking.ownerId == viewerId),
    "startsAt" -> instant(booking.startsAt),
    "minutes" -> JsNumber(booking.durationMinutes),
    "days" -> JsNumber(booking.daysOfWeek),
    "repeats" -> JsBoolean(booking.repeats),
    "state" -> JsString(booking.state)
  )

  /** One spawn for the board.
   *
   *  Times go out as absolute instants, never as pre-formatted strings or
   *  minutes-remaining: the page renders them in the reader's own zone, and a
   *  countdown computed here would be wrong by however long the response sat in
   *  flight or on screen between polls.
   *
   *  The sprite is a URL on our own domain or absent — the page falls back to
   *  the placeholder — so nothing here ever points a browser at the wiki that
   *  some of them cannot reach. */
  private def entryJson(entry: com.tibiabot.respawn.RespawnBoardEntry, viewerId: String): JsValue = {
    val spawn = entry.respawn
    val base = Map[String, JsValue](
      "id" -> JsNumber(spawn.id),
      "code" -> JsString(spawn.code),
      "name" -> JsString(spawn.name),
      "region" -> JsString(spawn.region),
      "state" -> JsString(entry.state),
      "queueLength" -> JsNumber(entry.queue.size),
      // Which action a card offers turns entirely on these, and the holder's
      // *name* cannot answer it — two people can share a character name, and a
      // viewer with no character set has none to compare against.
      "mine" -> JsBoolean(entry.active.exists(_.userId == viewerId)),
      "queued" -> JsBoolean(entry.queue.exists(_.userId == viewerId)),
      "booked" -> JsBoolean(entry.reservations.exists(_.userId == viewerId))
    )
    // Who is waiting, not just how many — a moderator handing a spawn over needs
    // somebody to hand it to, and asking them to type a Discord snowflake to do
    // it would be a worse tool than the button in the thread they already have.
    // Sent to everybody: the queue is public in the Discord thread already, so
    // hiding it here would be a pretence rather than a protection.
    val queue = Map[String, JsValue]("queue" -> JsArray(entry.queue.map(person).toVector))
    val holder = entry.active.map(claim => "holderId" -> (JsString(claim.userId): JsValue))
    val sprite = CreatureSprites.urlFor(spawn.creature).map(url => "sprite" -> (JsString(url): JsValue))
    val holderName = entry.holderLabel.map(name => "holder" -> (JsString(name): JsValue))
    // Both ends of a live hunt, so the page can draw the progress bar itself and
    // keep it moving between polls rather than freezing at whatever we sent.
    val window = entry.active.flatMap(claim =>
      for { start <- claim.startsAt; end <- claim.endsAt }
        yield Map[String, JsValue]("startsAt" -> instant(start), "endsAt" -> instant(end))
    ).getOrElse(Map.empty)
    val nextAt = entry.nextReservation.flatMap(_.startsAt).map(s => "nextAt" -> instant(s))
    val touched = entry.lastActivity.map(t => "lastActivity" -> instant(t))

    JsObject(base ++ window ++ sprite ++ holderName ++ holder ++ queue ++ nextAt ++ touched)
  }

  /** The board a visitor sees, plus what they are allowed to do with it. The
   *  tier travels so the page can hide tools it would be refused anyway —
   *  presentation only; every action re-checks it server-side. */
  def boardJson(entries: List[com.tibiabot.respawn.RespawnBoardEntry], tier: AccessTier,
                viewerId: String, limits: Option[BoardLimits]): JsObject = {
    val stamina = limits.map { l =>
      "limits" -> (JsObject(
        Map[String, JsValue](
          "maxDurationMinutes" -> JsNumber(l.maxDurationMinutes),
          "defaultDurationMinutes" -> JsNumber(l.defaultDurationMinutes),
          "claimableMinutes" -> JsNumber(l.claimableMinutes),
          "step" -> JsNumber(BoardLimits.Step),
          "boundBy" -> JsString(l.boundBy),
          "resetsAt" -> instant(l.resetsAt)
        ) ++ l.remainingMinutes.map(m => "remainingMinutes" -> (JsNumber(m): JsValue))
          ++ l.budgetMinutes.map(m => "budgetMinutes" -> (JsNumber(m): JsValue))
      ): JsValue)
    }
    JsObject(Map[String, JsValue](
      "tier" -> JsString(tier.name),
      "now" -> JsString(java.time.Instant.now().toString),
      "spawns" -> JsArray(entries.map(entryJson(_, viewerId)).toVector)
    ) ++ stamina)
  }
}
