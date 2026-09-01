package com.tibiabot
package persistence

import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

final case class OnlinePlayerSnapshot(level: Int, vocation: String, guildName: String, duration: Long, flag: String)

object OnlinePlayerSnapshot {
  implicit val format: RootJsonFormat[OnlinePlayerSnapshot] = jsonFormat5(OnlinePlayerSnapshot.apply)
}

/** Best-effort Redis persistence for a world's in-memory online-duration state
 *  (see tracking.OnlineTracker) — one snapshot blob per world, so a restart does
 *  not reset every player's displayed duration to zero. Loaded at boot, saved
 *  after every `OnlineTracker.updateFromOnline` cycle, on the existing poll.
 *
 *  Deliberately omits `time`: restoring it verbatim would make the first
 *  post-restart delta count the whole downtime gap toward duration, so
 *  OnlineTracker.restore re-stamps it. Written as one blob per cycle rather than
 *  per entry, since the state is rebuilt in full every poll and the poll must
 *  never touch Redis per character.
 *
 *  A no-op without Redis; every failure degrades to an empty load or dropped
 *  save, so it can never affect correctness. */
final class OnlineDurationPersistence(cache: RedisCache, world: String, ttl: FiniteDuration = 20.minutes)(implicit ec: ExecutionContext) {
  private val key = s"tibia:online-snapshot:${world.toLowerCase}"

  /** Read the last snapshot; an absent/corrupt/unreachable cache yields empty. */
  def load(): Future[Map[String, OnlinePlayerSnapshot]] =
    cache.get(key).map {
      case Some(json) => json.parseJson.convertTo[Map[String, OnlinePlayerSnapshot]]
      case None => Map.empty[String, OnlinePlayerSnapshot]
    }.recover { case NonFatal(_) => Map.empty[String, OnlinePlayerSnapshot] }

  /** Persist the current snapshot (best-effort; errors are swallowed by setEx). */
  def save(snapshot: List[tracking.OnlinePlayer]): Future[Unit] = {
    val dto = snapshot.map(p => p.name -> OnlinePlayerSnapshot(p.level, p.vocation, p.guildName, p.duration, p.flag)).toMap
    cache.setEx(key, dto.toJson.compactPrint, ttl)
  }
}
