package com.tibiabot.statistics

import com.tibiabot.domain.time.Clock
import com.tibiabot.domain.{ExperienceDelta, FragTally}
import com.tibiabot.persistence.{ExperienceRepository, FragRepository, HighscoreRepository, KillStatisticsRepository, WorldOnlineRepository}
import com.tibiabot.scheduler.ServerSaveSchedule
import com.typesafe.scalalogging.StrictLogging

import java.time.{Duration, LocalDate, ZonedDateTime}
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
    posted: String,
    /** Every character this discord hunts on this world, lowercased.
     *
     *  Read here rather than in the repository because it is the same list the
     *  online list and the deaths channel already hold in memory, and because
     *  "Most Exp Lost" is the only query that needs it — pushing it down would
     *  mean the cache reading a guild's own database. */
    huntedNames: Set[String] = Set.empty,
    /** That world's stored `statistics_kills_posted` — the last save day this
     *  channel carried creature figures for. */
    killsPosted: String = ""
) {

  /** Whether this channel still owes the board for `day`. String comparison
   *  against the stored ISO date: a channel that has never posted holds "",
   *  which matches nothing. */
  def owes(day: LocalDate): Boolean = posted != day.toString

  /** Whether this channel still owes the creature figures for `day`. */
  def owesKills(day: LocalDate): Boolean = killsPosted != day.toString
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
 *  ==Two halves, released by different things==
 *  The board and the war are ready the moment server save lands: every figure in
 *  them was written by the highscore sweep inside the closing day. The creature
 *  figures and the boss predictions are not — both read the day's kill
 *  statistics snapshot, which cannot be taken until tibia.com rolls its own
 *  figures, usually a minute or two after server save and occasionally half an
 *  hour later when the site is in maintenance.
 *
 *  So the post goes out in two, and only when it has to. The board and the war
 *  are sent as soon as the window opens. If the snapshot is already filed by
 *  then — a restart later in the morning, a fast roll — the creature figures and
 *  the bosses ride the same message and the day is one post, exactly as before.
 *  If it is not, they follow in a second message the minute it lands. Nobody
 *  waits on tibia.com to read who gained the most experience.
 *
 *  The wait is expressed as the summary row appearing rather than as a schedule,
 *  because only the primary takes the snapshot while every bot posts: a
 *  secondary has no way to observe that work except by seeing the row. One gate
 *  serves both.
 *
 *  It is bounded by `waitForKills`, after which the second half is written off
 *  for the day rather than retried forever. The deadline sits inside the
 *  server-save window on purpose, with several ticks to spare: a target still
 *  waiting when the window shuts would never be released at all, because
 *  tomorrow's tick owes tomorrow's day.
 *
 *  @param announce     hands a finished report to the caller to render and send;
 *                      the embed is built outside so this stays free of JDA. The
 *                      report's `kills` says whether the creature figures and
 *                      the bosses are riding this message or following in the
 *                      next one
 *  @param announceKills sends the second message — the creature figures and the
 *                      bosses — for a day whose board has already gone out
 *  @param recordPosted stores the day this target has now been served. A callback
 *                      rather than the world-config repository itself, because the
 *                      stored row has a cached twin in memory that `targets` reads
 *                      back on the very next tick — writing only one of the two
 *                      would repost the same day for the rest of the window
 *  @param waitForKills how long past server save to wait for the day's kill
 *                      statistics snapshot before posting without it
 *  @param now          injected so the window logic is testable without waiting
 *                      for ten in the morning
 */
final class StatisticsService(
    experience: ExperienceRepository,
    highscores: HighscoreRepository,
    killStatistics: KillStatisticsRepository,
    frags: FragRepository,
    worldOnline: WorldOnlineRepository,
    targets: () => List[StatisticsTarget],
    announce: (StatisticsTarget, DailyReport, FragTally, List[ExperienceDelta]) => Unit,
    announceKills: (StatisticsTarget, DailyReport) => Unit,
    recordPosted: (StatisticsTarget, LocalDate) => Unit,
    recordKillsPosted: (StatisticsTarget, LocalDate) => Unit,
    waitForKills: Duration = StatisticsService.WaitForKills,
    now: () => ZonedDateTime = () => ZonedDateTime.now(Clock.Berlin)
) extends StrictLogging {

  /** One pass. Cheap and safe to call on the ordinary minute tick: outside the
   *  server-save window it does nothing at all, and inside it every target whose
   *  two halves have both been served is answered by its stored dates without a
   *  query. */
  def tick(): Unit = {
    val currentTime = now()
    if (ServerSaveSchedule.isServerSaveWindow(currentTime.withZoneSameInstant(Clock.Berlin).toLocalTime)) {
      val day = DailyStatistics.reportedDay(currentTime)
      val all = targets()
      val boards = all.filter(_.owes(day))
      // Only targets whose board is already out. A target served in this very
      // tick is deliberately left for the next one: the board is sent by
      // clearing the channel and reposting, and a second message racing that
      // purge would be swept away by it.
      val creatures = all.filterNot(_.owes(day)).filter(_.owesKills(day))
      if (boards.nonEmpty || creatures.nonEmpty) postAll(boards, creatures, day, currentTime)
    }
  }

  private def postAll(boards: List[StatisticsTarget], creatures: List[StatisticsTarget],
                      day: LocalDate, at: ZonedDateTime): Unit = {
    val waited = waitedLongEnough(at)
    val worlds = (boards ++ creatures).map(_.world).distinct
    val filed = worlds.map(world => world -> killsFor(world, day)).toMap

    // Built per world rather than per target, because none of it is
    // guild-scoped — the frag tally is the one thing that cannot be shared this
    // way, and it is read per target below.
    //
    // A world only waiting on its creature figures does not get a report built
    // until they are actually there. That is six queries, and a morning
    // tibia.com spends in maintenance is forty ticks long.
    val wanted = worlds.filter(world => boards.exists(_.world == world) || filed(world).isDefined)
    val reports = wanted.map(world => world -> report(world, day, filed(world))).toMap

    boards.foreach { target =>
      reports.get(target.world).flatten.foreach(post(target, _))
    }
    creatures.foreach { target =>
      (filed(target.world), reports.get(target.world).flatten) match {
        case (Some(_), Some(report)) => postKills(target, report)
        case _ if waited =>
          // tibia.com never rolled inside the window, or the cache cannot be
          // read. The board is already out; this stops the rest of the window
          // asking for the other half.
          logger.info(s"Statistics: no kill statistics for '${target.world}' on $day by the deadline, " +
            "giving up on the creature figures for the day")
          markKillsPosted(target, day)
        case _ =>
          logger.debug(s"Statistics: waiting on the kill statistics snapshot for '${target.world}' on $day")
      }
    }
  }

  /** The second message: the creature figures and the bosses, for a day whose
   *  board went out without them.
   *
   *  The mark is written whether or not the send succeeded, for the same reason
   *  the board's is: a channel the bot has lost access to would otherwise be
   *  retried for the rest of the window and again every morning. */
  private def postKills(target: StatisticsTarget, report: DailyReport): Unit = {
    try announceKills(target, report)
    catch {
      case NonFatal(error) =>
        logger.warn(s"Statistics: could not post the creature figures for '${target.world}' " +
          s"to ${target.guildLabel}: ${error.getMessage}")
    }
    markKillsPosted(target, report.saveDay)
  }

  /** Whether to stop waiting for the day's kill statistics and post without
   *  them. Measured from server save rather than from the first tick, so a bot
   *  that started late inside the window does not get its own fresh deadline. */
  private def waitedLongEnough(at: ZonedDateTime): Boolean =
    Duration.between(ServerSaveSchedule.lastServerSave(at), at).compareTo(waitForKills) >= 0

  /** The day's kill figures, if they have been filed.
   *
   *  A failure reads the same as "not yet", which is the right way round: the
   *  creature figures wait and are asked for again rather than the board going
   *  out saying a world killed nothing, and the deadline stops that waiting from
   *  outlasting the window. */
  private def killsFor(world: String, day: LocalDate): Option[DayKillSummary] =
    try killStatistics.summary(world, day)
    catch {
      case NonFatal(error) =>
        logger.warn(s"Statistics: could not read the kill statistics for '$world' on $day: ${error.getMessage}")
        None
    }

  /** One guild's frags for the day, or an empty tally if the query failed.
   *
   *  A failure here does not hold the post back. The world figures are the bulk
   *  of it and are already in hand; losing the frag section is worth far less
   *  than losing the day. */
  private def tally(target: StatisticsTarget, day: LocalDate): FragTally =
    try frags.tally(target.guildId, target.world, day, FragTally.TopFraggers, FragTally.TopRepeats)
    catch {
      case NonFatal(error) =>
        logger.warn(s"Statistics: could not read frags for '${target.world}' " +
          s"in ${target.guildLabel}: ${error.getMessage}")
        FragTally.empty
    }

  /** The hunted characters who lost the most experience that day.
   *
   *  Usually returns very little: the experience table is the world's top
   *  thousand, and most tracked enemies are not in it. That is the honest answer
   *  rather than a fault, and the section is simply absent when it is empty. */
  private def enemyLosses(target: StatisticsTarget, day: LocalDate): List[ExperienceDelta] =
    if (target.huntedNames.isEmpty) Nil
    else try experience.lossesAmong(target.world, day, target.huntedNames, FragTally.TopRepeats)
    catch {
      case NonFatal(error) =>
        logger.warn(s"Statistics: could not read enemy experience for '${target.world}' " +
          s"in ${target.guildLabel}: ${error.getMessage}")
        Nil
    }

  /** Build one world's day, or None if the queries failed.
   *
   *  A failure here leaves `statistics_posted` alone, so the next tick inside
   *  the window tries again — which is what a transient database problem
   *  deserves. An *empty* report is not a failure and is handled by [[post]]. */
  private def report(world: String, day: LocalDate, kills: Option[DayKillSummary]): Option[DailyReport] =
    try {
      val (from, to) = DailyStatistics.window(day)
      // One row deeper than the post shows: the query orders by the delta, and
      // a mover who lost experience can still surface inside the top ten on a
      // quiet world. DailyStatistics.gains drops them, and asking for the extra
      // rows means dropping one does not silently shorten the list.
      val movers = experience.dailyMovers(world, day, DailyStatistics.TopGains * 2)
      // One query for every boss on the world rather than seventy-four. `from`
      // is the whole retained history: a world boss counts in months, so
      // narrowing this to recent days would hide exactly the bosses worth
      // predicting.
      //
      // This now includes the closing day itself, because the post waits for
      // that snapshot to be filed — so a boss killed yesterday reads as seen
      // yesterday rather than as never seen at all.
      val average = worldOnline.averages(world, day)
      val sightings = killStatistics.sightings(world, killStatistics.earliestDay(world).getOrElse(day))
      val predictions = BossPredictor.predictAll(sightings, day)
      // One query for both halves of the creature embed, and only where there is
      // an embed to fill: the day's rows are ordered by kills, so the creatures
      // are the head of the list and the specials are picked out of it by name.
      val killed = if (kills.isDefined) killStatistics.killsOn(world, day) else Nil
      Some(DailyReport(
        world = world,
        saveDay = day,
        gains = DailyStatistics.gains(movers),
        losses = experience.dailyLosses(world, day, DailyStatistics.TopLosses),
        advance = highscores.topAdvance(world, from, to),
        // Read by the caller, because whether it is there is what decided this
        // report would be built at all. Absent only past the deadline, where a
        // day with experience figures and no kill figures is worth more than
        // silence.
        kills = kills,
        topKills = killed.filter(row => SpecialKills.forRace(row.race).isEmpty).take(KillStatistics.TopKills),
        specialKills = SpecialKills.all.flatMap(kill =>
          killed.find(_.race.equalsIgnoreCase(kill.race)).map(row => kill -> row.killed)),
        predictions = predictions,
        awaitingSighting = BossPredictor.awaitingFirstSighting(sightings),
        // Absent on a world nothing ever sampled, which the bar answers with a
        // default scale rather than by going missing. Read here rather than in
        // the announce so it is fetched once per world like everything else in
        // the report, not once per discord watching it.
        averageOnline = average.map(_.online),
        averageLevel = average.map(_.level)
      ))
    } catch {
      case NonFatal(error) =>
        logger.warn(s"Statistics: could not build the report for '$world' on $day: ${error.getMessage}")
        None
    }

  /** Post the board to one channel and record that the day is done.
   *
   *  An empty report is marked as posted without anything being sent. The usual
   *  cause is a world on its first day of history, which has no previous rollup
   *  to measure a gain against. It marks only the board: the creature figures
   *  are a separate half and a world with nothing in the highscores can still
   *  have killed three million creatures.
   *
   *  Where the snapshot was already filed, the creature figures and the bosses
   *  are in this report and go out on this message, so the day is done in one
   *  and the second half is marked with it.
   *
   *  The mark is written whether or not the send succeeded. A channel the bot
   *  has lost access to would otherwise be retried for the rest of the window
   *  and again every morning, and the day it missed is not recoverable anyway. */
  private def post(target: StatisticsTarget, report: DailyReport): Unit = {
    try {
      // Frags alone are worth a post: a server whose world had a quiet day in the
      // highscores can still have had a war in it.
      val frags = tally(target, report.saveDay)
      val losses = enemyLosses(target, report.saveDay)
      if (report.nonEmpty || frags.nonEmpty) announce(target, report, frags, losses)
      else logger.debug(s"Statistics: nothing to report for '${target.world}' on ${report.saveDay}")
    } catch {
      case NonFatal(error) =>
        logger.warn(s"Statistics: could not post '${target.world}' to ${target.guildLabel}: ${error.getMessage}")
    }
    markPosted(target, report.saveDay)
    if (report.kills.isDefined) markKillsPosted(target, report.saveDay)
  }

  private def markPosted(target: StatisticsTarget, day: LocalDate): Unit =
    try recordPosted(target, day)
    catch {
      // Costs a repeat post rather than a wrong one, and only if the write keeps
      // failing — so it is worth a line in the log and nothing more.
      case NonFatal(error) =>
        logger.warn(s"Statistics: could not record the post for '${target.world}' in ${target.guildLabel}: ${error.getMessage}")
    }

  private def markKillsPosted(target: StatisticsTarget, day: LocalDate): Unit =
    try recordKillsPosted(target, day)
    catch {
      case NonFatal(error) =>
        logger.warn(s"Statistics: could not record the creature figures for '${target.world}' " +
          s"in ${target.guildLabel}: ${error.getMessage}")
    }
}

object StatisticsService {

  /** How long past server save to wait for the day's kill statistics before
   *  posting without them.
   *
   *  Forty minutes, which is not a guess about tibia.com so much as a position
   *  inside the server-save window: the window shuts at 10:45 and the tick runs
   *  every minute, so a deadline here leaves several attempts in hand. A target
   *  still deferred when the window shuts would not be posted at all — tomorrow
   *  it owes tomorrow's day — so the deadline has to clear the edge by more than
   *  one tick.
   *
   *  On an ordinary morning nothing waits anywhere near this long: the roll is
   *  recognised within a minute of server save. This is for the mornings
   *  tibia.com spends in maintenance. */
  val WaitForKills: Duration = Duration.ofMinutes(40)
}
