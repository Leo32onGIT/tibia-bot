package com.tibiabot.statistics

import com.tibiabot.domain.time.Clock
import com.tibiabot.domain.FragTally
import com.tibiabot.persistence.{ExperienceRepository, FragRepository, HighscoreRepository, KillStatisticsRepository}
import com.tibiabot.scheduler.ServerSaveSchedule
import com.typesafe.scalalogging.StrictLogging

import java.time.{LocalDate, ZonedDateTime}
import scala.util.control.NonFatal

/** One discord's statistics channel for one world.
 *
 *  `posted` is that world's stored `statistics_posted` — the last save day this
 *  channel carried — so the decision to post is made from the row the caller
 *  already read rather than from a second lookup. */
final case class StatisticsTarget(
    guildId: String,
    guildLabel: String,
    world: String,
    channelId: String,
    posted: String
) {

  /** Whether this channel still owes a post for `day`. String comparison against
   *  the stored ISO date: a channel that has never posted holds "", which
   *  matches nothing. */
  def owes(day: LocalDate): Boolean = posted != day.toString
}

/** Posts the daily statistics embed once per world per server-save day.
 *
 *  Unlike the highscore sweep this fetches nothing — every figure it reports is
 *  already in `bot_cache`, put there by the primary's hourly sweep. So there is
 *  no primary/secondary split to make here: each bot reads the shared tables and
 *  posts to the guilds it is actually in, which is the half of the highscore
 *  design that had to be per-bot anyway.
 *
 *  A world's report is built once per tick and shared across every guild
 *  tracking it, because none of it is guild-scoped. Fifty discords watching
 *  Antica is one pair of queries, not fifty.
 *
 *  @param announce     hands a finished report to the caller to render and send;
 *                      the embed is built outside so this stays free of JDA
 *  @param recordPosted stores the day this target has now been served. A callback
 *                      rather than the world-config repository itself, because the
 *                      stored row has a cached twin in memory that `targets` reads
 *                      back on the very next tick — writing only one of the two
 *                      would repost the same day for the rest of the window
 *  @param now          injected so the window logic is testable without waiting
 *                      for ten in the morning
 */
final class StatisticsService(
    experience: ExperienceRepository,
    highscores: HighscoreRepository,
    killStatistics: KillStatisticsRepository,
    frags: FragRepository,
    targets: () => List[StatisticsTarget],
    announce: (StatisticsTarget, DailyReport, FragTally) => Unit,
    recordPosted: (StatisticsTarget, LocalDate) => Unit,
    now: () => ZonedDateTime = () => ZonedDateTime.now(Clock.Berlin)
) extends StrictLogging {

  /** One pass. Cheap and safe to call on the ordinary 30-second tick: outside
   *  the server-save window it does nothing at all, and inside it every target
   *  that has already been served is answered by its stored date without a
   *  query. */
  def tick(): Unit = {
    val currentTime = now()
    if (ServerSaveSchedule.isServerSaveWindow(currentTime.withZoneSameInstant(Clock.Berlin).toLocalTime)) {
      val day = DailyStatistics.reportedDay(currentTime)
      val owed = targets().filter(_.owes(day))
      if (owed.nonEmpty) postAll(owed, day)
    }
  }

  private def postAll(owed: List[StatisticsTarget], day: LocalDate): Unit = {
    // Built per world rather than per target, and only for the worlds something
    // is actually waiting on. The frag tally cannot be shared this way — it is
    // read from the guild's own lists — so it is fetched per target below.
    val reports = owed.map(_.world).distinct.map(world => world -> report(world, day)).toMap
    owed.foreach { target =>
      reports.get(target.world).flatten.foreach(post(target, _))
    }
  }

  /** One guild's frags for the day, or an empty tally if the query failed.
   *
   *  A failure here does not hold the post back. The world figures are the bulk
   *  of it and are already in hand; losing the frag section is worth far less
   *  than losing the day. */
  private def tally(target: StatisticsTarget, day: LocalDate): FragTally =
    try frags.tally(target.guildId, target.world, day, FragTally.TopFraggers)
    catch {
      case NonFatal(error) =>
        logger.warn(s"Statistics: could not read frags for '${target.world}' " +
          s"in ${target.guildLabel}: ${error.getMessage}")
        FragTally.empty
    }

  /** Build one world's day, or None if the queries failed.
   *
   *  A failure here leaves `statistics_posted` alone, so the next tick inside
   *  the window tries again — which is what a transient database problem
   *  deserves. An *empty* report is not a failure and is handled by [[post]]. */
  private def report(world: String, day: LocalDate): Option[DailyReport] =
    try {
      val (from, to) = DailyStatistics.window(day)
      // One row deeper than the post shows: the query orders by the delta, and
      // a mover who lost experience can still surface inside the top ten on a
      // quiet world. DailyStatistics.gains drops them, and asking for the extra
      // rows means dropping one does not silently shorten the list.
      val movers = experience.dailyMovers(world, day, DailyStatistics.TopGains * 2)
      Some(DailyReport(
        world = world,
        saveDay = day,
        gains = DailyStatistics.gains(movers),
        loss = experience.dailyLoss(world, day),
        advance = highscores.topAdvance(world, from, to),
        // Absent where the snapshot was never taken. The post is not held back
        // for it: the two halves come from different sources, and a day with
        // experience figures and no kill figures is worth more than silence.
        kills = killStatistics.summary(world, day)
      ))
    } catch {
      case NonFatal(error) =>
        logger.warn(s"Statistics: could not build the report for '$world' on $day: ${error.getMessage}")
        None
    }

  /** Post a report to one channel and record that the day is done.
   *
   *  An empty report is marked as posted without anything being sent. Nothing
   *  later in the window can change it — the last snapshot inside the closing
   *  day was taken before the window opened — so retrying would be ninety more
   *  queries for the same silence. The usual cause is a world on its first day
   *  of history, which has no previous rollup to measure a gain against.
   *
   *  The mark is written whether or not the send succeeded. A channel the bot
   *  has lost access to would otherwise be retried for the rest of the window
   *  and again every morning, and the day it missed is not recoverable anyway. */
  private def post(target: StatisticsTarget, report: DailyReport): Unit = {
    try {
      // Frags alone are worth a post: a server whose world had a quiet day in the
      // highscores can still have had a war in it.
      val frags = tally(target, report.saveDay)
      if (report.nonEmpty || frags.nonEmpty) announce(target, report, frags)
      else logger.debug(s"Statistics: nothing to report for '${target.world}' on ${report.saveDay}")
    } catch {
      case NonFatal(error) =>
        logger.warn(s"Statistics: could not post '${target.world}' to ${target.guildLabel}: ${error.getMessage}")
    }
    markPosted(target, report.saveDay)
  }

  private def markPosted(target: StatisticsTarget, day: LocalDate): Unit =
    try recordPosted(target, day)
    catch {
      // Costs a repeat post rather than a wrong one, and only if the write keeps
      // failing — so it is worth a line in the log and nothing more.
      case NonFatal(error) =>
        logger.warn(s"Statistics: could not record the post for '${target.world}' in ${target.guildLabel}: ${error.getMessage}")
    }
}
