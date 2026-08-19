package com.tibiabot.web

import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpEntity, MediaTypes, StatusCodes}
import akka.http.scaladsl.model.headers.CacheDirectives.{`max-age`, `private`, `public`}
import akka.http.scaladsl.model.headers.{`Cache-Control`, `If-None-Match`, ETag, EntityTag}
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
  /** Everybody a guild's respawn system knows, for the moderator panel's stamina
   *  picker. A function like the two above rather than the service itself, so
   *  the route can be stood up in a test without one. */
  peopleOf: String => List[com.tibiabot.persistence.KnownMember],
  actions: RespawnActionPort,
  /** Told which guild a write has just changed, so a board held for a few
   *  seconds is not what the person who made it is shown next. Does nothing by
   *  default — the board is correct either way, only a little later. */
  boardChanged: String => Unit = _ => ()
)(implicit blocking: scala.concurrent.ExecutionContext) extends StrictLogging {

  // Pure renderers, kept on the companion so they can be read back in a test
  // without standing a whole route up. Imported rather than forwarded, so every
  // call site below reads exactly as it did.
  import RespawnDashboardRoute.{esc, serverChip}

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
      read(accessService.rememberedAccessFor(userId, guildIdsOf(userId), Some(guildId))) { granted =>
        granted.find(_.guildId == guildId) match {
          case Some(access) => inner(userId, access)
          case None         => complete(StatusCodes.Forbidden -> "Forbidden")
        }
      }
    }

  /** As [[withAccess]], but also hands over everything else the visitor can
   *  reach. Only the board page needs it, and only to decide whether its server
   *  chip is a switcher or a label — there is no point offering a choice to
   *  somebody with one server. */
  private def withAccessAmong(guildId: String)(inner: (GuildAccess, AccessReport) => Route): Route =
    discordAuth.authenticatedUser { userId =>
      read(accessService.rememberedReportFor(userId, guildIdsOf(userId), Some(guildId))) { report =>
        report.granted.find(_.guildId == guildId) match {
          case Some(access) => inner(access, report)
          case None         => complete(StatusCodes.Forbidden -> "Forbidden")
        }
      }
    }

  /** Runs a *write* if the visitor may make it — deciding here only when this
   *  bot is in a position to decide.
   *
   *  For a guild this bot is in, that is here and now, exactly as before: the
   *  remembered answer is good enough for somebody acting on their own claim,
   *  and anything acting on another person is resolved fresh.
   *
   *  For a guild it is not in, it is nobody's decision to make here. The bot
   *  serving this page cannot read a member of a guild it is not in, so it used
   *  to ask the bot that runs it over Redis, wait a second, and refuse if the
   *  answer did not arrive — then send the write to that same bot anyway. Two
   *  round trips to one process, and the first of them refused perfectly
   *  ordinary members whenever it lost the race. Now the write carries who is
   *  asking and the bot that has to do the work decides, which it can do without
   *  asking anybody: see [[RespawnCommandConsumer]].
   *
   *  What is still checked here is that the visitor is in the guild at all, from
   *  their own Discord login. That is not the permission — the tier and the
   *  worlds they can see are settled at the other end — it is what stops this
   *  bot relaying writes into guilds a signed-in stranger merely named.
   */
  private def withWrite(guildId: String, required: AccessTier)(inner: String => Route): Route =
    discordAuth.authenticatedUser { userId =>
      val theirs = guildIdsOf(userId)
      if (!theirs.contains(guildId)) forbidden("You're not in that server.")
      else if (!accessService.canSee(guildId)) inner(userId)
      else read(
        if (required.atLeast(AccessTier.Moderator)) accessService.accessIn(userId, theirs, guildId)
        else accessService.rememberedAccessFor(userId, theirs, Some(guildId))
      ) { granted =>
        granted.find(_.guildId == guildId) match {
          case Some(access) if access.tier.atLeast(required) => inner(userId)
          case _ => forbidden("You don't have permission to do that on this server.")
        }
      }
    }

  /** As [[withAccessAs]], but also requires the moderator tier in *this* guild.
   *
   *  What is left of it: the one moderator surface that is a *read* rather than
   *  a write, and so has to be answered here from what this bot can see. Every
   *  moderator write goes through [[withWrite]] instead, which can hand the
   *  decision to a bot in a better position to make it.
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
      //
      // Only the guild being acted on is resolved. Resolving every guild the
      // visitor is in made a force-leave wait on the other bots about servers
      // that had nothing to do with it.
      read(accessService.accessIn(userId, guildIdsOf(userId), guildId)) { granted =>
        granted.find(_.guildId == guildId) match {
          case Some(access) if access.tier.atLeast(AccessTier.Moderator) => inner(userId, access)
          case _ => complete(StatusCodes.Forbidden -> "Forbidden")
        }
      }
    }

  private def json(value: JsValue) =
    complete(HttpEntity(ContentTypes.`application/json`, value.compactPrint))

  /** JSON with an ETag, so a caller that already has this exact answer is told
   *  so instead of being sent it again.
   *
   *  The board is polled every ten seconds by every open tab and most of those
   *  polls find nothing has changed. The work of producing the answer still
   *  happens — knowing whether anything changed means reading it — but the
   *  answer stops crossing the network, and the page can skip its own work too.
   *
   *  `maxAge` is how long a caller may reuse it without asking at all. Zero for
   *  anything live; a catalogue can sit for a while, since a spawn being
   *  renamed is not urgent.
   */
  private def cachedJson(value: JsValue, maxAge: Long): Route = {
    val body = value.compactPrint
    val tag = EntityTag(Integer.toHexString(body.hashCode) + "-" + body.length)
    optionalHeaderValueByType(`If-None-Match`) { presented =>
      // Only an exact match counts. `*` means "any representation you have",
      // which is a question about whether the thing exists rather than about
      // this particular answer, so it is not treated as a hit.
      val known = presented.exists {
        case `If-None-Match`(akka.http.scaladsl.model.headers.EntityTagRange.Default(tags)) =>
          tags.exists(_.tag == tag.tag)
        case _ => false
      }
      val headers = List(ETag(tag), `Cache-Control`(`private`(), `max-age`(maxAge)))
      respondWithHeaders(headers) {
        if (known) complete(StatusCodes.NotModified)
        else complete(HttpEntity(ContentTypes.`application/json`, body))
      }
    }
  }

  /** Completes once the action has actually been performed — which for a guild
   *  another bot runs means once that process has answered. Nothing is parked
   *  waiting: the request simply is not completed until the Future is. */
  /** Every write the dashboard performs comes through here, which is why the
   *  board is forgotten here rather than in each of the eight places that make
   *  one. Forgotten on a refusal as well as on a success: a refusal often means
   *  the board was not what the page thought it was, which is exactly when a
   *  held copy is worth throwing away. */
  private def actionResult(guildId: String, result: scala.concurrent.Future[ActionResult]): Route =
    onSuccess(result) { answer =>
      boardChanged(guildId)
      json(JsObject("ok" -> JsBoolean(answer.ok), "message" -> JsString(answer.message)))
    }

  /** A refusal the page can read. It used to complete with a bare string, which
   *  arrives as text/plain — so the browser's `res.json()` threw and every
   *  refusal, whatever its reason, reached the person as "That did not go
   *  through". A permission problem and an unreachable bot are different things
   *  and have to be able to say so. */
  private def forbidden(message: String) =
    complete(StatusCodes.Forbidden ->
      HttpEntity(ContentTypes.`application/json`,
        JsObject("ok" -> JsBoolean(false), "message" -> JsString(message)).compactPrint))

  private def badRequest(message: String) =
    complete(StatusCodes.BadRequest ->
      HttpEntity(ContentTypes.`application/json`,
        JsObject("ok" -> JsBoolean(false), "message" -> JsString(message)).compactPrint))

  private def html(body: String) =
    complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, body))

  private def shell(title: String, inner: String): String =
    page("respawn.html").replace("<!--TITLE-->", esc(title)).replace("<!--BODY-->", inner)

  /** Every candidate server failed to answer.
   *
   *  Deliberately not [[nowhere]]. That page tells somebody to go and set the
   *  bot up, which is exactly the wrong advice for a visitor whose servers are
   *  all perfectly fine and merely did not reply in time — and it is advice
   *  they would have acted on, since nothing on that page suggests trying
   *  again. */
  private def unreachable(guilds: List[UnreachableGuild]): String =
    shell("Couldn't reach your servers", RespawnDashboardRoute.unreachableBody(guilds))

  /** Signed in, and nothing resolved.
   *
   *  Two very different situations wearing one page until now. Either we never
   *  learned which servers this visitor is in — the list lives in memory beside
   *  their login and does not survive a restart — or we knew them, checked every
   *  one, and none has a respawn list they can see.
   *
   *  The old page addressed only the second and offered only the first's fix, so
   *  somebody in the second state signed in again, came back to the identical
   *  page, and signed in again. `userGuildIds` is the whole of the difference,
   *  and it was sitting in the caller untouched.
   */
  private def signedInEmpty(userGuildIds: Set[String]): String = {
    val (title, body) =
      RespawnDashboardRoute.signedInEmptyPage(guildIdsKnown = userGuildIds.nonEmpty)
    shell(title, body)
  }

  private def picker(options: List[GuildAccess],
                     unreachable: List[UnreachableGuild] = Nil): String =
    shell("Choose a server", RespawnDashboardRoute.pickerBody(options, unreachable))

  /** The board for one guild. Its own page rather than the shared shell,
   *  because it carries a script and fetches its own data.
   *
   *  Only the guild id, name and tier are baked in — everything that changes
   *  arrives from `/board`, so the page is served once and then polls. The tier
   *  is presentation only: it decides which tools are drawn, never whether they
   *  are permitted, which every route re-checks for itself. */
  private def board(a: GuildAccess, among: AccessReport): String =
    page("board.html")
      .replace("<!--TITLE-->", esc(a.guildName))
      .replace("<!--SERVER-->", serverChip(a, among))
      .replace("<!--TIER-->", esc(a.tier.name))
      // The clock a booking's weekdays and the stamina reset are read in. The
      // page draws a local axis and has to say where the server's day falls on
      // it, which it has no way to work out for itself.
      .replace("<!--SERVER_TZ-->", com.tibiabot.domain.time.Clock.Berlin.getId)
      .replace("<!--SERVER_SAVE_HOUR-->",
        com.tibiabot.scheduler.ServerSaveSchedule.serverSaveTime.getHour.toString)
      // The face on the Claim button, taken from the same `daily-emoji` setting
      // the Discord one wears (RespawnThreads.claimEmoji) so the two buttons
      // read as the same action in both places. Empty when it is not a custom
      // emoji, which the page treats as "no image".
      .replace("<!--CLAIM_EMOJI-->",
        RespawnDashboardRoute.emojiImageUrl(com.tibiabot.Config.dailyEmoji).getOrElse(""))
      // Escaped for a JS string literal, not for markup: it lands inside quotes
      // in a script, where an apostrophe or backslash would break out.
      .replace("<!--GUILD_ID-->", a.guildId.replace("\\", "\\\\").replace("'", "\\'"))

  val routes: Route =
    pathEndOrSingleSlash {
      get {
        discordAuth.authenticatedUser { userId =>
          // Bound rather than called twice: whether we knew this visitor's
          // servers at all is what decides which empty state they see, and
          // asking again after resolving could give a different answer.
          val theirs = guildIdsOf(userId)
          // Resolved once and used twice: where to send them, and — if that is
          // straight to a board — whether its header has anywhere to switch to.
          //
          // From the short memory, like every other read here. This route
          // alone resolved live, so the most-visited page on the dashboard
          // paid a Discord REST call per candidate guild and a fresh wait on
          // the other bots every single time it was opened — and, resolving
          // outside the cache, never left an answer behind for the routes that
          // do read it. Landing is a read: it decides where to send somebody,
          // and the board it sends them to re-resolves before showing them
          // anything, so nothing is granted on the strength of what is
          // remembered here.
          read(accessService.rememberedReportFor(userId, theirs)) { report =>
            accessService.entryOf(report) match {
              case DashboardEntry.Nowhere               => html(signedInEmpty(theirs))
              case DashboardEntry.Unreachable(guilds)   => html(unreachable(guilds))
              case DashboardEntry.Straight(access)      => html(board(access, report))
              case DashboardEntry.Choose(opts, missing) => html(picker(opts, missing))
            }
          }
        }
      }
    } ~
    // The picker on demand, for somebody who is already on a board and wants a
    // different one. Everything they can reach, including the support server —
    // it is left out of the *landing* decision because it would ask nearly
    // everybody a question with one answer, not because it is off limits.
    path("choose") {
      get {
        discordAuth.authenticatedUser { userId =>
          val theirs = guildIdsOf(userId)
          read(accessService.rememberedReportFor(userId, theirs)) { report =>
            val sorted = report.granted.sortBy(_.guildName.toLowerCase)
            val missing = report.unreachable.sortBy(_.guildName.toLowerCase)
            if (sorted.nonEmpty) html(picker(sorted, missing))
            else if (missing.nonEmpty) html(unreachable(missing))
            else html(signedInEmpty(theirs))
          }
        }
      }
    } ~
    path("g" / Segment) { guildId =>
      get {
        withAccessAmong(guildId)((access, among) => html(board(access, among)))
      }
    } ~
    // The part of a board that does not change: what the spawns are called and
    // what they look like. Split out because it is by far the larger half of
    // the payload and almost never differs, so a poll should not carry it.
    path("g" / Segment / "catalogue") { guildId =>
      get {
        withAccess(guildId) { _ =>
          read(boardOf(guildId)) { entries =>
            cachedJson(RespawnDashboardRoute.catalogueJson(entries),
              RespawnDashboardRoute.CatalogueMaxAge)
          }
        }
      }
    } ~
    path("g" / Segment / "board") { guildId =>
      get {
        withAccessAs(guildId) { (userId, access) =>
          read((boardOf(guildId), limitsOf(guildId, userId))) { case (entries, limits) =>
            cachedJson(RespawnDashboardRoute.boardJson(entries, access.tier, userId, limits), 0L)
          }
        }
      }
    } ~
    path("g" / Segment / "claim") { guildId =>
      post {
        withWrite(guildId, AccessTier.Member) { userId =>
          entity(as[String]) { body =>
            val fields = RespawnDashboardRoute.parseBody(body)
            fields.get("code").map(_.trim).filter(_.nonEmpty) match {
              case None => badRequest("Which spawn?")
              case Some(code) =>
                val minutes = fields.get("minutes").flatMap(m => scala.util.Try(m.toInt).toOption)
                actionResult(guildId, actions.claim(
                  guildId, userId, fields.getOrElse("character", "").trim, code, minutes))
            }
          }
        }
      }
    } ~
    path("g" / Segment / "release") { guildId =>
      post {
        withWrite(guildId, AccessTier.Member) { userId =>
          entity(as[String]) { body =>
            val code = RespawnDashboardRoute.parseBody(body).get("code").map(_.trim).filter(_.nonEmpty)
            actionResult(guildId, actions.release(guildId, userId, code))
          }
        }
      }
    } ~
    path("g" / Segment / "extend") { guildId =>
      post {
        withWrite(guildId, AccessTier.Member) { userId =>
          entity(as[String]) { body =>
            RespawnDashboardRoute.parseBody(body).get("minutes").flatMap(m => scala.util.Try(m.toInt).toOption) match {
              case Some(extra) if extra > 0 => actionResult(guildId, actions.extend(guildId, userId, extra))
              case _ => badRequest("How much longer?")
            }
          }
        }
      }
    } ~
    path("g" / Segment / "book") { guildId =>
      post {
        withWrite(guildId, AccessTier.Member) { userId =>
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
                actionResult(guildId, actions.book(
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
        withWrite(guildId, AccessTier.Member) { userId =>
          entity(as[String]) { body =>
            RespawnDashboardRoute.parseBody(body).get("scheduleId").flatMap(s => scala.util.Try(s.toLong).toOption) match {
              case Some(id) => actionResult(guildId, actions.cancelBooking(guildId, userId, id))
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
        withWrite(guildId, AccessTier.Moderator) { userId =>
          entity(as[String]) { body =>
            RespawnDashboardRoute.parseBody(body).get("code").map(_.trim).filter(_.nonEmpty) match {
              case Some(code) => actionResult(guildId, actions.forceLeave(guildId, userId, code))
              case None       => badRequest("Which spawn?")
            }
          }
        }
      }
    } ~
    path("g" / Segment / "reassign") { guildId =>
      post {
        withWrite(guildId, AccessTier.Moderator) { userId =>
          entity(as[String]) { body =>
            val fields = RespawnDashboardRoute.parseBody(body)
            (fields.get("code").map(_.trim).filter(_.nonEmpty),
             fields.get("toUserId").map(_.trim).filter(_.nonEmpty)) match {
              case (Some(code), Some(to)) => actionResult(guildId, actions.reassign(guildId, userId, code, to))
              case _ => badRequest("A reassignment needs a spawn and somebody to give it to.")
            }
          }
        }
      }
    } ~
    // Putting one evening of the calendar right. Both name the slot by the
    // instant it starts on rather than by a row id, because the grid draws days
    // a rule has not written down yet and those have no id to send.
    path("g" / Segment / "drop-slot") { guildId =>
      post {
        withWrite(guildId, AccessTier.Moderator) { userId =>
          entity(as[String]) { body =>
            val fields = RespawnDashboardRoute.parseBody(body)
            (fields.get("code").map(_.trim).filter(_.nonEmpty),
             fields.get("startsAt").flatMap(RespawnDashboardRoute.instantAt)) match {
              case (Some(code), Some(start)) =>
                actionResult(guildId, actions.dropSlot(guildId, userId, code, start))
              case _ => badRequest("Removing a slot needs a spawn and which day.")
            }
          }
        }
      }
    } ~
    path("g" / Segment / "reassign-slot") { guildId =>
      post {
        withWrite(guildId, AccessTier.Moderator) { userId =>
          entity(as[String]) { body =>
            val fields = RespawnDashboardRoute.parseBody(body)
            (fields.get("code").map(_.trim).filter(_.nonEmpty),
             fields.get("startsAt").flatMap(RespawnDashboardRoute.instantAt),
             fields.get("toUserId").map(_.trim).filter(_.nonEmpty)) match {
              case (Some(code), Some(start), Some(to)) =>
                actionResult(guildId, actions.reassignSlot(guildId, userId, code, start, to))
              case _ => badRequest("Moving a slot needs a spawn, which day, and somebody to give it to.")
            }
          }
        }
      }
    } ~
    path("g" / Segment / "edit-slot") { guildId =>
      post {
        withWrite(guildId, AccessTier.Moderator) { userId =>
          entity(as[String]) { body =>
            val fields = RespawnDashboardRoute.parseBody(body)
            (fields.get("code").map(_.trim).filter(_.nonEmpty),
             fields.get("startsAt").flatMap(RespawnDashboardRoute.instantAt),
             fields.get("minutes").flatMap(m => scala.util.Try(m.toInt).toOption).filter(_ > 0)) match {
              case (Some(code), Some(start), Some(minutes)) =>
                actionResult(guildId, actions.editSlot(guildId, userId, code, start, minutes))
              case _ => badRequest("Changing a slot's length needs a spawn, which day, and how long.")
            }
          }
        }
      }
    } ~
    // Who a moderator may hand stamina to. Behind the moderator gate rather than
    // merely unadvertised: it is a list of everybody who has used the system
    // here, which is not something an ordinary member should be able to
    // enumerate just because they can reach the board.
    path("g" / Segment / "people") { guildId =>
      get {
        withModerator(guildId) { (_, _) =>
          read(peopleOf(guildId)) { people =>
            // Cached briefly. It changes only when somebody claims for the first
            // time, and the picker is reopened far more often than that.
            cachedJson(RespawnDashboardRoute.peopleJson(people), RespawnDashboardRoute.PeopleMaxAge)
          }
        }
      }
    } ~
    path("g" / Segment / "extend-holder") { guildId =>
      post {
        withWrite(guildId, AccessTier.Moderator) { userId =>
          entity(as[String]) { body =>
            val fields = RespawnDashboardRoute.parseBody(body)
            (fields.get("code").map(_.trim).filter(_.nonEmpty),
             fields.get("minutes").flatMap(m => scala.util.Try(m.toInt).toOption).filter(_ > 0)) match {
              case (Some(code), Some(minutes)) =>
                actionResult(guildId, actions.extendHolder(guildId, userId, code, minutes))
              case _ => badRequest("An extension needs a spawn and how much longer.")
            }
          }
        }
      }
    } ~
    path("g" / Segment / "grant-stamina") { guildId =>
      post {
        withWrite(guildId, AccessTier.Moderator) { userId =>
          entity(as[String]) { body =>
            val fields = RespawnDashboardRoute.parseBody(body)
            (fields.get("userId").map(_.trim).filter(_.nonEmpty),
             fields.get("minutes").flatMap(m => scala.util.Try(m.toInt).toOption)) match {
              case (Some(target), Some(minutes)) =>
                actionResult(guildId, actions.grantStamina(guildId, userId, target, minutes))
              case _ => badRequest("A grant needs somebody and an amount.")
            }
          }
        }
      }
    } ~
    // Adding a code, which is a change to what the whole server can claim rather
    // than to one claim — so it is a moderator tool like the three above, and
    // gated the same way.
    path("g" / Segment / "spawns") { guildId =>
      post {
        withWrite(guildId, AccessTier.Moderator) { userId =>
          entity(as[String]) { body =>
            val fields = RespawnDashboardRoute.parseBody(body)
            // Only the two that a spawn cannot do without are required here. The
            // rest of the checking — the shape of a code, whether it is already
            // taken, whether a creature has a picture — belongs to the service,
            // which is where the same rules apply to every way in.
            (fields.get("code").map(_.trim).filter(_.nonEmpty),
             fields.get("name").map(_.trim).filter(_.nonEmpty)) match {
              case (Some(code), Some(name)) =>
                actionResult(guildId, actions.addSpawn(guildId, userId, code,
                  fields.getOrElse("region", "").trim, name, fields.getOrElse("creature", "").trim))
              case _ => badRequest("A spawn needs at least a code and a name.")
            }
          }
        }
      }
    } ~
    // Its own path rather than a DELETE on the one above: the page sends JSON
    // bodies everywhere else, and one endpoint with a different shape is how a
    // caller ends up sending a body nobody reads.
    path("g" / Segment / "remove-spawn") { guildId =>
      post {
        withWrite(guildId, AccessTier.Moderator) { userId =>
          entity(as[String]) { body =>
            RespawnDashboardRoute.parseBody(body).get("code").map(_.trim).filter(_.nonEmpty) match {
              case Some(code) => actionResult(guildId, actions.removeSpawn(guildId, userId, code))
              case None       => badRequest("Which spawn?")
            }
          }
        }
      }
    } ~
    // One spawn's own claim ceiling. A moderator tool like the two above, and
    // gated the same way.
    //
    // An absent or empty "minutes" clears the override rather than failing: on
    // this endpoint "no value" is the instruction to follow the server again,
    // which is the same thing an empty box means in the Discord form.
    path("g" / Segment / "spawn-max") { guildId =>
      post {
        withWrite(guildId, AccessTier.Moderator) { userId =>
          entity(as[String]) { body =>
            val fields = RespawnDashboardRoute.parseBody(body)
            fields.get("code").map(_.trim).filter(_.nonEmpty) match {
              case None => badRequest("Which spawn?")
              case Some(code) =>
                // Read the same way the Discord form reads it — see ClaimDuration,
                // which is the only thing on either side that knows what "2h"
                // means. An empty box is a clear rather than a refusal.
                com.tibiabot.domain.ClaimDuration.parse(fields.getOrElse("minutes", "")) match {
                  case Right(minutes) =>
                    actionResult(guildId, actions.setSpawnMax(guildId, userId, code, minutes))
                  case Left(problem) => badRequest(problem)
                }
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

  /** Escapes text that came from Discord — a guild name is chosen by somebody
   *  else and lands in our markup, so it is never interpolated raw. */
  private[web] def esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
      .replace("\"", "&quot;").replace("'", "&#39;")

  /** Which Discord this board belongs to, in the masthead, and the way to leave
   *  it for another.
   *
   *  Every code, claim and booking on the page belongs to exactly one server,
   *  and somebody in two communities that both run the bot has two boards that
   *  look alike — so the page says which one it is rather than leaving it to be
   *  inferred from the tab. `/dashboard` follows it the way `/status` follows
   *  the bot's name on the owner page, so the two mastheads read alike.
   *
   *  A menu only when there is somewhere to go: a chevron on the page of
   *  somebody with one server would offer a choice that does not exist, so that
   *  case stays a plain label.
   *
   *  "Somewhere to go" includes a server that failed to answer. Landing here
   *  because the picker collapsed to one entry used to strip the switcher too,
   *  so the one page a visitor most needed a way off was the one page with no
   *  way off - the full picker at `/dashboard/choose` was still there, but
   *  nothing on screen said so. A server that could not be resolved is listed
   *  unlinked, since we do not know it is theirs to open; what the menu is for
   *  in that state is the "All servers" row beneath it.
   *
   *  Rendered here rather than fetched, because the caller already holds every
   *  server this viewer can reach — it is what decides whether the chevron is
   *  drawn at all — so the menu costs one substitution and no round trip. The
   *  items are ordinary links: switching rebuilds the board against another
   *  guild, which is a page load however it is dressed. The full picker at
   *  `/dashboard/choose` stays as the landing answer and for anyone who arrives
   *  there directly.
   */
  /** Where the sign-in lives. Written once here rather than at each use, since
   *  three screens now point at it. */
  private[web] val LoginPath = "/dashboard/auth/login"

  /** The title and body for a visitor who is signed in and has nothing.
   *
   *  `guildIdsKnown` is the branch: false means we hold no list of their servers
   *  and have to ask Discord again, true means we asked, checked every server,
   *  and there was nothing on the other end. The second must not offer to sign
   *  them in again — that is the loop the old single page created.
   *
   *  The two share a composition on purpose. It is the same handshake either
   *  way; what differs is the state of the connection, which is doing the work
   *  of a sentence before any sentence is read.
   */
  private[web] def signedInEmptyPage(guildIdsKnown: Boolean): (String, String) =
    if (!guildIdsKnown)
      ("Reconnect",
       s"""<div class="empty auth">
          |  <div class="shake" aria-hidden="true">
          |    <img class="face" src="/dashboard/images/avatar.png" alt="">
          |    <span class="wire"></span>
          |    <span class="disc"><i class="ti ti-brand-discord"></i></span>
          |  </div>
          |  <h1>Reconnect your Discord</h1>
          |  <p>We only keep your server list while you're signed in. Reconnect to see the respawn list.</p>
          |  <a class="btn btn-discord" href="$LoginPath"><i class="ti ti-brand-discord" aria-hidden="true"></i> Continue with Discord</a>
          |  <p class="foot">Violent Bot only reads which servers you're in &mdash; never your messages.</p>
          |</div>""".stripMargin)
    else
      ("No respawn lists",
       // "Check again" is a plain reload, and it is the action that actually
       // helps here: somebody in this state is nearly always waiting on a
       // moderator to let them into a channel, and the answer behind this page
       // is cached for up to AccessCache.DefaultStaleAfter.
       s"""<div class="empty auth">
          |  <div class="shake" aria-hidden="true">
          |    <img class="face" src="/dashboard/images/avatar.png" alt="">
          |    <span class="wire done"></span>
          |    <span class="slot"><i class="ti ti-minus"></i></span>
          |  </div>
          |  <h1>No respawn lists on your servers</h1>
          |  <p>You're signed in &mdash; but none of the Discord servers you're in have a respawn list that you can view.</p>
          |  <a class="btn btn-discord" href="/dashboard"><i class="ti ti-refresh" aria-hidden="true"></i> Check again</a>
          |  <p class="foot">Already in a guild that uses this? Ask a moderator to let you see the respawn channels.</p>
          |</div>""".stripMargin)

  /** The servers a page could not reach, as a sentence.
   *
   *  Ids rather than names where the name was never known, which is better than
   *  a blank: it is still something to search a log for. */
  private[web] def namesOf(guilds: List[UnreachableGuild]): String = {
    val named = guilds
      .map(g => if (g.guildName.nonEmpty) g.guildName else g.guildId)
      .map(name => s"<strong>${esc(name)}</strong>")
    named match {
      case Nil           => "Reload in a moment to try again."
      case single :: Nil => s"Waiting on $single. Reload in a moment to try again."
      case many          => s"Waiting on ${many.init.mkString(", ")} and ${many.last}. Reload in a moment to try again."
    }
  }

  /** The body of the try-again page: every candidate server failed to answer.
   *
   *  Deliberately not the empty state. That page tells somebody to go and set
   *  the bot up, which is exactly the wrong advice for a visitor whose servers
   *  are all perfectly fine and merely did not reply in time — and it is advice
   *  they would have acted on, since nothing on it suggests reloading. */
  private[web] def unreachableBody(guilds: List[UnreachableGuild]): String =
    s"""<div class="empty">
       |  <i class="ti ti-plug-connected-x" aria-hidden="true"></i>
       |  <h1>Couldn't reach your servers</h1>
       |  <p>Your Discord servers didn't answer in time, so we can't tell what you
       |     have access to. Nothing has changed &mdash; this is on our side.</p>
       |  <p class="hint">${namesOf(guilds)}</p>
       |  <a class="btn" href="/dashboard">Try again</a>
       |</div>""".stripMargin

  /** The body of the picker: the list, and — when the list is knowingly short —
   *  what is missing from it.
   *
   *  The note is the whole reason a one-entry picker is worth showing at all.
   *  Without it a visitor used to two servers sees one and reasonably concludes
   *  they have lost access to the other; with it, they know to reload. Which is
   *  also why the blurb above it does not promise a choice it cannot keep. */
  private[web] def pickerBody(options: List[GuildAccess],
                              unreachable: List[UnreachableGuild] = Nil): String =
    s"""<div class="picker">
       |  <h1>Choose a server</h1>
       |  <p>${if (options.size > 1) "You can use the respawn board on more than one."
                else "Where you can use the respawn board."}</p>
       |  ${if (unreachable.isEmpty) "" else
             s"""<p class="hint warn"><i class="ti ti-alert-triangle" aria-hidden="true"></i>
                 |     <span>Some of your servers didn't answer, so this list may be short.
                 |     ${namesOf(unreachable)}</span></p>""".stripMargin}
       |  <div class="choices">
       |    ${options.map(choice).mkString("\n")}
       |  </div>
       |</div>""".stripMargin

  private[web] def choice(a: GuildAccess): String =
    s"""<a class="choice" href="/dashboard/g/${esc(a.guildId)}">
       |  <span class="choice-name">${esc(a.guildName)}</span>
       |  <span class="choice-meta">${a.worlds.size} world${if (a.worlds.size == 1) "" else "s"} &middot; ${esc(a.worlds.take(3).mkString(", "))}</span>
       |  <span class="tier tier-${a.tier.name}">${a.tier.name}</span>
       |</a>""".stripMargin

  private[web] def serverChip(a: GuildAccess, among: AccessReport): String = {
    val glyph = """<i class="ti ti-brand-discord" aria-hidden="true"></i>"""
    val suffix = """<span class="brand-suffix">/dashboard</span>"""
    // The name is wrapped rather than written bare, so it can be given a width
    // and cut when it is longer than the masthead has room for — see .sw-title.
    // `title` carries the whole of it for anyone who wants to read it.
    def named(name: String) = s"""<span class="sw-title" title="${esc(name)}">${esc(name)}</span>"""
    if (among.granted.size <= 1 && among.complete)
      s"""<span class="server">$glyph${named(a.guildName)}$suffix</span>"""
    else {
      // The server's own face, and what the viewer is on it. A row of identical
      // Discord glyphs told you nothing you could pick a server by; an avatar is
      // the thing people actually recognise a community from. Guilds that never
      // set one fall back to the glyph rather than to a broken image.
      //
      // The tier travels because a board you moderate and a board you are a
      // member of are very different places, and knowing which before you land
      // saves a page load to find out.
      val items = among.granted.map { g =>
        val here = g.guildId == a.guildId
        val tick = if (here) """<i class="ti ti-check sw-check" aria-hidden="true"></i>""" else ""
        val face = g.iconUrl match {
          case Some(url) => s"""<img class="sw-icon" src="${esc(url)}" alt="" loading="lazy">"""
          case None      => """<i class="ti ti-brand-discord sw-glyph" aria-hidden="true"></i>"""
        }
        s"""<a class="sw-item${if (here) " on" else ""}" href="/dashboard/g/${esc(g.guildId)}">""" +
          face +
          s"""<span class="sw-name">${esc(g.guildName)}</span>""" +
          s"""<span class="tier tier-${g.tier.name}">${g.tier.name}</span>$tick</a>"""
      }.mkString
      // Named but not linked: we never got an answer about this one, so
      // offering it as a destination would be offering a 403. Saying it exists
      // is the point — a visitor who came for that server needs to know it is
      // missing rather than gone.
      val pending = among.unreachable.map { g =>
        val name = if (g.guildName.nonEmpty) g.guildName else g.guildId
        s"""<span class="sw-item off" title="This server didn't answer — reload to try again">""" +
          """<i class="ti ti-plug-connected-x sw-glyph" aria-hidden="true"></i>""" +
          s"""<span class="sw-name">${esc(name)}</span>""" +
          """<span class="tier">no answer</span></span>"""
      }.mkString
      // Always present in the menu, so there is one reliable way back to the
      // full list however incomplete this menu happens to be.
      val all = """<a class="sw-item sw-all" href="/dashboard/choose">""" +
        """<i class="ti ti-layout-grid sw-glyph" aria-hidden="true"></i>""" +
        """<span class="sw-name">All servers</span></a>"""
      s"""<span class="sw" id="server-switch">""" +
        s"""<button class="sw-btn" id="server-switch-btn" type="button" """ +
        s"""aria-haspopup="true" aria-expanded="false" title="Switch server">""" +
        s"""$glyph${named(a.guildName)}$suffix""" +
        s"""<i class="ti ti-chevron-down chev" aria-hidden="true"></i></button>""" +
        s"""<span class="sw-menu"><span class="sw-label">Your servers</span>$items$pending$all</span></span>"""
    }
  }

  /** A month. The file behind a given creature name is immutable in practice,
   *  and a stale sprite is the mildest possible wrongness. */
  /** How long a catalogue may be reused without asking. Long enough that
   *  opening the board twice in a sitting costs one fetch, short enough that a
   *  renamed spawn corrects itself while somebody is still looking. */
  val CatalogueMaxAge: Long = 120L

  /** How long the stamina picker's list of people may be reused. Short, because
   *  somebody who has just made their first claim should turn up in it without
   *  the moderator wondering why they cannot find them. */
  val PeopleMaxAge: Long = 30L

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

  /** The CDN image behind a configured `<:name:id>` (or `<a:name:id>`) emoji.
   *
   *  So the dashboard's Claim button can wear the same face as the Discord one
   *  without the id being written down twice — it is configuration, and a guild
   *  that repoints `daily-emoji` should not have to remember there is a copy of
   *  it baked into a web page.
   *
   *  Nothing but a custom emoji resolves: a unicode emoji, an empty setting, or
   *  anything malformed gives None and the button simply goes without a face,
   *  which is better than the page carrying a broken image. The id is matched as
   *  digits only, so what lands in the URL — and therefore in a JS string
   *  literal on the page — cannot escape either.
   */
  private[web] def emojiImageUrl(formatted: String): Option[String] =
    """^<(a?):[A-Za-z0-9_]{2,32}:(\d{1,32})>$""".r
      .findFirstMatchIn(formatted.trim)
      .map(m => s"https://cdn.discordapp.com/emojis/${m.group(2)}.${if (m.group(1).isEmpty) "png" else "gif"}")

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

  /** One instant off a posted field, for the moderator tools that name a day of
   *  the calendar rather than a row. Anything unparseable is nothing rather than
   *  now: a slot at the wrong moment is a slot belonging to somebody who was
   *  never meant to lose it. */
  private[web] def instantAt(raw: String): Option[java.time.ZonedDateTime] =
    scala.util.Try(java.time.Instant.parse(raw.trim).atZone(java.time.ZoneOffset.UTC)).toOption

  /** Somebody on a board, as the page needs them: an id to act on and a name to
   *  show. Never one without the other — a name alone cannot be acted on, and an
   *  id alone cannot be read. */
  private def person(claim: com.tibiabot.domain.RespawnClaim): JsValue = JsObject(
    "id" -> JsString(claim.userId),
    "name" -> JsString(if (claim.characterName.nonEmpty) claim.characterName else claim.userName))

  private def personName(claim: com.tibiabot.domain.RespawnClaim): String =
    if (claim.characterName.nonEmpty) claim.characterName else claim.userName

  private def instant(when: java.time.ZonedDateTime): JsValue = JsString(when.toInstant.toString)

  /** How many rows of "up next" a spawn sends. Matches
   *  `RespawnEmbeds.RowsPerField`, because the panel and the Discord card are
   *  meant to be the same list — a page that showed twelve where the card showed
   *  ten would read as two different answers to one question. The count of
   *  everything is sent alongside, so the page can own up to what it is not
   *  showing without being sent it. */
  private[web] val UpNextRows: Int = 10

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
      // Who that actually is, for a grid where most blocks are labelled with a
      // Tibia character. Sent as two plain fields rather than one composed
      // string: the page has to decide what to draw from how much room the block
      // has, and cannot take a sentence apart again.
      "account" -> JsString(slot.account),
      "nickname" -> JsString(slot.nickname),
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
  /** What a spawn is, as opposed to what is happening on it.
   *
   *  Its name, where it is and what it looks like change when an admin edits
   *  the catalogue and not otherwise, so this is fetched once and kept. It is
   *  also most of the bytes: on a 285-spawn guild the names, regions and sprite
   *  paths are several times the size of everything that actually moves. */
  /** The people a moderator may pick between. Both names travel: the account is
   *  what is searchable and unique, the nickname is what anybody would actually
   *  look for. Neither is composed into a sentence here — the page decides how
   *  to show them, and it also has to search them separately. */
  private[web] def peopleJson(people: List[com.tibiabot.persistence.KnownMember]): JsObject =
    JsObject("people" -> JsArray(people.map { person =>
      JsObject(
        "id" -> JsString(person.userId),
        "name" -> JsString(person.userName),
        "nickname" -> JsString(person.nickname)): JsValue
    }.toVector))

  private[web] def catalogueJson(entries: List[com.tibiabot.respawn.RespawnBoardEntry]): JsObject =
    JsObject("spawns" -> JsArray(entries.map { entry =>
      val spawn = entry.respawn
      JsObject(Map[String, JsValue](
        "id" -> JsNumber(spawn.id),
        "code" -> JsString(spawn.code),
        "name" -> JsString(spawn.name),
        "region" -> JsString(spawn.region),
        // Whether this guild added it, which is the only kind a moderator may
        // remove — the bundled ones come back on the next boot. Sent to
        // everybody, because it belongs to the spawn rather than to the viewer,
        // and this payload is shared by every visitor and cached as one thing.
        "custom" -> JsBoolean(spawn.source == com.tibiabot.domain.Respawn.SourceCustom),
        // The creature by name as well as by picture, so the filter can find a
        // spawn by the monster somebody actually went there for. The sprite below
        // is built from this same field, which is what stops a search and a
        // picture ever disagreeing about which monster a spawn is. Empty for the
        // many catalogue rows whose creature is deliberately unset.
        "creature" -> JsString(spawn.creature)
      )
        ++ CreatureSprites.urlFor(spawn.creature).map(url => "sprite" -> (JsString(url): JsValue))
        // This spawn's own claim ceiling, when it has been given one. Absent
        // rather than equal to the server's, so the page can say *which* limit
        // is binding — and so a guild retuning its own number does not need
        // every catalogue row rewritten. In the catalogue rather than the board
        // because it is configuration, not state: it changes when a moderator
        // changes it, which is far rarer than the ten-second poll.
        ++ spawn.maxDurationMinutes.map(m => "maxMinutes" -> (JsNumber(m): JsValue)))
    }.toVector))

  private def entryJson(entry: com.tibiabot.respawn.RespawnBoardEntry, viewerId: String): JsValue = {
    val spawn = entry.respawn
    val base = Map[String, JsValue](
      "code" -> JsString(spawn.code),
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
    val queue =
      if (entry.queue.isEmpty) Map.empty[String, JsValue]
      else Map[String, JsValue]("queue" -> JsArray(entry.queue.map(person).toVector))

    // The same queue again, as rows to read rather than people to act on: how
    // long each would hunt for and when their turn would come. The start is
    // projected — see RespawnBoardEntry.projectedQueueStarts — and the page marks
    // it as approximate rather than printing it like a booking's.
    val now = java.time.ZonedDateTime.now()
    val waiting =
      if (entry.queue.isEmpty) Map.empty[String, JsValue]
      else Map[String, JsValue]("waiting" -> JsArray(
        entry.projectedQueueStarts(now).take(UpNextRows).map { case (claim, at) =>
          JsObject(
            "name" -> JsString(personName(claim)),
            "minutes" -> JsNumber(claim.durationMinutes),
            "startsAt" -> instant(at),
            "mine" -> JsBoolean(claim.userId == viewerId)
          ): JsValue
        }.toVector))

    // Bookings that have not started, soonest first — what the panel lists under
    // "up next" below the queue. Only the rows: a booking whose slot has not been
    // written yet lives in the schedule rather than here, and the calendar below
    // is where those are drawn.
    val ahead = entry.reservations.filter(_.startsAt.exists(_.isAfter(now)))
    val booked =
      if (ahead.isEmpty) Map.empty[String, JsValue]
      else Map[String, JsValue](
        "booked" -> JsArray(ahead.take(UpNextRows).flatMap { slot =>
          slot.startsAt.map { start =>
            JsObject(
              "name" -> JsString(personName(slot)),
              "minutes" -> JsNumber(slot.durationMinutes),
              "startsAt" -> instant(start),
              "mine" -> JsBoolean(slot.userId == viewerId),
              "asked" -> JsBoolean(slot.requestPending)
            ): JsValue
          }
        }.toVector),
        // Everything there was before the cap, so one note can cover it.
        "bookedTotal" -> JsNumber(ahead.size))
    val holder = entry.active.map(claim => "holderId" -> (JsString(claim.userId): JsValue))
    val holderName = entry.holderLabel.map(name => "holder" -> (JsString(name): JsValue))
    // Both ends of a live hunt, so the page can draw the progress bar itself and
    // keep it moving between polls rather than freezing at whatever we sent.
    val window = entry.active.flatMap(claim =>
      for { start <- claim.startsAt; end <- claim.endsAt }
        yield Map[String, JsValue]("startsAt" -> instant(start), "endsAt" -> instant(end))
    ).getOrElse(Map.empty)
    val nextAt = entry.nextReservation.flatMap(_.startsAt).map(s => "nextAt" -> instant(s))
    val touched = entry.lastActivity.map(t => "lastActivity" -> instant(t))

    JsObject(base ++ window ++ holderName ++ holder ++ queue ++ waiting ++ booked ++ nextAt ++ touched)
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
    // Deliberately no clock in here. It used to carry one, which nothing read
    // and which changed on every response — so the ETag changed on every
    // response too, and "nothing has changed since last time" could never be
    // true. Every time that matters is already an absolute instant on the thing
    // it belongs to.
    JsObject(Map[String, JsValue](
      "tier" -> JsString(tier.name),
      "spawns" -> JsArray(entries.map(entryJson(_, viewerId)).toVector)
    ) ++ stamina)
  }
}
