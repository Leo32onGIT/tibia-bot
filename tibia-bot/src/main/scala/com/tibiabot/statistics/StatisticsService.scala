package com.tibiabot.statistics

import com.tibiabot.domain.time.Clock
import com.tibiabot.domain.{ExperienceDelta, FragTally}
import com.tibiabot.persistence.{ExperienceRepository, FragRepository, HighscoreRepository, KillStatisticsRepository, WorldOnlineRepository}
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
    posted: String,
    /** Every character this discord hunts on this world, lowercased.
     *
     *  Read here rather than in the repository because it is the same list the
     *  online list and the deaths channel already hold in memory, and because
     *  "Most Exp Lost" is the only query that needs it — pushing it down would
     *  mean the cache reading a guild's own database. */
    huntedNames: Set[String] = Set.empty
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
 *  ==One message, and why nothing waits==
 *  Every figure the post carries is in hand before the window opens. The board
 *  and the war come from the highscore sweep inside the closing day; the
 *  creature figures and the boss predictions come from the kill statistics
 *  snapshot, which tibia.com publishes in a nightly batch at around 03:10 Berlin
 *  and [[KillStatisticsService]] files from 04:00 — six hours of slack before
 *  anything here reads it.
 *
 *  This briefly posted in two messages, on the belief that tibia.com rolled its
 *  kill statistics at server save and the snapshot would land mid-window. It
 *  does not, and the staging was solving a problem that only existed in the
 *  assumption. What remains of it is the shape of the embeds — the creature
 *  figures are their own embed rather than a section of the board — which is
 *  worth keeping on its own merits.
 *
 *  A day whose snapshot genuinely is missing — tibia.com down through the whole
 *  night and morning — posts without those two embeds rather than waiting or
 *  skipping the day. The board is most of the value and it is never at risk.
 *
 *  @param announce     hands a finished report to the caller to render and send;
 *                      the embed is built outside so this stays free of JDA. The
 *                      report's `kills` says whether the creature figures and
 *                      the bosses are in it at all
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
    worldOnline: WorldOnlineRepository,
    targets: () => List[StatisticsTarget],
    announce: (StatisticsTarget, DailyReport, FragTally, List[ExperienceDelta]) => Unit,
    recordPosted: (StatisticsTarget, LocalDate) => Unit,
    now: () => ZonedDateTime = () => ZonedDateTime.now(Clock.Berlin)
) extends StrictLogging {

  /** One pass. Cheap and safe to call often: outside the server-save window it
   *  does nothing at all, and inside it every target that has already been
   *  served is answered by its stored date without a query.
   *
   *  How often it is called is how close to ten the post lands, since the
   *  schedule is anchored to the bot's boot rather than to the clock — so the
   *  tick interval is a product decision more than a cost one. */
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
    val reports = owed.map(_.world).distinct
      .map(world => world -> report(world, day, killsFor(world, day))).toMap
    owed.foreach { target =>
      reports.get(target.world).flatten.foreach(post(target, _))
    }
  }

  /** The day's kill figures, if they have been filed.
   *
   *  Normally they were, six hours earlier. A failure or a genuine gap reads the
   *  same way — the creature and boss embeds are left off this post rather than
   *  holding the board back, which is the same rule every other absent section
   *  follows. */
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
        // Everything kept by name comes out: the catalogued bosses, the Dream
        // Courts five and the specials are all in the day's rows for their own
        // reasons and none of them is a creature the world was hunting.
        topKills = killed.filterNot(row => KillStatistics.keptByName.contains(row.race.toLowerCase))
          .take(KillStatistics.TopKills),
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

  /** Post the day to one channel and record that it is done.
   *
   *  An empty report is marked as posted without anything being sent. Nothing
   *  later in the window can change it — every figure it would carry was settled
   *  before the window opened — so retrying would be forty more queries for the
   *  same silence. The usual cause is a world on its first day of history, which
   *  has no previous rollup to measure a gain against.
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
