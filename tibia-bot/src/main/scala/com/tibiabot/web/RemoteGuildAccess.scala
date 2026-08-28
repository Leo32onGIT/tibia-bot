package com.tibiabot.web

import akka.actor.Scheduler
import com.tibiabot.persistence.RedisCache
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import RemoteGuildAccess.{Answered, Asked, Unanswered}

/** The guilds a visitor can use that *this* bot is not in.
 *
 *  Reads the rosters every bot publishes to find which guilds are run
 *  elsewhere, narrows those to ones the visitor is actually in, and asks the
 *  bot that runs each about them — see [[AccessQuery]].
 *
 *  Nothing here blocks a thread: the wait is a scheduled re-read, the same way
 *  a relayed write waits.
 *
 *  ==Cost==
 *  A guild is only ever asked about when the visitor is in it *and* somebody
 *  publishes a roster naming it, which is normally a handful at most. Asking is
 *  worth roughly a second, so the answer is cached by whoever calls this — see
 *  [[DashboardAccessService.rememberedAccessFor]] — and the ten-second board
 *  poll pays nothing.
 *
 *  A quiet or absent bot costs the timeout once and then contributes no guilds.
 *  That is still the right failure - a picker one server short is a smaller
 *  wrong than a dashboard that will not load - but the short picker has to say
 *  so, which is why this reports the guilds it could not get an answer about
 *  rather than merely leaving them out. A list silently one short is
 *  indistinguishable from a complete one, and at two servers down to one it
 *  stopped being a picker at all.
 */
