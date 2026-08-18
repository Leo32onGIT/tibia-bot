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
        Future.traverse(candidates) { case (guildId, guildName) =>
          ask(guildId, userId, remembering).map {
            case Answered(access) => AccessReport(access.toList, Nil)
            case Unanswered       => AccessReport(Nil, List(UnreachableGuild(guildId, guildName)))
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
    known.collect { case (id, name) if userGuildIds.contains(id) && !isLocal(id) =>
      UnreachableGuild(id, name)
    }
  }

  /** Who runs what, as last read. Rosters are republished every thirty seconds
   *  and change only when a bot joins or leaves a guild, so re-reading them per
   *  request buys nothing — and costs a great deal: `keysMatching` is Redis
   *  `KEYS`, which walks the whole keyspace and stalls the server while it
   *  does. Read on a page load it ran against every online-list snapshot and
   *  character cache the bot holds. */
  @volatile private var rosterCache: (Long, List[(String, String)]) = (0L, Nil)

  /** Id and name both, now that a guild which fails to answer has to be named
   *  on screen. The roster already carried the name for the picker's benefit;
   *  it was being thrown away here and re-derived from the answer, which is
   *  exactly the thing that does not arrive when this goes wrong. */
  private def rosterGuilds(): Future[List[(String, String)]] = {
    val (readAt, known) = rosterCache
    if (System.nanoTime() - readAt < RemoteGuildAccess.RosterMaxAge.toNanos) Future.successful(known)
    else cache.keysMatching(GuildRoster.pattern).flatMap { keys =>
      Future.traverse(keys)(cache.get).map { values =>
        val named = values.flatten.flatMap(GuildRoster.fromJson)
          .flatMap(_.guilds.map(g => g.id -> g.name))
          .distinctBy(_._1)
        rosterCache = (System.nanoTime(), named)
        named
      }
    }
  }

  /** Guilds somebody else runs that this visitor is in, named. */
  private def foreignGuilds(userGuildIds: Set[String]): Future[List[(String, String)]] =
    rosterGuilds().map(_.filter { case (id, _) => userGuildIds.contains(id) }
      // A guild this process is in needs no asking - it resolved it itself, and
      // asking would invite a second, possibly different answer for it.
      .filterNot { case (id, _) => isLocal(id) })

  private def ask(guildId: String, userId: String, remembering: Boolean): Future[Asked] = {
    val id = newId()
    val query = AccessQuery(id, guildId, userId)
    // The request outlives the wait, so an answering bot that gets to it a
    // moment after we give up still finds a whole question rather than half.
    cache.setEx(AccessQuery.requestKey(guildId, id), query.toJson, timeout + 5.seconds)
      .flatMap(_ => await(id, deadline = System.nanoTime() + timeout.toNanos))
      .map {
        // Two answers and a silence. Both answers are authoritative and replace
        // whatever was remembered - including the "no", which must forget, or
        // somebody who lost access would keep it for the length of the memory.
        case Some(answer) => remember(userId, guildId, answer.access); Answered(answer.access)
        // Silence, softened by the standing memory where there is one: a guild
        // that answered a moment ago counts as answered, which is the whole
        // point of keeping it. Only a silence with nothing behind it is
        // reported as unanswered.
        case None if remembering => lastGood(userId, guildId).fold[Asked](Unanswered)(a => Answered(Some(a)))
        case None => Unanswered
      }
      .recover {
        case NonFatal(e) =>
          logger.warn(s"Could not ask about dashboard access in guild '$guildId': ${e.getMessage}")
          if (remembering) lastGood(userId, guildId).fold[Asked](Unanswered)(a => Answered(Some(a)))
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

  /** Shorter than a relayed write's, and deliberately: a write is somebody
   *  waiting on a button they pressed, where this is a page that must render.
   *
   *  Cut from two and a half seconds after it started timing out in the wild.
   *  A bot that has not answered in a second is not about to make the page
   *  pleasant, and the cost of waiting is paid on a pool of four threads — so
   *  a handful of visitors waiting on a bot that is gone is enough to hold up
   *  everybody else's requests too. A roster outlives the bot that published it
   *  by up to two minutes, so a deploy guarantees a window where exactly that
   *  happens.
   */
  val DefaultTimeout: FiniteDuration = 1.second
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
