package com.tibiabot.persistence

import com.tibiabot.domain.{HighscoreEvent, HighscoreRecord}
import com.tibiabot.tibiadata.response.HighscoreEntry

import java.time.Instant

/** Persistence port for the highscore tables in the shared `bot_cache`
 *  database.
 *
 *  World-scoped rather than guild-scoped, alongside the deaths, levels and
 *  world-transfer caches, and for the same reason those are: "has this
 *  character advanced?" is a fact about the world, and every discord tracking
 *  it is looking at the same answer. Keyed per guild it would mean a discord
 *  adding a world for the first time finding an empty table and treating the
 *  whole top thousand as fresh advances.
 *
 *  Blue and red share this database, so one bot writing these tables is enough
 *  for both to read them.
 *
 *  `category` is the endpoint's slug — the same String
 *  [[com.tibiabot.tibiadata.HighscoreCategory.slug]] produces. Kept a plain
 *  String here so persistence does not have to know the endpoint's vocabulary,
 *  the same way it takes worlds and guild names as Strings. */
trait HighscoreRepository {

  /** Every stored score for one list on one world, keyed by lowercased name —
   *  the shape [[com.tibiabot.highscores.HighscoreDiff]] wants for the whole
   *  page-set at once. One query per list per snapshot rather than a lookup per
   *  character. */
  def load(world: String, category: String): Map[String, HighscoreRecord]

  /** Write a whole list's readings in one batch, inserting new characters and
   *  updating existing ones.
   *
   *  `lastSeen` is stamped on every row present in the snapshot, whether or not
   *  the score moved — see [[com.tibiabot.domain.HighscoreRecord]] for why the
   *  unchanged rows have to be touched too. `snapshotAt` is when tibia.com built the data, never
   *  when the write happened, so the staleness rule measures game time rather
   *  than how long our own fetch took. */
  def upsertAll(world: String, category: String, entries: List[HighscoreEntry], snapshotAt: Instant): Unit

  /** File detected advances. Audit only — the stored score is what stops a
   *  repost, so losing these rows can never cause a double announcement. */
  def recordEvents(events: List[HighscoreEvent]): Unit

  /** A world's advances since `since`, most recent first. For the dashboard and
   *  for answering "why did that not post?". */
  def events(world: String, since: Instant): List[HighscoreEvent]

  /** Drop scores for characters not seen since `before` — the ones that have
   *  fallen out of the top thousand for good.
   *
   *  Housekeeping rather than correctness: a lingering row is already harmless,
   *  because the staleness rule re-baselines it in silence if its character
   *  ever comes back. Safe only well past [[com.tibiabot.highscores.HighscoreDiff.MaxBaselineAge]],
   *  since deleting a row inside that window turns a real advance into a first
   *  sighting and swallows it. */
  def removeStale(world: String, before: Instant): Unit

  /** Drop audit rows older than `before`. */
  def removeExpiredEvents(before: Instant): Unit
}
