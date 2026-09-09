package com.tibiabot.statistics

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** How likely a boss is to be up. */
sealed abstract class Chance(val rank: Int)

object Chance {
  /** Inside a spawn window on the tight reading of it. */
  case object High extends Chance(2)

  /** Inside a window only if the boss's cycle is running a day either side of
   *  what the catalogue says. Real, and worth checking, but not worth a trip. */
  case object Low extends Chance(1)

  /** Not due. */
  case object None extends Chance(0)
}

/** One spawn point's prediction.
 *
 *  `daysSince` is how long since that spawn point was last seen. `windowMin` and
 *  `windowMax` are the window it is counting towards, which is not always the
 *  boss's own figures: a boss missed for several cycles is counting towards its
 *  second or third window, and past a certain point the windows overlap so
 *  completely that there is no upper bound left worth printing — that is what a
 *  `windowMax` of None means. */
final case class BossChance(chance: Chance, daysSince: Int, windowMin: Int, windowMax: Option[String])

/** A boss and the state of each of its spawn points. */
final case class BossPrediction(boss: Boss, chances: List[BossChance]) {

  /** The best any of its spawn points is doing — what a reader sorts on, since
   *  one spawn point being up is enough reason to go. */
  def best: Chance = chances.map(_.chance).maxByOption(_.rank).getOrElse(Chance.None)

  /** The fewest days since any spawn point was seen. */
  def daysSince: Int = chances.map(_.daysSince).minOption.getOrElse(0)
}

/** Whether a boss is due, from how long it has been since it was last killed.
 *
 *  ==Provenance==
 *  The window arithmetic in `chanceFor` is a port of `BossPredictor.getChance`
 *  from github.com/kik-tibia/boss-tracker (MIT, © 2023 kik-tibia), and the
 *  spawn-point handling of `getChances` beside it. Taken rather than re-derived
 *  because it encodes years of watching these bosses, and the non-obvious part —
 *  that a boss missed for several cycles is counting towards a *later* window,
 *  not still towards its first — is exactly the part that would have been got
 *  wrong.
 *
 *  ==What this will not do==
 *  A boss that has never been seen in our history is not predicted at all. There
 *  is no anchor for it: the last spawn could be the day before our first snapshot
 *  or a year before it, and nothing here can tell those apart. Guessing from "at
 *  least N days" would make a boss look overdue purely because the bot is new,
 *  which is the one way this feature could actively mislead. Each boss becomes
 *  predictable the first time it is killed after the snapshots begin — about a
 *  month for the short cycles, up to six for the world bosses. */
object BossPredictor {

  /** Predict one boss, or None when it has never been seen.
   *
   *  `sightings` are the days it was seen, newest first, each with that day's
   *  kill count — see [[com.tibiabot.persistence.KillStatisticsRepository.sightings]]. */
  def predict(boss: Boss, sightings: List[(LocalDate, Int)], today: LocalDate): Option[BossPrediction] =
    if (!boss.predict || sightings.isEmpty) Option.empty
    else {
      // A boss with several spawn points has several independent cycles running,
      // so each of its last few sightings is one of them still counting. A day
      // that killed three of them is three sightings, not one.
      val days =
        if (boss.spawnPoints <= 1) sightings.take(1).map(_._1)
        else sightings.flatMap { case (day, killed) => List.fill(math.max(1, killed))(day) }
          .take(boss.spawnPoints)
      Some(BossPrediction(boss, days.map(day => chanceFor(today, day, boss.windowMin, boss.windowMax))))
    }

  /** Every predictable boss the catalogue knows, best chance first.
   *
   *  Bosses with no sighting are absent rather than listed as "not due" — see the
   *  note above on why a missing anchor is not the same as a long wait. */
  def predictAll(sightings: Map[String, List[(LocalDate, Int)]], today: LocalDate): List[BossPrediction] =
    BossCatalogue.bosses
      .flatMap(boss => predict(boss, sightings.getOrElse(boss.race.toLowerCase, Nil), today))
      .sortBy(prediction => (-prediction.best.rank, -prediction.daysSince, prediction.boss.name))

  /** How many predictable bosses are still waiting for a first sighting.
   *
   *  Worth saying out loud in the post: a reader seeing four bosses listed on a
   *  world with fifty-seven predictable ones should know the difference is the
   *  history being young, not the bosses being quiet. */
  def awaitingFirstSighting(sightings: Map[String, List[(LocalDate, Int)]]): Int =
    BossCatalogue.bosses.count(boss =>
      boss.predict && !sightings.get(boss.race.toLowerCase).exists(_.nonEmpty))

  /** Whether a boss is due, and what window it is counting towards.
   *
   *  Ported from kik-tibia/boss-tracker, preserving its arithmetic. The idea: a
   *  boss last seen `daysSince` ago could be in its first spawn window, or — if
   *  it was missed — its second or third. It is "high chance" when the number of
   *  windows whose *start* has passed is at least the number whose *end* has, and
   *  "low chance" on the same test with the window widened a day at each side, so
   *  a cycle running slightly off the catalogue's figures still shows up.
   *
   *  The divisors are guarded, which the original did not need to be: its data
   *  file was its own. This one is a resource anybody can edit, and a `windowMin`
   *  of 1 or a `windowMax` equal to `windowMin` would divide by zero. */
  private[statistics] def chanceFor(today: LocalDate, lastSeen: LocalDate, min: Int, max: Int): BossChance = {
    val daysSince = math.max(0, ChronoUnit.DAYS.between(lastSeen, today).toInt)
    val startWindowsHigh = daysSince / math.max(1, min)
    val endWindowsHigh = (daysSince - 1) / math.max(1, max) + 1
    val startWindowsLow = daysSince / math.max(1, min - 1)
    val endWindowsLow = (daysSince - 1) / math.max(1, max + 1) + 1

    val chance =
      if (startWindowsHigh >= endWindowsHigh) Chance.High
      else if (startWindowsLow >= endWindowsLow) Chance.Low
      else Chance.None

    // Inside the first window, the boss's own figures are the window — shown even
    // when the chance is None, so a reader can see how far off it is.
    if (daysSince <= max) BossChance(chance, daysSince, min, Some(max.toString))
    else {
      // Past the point where consecutive windows overlap completely there is no
      // meaningful upper bound left, and the window reads as "N+".
      val startOfEndless = (max - 1) / math.max(1, max - min) * min
      val windowStart = math.min(math.max(startWindowsHigh, 1) * min, startOfEndless)
      val windowEnd = math.max(startWindowsHigh, 1) * max
      BossChance(chance, daysSince, windowStart,
        if (windowStart >= startOfEndless) Option.empty else Some(windowEnd.toString))
    }
  }
}
