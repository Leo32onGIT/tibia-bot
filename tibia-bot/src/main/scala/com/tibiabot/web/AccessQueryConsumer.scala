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

  private def answer(guildId: String, id: String): Future[Unit] = {
    val requestKey = AccessQuery.requestKey(guildId, id)
    cache.get(requestKey).flatMap {
      case None => Future.unit         // already answered, or never there
      case Some(raw) => AccessQuery.fromJson(raw) match {
        case None =>
          logger.warn(s"Dropping an unreadable dashboard access query for guild '$guildId'")
          cache.delete(requestKey)
        case Some(query) =>
          // Taken off the board before the slow part, not after. Resolving is a
          // blocking Discord REST call and the beat below is shorter than one,
          // so a question left in place while it ran was picked up again by the
          // next sweep and resolved a second time — and a third, for as long as
          // the key lived. That cost the answering bot several REST calls per
          // question, which lengthened the very lookup the asker is timing.
          //
          // Safe to drop it first because every path from here writes a reply,
          // including the failure below. The one case it loses is this process
          // dying mid-resolve, where the asker times out — which is what would
          // have happened anyway.
          cache.delete(requestKey).flatMap { _ =>
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
  }
}

object AccessQueryConsumer {
  /** Long enough for a caller that has just missed a sweep to still find the
   *  answer, short enough that Redis is not left holding stale ones. */
  val ReplyTtl: FiniteDuration = 30.seconds

  /** Faster than the write consumer's beat, because this one is racing a clock
   *  the write relay does not have.
   *
   *  The asker gives up after [[RemoteGuildAccess.DefaultTimeout]] — one second
   *  — so the whole of "notice the question, resolve it, reply" has to fit
   *  inside that. On a one-second beat it did not: a question arriving just
   *  after a sweep waited nearly a second to be seen at all, and the blocking
   *  Discord lookup that follows then took the answer past the deadline. The
   *  guild was left out of the picker, or the board it named answered 403 —
   *  about half the time, and differently on each reload.
   *
   *  A quarter-second leaves room for the lookup inside the same budget. It is
   *  four `KEYS` a second rather than one, over a pattern nothing else writes
   *  to; the amplification fixed in `answer` above takes away several times
   *  that much work in Discord calls. */
  val SweepEvery: FiniteDuration = 250.millis
}
