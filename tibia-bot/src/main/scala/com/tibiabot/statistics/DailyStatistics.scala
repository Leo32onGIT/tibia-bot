package com.tibiabot.statistics

import com.tibiabot.domain.{ExperienceDelta, HighscoreEvent}
import com.tibiabot.domain.time.Clock
import com.tibiabot.scheduler.ServerSaveSchedule

import java.time.{Instant, LocalDate, ZonedDateTime}

/** One world's finished server-save day, as the Statistics channel reports it.
 *
 *  Everything here is a fact about the world rather than about any discord, so
 *  one of these is built per world per day and posted to every guild tracking
 *  it — see [[StatisticsService]]. The frag tally, which is the one guild-scoped
 *  thing the channel will eventually carry, is deliberately not part of this. */
final case class DailyReport(
    world: String,
    saveDay: LocalDate,
    gains: List[ExperienceDelta],
    losses: List[ExperienceDelta],
    advance: Option[HighscoreEvent],
    /** What the world was killing, from the day's kill statistics snapshot.
     *
     *  None where the snapshot was never taken — a bot that was down, an
     *  upstream 503 that outlasted the day, or simply the first day after this
     *  shipped. The board is unaffected: it and the creature figures come from
     *  different sources and it never waits on this one.
     *
     *  Also the gate the post is staged on — see [[StatisticsService]] — so
     *  everything below that reads the snapshot is present exactly when this is. */
    kills: Option[DayKillSummary] = None,
    /** The creatures the world killed most of that day, largest first. */
    topKills: List[BossKills] = Nil,
    /** The special bosses that died that day, and how many, in the order
     *  [[SpecialKills]] lists them. Empty on the ordinary day none did. */
    specialKills: List[(SpecialKill, Int)] = Nil,
    /** Which bosses are due, best chance first.
     *
     *  Empty for a long while after this ships, and that is the honest state
     *  rather than a fault: a boss with no sighting in our history has no anchor
     *  to count from. `awaitingSighting` says how many are in that position, so
     *  a short list is legible as a young history rather than a quiet world. */
    predictions: List[BossPrediction] = Nil,
    awaitingSighting: Int = 0,
    /** How many players were on this world on average that day, and what level
     *  one of them was.
     *
     *  Only the PVP bar reads them: population decides how much fighting the
     *  world could plausibly have held, and level decides what one death of a
     *  local is worth. None for a day nothing sampled it, where the bar falls
     *  back to a default scale rather than going missing. */
    averageOnline: Option[Double] = None,
    averageLevel: Option[Double] = None
) {

  /** Nothing to say. The ordinary cause is a cold start rather than a quiet day:
   *  a gain needs two consecutive rollups, so a world's very first reportable
   *  day is the second one it was swept. A world where genuinely nobody in the
   *  top thousand moved and nobody advanced a skill is possible in principle and
   *  reads the same way — silence, which is the honest answer either way. */
  def isEmpty: Boolean =
    gains.isEmpty && losses.isEmpty && advance.isEmpty && kills.isEmpty && dueBosses.isEmpty

  /** The bosses worth a line: the ones that might actually be up. A boss three
   *  days into a twelve-day window is not news. */
  def dueBosses: List[BossPrediction] = predictions.filter(_.best != Chance.None)

  def nonEmpty: Boolean = !isEmpty
}

/** When a day closes, what window it covers, and how much of it to show.
 *
 *  Pure — no database, no JDA, no Config. Everything that decides *what the
 *  numbers mean* lives here so it can be pinned by tests, the same split
 *  [[com.tibiabot.highscores.HighscoreDiff]] keeps against `HighscoreSweep`. */
object DailyStatistics {

  /** How many gainers the post names. Ten, as asked; the description has room
   *  for roughly thirty, so this is a choice about readability rather than a
   *  limit being pressed against. */
  val TopGains: Int = 10

  /** The save day a post made at `now` should report: the one that has just
   *  closed, not the one currently running.
   *
   *  At 10:15 Berlin on the 11th, the current save day is the 11th — fifteen
   *  minutes old and worth nothing. The day worth reporting is the 10th, which
   *  ran from 10:00 on the 10th to 10:00 on the 11th and is keyed `save_day =
   *  10th` by [[com.tibiabot.persistence.ExperienceRepository.recordDaily]].
   *
   *  This is why the post belongs in the server-save window and nowhere else.
   *  Its figures come from the last highscore snapshot taken inside the closing
   *  day — around 09:40, since tibia.com rebuilds hourly on the :40 — so running
   *  it earlier reports a day that has not finished. */
  def reportedDay(now: ZonedDateTime): LocalDate =
    ServerSaveSchedule.lastServerSave(now).toLocalDate.minusDays(1)

  /** The instants a save day spans: its own server save until the next one.
   *
   *  Resolved through Berlin at both ends rather than as "start plus 24 hours",
   *  because two days a year are 23 or 25 hours long and a fixed offset would
   *  put an hour of advances in the wrong day on each of them. */
  def window(saveDay: LocalDate): (Instant, Instant) = {
    val from = saveDay.atTime(ServerSaveSchedule.serverSaveTime).atZone(Clock.Berlin)
    val to = saveDay.plusDays(1).atTime(ServerSaveSchedule.serverSaveTime).atZone(Clock.Berlin)
    (from.toInstant, to.toInstant)
  }

  /** The day's gains, largest first, trimmed to what the post names.
   *
   *  A zero or negative delta is not a gain and is dropped even when it survives
   *  the query's ordering — on a very quiet world the tenth-placed mover can be
   *  somebody who simply died, and listing them under "top experience gained"
   *  would be wrong. That leaves the list short rather than padded, which is
   *  the honest shape. */
  def gains(movers: List[ExperienceDelta], limit: Int = TopGains): List[ExperienceDelta] =
    movers.filter(_.gained > 0).sortBy(-_.gained).take(limit)

  /** How many losers the post names. Half the gainers: a day's losses are one
   *  story — who died badly — where the gains are a leaderboard. */
  val TopLosses: Int = 5

  /** The day's worst losses, worst first, trimmed to what the post names.
   *
   *  Takes the whole mover list rather than trusting a caller to have asked for
   *  the right end of it, so the rule that a loss must actually be negative is
   *  stated once. */
  def losses(movers: List[ExperienceDelta], limit: Int = TopLosses): List[ExperienceDelta] =
    movers.filter(_.gained < 0).sortBy(_.gained).take(limit)
}
