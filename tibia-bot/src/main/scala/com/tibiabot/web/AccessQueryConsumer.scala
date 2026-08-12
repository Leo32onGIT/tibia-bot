package com.tibiabot.web

import com.tibiabot.persistence.RedisCache
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Answers another bot's question about who somebody is in a guild this one is
 *  in — see [[AccessQuery]] for why that question has to be asked at all.
 *
 *  Every bot runs one. A query names its guild in the key, so each process
 *  answers only for guilds it can actually see and the rest are left alone.
 *
 *  Unlike the write consumer this takes no lease and keeps no record of what it
 *  has handled. Answering twice is harmless — the reply is the same each time,
 *  and it describes rather than performs — so the machinery that makes a write
 *  at-most-once would be cost for nothing here.
 */
final class AccessQueryConsumer(
  cache: RedisCache,
  /** Resolves a visitor in one guild, exactly as the dashboard would for a
   *  guild of its own. `None` when they may not use it. */
  resolve: (String, String) => Option[GuildAccess],
  /** Whether this process can see the guild at all. Not whether it *runs* the
   *  respawns there: a bot sharing the guild can read a member just as well,
   *  and refusing on ownership would leave a question nobody answers. */
  canSee: String => Boolean
)(implicit ec: ExecutionContext) extends StrictLogging {

  def sweep(): Future[Unit] =
    cache.keysMatching(AccessQuery.requestPattern).flatMap { keys =>
      val mine = keys.flatMap(AccessQuery.parseRequestKey).filter { case (guildId, _) => canSee(guildId) }
      Future.traverse(mine) { case (guildId, id) => answer(guildId, id) }.map(_ => ())
    }.recover {
      case NonFatal(e) =>
        logger.warn(s"Could not sweep dashboard access queries: ${e.getMessage}")
        ()
    }

  private def answer(guildId: String, id: String): Future[Unit] =
    cache.get(AccessQuery.requestKey(guildId, id)).flatMap {
      case None => Future.unit         // already answered and expired, or never there
      case Some(raw) => AccessQuery.fromJson(raw) match {
        case None =>
          logger.warn(s"Dropping an unreadable dashboard access query for guild '$guildId'")
          Future.unit
        case Some(query) =>
          val reply = try AccessAnswer(resolve(query.guildId, query.userId)) catch {
            case NonFatal(e) =>
              // A failure to resolve is answered as "no access" rather than left
              // unanswered: the asker would wait out its timeout either way, and
              // this way it stops asking and the reason is written down here.
              logger.warn(s"Could not resolve dashboard access in guild '$guildId': ${e.getMessage}")
              AccessAnswer(None)
          }
          cache.setEx(AccessQuery.replyKey(id), reply.toJson, AccessQueryConsumer.ReplyTtl)
      }
    }
}

object AccessQueryConsumer {
  /** Long enough for a caller that has just missed a sweep to still find the
   *  answer, short enough that Redis is not left holding stale ones. */
  val ReplyTtl: FiniteDuration = 30.seconds

  /** The same beat the write consumer runs on, and for the same reason: it is
   *  the shortest wait somebody is made to sit through, so it is the one that
   *  decides how a dashboard load feels. */
  val SweepEvery: FiniteDuration = 1.second
}
