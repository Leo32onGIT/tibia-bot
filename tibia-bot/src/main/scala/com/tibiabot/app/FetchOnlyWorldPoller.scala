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
 *  guild on this bot, so nothing here has an audience — the entire product of
 *  this class is the side effect of the fetches passing through
 *  [[com.tibiabot.tibiadata.SharedWorldTibiaApi]] underneath and being
 *  published to Redis, where the secondary that does serve the world reads them
 *  instead of calling the upstream itself.
 *
 *  '''This deliberately does not reuse [[com.tibiabot.TibiaBot]].''' Reaching
 *  for it is the obvious move, since most of its posting is already skipped for
 *  a world with no guilds — but not all of its writes are. `addDeathsCache` and
 *  `addLevelsCache` sit outside that guard and write dedup rows into the shared
 *  `bot_cache`. A primary running a full stream over somebody else's world
 *  would mark those deaths as already seen, and a secondary loads that table at
 *  boot — so its next restart would silently stop posting deaths it had never
 *  actually posted. Wrong, shared between bots, and visible only as deaths
 *  quietly going missing. Hence a class that can only fetch.
 *
 *  What it fetches mirrors a real stream closely enough to be useful: the same
 *  fan-out width over the same client stack, so the cache is filled with the
 *  same sheets a real poll would have produced, on the same schedule. What it
 *  cannot do is remember who was recently online — a real stream keeps checking
 *  someone for a few minutes after they log off, to catch a death that lands
 *  after the logout. Here the online list is the whole population, which is
 *  the small, safe difference: the secondary still fetches such a straggler
 *  itself on a cache miss, exactly as it does today. */
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
