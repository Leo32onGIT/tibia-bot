package com.tibiabot.tracking

import java.time.ZonedDateTime
import scala.collection.mutable

/** Level-up dedup state extracted from `TibiaBot.recentLevels`.
 *
 *  OPTIMIZED implementation: keyed by (name, level), keeping the record with the
 *  greatest lastLogin. The original `mutable.Set[CharLevel]` was scanned
 *  linearly with `.exists`/`.filter` on every character every cycle
 *  (TibiaBot.scala 625-659); here `shouldRecord` is O(1). Behaviour is
 *  identical because the original forall/exists over all matching records
 *  reduces exactly to a comparison against the newest matching lastLogin —
 *  locked in by LevelTrackerSpec.
 */
final case class LevelRecord(
  name: String,
  level: Int,
  vocation: String,
  lastLogin: ZonedDateTime,
  time: ZonedDateTime
)

final class LevelTracker {
  // keyed by (name, level), holding the record with the greatest lastLogin.
  private val recent = mutable.Map.empty[(String, Int), LevelRecord]

  def size: Int = recent.size
  def snapshot: Set[LevelRecord] = recent.values.toSet
  def load(records: Iterable[LevelRecord]): Unit = records.foreach(record)

  /** Should this (name, level) advancement be posted & recorded?
   *
   *  Faithful to the gate at TibiaBot.scala 625-627 / 650-652:
   *    !exists(name,level)  ||  all matching records have lastLogin < sheetLastLogin
   *  i.e. there is NO record for (name, level) whose lastLogin is at or after
   *  the current sheet login. With one kept record (the max lastLogin), this is
   *  simply: absent, or its lastLogin is before the sheet login. */
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
   *  (mirrors cleanUp() line 1736-1739). */
  def prune(now: ZonedDateTime, expirySeconds: Long): Unit =
    recent.filterInPlace { case (_, r) => java.time.Duration.between(r.time, now).getSeconds < expirySeconds }
}
