package com.tibiabot.web

import com.tibiabot.persistence.RedisCache
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Answers another bot's question about who somebody is in a guild this one is in
 *  — see [[AccessQuery]] for why the question has to be asked at all. Every bot
 *  runs one, and a question names its guild, so each process answers only for
 *  guilds it can see.
 *
 *  Unlike the write consumer this takes no lease and records nothing: answering
 *  twice is harmless, since the reply describes rather than performs.
 *
 *  ==Two ways in==
 *  Questions arrive on this bot's own channel ([[listen]]) or, from a bot too old
 *  to publish, as a key found by [[sweep]]. The channel is the one that matters —
 *  free until somebody asks, and instant. The sweep is a compatibility path on its
 *  way out (see [[AccessQueryConsumer.SweepEvery]]). Either way the answer goes
 *  where the question says, so arrival and reply are decided separately. */
final class AccessQueryConsumer(
  cache: RedisCache,
  /** Resolves a visitor in one guild, exactly as the dashboard would for a
   *  guild of its own. `None` when they may not use it. */
  resolve: (String, String) => Option[GuildAccess],
  /** Whether this process can see the guild at all. Not whether it *runs* the
   *  respawns there: a bot sharing the guild can read a member just as well,
   *  and refusing on ownership would leave a question nobody answers. */
  canSee: String => Boolean,
  /** This bot's own id, which names the channel it is asked on. */
  selfBotId: String = ""
)(implicit ec: ExecutionContext) extends StrictLogging {

  /** Start listening for questions addressed to this bot. Answers whether it
   *  worked, because the roster has to say whether this bot is listening and that
   *  claim must be true — advertising a channel it never subscribed to would leave
   *  every other bot publishing into silence until a deadline expired. */
  def listen(): Future[Boolean] =
    if (selfBotId.isEmpty) Future.successful(false)
    else cache.subscribe(AccessQuery.questionChannel(selfBotId)) { raw =>
      AccessQuery.fromJson(raw) match {
        case None => logger.warn("Dropping an unreadable dashboard access question")
        case Some(query) =>
          // Off the delivery thread before anything slow happens. This lands on
          // whatever thread the Redis client reads messages on, and answering a
          // question means a *blocking* Discord lookup — run here it would stop
          // that client dead, taking every other Redis user in the process with
          // it for as long as Discord took to reply.
          Future(answerNow(query))(ec).flatten
          ()
      }
    }.map(_ => true).recover {
      case NonFatal(e) =>
        logger.warn(s"Could not listen for dashboard access questions: ${e.getMessage}")
        false
    }

  /** The compatibility path: questions left as keys by a bot that predates the
   *  channel. Finds nothing at all once every bot in the fleet advertises
   *  `pubSub` in its roster. */
  def sweep(): Future[Unit] =
    cache.keysMatching(AccessQuery.requestPattern).flatMap { keys =>
      val mine = keys.flatMap(AccessQuery.parseRequestKey).filter { case (guildId, _) => canSee(guildId) }
      Future.traverse(mine) { case (guildId, id) => answerKeyed(guildId, id) }.map(_ => ())
    }.recover {
      case NonFatal(e) =>
        logger.warn(s"Could not sweep dashboard access queries: ${e.getMessage}")
        ()
    }

  private def answerKeyed(guildId: String, id: String): Future[Unit] = {
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
          //
          // Flattened rather than fired off, so a finished sweep really does
          // mean the answers are written — which is what the tests step, and
          // what makes a beat's work observable at all.
          cache.delete(requestKey).flatMap(_ => Future(answerNow(query)).flatten)
      }
    }
  }

  /** Resolve one question and send the answer wherever it asked to be sent.
   *
   *  Blocking, and always called somewhere that expects to block. A guild this
   *  process cannot see is answered as a refusal rather than left in silence,
   *  the same as a resolution that throws: the asker would wait out its
   *  deadline either way, and a definite answer stops it asking again.
   */
  private def answerNow(query: AccessQuery): Future[Unit] = {
    val reply =
      if (!canSee(query.guildId)) AccessAnswer(None, query.id)
      else
        try AccessAnswer(resolve(query.guildId, query.userId), query.id)
        catch {
          case NonFatal(e) =>
            // A failure to resolve is answered as "no access" rather than left
            // unanswered: the asker would wait out its timeout either way, and
            // this way it stops asking and the reason is written down here.
            logger.warn(s"Could not resolve dashboard access in guild '${query.guildId}': ${e.getMessage}")
            AccessAnswer(None, query.id)
        }
    // Back the way it came. A question that named a reply channel is answered
    // there; one that named none came from a bot still watching a reply key.
    if (query.replyTo.nonEmpty)
      cache.publish(AccessQuery.replyChannel(query.replyTo), reply.toJson).map(_ => ())
    else
      cache.setEx(AccessQuery.replyKey(query.id), reply.toJson, AccessQueryConsumer.ReplyTtl)
  }
}

object AccessQueryConsumer {
  /** Long enough for a caller that has just missed a sweep to still find the
   *  answer, short enough that Redis is not left holding stale ones. Only the
   *  key path uses it. */
  val ReplyTtl: FiniteDuration = 30.seconds

  /** How often the compatibility sweep runs.
   *
   *  Was a quarter of a second, and had to be while a question sitting in a key
   *  was invisible until the next sweep. A published question arrives the instant
   *  it is sent, so this now serves only a bot old enough to write keys.
   *
   *  Worth cutting, because each beat is a Redis `KEYS` — a walk of the *entire*
   *  keyspace, dominated here by a key per character name, while the
   *  single-threaded server does nothing else — and every bot ran four a second.
   *
   *  Not cut as far as it will go, since this beat is still inside an old asker's
   *  deadline. A key written just after a sweep waits for the next, and the
   *  blocking Discord lookup adds a few hundred milliseconds more; against three
   *  seconds, a two-second beat left half a second of margin that one slow lookup
   *  ate. A second leaves three times that and still removes three quarters of the
   *  `KEYS`.
   *
   *  Once no roster advertises anything but `pubSub`, nothing writes a key and this
   *  whole path can go. */
  val SweepEvery: FiniteDuration = 1.second
}
