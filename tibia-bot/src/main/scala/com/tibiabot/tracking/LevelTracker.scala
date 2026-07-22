package com.tibiabot.tracking

import java.time.ZonedDateTime
import scala.collection.mutable

/** Level-up dedup state used by TibiaBot to decide whether a level-up should
 *  be posted & recorded. Keyed by (name, level), keeping only the record with
 *  the greatest lastLogin — behaviour pinned by LevelTrackerSpec.
 */
final case class LevelRecord(
  name: String,
  level: Int,
  vocation: String,
  lastLogin: ZonedDateTime,
  time: ZonedDateTime
)

final class LevelTracker {
  private val recent = mutable.Map.empty[(String, Int), LevelRecord]

  def size: Int = recent.size
  def snapshot: Set[LevelRecord] = recent.values.toSet
  def load(records: Iterable[LevelRecord]): Unit = records.foreach(record)

  /** Should this (name, level) advancement be posted & recorded? True unless
   *  there's already a record for (name, level) whose lastLogin is at or
   *  after this sheet login — i.e. absent, or its lastLogin is before it. */
  def shouldRecord(name: String, level: Int, sheetLastLogin: ZonedDateTime): Boolean =
    recent.get((name, level)).forall(_.lastLogin.isBefore(sheetLastLogin))

  /** Keep the record with the greatest lastLogin for each (name, level). */
  def record(r: LevelRecord): Unit = {
    val key = (r.name, r.level)
    recent.get(key) match {
      case Some(existing) if existing.lastLogin.isAfter(r.lastLogin) => // keep the newer one
      case _ => recent.update(key, r)
    }
  }

  /** Remove records older than `expirySeconds` measured by recorded `time`
   *  (called from TibiaBot's periodic cleanUp()). */
  def prune(now: ZonedDateTime, expirySeconds: Long): Unit =
    recent.filterInPlace { case (_, r) => java.time.Duration.between(r.time, now).getSeconds < expirySeconds }
}
