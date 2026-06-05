package com.tibiabot
package persistence

import spray.json._
import spray.json.DefaultJsonProtocol._

import java.time.ZonedDateTime
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Best-effort Redis persistence for the in-memory Date-header character cache.
 *
 *  Stored as a single periodic snapshot blob (key `tibia:chardate-snapshot`) of
 *  name -> last-seen ISO timestamp. Boot `load()`s it so a restart doesn't
 *  re-baseline ~8000 characters against the rate-limited API; a scheduled
 *  `save()` writes it.
 *
 *  Deliberately a WHOLE-MAP snapshot, not per-character write-behind: the
 *  death-detection hot path writes the character cache ~8000x/cycle, so it must
 *  never touch Redis per entry. Up to one snapshot interval of cache state can be
 *  lost on an abrupt crash — acceptable, since the fallback is just a few extra
 *  re-fetches (far better than losing the entire cache on every restart).
 *
 *  No-op when Redis is disabled (NoopRedisCache); all failures degrade to an
 *  empty load / dropped save, so this can never affect correctness. */
final class CharacterCachePersistence(cache: RedisCache, ttl: FiniteDuration = 7.days)(implicit ec: ExecutionContext) {
  private val key = "tibia:chardate-snapshot"

  /** Read the last snapshot; an absent/corrupt/unreachable cache yields empty. */
  def load(): Future[Map[String, ZonedDateTime]] =
    cache.get(key).map {
      case Some(json) =>
        json.parseJson.convertTo[Map[String, String]].toList.flatMap { case (name, iso) =>
          try Some(name -> ZonedDateTime.parse(iso)) catch { case NonFatal(_) => None }
        }.toMap
      case None => Map.empty[String, ZonedDateTime]
    }.recover { case NonFatal(_) => Map.empty[String, ZonedDateTime] }

  /** Persist the current snapshot (best-effort; errors are swallowed by setEx). */
  def save(snapshot: Map[String, ZonedDateTime]): Future[Unit] =
    cache.setEx(key, snapshot.map { case (k, v) => k -> v.toString }.toJson.compactPrint, ttl)
}
