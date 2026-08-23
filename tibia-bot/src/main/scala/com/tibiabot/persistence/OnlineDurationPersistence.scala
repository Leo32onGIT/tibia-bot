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
 *  (see tracking.OnlineTracker) — one snapshot blob per world, keyed by world
 *  name, so a restart doesn't reset every online player's displayed duration
 *  back to zero. Boot `load()`s it; a save is triggered after every real
 *  `OnlineTracker.updateFromOnline` cycle (TibiaBot's existing ~60s poll, no
 *  separate schedule needed).
 *
 *  Deliberately omits `time` (last-updated-at) from the snapshot — restoring
 *  it verbatim would make the first post-restart poll's delta count the
 *  entire downtime gap toward duration; OnlineTracker.restore re-stamps it to
 *  the restore moment instead. Written as one whole blob per cycle rather
 *  than per entry, because the state this wraps is rebuilt in full every poll
 *  and the poll must never touch Redis per character.
 *
 *  No-op when Redis is disabled (NoopRedisCache); all failures degrade to an
 *  empty load / dropped save, so this can never affect correctness. */
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
