package com.tibiabot.domain

/** One guild's tracked-activity rows, looked up by name instead of scanned for.
 *
 *  The rows themselves stay a `List[PlayerCache]` — that is what the whole
 *  activity map is edited as, and what gets written back — but the death scan
 *  only ever asks the list one question: *is there a row under this name, and
 *  what is in it?* Asked with `equalsIgnoreCase` down a list, that is a full
 *  pass per question, and the scan asks it three times per character per
 *  discord. On the busiest world the discords watching it hold ~52,000 rows
 *  between them, so a single character cost ~200,000 string comparisons before
 *  anything had been decided about them.
 *
 *  Names are matched case-insensitively throughout the activity code, so the
 *  key is the lowercased name and every entry point lowercases what it is
 *  given. Callers never see the key.
 *
 *  Where two rows collapse onto one key — the table's primary key is on `name`,
 *  which Postgres compares case-sensitively, so "Bob" and "bob" can both exist —
 *  the *first* in list order wins, because that is the row `find` and `exists`
 *  would have returned.
 */
final class ActivityIndex private (private val byLowerName: Map[String, PlayerCache]) {

  /** The row stored under `name`, if there is one. */
  def get(name: String): Option[PlayerCache] = byLowerName.get(name.toLowerCase)

  /** Whether any row is stored under `name`. */
  def contains(name: String): Boolean = byLowerName.contains(name.toLowerCase)

  /** How many distinct names are indexed — fewer than the rows built from, if
   *  any of them collided. */
  def size: Int = byLowerName.size
}

object ActivityIndex {

  val empty: ActivityIndex = new ActivityIndex(Map.empty)

  /** Built in reverse so that on a key collision the row written last — and so
   *  the one that survives — is the one earliest in `rows`. */
  def apply(rows: List[PlayerCache]): ActivityIndex =
    if (rows.isEmpty) empty
    else new ActivityIndex(rows.reverseIterator.map(row => row.name.toLowerCase -> row).toMap)
}
