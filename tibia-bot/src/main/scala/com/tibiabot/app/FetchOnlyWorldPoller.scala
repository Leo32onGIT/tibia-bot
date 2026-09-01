package com.tibiabot
package app

import akka.actor.{ActorSystem, Cancellable}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.tibiabot.tibiadata.TibiaApi
import com.tibiabot.tibiadata.response.OnlinePlayers
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Polls a world purely to fill the shared cache, posting nothing anywhere.
 *
 *  Runs on the primary for worlds only a secondary serves. Those worlds have no
 *  guild here, so nothing has an audience — the whole product is the side effect
 *  of fetches passing through [[com.tibiabot.tibiadata.SharedWorldTibiaApi]] and
 *  being published to Redis for the secondary to read.
 *
 *  '''This deliberately does not reuse [[com.tibiabot.TibiaBot]].''' Most of its
 *  posting is already skipped for a world with no guilds, but not all its writes
 *  are: `addDeathsCache` and `addLevelsCache` sit outside that guard and write
 *  dedup rows into the shared `bot_cache`. A primary running a full stream over
 *  somebody else's world would mark those deaths seen, and since a secondary loads
 *  that table at boot, its next restart would silently stop posting deaths it
 *  never posted. Hence a class that can only fetch.
 *
 *  It mirrors a real stream closely enough to be useful: same fan-out width, same
 *  client stack, same schedule. What it cannot do is remember who was recently
 *  online — a real stream keeps checking somebody for a few minutes after they log
 *  off. Here the online list is the whole population, which is the safe
 *  difference: the secondary still fetches such a straggler on a cache miss. */
final class FetchOnlyWorldPoller(
    world: String,
    api: TibiaApi,
    pollInterval: FiniteDuration,
    firstPollDelay: FiniteDuration,
    fanOut: Int
)(implicit system: ActorSystem, ec: ExecutionContext, mat: Materializer) extends StrictLogging {

  def start(): Cancellable =
    system.scheduler.scheduleWithFixedDelay(firstPollDelay, pollInterval)(() => pollOnce())

  private def pollOnce(): Unit =
    try {
      api.getWorld(world).flatMap {
        case Right(response) =>
          val online: List[OnlinePlayers] = response.world.online_players.getOrElse(Nil)
          if (online.isEmpty) Future.successful(0)
          else
            Source(online.map(_.name).toSet)
              .mapAsyncUnordered(fanOut)(api.getCharacter)
              .runWith(Sink.ignore)
              .map(_ => online.size)
        case Left(error) =>
          // Nothing to do about it here: the world is re-polled next interval,
          // and the secondary that actually serves it covers the gap by
          // fetching for itself while nothing is published.
          logger.debug(s"Fetch-only poll for world '$world' could not read the world: $error")
          Future.successful(0)
      }.recover {
        case NonFatal(e) =>
          logger.warn(s"Fetch-only poll for world '$world' failed: ${e.getMessage}")
          0
      }
      ()
    } catch {
      case NonFatal(e) => logger.warn(s"Fetch-only poll for world '$world' could not start: ${e.getMessage}")
    }
}
