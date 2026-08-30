package com.tibiabot.web

import com.tibiabot.discord.{DiscordGateway, MemberLookup}

/** The parts of a tracked world this needs: its name, and the category whose
 *  visibility stands in for belonging to that world's team.
 *
 *  Narrower than `domain.Worlds` on purpose. That carries twenty-six fields
 *  about channels, roles and display options, none of which bear on who may
 *  open the dashboard — depending on it would drag all of them into every test
 *  here for the sake of two.
 */
final case class WorldChannel(name: String, categoryId: String)

/** Resolves which guilds a signed-in visitor may use the respawn dashboard in,
 *  and at what tier.
 *
 *  The lookups are injected as plain functions rather than repositories so this
 *  can be exercised without a database or a Discord connection; the decisions
 *  themselves live in [[DashboardAccess]], which has no dependencies at all.
 *
 *  `respawnForumExists` and `worldsOf` are per-guild reads against that guild's
 *  own database; `moderatorRoleOf` is the guild's configured "Violent Bot
 *  Moderator" role id, or "0" when it has none.
 */
final class DashboardAccessService(
  discordGateway: DiscordGateway,
  /** Whether this guild has a respawn forum that is actually there: the
   *  settings row says which channel it is, *and* that channel still resolves
   *  on Discord.
   *
   *  Both halves are needed and the second is the one that was missing. The row
   *  is written with a placeholder channel id before the forum is created (see
   *  `ChannelService.createSpawnsForum`), so a setup that fell over on a
   *  missing permission leaves a guild that reads as configured and has no
   *  forum — and a forum deleted by hand afterwards leaves exactly the same
   *  state. Either way the picker offered a server whose dashboard has nothing
   *  behind it.
   *
   *  Takes the guild rather than its id so the caller can answer it with
   *  `RespawnThreads.findForum`, which is how the rest of the bot asks the same
   *  question. Two places deciding "is there a forum here" separately is the
   *  kind of drift that only shows up as a server appearing where it should
   *  not. */
  respawnForumExists: net.dv8tion.jda.api.entities.Guild => Boolean,
  worldsOf: String => List[WorldChannel],
  moderatorRoleOf: String => String,
  /** The bot's own support Discord, which is kept out of the guild picker —
   *  see [[DashboardAccess.entryFor]]. Empty means there is no such guild, and
   *  every guild counts. */
  demoGuildId: String = "",
  cache: AccessCache = new AccessCache(AccessCache.DefaultStaleAfter),
  /** Guilds another bot in this fleet runs, which this process cannot resolve
   *  for itself — see [[RemoteGuildAccess]]. Absent on a deployment with no
   *  Redis or no other bots, where it costs nothing and contributes nothing. */
  remote: Option[RemoteGuildAccess] = None,
  /** How long to let the other bots answer before rendering without them. Kept
   *  a little above [[RemoteGuildAccess.DefaultTimeout]] so the wait is decided
   *  there, by the part that can say which guild gave up, rather than here.
   *
   *  Derived from that timeout rather than written down beside it, because the
   *  two moving apart is silent and ruinous: a backstop at or under the
   *  per-guild deadline fires first, and this path cannot say which guild it
   *  was waiting on, so it reports *every* foreign guild as unanswered. Raising
   *  the deadline while leaving a fixed two seconds here would have turned one
   *  slow server into all of them.
   *
   *  This is a backstop and should not be what fires. When it did, it meant a
   *  page load held one of four blocking threads for its whole duration — so
   *  the ceiling matters more than the guild it was waiting on. */
  remoteWait: java.time.Duration =
    java.time.Duration.ofMillis(RemoteGuildAccess.DefaultTimeout.toMillis + 1000),
  /** Where a refresh runs when nobody is waiting for it — see
   *  [[rememberedReportFor]]. Blocking work, so it wants a pool of its own and
   *  a bounded one: its width is the ceiling on how many Discord lookups this
   *  can have in the air at once, which is the whole point of doing them here
   *  rather than on whichever request happened to find the entry stale.
   *
   *  Defaulted so a test or a deployment that never refreshes in the background
   *  need not think about it; BotApp passes a real one. */
  refreshOn: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global,
  /** How long a reader that lost the race to resolve will wait on the winner
   *  before giving up and resolving for itself. A backstop against a hung
   *  resolution holding readers indefinitely; duplicating the work is wasteful
   *  but never wrong. */
  shareWait: java.time.Duration = java.time.Duration.ofSeconds(15),
  /** Where a visitor's guilds are looked up when there is more than one of
   *  them — see [[localAccessFor]].
   *
   *  Must not be any pool this service is itself called on. Every caller here
   *  blocks waiting for these, so fanning out onto the caller's own pool would
   *  have a full pool of resolutions each waiting on subtasks that pool has no
   *  thread left to run. */
  lookupOn: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global,
  /** How long the whole of one visitor's guilds may take. A backstop against a
   *  Discord call that never comes back, not a latency target — it should never
   *  be what decides an answer. */
  lookupWait: java.time.Duration = java.time.Duration.ofSeconds(15)
) extends com.typesafe.scalalogging.StrictLogging {

  /** As [[accessFor]], but willing to answer from the last few seconds.
   *
   *  For reads, and for a member acting on their own claim. Working this out
   *  costs a Discord REST call per candidate guild, and a board left open polls
   *  every ten seconds — so without this, watching a page cost six round trips
   *  a minute and put that latency on every one of them.
   *
   *  Anything that acts on somebody else's claim calls [[accessFor]] instead,
   *  because the cost of a stale answer there is a moderator who lost the role
   *  still being able to move people off spawns. The worst this can do is let
   *  somebody read a board they were removed from moments ago.
   *
   *  `mustInclude` names the guild the caller is about to check, where there is
   *  one. A remembered answer that does not contain it is thrown away and the
   *  question asked again, because the only thing that answer can produce is a
   *  refusal — and a refusal is exactly what a half-resolved list looks like.
   *  One page load that lost its race with another bot would otherwise be
   *  remembered as "no such server for you" and refuse every reload for the
   *  next three quarters of a minute.
   *
   *  It costs nothing in the ordinary case: a visitor reading a board they can
   *  see is answered from the memory as before, and it is only the load that
   *  was about to fail anyway that pays for a fresh lookup.
   */
  def rememberedAccessFor(userId: String, userGuildIds: Set[String],
                          mustInclude: Option[String] = None): List[GuildAccess] =
    rememberedReportFor(userId, userGuildIds, mustInclude).granted

  /** As [[rememberedAccessFor]], keeping what the pass failed to resolve.
   *
   *  The memory holds the whole report rather than the granted half, so a page
   *  drawn from it can still say that a server is missing. Remembering only the
   *  successes would have the cache quietly launder an incomplete answer into
   *  one that looked complete, which is the original bug wearing a hat.
   */
  def rememberedReportFor(userId: String, userGuildIds: Set[String],
                          mustInclude: Option[String] = None): AccessReport = {
    val key = s"$userId:${userGuildIds.toList.sorted.mkString(",")}"
    def usable(report: AccessReport) =
      mustInclude.forall(guildId => report.granted.exists(_.guildId == guildId))
    cache.get(key).filter(entry => usable(entry.report)) match {
      // Good as it stands.
      case Some(entry) if !entry.stale => entry.report
      // Old enough to be worth resolving again, but not so old it cannot be
      // used. The reader takes it now and the work happens behind them, which
      // is the difference between a dashboard a few hundred people can have
      // open and one where every entry falling due lands a chain of blocking
      // Discord calls in front of whoever polled at the wrong moment.
      case Some(entry) => refreshInBackground(key, userId, userGuildIds); entry.report
      // Nothing usable: resolve it with the reader waiting, as a cold entry
      // always did.
      case None => resolveShared(key, userId, userGuildIds)
    }
  }

  /** Resolutions in flight, so the same question is only ever being asked once.
   *
   *  Keyed exactly as the cache is. Without it a visitor with two tabs, or one
   *  whose entry falls due between two polls, paid for two full resolutions at
   *  once — and on a cold start every reader raced every other, which is the
   *  moment there is least Discord budget to spare.
   */
  private val inFlight =
    new java.util.concurrent.ConcurrentHashMap[String, scala.concurrent.Future[AccessReport]]()

  /** The shared resolution for `key`, started here if nobody else has started
   *  it, along with whether this caller is the one that has to run it.
   *
   *  Returned rather than run so the two callers can differ on *where*: a
   *  reader with nothing to show runs it on its own thread, exactly as before,
   *  while a refresh behind a stale answer is handed to [[refreshOn]]. */
  private def sharedResolution(key: String, userId: String, userGuildIds: Set[String])
      : (scala.concurrent.Future[AccessReport], Option[() => Unit]) = {
    val promise = scala.concurrent.Promise[AccessReport]()
    Option(inFlight.putIfAbsent(key, promise.future)) match {
      case Some(running) => (running, None)
      case None =>
        val run = () => {
          try {
            val fresh = accessReportFor(userId, userGuildIds)
            cache.put(key, fresh)
            promise.success(fresh)
          } catch {
            case scala.util.control.NonFatal(e) => promise.failure(e)
          } finally {
            // After completing, never before: a caller arriving in the gap
            // finds a finished Future rather than starting a second pass.
            inFlight.remove(key)
            ()
          }
          ()
        }
        (promise.future, Some(run))
    }
  }

  /** Resolve with the caller waiting, sharing the work with anyone already
   *  doing it.
   *
   *  A loser waits on the winner rather than resolving too. If that wait runs
   *  out — a resolution wedged somewhere unforeseen — it falls through and does
   *  the work itself, which costs a duplicate lookup and is never wrong.
   */
  private def resolveShared(key: String, userId: String, userGuildIds: Set[String]): AccessReport =
    sharedResolution(key, userId, userGuildIds) match {
      case (future, Some(run)) =>
        run()
        // Already completed by `run`, so this only unwraps it.
        scala.concurrent.Await.result(future, scala.concurrent.duration.Duration.Inf)
      case (future, None) =>
        try scala.concurrent.Await.result(
          future, scala.concurrent.duration.Duration.fromNanos(shareWait.toNanos))
        catch {
          case scala.util.control.NonFatal(e) =>
            logger.warn(s"Gave up sharing a dashboard access lookup, resolving again: ${e.getMessage}")
            val fresh = accessReportFor(userId, userGuildIds)
            cache.put(key, fresh)
            fresh
        }
    }

  /** Resolve behind a reader who has already been given the old answer.
   *
   *  Nothing waits on this and nothing is failed by it: the entry it would have
   *  replaced is still there and still usable until its outer horizon, so a
   *  refresh that cannot get through simply leaves the reader with what they
   *  already had. Said out loud all the same — refreshes failing quietly for
   *  ten minutes is exactly the state worth knowing about.
   */
  private def refreshInBackground(key: String, userId: String, userGuildIds: Set[String]): Unit =
    sharedResolution(key, userId, userGuildIds) match {
      case (_, None) => ()      // somebody is already on it
      case (future, Some(run)) =>
        future.failed.foreach { e =>
          logger.warn(s"Could not refresh dashboard access for '$userId': ${e.getMessage}")
        }(scala.concurrent.ExecutionContext.parasitic)
        // A pool that will not take the work has to be tidied up after by
        // hand. `run` is what normally clears the in-flight entry, so a
        // rejected task would otherwise leave this key claimed by a resolution
        // that is never going to happen — and nothing would refresh it again,
        // nor would a reader past the outer horizon get past the wait.
        try refreshOn.execute(() => run())
        catch {
          case scala.util.control.NonFatal(e) =>
            inFlight.remove(key)
            logger.warn(s"Could not start a dashboard access refresh for '$userId': ${e.getMessage}")
        }
    }

  /** Every guild this visitor can use, resolved live.
   *
   *  `userGuildIds` comes from their login (see [[UserGuildCache]]) and is used
   *  only to narrow the bot's several hundred guilds to the few worth a REST
   *  call — it grants nothing. Everything that decides access is read here,
   *  now, so a stale or tampered-with guild list can at worst make this do less
   *  work, never more.
   *
   *  An empty list resolves to no access, and deliberately so.
   *
   *  It briefly did the opposite: an empty list was treated as "no hint" and
   *  every guild was considered, so that somebody whose cache had aged out was
   *  not shown an empty dashboard. That is a real problem, but this was the
   *  wrong answer to it. Each candidate costs a blocking member lookup, and
   *  after a restart *every* visitor's list is empty at once — so the fallback
   *  turned one page load into one REST call per respawn-configured guild and
   *  pushed `GET /dashboard` past akka's request timeout in production.
   *
   *  The narrowing is what keeps this affordable, so it stays. The cache going
   *  empty wants fixing where it happens — by outliving a restart — rather than
   *  by scanning everything each time it does.
   */
  def accessFor(userId: String, userGuildIds: Set[String]): List[GuildAccess] =
    accessReportFor(userId, userGuildIds).granted

  /** The same resolution, keeping hold of what it could not resolve.
   *
   *  Both halves can fail independently and for unrelated reasons - a Discord
   *  rate limit here, a bot that did not answer over there - and neither
   *  failure says anything about the visitor. Reporting them is what lets the
   *  landing page tell "you have one server" apart from "one of your servers
   *  did not answer": the same list, and completely different pages.
   */
  def accessReportFor(userId: String, userGuildIds: Set[String]): AccessReport =
    localAccessFor(userId, userGuildIds) ++ remoteAccessFor(userId, userGuildIds)

  /** Guilds another bot runs, resolved by asking it.
   *
   *  A visitor's tier is their roles and what channels they can see, and only a
   *  bot in the guild can be told either — so a guild run elsewhere could never
   *  be resolved here and was left out of the picker entirely, however plainly
   *  the visitor belonged to it.
   *
   *  Blocking, unlike everything it calls: this whole service is already run on
   *  the blocking pool by the routes above (see `RespawnDashboardRoute.read`),
   *  and every caller is shaped around getting a list rather than a promise of
   *  one. The wait is bounded twice over — once per guild inside, once here —
   *  and a timeout yields no guilds rather than an error, so the worst it can
   *  do is show a picker one server short.
   */
  private def remoteAccessFor(userId: String, userGuildIds: Set[String],
                             remembering: Boolean = true): AccessReport =
    remote.fold(AccessReport.Empty) { resolver =>
      try scala.concurrent.Await.result(
        resolver.accessFor(userId, userGuildIds, remembering),
        scala.concurrent.duration.Duration.fromNanos(remoteWait.toNanos))
      catch {
        case scala.util.control.NonFatal(e) =>
          // The backstop firing, rather than the per-guild wait inside. Nothing
          // out here knows how far the resolver had got, so every foreign guild
          // this visitor is in is reported as unanswered - which is true of at
          // least one of them, and is the safe way to be wrong: it shows the
          // picker and names a server, rather than silently dropping the lot.
          //
          // Marked unknown as well as unanswered, because `pendingFor` can only
          // name what the last roster read knew about. Where that read had not
          // happened yet, or had itself failed, it names nothing at all and the
          // report would otherwise look like a complete "no other servers".
          logger.warn(s"Gave up waiting on other bots for dashboard access: ${e.getMessage}")
          AccessReport(Nil, resolver.pendingFor(userGuildIds), fleetUnknown = true)
      }
    }

  /** Whether this process is in the guild at all, and so whether it can decide
   *  anything about who somebody is there.
   *
   *  The question behind who checks permission for a write. A bot that cannot
   *  see the guild has no way to read a member of it, and asking the bot that
   *  can — then deciding here on its answer — is a round trip that can fail for
   *  somebody perfectly entitled. So a write into a guild this bot cannot see is
   *  carried to the bot that can, and decided there; see [[RespawnCommand]]. */
  def canSee(guildId: String): Boolean = discordGateway.guildById(guildId) != null

  /** One guild, resolved here and never by asking anyone else.
   *
   *  What [[AccessQueryConsumer]] answers with. It must not be the full
   *  [[accessFor]]: that asks the other bots in turn, so answering a question
   *  with it would have two processes asking each other the same one until both
   *  timed out.
   */
  def localAccessIn(userId: String, guildId: String): Option[GuildAccess] =
    localAccessFor(userId, Set(guildId)).granted.headOption

  /** Every guild this bot is in that is worth asking Discord about, with what
   *  has to be asked. Cheap local reads only — a guild that never set the
   *  respawn system up, whose respawn forum is gone, or that has no world with
   *  a category recorded, is dismissed here rather than costing a REST call to
   *  dismiss. */
  private def worthAsking(userGuildIds: Set[String]): List[(net.dv8tion.jda.api.entities.Guild, List[WorldChannel])] =
    discordGateway.guilds.filter(g => userGuildIds.contains(g.getId)).flatMap { guild =>
      if (!respawnForumExists(guild)) None
      else {
        // A world with no category recorded can't be used to prove anything, so
        // it is dropped rather than treated as visible to everyone.
        val worlds = worldsOf(guild.getId).filter(_.categoryId.nonEmpty)
        if (worlds.isEmpty) None else Some(guild -> worlds)
      }
    }

  // The icon is read at the call sites below because this is the only place
  // holding the JDA guild; it is null for a guild that never set one, which
  // Option turns into an absence the page can fall back from.
  private def resolveOne(userId: String,
                         entry: (net.dv8tion.jda.api.entities.Guild, List[WorldChannel])): AccessReport = {
    val (guild, worlds) = entry
    resolveGuild(userId, guild.getId, guild.getName, Option(guild.getIconUrl), worlds)
  }

  /** Everything this bot can decide for itself about a visitor.
   *
   *  Each guild costs a blocking Discord REST call, and these used to be folded
   *  over one at a time — so somebody in three tracked guilds waited for three
   *  round trips end to end, and held the thread doing it for the sum of them.
   *  Since no guild's answer bears on any other's, the wait is now the longest
   *  of them rather than the total.
   *
   *  Which matters most where it is least visible: the background refresh (see
   *  [[rememberedReportFor]]) occupies a thread for exactly this long, so
   *  shortening it is what decides how many visitors one small pool can keep
   *  refreshed.
   *
   *  One guild - much the commonest case - is resolved on the caller's own
   *  thread. Handing a single lookup to another pool and waiting for it back
   *  costs a thread hop and buys nothing.
   */
  private def localAccessFor(userId: String, userGuildIds: Set[String]): AccessReport =
    worthAsking(userGuildIds) match {
      case Nil          => AccessReport.Empty
      case entry :: Nil => resolveOne(userId, entry)
      case entries =>
        val started = entries.map(entry =>
          scala.concurrent.Future(resolveOne(userId, entry))(lookupOn))
        implicit val on: scala.concurrent.ExecutionContext = lookupOn
        try scala.concurrent.Await.result(
          // Order is preserved, so what comes back reads the same as the fold
          // it replaced.
          scala.concurrent.Future.sequence(started),
          scala.concurrent.duration.Duration.fromNanos(lookupWait.toNanos)
        ).foldLeft(AccessReport.Empty)(_ ++ _)
        catch {
          case scala.util.control.NonFatal(e) =>
            // A backstop against a Discord call that never returns - JDA's
            // blocking form has no timeout of its own, so before this a wedged
            // lookup held the request until akka gave up on it. Nothing here
            // knows which of them was slow, so all are reported unanswered:
            // true of at least one, and the page says so rather than quietly
            // showing a visitor fewer servers than they have.
            logger.warn(s"Gave up resolving dashboard access for '$userId': ${e.getMessage}")
            AccessReport(Nil, entries.map { case (guild, _) =>
              UnreachableGuild(guild.getId, guild.getName)
            })
        }
    }

  /** One guild: granted, refused, or unanswered.
   *
   *  A refusal - not a member, or a member who cannot see any tracked world's
   *  category - is an empty report, exactly as it always was. A lookup that
   *  never got an answer is reported as unreachable instead of vanishing, which
   *  is the whole point of [[com.tibiabot.discord.MemberLookup]].
   */
  private def resolveGuild(userId: String, guildId: String, guildName: String,
                           iconUrl: Option[String],
                           worlds: List[WorldChannel]): AccessReport =
    discordGateway.memberLookup(guildId, userId, worlds.map(_.categoryId)) match {
      case MemberLookup.Denied => AccessReport.Empty
      case MemberLookup.Unreachable(reason) =>
        logger.warn(s"Could not resolve dashboard access in guild '$guildId': $reason")
        AccessReport(Nil, List(UnreachableGuild(guildId, guildName)))
      case MemberLookup.Allowed(member) =>
        val visibleWorlds = worlds.filter(w => member.visibleChannelIds.contains(w.categoryId)).map(_.name)
        if (!DashboardAccess.eligible(respawnConfigured = true, visibleWorlds)) AccessReport.Empty
        else {
          val moderatorRole = moderatorRoleOf(guildId)
          // An unset role id must not match anything - a guild with no moderator
          // role would otherwise promote everyone who happens to hold no roles.
          val hasRole = moderatorRole.nonEmpty && moderatorRole != "0" &&
            member.roleIds.contains(moderatorRole)
          AccessReport.of(List(GuildAccess(
            guildId, guildName,
            AccessTier.of(member.hasManageServer, hasRole),
            visibleWorlds,
            iconUrl
          )))
        }
    }

  /** Where to send this visitor when they arrive. */
  def entryFor(userId: String, userGuildIds: Set[String]): DashboardEntry =
    entryOf(accessReportFor(userId, userGuildIds))

  /** The same decision over guilds already resolved.
   *
   *  Split out because the landing page needs both halves — where to send them,
   *  and the list itself, which is what tells the board's header whether there
   *  is anywhere to switch to — and resolving access twice costs a Discord REST
   *  call per candidate guild. */
  def entryOf(report: AccessReport): DashboardEntry =
    DashboardAccess.entryFor(report, demoGuildId)

  /** Whether a request may act on `guildId` at `required` or better.
   *
   *  Resolved fresh every time rather than read from anything cached: this is
   *  the check that actually grants a mutation, and a moderator who lost the
   *  role a minute ago must not still be able to move somebody else's claim.
   */
  def permits(userId: String, userGuildIds: Set[String], guildId: String, required: AccessTier): Boolean =
    DashboardAccess.permits(accessIn(userId, userGuildIds, guildId), guildId, required)

  /** Access in one named guild, resolved fresh.
   *
   *  Whoever asks this already knows which guild they mean, so the other bots
   *  are only troubled when the answer can actually come from one of them.
   *  Going through the full [[accessFor]] made every moderator action — a
   *  force-leave, a reassign — wait on Redis and on however many bots had
   *  published a roster, for guilds that had nothing to do with the action.
   *
   *  A guild run elsewhere is asked about without the standing memory that
   *  [[RemoteGuildAccess]] keeps for reads. This is the check that grants a
   *  mutation, so a bot that cannot say *now* whether somebody is still a
   *  moderator has to be taken as a no.
   */
  def accessIn(userId: String, userGuildIds: Set[String], guildId: String): List[GuildAccess] =
    if (!userGuildIds.contains(guildId)) Nil
    else if (discordGateway.guildById(guildId) != null) localAccessFor(userId, Set(guildId)).granted
    else remoteAccessFor(userId, Set(guildId), remembering = false).granted
}
