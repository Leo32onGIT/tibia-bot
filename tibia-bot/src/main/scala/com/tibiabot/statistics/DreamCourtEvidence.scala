package com.tibiabot.statistics

import com.tibiabot.domain.time.DreamScarCycle

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What the banked kills say a world's Dream Courts boss is today.
 *
 *  @param bossIndex  the winning position in [[com.tibiabot.domain.time.DreamScarCycle.bossCycle]]
 *  @param votes      days that agreed with it, once projected forward to today
 *  @param runnerUp   days that agreed with the next best answer
 *  @param days       days in the window that had any kill at all to vote with
 */
final case class DreamCourtVerdict(bossIndex: Int, votes: Int, runnerUp: Int, days: Int) {
  def boss: String = DreamScarCycle.bossCycle(bossIndex)
  def lead: Int = votes - runnerUp
}

/** Deriving a world's Dream Courts boss from what it actually killed.
 *
 *  ==Why this is a vote and not a reading==
 *  The obvious approach — yesterday's kills name yesterday's boss, so add one —
 *  does not survive contact with the data. Several of the five are killed on the
 *  same world on the same day, so the signal is which was killed *most*, and on
 *  a full sweep of ninety-six worlds that agreed with the wiki's own offsets
 *  only about 46% of the time. The disagreements were not random: a quarter sat
 *  exactly one step behind and a quarter exactly one step ahead, with almost
 *  nothing further out.
 *
 *  Symmetric ±1 is the shape of a noisy estimator, not of a drifted page — a
 *  crash that bumps a world's rotation can only push it *ahead*. Two thirds of
 *  the signals were also thin: 2-vs-1, 3-vs-2, one outright tie. So a single
 *  day is not evidence of anything, and acting on one would make the boss wrong
 *  more often than the wiki already does.
 *
 *  What that same shape does allow is accumulation. Every day's answer is
 *  projected forward to today — the rotation advances exactly one step per day,
 *  so an observation from nine days ago is still a vote about today — and the
 *  errors cancel because they fall either side of the truth. Over a fortnight
 *  the mode is a far better answer than any one day, and the margin says how
 *  much better.
 *
 *  ==Why there is no stored anchor==
 *  The banked kill statistics *are* the durable record. Recomputing from them
 *  each morning costs one query per world and leaves nothing to go stale, no
 *  rule for how long an anchor stays trusted, and no second copy of the truth to
 *  disagree with the first. The window is the answer to "how long is this good
 *  for".
 */
object DreamCourtEvidence {

  private val races: Map[String, Int] =
    DreamScarCycle.bossCycle.zipWithIndex.map { case (name, index) => name.toLowerCase -> index }.toMap

  /** The boss a single day points at: the one that world killed most of.
   *
   *  None where nothing was killed, and none on a tie — roughly a quarter of
   *  world-days are silent and a tie is genuinely two answers, so both abstain
   *  rather than guess. */
  def observed(rows: List[BossKills]): Option[Int] = {
    val killed = rows.flatMap(row => races.get(row.race.toLowerCase).map(_ -> row.killed))
      .filter(_._2 > 0)
      .sortBy { case (index, count) => (-count, index) }
    killed match {
      case (index, best) :: rest if !rest.headOption.exists(_._2 == best) => Some(index)
      case _ => None
    }
  }

  /** Every day's answer, carried forward to `today`.
   *
   *  The projection is what makes old days useful: the rotation advances one
   *  step per day, so a boss seen four days ago implies a boss four steps on. */
  def votes(rows: List[BossKills], today: LocalDate): Map[Int, Int] =
    rows.groupBy(_.saveDay).toList
      .flatMap { case (day, onThatDay) =>
        observed(onThatDay).map { index =>
          val forward = ChronoUnit.DAYS.between(day, today)
          Math.floorMod(index + forward, DreamScarCycle.bossCycle.length.toLong).toInt
        }
      }
      .groupBy(identity)
      .map { case (index, all) => index -> all.size }

  /** The window's verdict, or None where it is not worth acting on.
   *
   *  Two guards, and both matter. `minDays` is how much evidence there has to be
   *  at all — a world nobody hunts the Dream Courts on will never reach it, and
   *  that is the correct outcome rather than a problem to solve. `minLead` is how
   *  far ahead the winner has to be, which is what stops the ±1 neighbours
   *  winning on a run of thin days.
   *
   *  A world that fails either keeps whatever the wiki says. Silence here is
   *  deliberately the safe answer. */
  def verdict(rows: List[BossKills], today: LocalDate, minDays: Int, minLead: Int): Option[DreamCourtVerdict] = {
    val tally = votes(rows, today)
    val days = tally.values.sum
    if (days < minDays) None
    else {
      val ranked = tally.toList.sortBy { case (index, count) => (-count, index) }
      ranked match {
        case (index, best) :: rest =>
          val second = rest.headOption.map(_._2).getOrElse(0)
          if (best - second < minLead) None
          else Some(DreamCourtVerdict(index, best, second, days))
        case Nil => None
      }
    }
  }
}
