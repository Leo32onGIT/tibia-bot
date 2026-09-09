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
    loss: Option[ExperienceDelta],
    advance: Option[HighscoreEvent]
) {

  /** Nothing to say. The ordinary cause is a cold start rather than a quiet day:
   *  a gain needs two consecutive rollups, so a world's very first reportable
   *  day is the second one it was swept. A world where genuinely nobody in the
   *  top thousand moved and nobody advanced a skill is possible in principle and
   *  reads the same way — silence, which is the honest answer either way. */
  def isEmpty: Boolean = gains.isEmpty && loss.isEmpty && advance.isEmpty

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

  /** The day's single worst loss, or None if nobody ended it down.
   *
   *  Takes the whole mover list rather than trusting a caller to have asked for
   *  the right end of it, so the rule that a loss must actually be negative is
   *  stated once. */
  def loss(movers: List[ExperienceDelta]): Option[ExperienceDelta] =
    movers.filter(_.gained < 0).sortBy(_.gained).headOption
}