final class RemoteGuildAccess(
  cache: RedisCache,
  scheduler: Scheduler,
  /** This bot's own guilds, so its own are never asked about over Redis. */
  isLocal: String => Boolean,
  /** This bot's own id, which names the channel answers come back on. Empty
   *  falls back to the key-based wait throughout, which is what a test that
   *  does not care about the transport gets. */
  selfBotId: String = "",
  timeout: FiniteDuration = RemoteGuildAccess.DefaultTimeout,
  pollEvery: FiniteDuration = RemoteGuildAccess.DefaultPoll,
  newId: () => String = () => java.util.UUID.randomUUID().toString
)(implicit ec: ExecutionContext) extends StrictLogging {

  /** Every guild in `userGuildIds` that another bot runs and this visitor may
   *  use. Empty — never failed — when Redis is unreachable or nobody answers.
   *
   *  `remembering` decides what a *missed* answer means. With it, a guild that
   *  answered a moment ago but has not answered this time keeps the tier it
   *  gave then; without it, no answer means no guild. Reads take the first,
   *  because a page that drops a server the visitor was just using is the whole
   *  of the bug this exists to avoid. Moderator actions take the second — see
   *  [[DashboardAccessService.accessIn]].
   */
  def accessFor(userId: String, userGuildIds: Set[String],
                remembering: Boolean = true): Future[AccessReport] =
    if (userGuildIds.isEmpty) Future.successful(AccessReport.Empty)
    else foreignGuilds(userGuildIds).flatMap {
      case Nil => Future.successful(AccessReport.Empty)
      case candidates =>
        Future.traverse(candidates) { guild =>
          ask(guild, userId, remembering).map {
            case Answered(access) => AccessReport(access.toList, Nil)
            case Unanswered       =>
              AccessReport(Nil, List(UnreachableGuild(guild.guildId, guild.guildName)))
          }
        }.map(_.foldLeft(AccessReport.Empty)(_ ++ _))
    }.recover {
      case NonFatal(e) =>
        // Redis itself, rather than any one bot. Which guilds were involved is
        // not known at this point - the roster read is what failed - so this
        // reports nothing rather than inventing names, and the caller's own
        // backstop covers the case where it matters.
        logger.warn(s"Could not resolve dashboard access held by other bots: ${e.getMessage}")
        AccessReport.Empty
    }

  /** The foreign guilds this visitor is in, as far as the last roster read
   *  knows, named. For the caller's backstop: when the whole wait is abandoned
   *  from outside, these are the guilds that were being waited on, and saying
   *  so beats reporting a complete answer that is missing all of them.
   *
   *  Answered from the roster cache alone and never by reading Redis, because
   *  the one caller is already on a thread that has just given up waiting. An
   *  empty roster cache yields nothing, which is the same silence as before. */
  def pendingFor(userGuildIds: Set[String]): List[UnreachableGuild] = {
    val (_, known) = rosterCache
    known.collect {
      case g if userGuildIds.contains(g.guildId) && !isLocal(g.guildId) =>
        UnreachableGuild(g.guildId, g.guildName)
    }
  }

  /** Who runs what, as last read. Rosters are republished every thirty seconds
   *  and change only when a bot joins or leaves a guild, so re-reading them per
   *  request buys nothing — and costs a great deal: `keysMatching` is Redis
   *  `KEYS`, which walks the whole keyspace and stalls the server while it
   *  does. Read on a page load it ran against every online-list snapshot and
   *  character cache the bot holds. */
  @volatile private var rosterCache: (Long, List[RemoteGuildAccess.OwnedGuild]) = (0L, Nil)

  /** Whether every bot in the fleet is listening on a channel, as of the last
   *  roster read. False until something has actually been read, which is the
   *  safe way round — see [[fleetAllOnChannels]]. */
  @volatile private var everyBotOnChannel: Boolean = false

  /** Whether the key-based question path can be left alone entirely.
   *
   *  Every running bot publishes a roster, including one with no guilds in it,
   *  so this sees the whole fleet rather than only the parts of it that own
   *  something. A bot too old to know about channels has no `pubSub` in its
   *  roster at all, which reads as `false` — so it is spotted by the same test
   *  that spots one whose subscription failed, and both are reasons to keep
   *  watching for keys.
   *
   *  Answering this costs nothing beyond the roster read the asker already
   *  does on its own thirty-second beat, which is the point: it lets the sweep
   *  retire itself the moment the last old bot in the fleet is restarted,
   *  rather than waiting for somebody to notice and delete it.
   */
  def fleetAllOnChannels: Future[Boolean] = rosterGuilds().map(_ => everyBotOnChannel)

  /** Id and name both, now that a guild which fails to answer has to be named
   *  on screen. The roster already carried the name for the picker's benefit;
   *  it was being thrown away here and re-derived from the answer, which is
   *  exactly the thing that does not arrive when this goes wrong. */
  private def rosterGuilds(): Future[List[RemoteGuildAccess.OwnedGuild]] = {
    val (readAt, known) = rosterCache
    if (System.nanoTime() - readAt < RemoteGuildAccess.RosterMaxAge.toNanos) Future.successful(known)
    else cache.keysMatching(GuildRoster.pattern).flatMap { keys =>
      Future.traverse(keys)(cache.get).map { values =>
        val rosters = values.flatten.flatMap(GuildRoster.fromJson)
        // Read from the rosters themselves rather than from the guilds below,
        // because a bot that runs nothing still publishes one — and it can
        // still be asking about everybody else's guilds.
        everyBotOnChannel = rosters.nonEmpty && rosters.forall(_.pubSub)
        val named = rosters
          // Who published the roster is kept now, not just what was in it. It
          // is the whole saving of the channel transport: knowing which bot
          // runs a guild means the question can be sent *to* that bot, instead
          // of being left somewhere every bot in the fleet has to go looking.
          .flatMap((r: GuildRoster) => r.guilds.map(g =>
            RemoteGuildAccess.OwnedGuild(g.id, g.name, r.botId, r.pubSub)))
          .distinctBy(_.guildId)
        rosterCache = (System.nanoTime(), named)
        named
      }
    }
  }

  /** Guilds somebody else runs that this visitor is in, named. */
  private def foreignGuilds(userGuildIds: Set[String]): Future[List[RemoteGuildAccess.OwnedGuild]] =
    rosterGuilds().map(_.filter(g => userGuildIds.contains(g.guildId))
      // A guild this process is in needs no asking - it resolved it itself, and
      // asking would invite a second, possibly different answer for it.
      .filterNot(g => isLocal(g.guildId)))

  /** Answers this bot is still waiting for, by question id.
   *
   *  Needed because replies share one channel per asking bot rather than one
   *  key per question — a subscription taken out and torn down around every
   *  lookup would cost more than the lookup — so an arriving answer has to be
   *  matched back to whoever asked for it. */
  private val pending =
    new java.util.concurrent.ConcurrentHashMap[String, scala.concurrent.Promise[Option[AccessAnswer]]]()

  /** Start listening for answers to this bot's questions.
   *
   *  Set up once, at startup, and never torn down. Answers whether it worked so
   *  the caller can decide what to advertise; a failure here simply leaves
   *  every question going out on the key path, which still works.
   */
  def listen(): Future[Boolean] =
    if (selfBotId.isEmpty) Future.successful(false)
    else cache.subscribe(AccessQuery.replyChannel(selfBotId)) { raw =>
      AccessQuery.answerFromJson(raw) match {
        case Some(answer) if answer.id.nonEmpty =>
          // Nothing slow happens here — the waiting Future is completed and the
          // work resumes on its own execution context — so this is safe to run
          // on whatever thread the Redis client delivers on.
          Option(pending.remove(answer.id)).foreach(_.trySuccess(Some(answer)))
        case _ => logger.warn("Dropping an unreadable or unaddressed dashboard access answer")
      }
    }.map(_ => true).recover {
      case NonFatal(e) =>
        logger.warn(s"Could not listen for dashboard access answers: ${e.getMessage}")
        false
    }

  /** Ask over the channel the owning bot is listening on.
   *
   *  Two things this buys over leaving a key. The question arrives the instant
   *  it is sent rather than on the answerer's next sweep, which was worth up to
   *  a quarter of a second of the deadline for nothing. And `PUBLISH` says how
   *  many listeners it reached, so a bot that is *not* there is known at once —
   *  the visitor is told a server did not answer in one round trip instead of
   *  after the full timeout, which is the difference between a page that feels
   *  broken and one that feels honest.
   */
  private def askViaChannel(guild: RemoteGuildAccess.OwnedGuild, userId: String,
                            id: String): Future[Option[AccessAnswer]] = {
    val promise = scala.concurrent.Promise[Option[AccessAnswer]]()
    // Registered before the question goes out, so an answer cannot arrive
    // before there is anything here to match it against.
    pending.put(id, promise)
    val query = AccessQuery(id, guild.guildId, userId, selfBotId)
    cache.publish(AccessQuery.questionChannel(guild.ownerBotId), query.toJson).flatMap { reached =>
      if (reached <= 0) {
        // Nobody runs that guild any more, or the bot that does is down. Its
        // roster outlives it by a couple of minutes, so this is exactly the
        // window a deploy opens — and now it costs a round trip instead of the
        // whole deadline.
        pending.remove(id)
        logger.info(s"Nobody is listening for questions about guild '${guild.guildId}'")
        Future.successful(None)
      } else {
        scheduler.scheduleOnce(timeout) {
          if (pending.remove(id) != null) {
            logger.warn(s"No answer about dashboard access in guild '${guild.guildId}' " +
              s"from bot '${guild.ownerBotId}' within $timeout")
            promise.trySuccess(None)
            ()
          }
        }
        promise.future
      }
    }
  }

  /** Ask by leaving a key, for a bot that is not listening on a channel.
   *
   *  The original transport, kept only for the length of a rolling deploy: a
   *  bot that has not yet been restarted onto the new build advertises nothing
   *  in its roster, and this is how it still gets asked. Once nothing in the
   *  fleet is that old, no key is ever written.
   */
  private def askViaKey(guild: RemoteGuildAccess.OwnedGuild, userId: String,
                        id: String): Future[Option[AccessAnswer]] = {
    val query = AccessQuery(id, guild.guildId, userId)
    // The request outlives the wait, so an answering bot that gets to it a
    // moment after we give up still finds a whole question rather than half.
    cache.setEx(AccessQuery.requestKey(guild.guildId, id), query.toJson, timeout + 5.seconds)
      .flatMap(_ => await(id, deadline = System.nanoTime() + timeout.toNanos))
  }

  private def ask(guild: RemoteGuildAccess.OwnedGuild, userId: String,
                  remembering: Boolean): Future[Asked] = {
    val id = newId()
    val asked =
      if (guild.pubSub && selfBotId.nonEmpty) askViaChannel(guild, userId, id)
      else askViaKey(guild, userId, id)
    asked
      .map {
        // Two answers and a silence. Both answers are authoritative and replace
        // whatever was remembered - including the "no", which must forget, or
        // somebody who lost access would keep it for the length of the memory.
        case Some(answer) => remember(userId, guild.guildId, answer.access); Answered(answer.access)
        // Silence, softened by the standing memory where there is one: a guild
        // that answered a moment ago counts as answered, which is the whole
        // point of keeping it. Only a silence with nothing behind it is
        // reported as unanswered.
        case None if remembering =>
          lastGood(userId, guild.guildId).fold[Asked](Unanswered)(a => Answered(Some(a)))
        case None => Unanswered
      }
      .recover {
        case NonFatal(e) =>
          pending.remove(id)
          logger.warn(s"Could not ask about dashboard access in guild '${guild.guildId}': ${e.getMessage}")
          if (remembering) lastGood(userId, guild.guildId).fold[Asked](Unanswered)(a => Answered(Some(a)))
          else Unanswered
      }
  }

  /** The answer, or `None` for no answer at all — which is not the same as an
   *  answer of "they may not", and is why this is an `Option[AccessAnswer]`
   *  rather than the access itself. */
  private def await(id: String, deadline: Long): Future[Option[AccessAnswer]] =
    cache.get(AccessQuery.replyKey(id)).flatMap {
      case Some(raw) => Future.successful(AccessQuery.answerFromJson(raw))
      case None if System.nanoTime() >= deadline =>
        // Nobody who runs that guild was listening — or they were, and did not
        // finish in time. Said here because a bot that has stopped answering is
        // worth noticing; what the visitor sees depends on whether anything is
        // remembered about them there.
        logger.info(s"No answer about dashboard access for query '$id'")
        Future.successful(None)
      case None => after(pollEvery).flatMap(_ => await(id, deadline))
    }

  /** What the bot that runs each guild last said about somebody.
   *
   *  Only ever read when that bot has just failed to answer, and only for a
   *  couple of minutes. It exists because the alternative reading of silence —
   *  "no access" — is wrong far more often than it is right: the fleet talks
   *  over Redis on a beat, and a page load that lands between beats was making
   *  a server disappear from the picker and its board answer 403, for somebody
   *  whose standing in it had not changed at all.
   *
   *  Deliberately not a cache of the fast path: a live answer always wins, so
   *  the usual cost of losing a role is unchanged. Only the pause between "the
   *  other bot went quiet" and "the visitor is told" is bought here, and it is
   *  bounded by [[RemoteGuildAccess.MemoryTtl]].
   */
  private val remembered = new java.util.concurrent.ConcurrentHashMap[(String, String), (GuildAccess, Long)]()

  private def remember(userId: String, guildId: String, access: Option[GuildAccess]): Unit = {
    access match {
      case Some(a) =>
        if (remembered.size >= RemoteGuildAccess.MemoryMaxEntries) forgetExpired()
        remembered.put((userId, guildId), (a, System.nanoTime()))
      case None => remembered.remove((userId, guildId))
    }
    ()
  }

  private def lastGood(userId: String, guildId: String): Option[GuildAccess] =
    Option(remembered.get((userId, guildId))).collect {
      case (access, at) if System.nanoTime() - at < RemoteGuildAccess.MemoryTtl.toNanos => access
    }

  private def forgetExpired(): Unit = {
    val cutoff = System.nanoTime() - RemoteGuildAccess.MemoryTtl.toNanos
    remembered.entrySet().removeIf(e => e.getValue._2 <= cutoff)
    // Nothing had expired, so this is real traffic rather than a leak. Dropping
    // it all costs a slow page each, once, which is what not remembering at all
    // would have cost every time.
    if (remembered.size >= RemoteGuildAccess.MemoryMaxEntries) remembered.clear()
  }

  private def after(delay: FiniteDuration): Future[Unit] = {
    val promise = scala.concurrent.Promise[Unit]()
    scheduler.scheduleOnce(delay)(promise.success(()))
    promise.future
  }
}

