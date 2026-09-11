package com.tibiabot.statistics

import com.tibiabot.domain.time.DreamScarCycle
import com.tibiabot.persistence.KillStatisticsRepository
import com.typesafe.scalalogging.StrictLogging

import java.time.LocalDate
import scala.util.control.NonFatal

/** What to do with a verdict that disagrees with the wiki. */
sealed trait DreamCourtMode

object DreamCourtMode {

  /** Work out the answer and log where it differs, but leave the wiki in charge.
   *
   *  Where this starts, and where it should stay until the log has been read.
   *  The evidence rule is built on one day's measurements of a signal that was
   *  right about half the time on its own; a fortnight of them should be much
   *  better, but "should be" is not "is", and observing costs one query per world
   *  per morning to find out for certain. */
  case object Observe extends DreamCourtMode

  /** Let the evidence win where it is confident. */
  case object Heal extends DreamCourtMode

  def parse(text: String): DreamCourtMode =
    if (text.trim.equalsIgnoreCase("heal")) Heal else Observe
}

/** Corrects the Dream Courts boss of the day from what worlds actually killed.
 *
 *  ==The problem==
 *  The boss is read off a wiki page whose per-world offsets are wrong in two
 *  different ways. They drift: a server reset bumps a world's rotation and the
 *  page stays wrong until somebody edits it, which is the failure this was
 *  started for. And for forty-three of a hundred and eleven worlds the page is
 *  marked uncertain and falls through to a default, so if the true offsets are
 *  anything like evenly spread, roughly four in five of those worlds have been
 *  wrong every day since they existed. The second is the larger problem and the
 *  same fix.
 *
 *  ==The correction==
 *  Kill statistics are the one source that answers it from evidence, because the
 *  boss of the day is the one people can actually go and kill.
 *  [[DreamCourtEvidence]] holds why that is a fortnight's vote rather than a
 *  reading, and what it refuses to answer.
 *
 *  A world only moves when the evidence is confident *and* disagrees. Everything
 *  else — a quiet world, a thin margin, a world nobody hunts the Dream Courts on
 *  — keeps what the wiki said. That asymmetry is deliberate: the wiki is right
 *  about most worlds and this is here for the ones it is not.
 *
 *  ==Primary only==
 *  It reads the shared cache and writes nothing, so running it on both bots
 *  would be the same answer computed twice. The secondary takes the corrections
 *  in its own refresh, off the same rows.
 *
 *  @param window how many days of history a verdict is drawn from
 */
final class DreamCourtService(
    killStatistics: KillStatisticsRepository,
    mode: () => DreamCourtMode,
    window: Int = DreamCourtService.Window,
    minDays: Int = DreamCourtService.MinDays,
    minLead: Int = DreamCourtService.MinLead
) extends StrictLogging {

  private val races: Set[String] = DreamScarCycle.bossCycle.map(_.toLowerCase).toSet

  /** The wiki's map, with the worlds the evidence is confident about corrected.
   *
   *  Takes and returns the same shape the wiki read produces, so this slots in
   *  between that read and the map the post uses without either end knowing it
   *  is there. */
  def correct(fromWiki: Map[String, String], today: LocalDate): Map[String, String] = {
    val healing = mode() == DreamCourtMode.Heal
    val corrections = fromWiki.keys.toList.sorted.flatMap { world =>
      divergence(world, fromWiki.get(world), today).map(world -> _)
    }

    if (corrections.isEmpty) fromWiki
    else if (!healing) {
      logger.info(s"Dream Courts: ${corrections.size} world(s) where the kills disagree with the wiki, " +
        "not corrected because the evidence is only being observed")
      fromWiki
    } else {
      logger.info(s"Dream Courts: corrected ${corrections.size} world(s) from the kill statistics")
      fromWiki ++ corrections.map { case (world, verdict) => world -> verdict.boss }
    }
  }

  /** One world's verdict, if it both says something and says something
   *  different. Logged either way, because the whole point of the observe mode
   *  is that the disagreements are readable before they are acted on. */
  private def divergence(world: String, wikiBoss: Option[String], today: LocalDate): Option[DreamCourtVerdict] =
    evidence(world, today).filter { verdict =>
      val differs = !wikiBoss.exists(_.equalsIgnoreCase(verdict.boss))
      if (differs)
        logger.info(s"Dream Courts: '$world' killed ${verdict.boss} on ${verdict.votes} of " +
          s"${verdict.days} day(s) with a signal — the wiki says ${wikiBoss.getOrElse("nothing")} " +
          s"(runner-up ${verdict.runnerUp})")
      else
        logger.debug(s"Dream Courts: '$world' agrees with the wiki on ${verdict.boss} " +
          s"(${verdict.votes} of ${verdict.days})")
      differs
    }

  private def evidence(world: String, today: LocalDate): Option[DreamCourtVerdict] =
    try DreamCourtEvidence.verdict(
      killStatistics.dailyCounts(world, today.minusDays(window.toLong), races), today, minDays, minLead)
    catch {
      case NonFatal(error) =>
        logger.warn(s"Dream Courts: could not read the kill history for '$world': ${error.getMessage}")
        None
    }
}

object DreamCourtService {

  /** How far back a verdict looks.
   *
   *  A fortnight. About a quarter of world-days have no Dream Courts kill at all,
   *  so fourteen days is roughly ten usable ones — enough for a mode to pull
   *  clear of the neighbours that sit one step either side of it, without
   *  reaching so far back that a world which genuinely was bumped takes a month
   *  to be believed. */
  val Window: Int = 14

  /** How many days with a signal a verdict needs before it counts.
   *
   *  Six. Below that the mode is one or two votes and the ±1 neighbours are
   *  within noise of it. A world that never reaches six is a world nobody hunts
   *  the Dream Courts on, and leaving it to the wiki is the right answer rather
   *  than a gap. */
  val MinDays: Int = 6

  /** How far clear of the runner-up the winner has to be.
   *
   *  Two. One would let a single noisy day decide a close window, which is
   *  exactly the failure mode the accumulation exists to avoid. */
  val MinLead: Int = 2
}
