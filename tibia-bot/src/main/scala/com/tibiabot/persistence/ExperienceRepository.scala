package com.tibiabot.persistence

import com.tibiabot.domain.ExperiencePoint
import com.tibiabot.tibiadata.response.HighscoreEntry

import java.time.{Instant, LocalDate}

/** Persistence port for the experience history — the half of this feature that
 *  posts nothing and exists only so the Statistics channel has something to
 *  read when it is built.
 *
 *  Two tables rather than one, because the honest hourly reading and the thing
 *  worth keeping for a year are different sizes. A snapshot is a thousand rows
 *  per world; at 68 worlds and 24 snapshots that is 1.63M rows a day, which is
 *  58 GB a year on a disk already at 80%. So the raw readings live a week —
 *  enough for any intra-day curve — and a rollup carries one row per character
 *  per server-save day for the long term at about a fortieth of the volume. */
trait ExperienceRepository {

  /** File one snapshot's readings. */
  def recordReadings(world: String, entries: List[HighscoreEntry], observed: Instant): Unit

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

  def removeExpiredReadings(before: Instant): Unit

  def removeExpiredDaily(before: LocalDate): Unit
}