object RemoteGuildAccess {
  /** A guild in the fleet's rosters: which it is, who runs it, and whether that
   *  bot can be asked directly. */
  private[web] final case class OwnedGuild(guildId: String, guildName: String,
                                           ownerBotId: String, pubSub: Boolean)

  /** What asking one bot produced. [[Answered]] carries a decision the owning
   *  bot actually made, including its "no"; [[Unanswered]] means nobody said
   *  anything and the guild's standing is simply unknown.
   *
   *  Both used to be `Option[GuildAccess]`, so a bot that was merely quiet
   *  looked identical to one that had refused - which is how a guild silently
   *  fell out of somebody's picker.
   *
   *  Out here rather than inside the class for the same reason
   *  [[UserGuildCache.Entry]] is: an inner case class carries a reference back
   *  to its enclosing instance that cannot be checked at run time, which the
   *  compiler warns about. */
  private[web] sealed trait Asked
  private[web] final case class Answered(access: Option[GuildAccess]) extends Asked
  private[web] case object Unanswered extends Asked

  /** How long one bot is given to answer about one guild.
   *
   *  Was cut to a second when the wait was paid on the same four threads as
   *  every dashboard write: a handful of visitors waiting on a bot that had
   *  gone away was enough to hold up everybody else's requests, so the cost of
   *  waiting mattered more than the answer did.
   *
   *  A second turned out to be under the real cost of an answer rather than
   *  over it. The round trip is: the question reaches Redis, the answering bot
   *  notices it on its next sweep (up to `AccessQueryConsumer.SweepEvery`), it
   *  makes a *blocking Discord REST call* to read the member, it writes the
   *  reply, and the asker notices that on its next poll (up to `DefaultPoll`).
   *  A third of the budget was gone to scheduling jitter before any work
   *  started, and the Discord call in the middle is worth a few hundred
   *  milliseconds on a good day and much more behind a rate limit. So the
   *  deadline was landing in the middle of the distribution: the answer was
   *  usually on its way, and got thrown away.
   *
   *  Three seconds sits past the tail rather than inside it. This screen is
   *  chosen once at the start of a session and does not need to be quick — it
   *  needs to be right, and a picker that quietly drops a server is wrong in a
   *  way a slow one is not. The thread cost that forced the cut is answered
   *  where it belongs: reads have their own pool now (see BotApp), so a wait
   *  here no longer holds up a write.
   */
  val DefaultTimeout: FiniteDuration = 3.seconds
  val DefaultPoll: FiniteDuration = 100.millis

  /** How long the who-runs-what listing is reused. Rosters are republished
   *  every thirty seconds and only change when a bot joins or leaves a guild. */
  val RosterMaxAge: FiniteDuration = 30.seconds

  /** How long a guild the fleet has gone quiet about keeps the standing it was
   *  last given. Long enough to cover a restart or a deploy of the bot that
   *  runs it, short enough that a guild genuinely gone stops being offered. */
  val MemoryTtl: FiniteDuration = 2.minutes

  /** A row per visitor per foreign guild. Bounded so a burst cannot grow the
   *  heap; see `forgetExpired`. */
  val MemoryMaxEntries: Int = 5000
}
