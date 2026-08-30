package com.tibiabot
package app

import akka.actor.Cancellable
import com.typesafe.scalalogging.StrictLogging
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Keeps the primary fetching every world the fleet needs, not just its own.
 *
 *  Sharing only ever helped where two bots happened to track the same world.
 *  For a world only a secondary serves, the primary had no reason to fetch it,
 *  so every one of that secondary's lookups missed and it fetched for itself —
 *  which is the whole problem, since only the primary's address is whitelisted.
 *  This closes that gap by having the primary poll the difference.
 *
 *  Nothing new has to be reported for it to know what the difference is.
 *  Secondaries already publish a full status snapshot every few seconds,
 *  including the exact list of worlds they poll, and the primary already reads
 *  those snapshots for its dashboard. This reuses that: desired = everything
 *  any secondary polls, minus everything this bot already polls properly.
 *
 *  Reconciling on a timer rather than reacting to events is deliberate. The
 *  snapshots expire on their own, so a secondary that dies simply stops
 *  appearing and its worlds are dropped on the next pass with no teardown
 *  message to miss. The cost of being a beat late is one interval of a
 *  secondary fetching for itself, which is exactly what it did before. */
final class UnionFetchReconciler(
    localWorlds: () => Set[String],
    secondaryStatuses: () => Future[Vector[JsObject]],
    startPoller: String => Cancellable,
    enabled: Boolean
)(implicit ec: ExecutionContext) extends StrictLogging {

  private var pollers = Map.empty[String, Cancellable]

  /** Worlds this bot polls for somebody else. Test/diagnostic only. */
  private[app] def covering: Set[String] = synchronized(pollers.keySet)

  /** Every world named in a secondary's snapshot. Anything unreadable is
   *  skipped rather than failing the pass — one malformed snapshot must not
   *  stop the primary covering everybody else. */
  private[app] def worldsWanted(statuses: Vector[JsObject]): Set[String] =
    statuses.flatMap { status =>
      try
        status.fields.get("worlds").collect { case JsArray(worlds) => worlds }.getOrElse(Vector.empty).flatMap { world =>
          world.asJsObject.fields.get("name").collect { case JsString(name) => name }
        }
      catch {
        case NonFatal(e) =>
          logger.warn(s"Skipping an unreadable secondary snapshot while working out which worlds to cover: ${e.getMessage}")
          Vector.empty
      }
    }.toSet

  /** Start covering what is newly wanted, stop covering what is not.
   *
   *  A world this bot has taken on properly since the last pass is dropped
   *  here: the real stream fetches it anyway, and leaving both running would
   *  double this world's requests to the upstream for no benefit at all. */
  def reconcile(): Future[Unit] =
    if (!enabled) Future.unit
    else
      secondaryStatuses().map { statuses =>
        val wanted = worldsWanted(statuses) -- localWorlds()
        synchronized {
          val toStop = pollers.keySet -- wanted
          val toStart = wanted -- pollers.keySet

          toStop.foreach { world =>
            pollers.get(world).foreach(_.cancel())
            pollers -= world
          }
          toStart.foreach { world =>
            pollers += (world -> startPoller(world))
          }

          if (toStart.nonEmpty)
            logger.info(s"Fetching ${toStart.size} world(s) on behalf of the fleet, which no guild here tracks: ${toStart.toList.sorted.mkString(", ")}")
          if (toStop.nonEmpty)
            logger.info(s"No longer fetching ${toStop.size} world(s) for the fleet: ${toStop.toList.sorted.mkString(", ")}")
        }
      }.recover {
        case NonFatal(e) => logger.warn(s"Could not work out which worlds to fetch for the fleet: ${e.getMessage}")
      }

  /** Stop every poller this started. */
  def shutdown(): Unit = synchronized {
    pollers.values.foreach(_.cancel())
    pollers = Map.empty
  }
}
