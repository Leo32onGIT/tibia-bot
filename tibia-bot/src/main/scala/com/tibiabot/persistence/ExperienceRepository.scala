package com.tibiabot.persistence

import com.tibiabot.domain.ExperienceDelta
import com.tibiabot.tibiadata.response.HighscoreEntry

import java.time.{Instant, LocalDate}

/** Persistence port for the experience history the Statistics channel reads.
 *
 *  Two tables, because a day and a reading answer different questions. The
 *  rollup holds one row per character per server-save day and can only say what
 *  a day came to. The readings hold every hourly snapshot, which is what a
 *  window ending *now* needs: "the last 24 hours" is this reading minus the one
 *  from a day ago, and no arrangement of daily totals contains that.
 *
 *  They are expensive and the size is the whole argument for keeping them
 *  apart. A snapshot is a thousand rows per world; at 68 worlds and 24
 *  snapshots that is 1.63M rows a day, measured at 30.7 MB per world for the
 *  week they are kept — against a rollup carrying a fortieth of the volume for
 *  ninety days. That week was dropped in September 2026, correctly, while
 *  nothing read it, and came back for the statistics post's refresh button.
 *  The button went on 26 Sep 2026 and the readings stayed, by choice, so the
 *  intra-day curve stays buildable. The daily post also reads them to learn
 *  when a world's closing reading is in (see [[readingTimes]]). */
trait ExperienceRepository {

  /** File one snapshot's readings, keyed by the instant tibia.com built it.
   *
   *  Every tracked character every hour, so the largest write this feature
   *  makes. Re-filing a snapshot already stored is a no-op rather than a
   *  correction — see the implementation. */
  def recordReadings(world: String, entries: List[HighscoreEntry], observed: Instant): Unit

  /** Fold the same readings into the day's rollup.
   *
   *  Written on every snapshot rather than once at server save, last write
   *  winning. That makes the row a live figure during the day and the closing
   *  one after it, needs no schedule of its own, and heals itself after a
   *  restart — where a single timed write would simply miss the day. */
  def recordDaily(world: String, entries: List[HighscoreEntry], saveDay: LocalDate): Unit

  /** The instants this world has readings at, oldest first, within `from` to
   *  `to` inclusive.
   *
   *  A world's readings land on a handful of instants rather than being spread
   *  over the hour: one sweep stamps every character it read with the same
   *  `snapshotAt`, so this comes back as roughly one instant per hour. That is
   *  what lets a window be pinned to two exact instants and matched on equality
   *  in [[gainsBetween]], instead of each character being measured over a
   *  slightly different span.
   *
   *  The daily post asks it one question: whether the reading that closes a
   *  save day is in for a world yet, so the post can be brought up to it — see
   *  [[com.tibiabot.statistics.StatisticsService]]. */
  def readingTimes(world: String, from: Instant, to: Instant): List[Instant]

  /** The largest experience gains between two readings, largest first.
   *
   *  `from` and `to` are snapshot instants [[readingTimes]] returned, not
   *  arbitrary times: both ends are matched on equality, so anything else finds
   *  nothing at all rather than the nearest reading.
   *
   *  Same exclusion as [[dailyGains]], for the same reason and by the same
   *  means: the join drops anybody missing from either end, since entering the
   *  world's top thousand is not experience gained. Only real gains come back,
   *  enforced in the query rather than left to the caller. */
  def gainsBetween(world: String, from: Instant, to: Instant, limit: Int): List[ExperienceDelta]

  /** The largest experience losses between the same two readings, worst first.
   *  Same join and same exclusions as [[gainsBetween]], read from the other
   *  end. */
  def lossesBetween(world: String, from: Instant, to: Instant, limit: Int): List[ExperienceDelta]

  /** The day's biggest experience gains on one world, largest first.
   *
   *  A day's gain is the difference between the rollup for `saveDay` and the one
   *  for the day before it, so a character present in only one of them is left
   *  out entirely: entering the world's top thousand is not a day's experience,
   *  and neither is dropping out of it. That also means the first `saveDay` a
   *  world was ever swept has nothing to report, which is the correct answer
   *  rather than a gap to paper over.
   *
   *  Only real gains, which the query itself enforces — on a very quiet world
   *  the tenth-placed mover can be somebody who simply died, and listing them
   *  under "top experience gained" would be wrong. So this comes back shorter
   *  than `limit` on a quiet day, the same way [[dailyLosses]] does. */
  def dailyGains(world: String, saveDay: LocalDate, limit: Int): List[ExperienceDelta]

  /** The largest experience losses on one world that day, worst first. Same join
   *  and same exclusions as [[dailyGains]], read from the other end.
   *
   *  Only real losses, which the query itself enforces — on a world where
   *  almost everybody gained, the bottom of the ordering is still a gain, and
   *  listing those under a heading that says losses would be wrong. So this
   *  comes back shorter than `limit` on a quiet day and empty where nobody
   *  ended the day down, which is the honest shape rather than a padded one. */
  def dailyLosses(world: String, saveDay: LocalDate, limit: Int): List[ExperienceDelta]

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

  def removeExpiredReadings(before: Instant): Unit

  def removeExpiredDaily(before: LocalDate): Unit
}
