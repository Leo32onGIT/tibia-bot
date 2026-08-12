package com.tibiabot.web

import akka.actor.Scheduler
import com.tibiabot.persistence.RedisCache
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

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
 *  That is the right failure: a picker one server short is a smaller wrong than
 *  a dashboard that will not load.
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
   */
  def accessFor(userId: String, userGuildIds: Set[String]): Future[List[GuildAccess]] =
    if (userGuildIds.isEmpty) Future.successful(Nil)
    else foreignGuilds(userGuildIds).flatMap {
      case Nil => Future.successful(Nil)
      case candidates =>
        Future.traverse(candidates)(guildId => ask(guildId, userId)).map(_.flatten)
    }.recover {
      case NonFatal(e) =>
        logger.warn(s"Could not resolve dashboard access held by other bots: ${e.getMessage}")
        Nil
    }

  /** Guild ids somebody else runs that this visitor is in. */
  private def foreignGuilds(userGuildIds: Set[String]): Future[List[String]] =
    cache.keysMatching(GuildRoster.pattern).flatMap { keys =>
      Future.traverse(keys)(cache.get).map { values =>
        values.flatten
          .flatMap(GuildRoster.fromJson)
          .flatMap(_.guilds.map(_.id))
          .filter(userGuildIds.contains)
          // A guild this process is in needs no asking — it resolved it itself,
          // and asking would invite a second, possibly different answer for it.
          .filterNot(isLocal)
          .distinct
      }
    }

  private def ask(guildId: String, userId: String): Future[Option[GuildAccess]] = {
    val id = newId()
    val query = AccessQuery(id, guildId, userId)
    // The request outlives the wait, so an answering bot that gets to it a
    // moment after we give up still finds a whole question rather than half.
    cache.setEx(AccessQuery.requestKey(guildId, id), query.toJson, timeout + 5.seconds)
      .flatMap(_ => await(id, deadline = System.nanoTime() + timeout.toNanos))
      .recover {
        case NonFatal(e) =>
          logger.warn(s"Could not ask about dashboard access in guild '$guildId': ${e.getMessage}")
          None
      }
  }

  private def await(id: String, deadline: Long): Future[Option[GuildAccess]] =
    cache.get(AccessQuery.replyKey(id)).flatMap {
      case Some(raw) => Future.successful(AccessQuery.answerFromJson(raw).flatMap(_.access))
      case None if System.nanoTime() >= deadline =>
        // Nobody who runs that guild was listening. Silent to the visitor by
        // design — they simply do not see a server they may not have known was
        // there — but said here, because a bot that has stopped answering is
        // worth noticing.
        logger.info(s"No answer about dashboard access for query '$id'; leaving that server out")
        Future.successful(None)
      case None => after(pollEvery).flatMap(_ => await(id, deadline))
    }

  private def after(delay: FiniteDuration): Future[Unit] = {
    val promise = scala.concurrent.Promise[Unit]()
    scheduler.scheduleOnce(delay)(promise.success(()))
    promise.future
  }
}

object RemoteGuildAccess {
  /** Shorter than a relayed write's, and deliberately: a write is somebody
   *  waiting on a button they pressed, where this is a page that must render.
   *  Two seconds covers a sweep that has only just been missed. */
  val DefaultTimeout: FiniteDuration = 2500.millis
  val DefaultPoll: FiniteDuration = 150.millis
}
