package com.tibiabot.persistence

import com.tibiabot.domain.{ExperienceDelta, ExperiencePoint}
import com.tibiabot.tibiadata.response.HighscoreEntry

import java.time.LocalDate

/** Persistence port for the experience history the Statistics channel reads.
 *
 *  One table, holding one row per character per server-save day. There used to
 *  be a second one keeping every hourly reading behind it, on the reasoning that
 *  an intra-day curve would want them. Nothing was ever built that read it, and
 *  measured against real Postgres it was 30 MB per world of the 58 this feature
 *  uses — more than half the disk, for a table whose only statements were an
 *  INSERT and a DELETE. If the curve is ever wanted, the readings are fifteen
 *  lines to bring back; the year of them nobody looked at is not. */
trait ExperienceRepository {

  /** Fold the same readings into the day's rollup.
   *
   *  Written on every snapshot rather than once at server save, last write
   *  winning. That makes the row a live figure during the day and the closing
   *  one after it, needs no schedule of its own, and heals itself after a
   *  restart — where a single timed write would simply miss the day. */
  def recordDaily(world: String, entries: List[HighscoreEntry], saveDay: LocalDate): Unit

  /** One character's daily points from `from` onward, oldest first — the shape
   *  an "experience gained" series wants. */
  def daily(world: String, name: String, from: LocalDate): List[ExperiencePoint]

  /** The day's biggest experience gains on one world, largest first.
   *
   *  A day's gain is the difference between the rollup for `saveDay` and the one
   *  for the day before it, so a character present in only one of them is left
   *  out entirely: entering the world's top thousand is not a day's experience,
   *  and neither is dropping out of it. That also means the first `saveDay` a
   *  world was ever swept has no movers at all, which is the correct answer
   *  rather than a gap to paper over.
   *
   *  Gains only — [[dailyLoss]] is the other end of the same ordering. */
  def dailyMovers(world: String, saveDay: LocalDate, limit: Int): List[ExperienceDelta]

  /** The single largest experience loss on one world that day, or None if
   *  nobody ended the day down. Same join and same exclusions as
   *  [[dailyMovers]], read from the other end. */
  def dailyLoss(world: String, saveDay: LocalDate): Option[ExperienceDelta]

  /** The largest experience losses that day among a named set of characters,
   *  worst first.
   *
   *  For the PVP post's "Most Exp Lost", where the set is one guild's hunted
   *  list. Names are matched lowercased, the same key
   *  [[com.tibiabot.highscores.HighscoreDiff.key]] stores.
   *
   *  Usually returns very little, and that is the honest answer rather than a
   *  fault: this table holds the world's top thousand by experience, and most
   *  tracked enemies are ordinary players who are not in it. An enemy with no row
   *  has no figure at all, not a figure of zero. */
  def lossesAmong(world: String, saveDay: LocalDate, names: Set[String], limit: Int): List[ExperienceDelta]

  def removeExpiredDaily(before: LocalDate): Unit
}
