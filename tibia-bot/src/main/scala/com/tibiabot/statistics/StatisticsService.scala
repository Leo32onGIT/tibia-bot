package com.tibiabot.statistics

import com.tibiabot.domain.time.Clock
import com.tibiabot.domain.{ExperienceDelta, FragTally}
import com.tibiabot.persistence.{ExperienceRepository, FragRepository, HighscoreRepository, KillStatisticsRepository, WorldOnlineRepository}
import com.tibiabot.scheduler.ServerSaveSchedule
import com.typesafe.scalalogging.StrictLogging

import java.time.{Instant, LocalDate, ZonedDateTime}
import scala.collection.concurrent.TrieMap
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
    /** The messages this channel's last post went out as — see
     *  [[StatisticsMessages]]. */
    messages: StatisticsMessages = StatisticsMessages.empty
) {

  /** Whether this channel still owes a post for `day`. String comparison against
   *  the stored ISO date: a channel that has never posted holds "", which
   *  matches nothing. */
  def owes(day: LocalDate): Boolean = posted != day.toString
}

/** The messages a channel's post went out as, and the save day it was for.
 *
 *  Stored in the guild's `worlds.statistics_messages` as `day:id,id`, so the
 *  next day's post deletes exactly these and the day's one update edits them:
 *  nothing is found by reading the channel. Empty for a post made before the
 *  ids were kept (26 Sep 2026), which the next post clears by reading the
 *  channel's history once. */
final case class StatisticsMessages(day: String, ids: List[String]) {

  def encode: String = if (ids.isEmpty) "" else s"$day:${ids.mkString(",")}"

  /** Whether these are the post for `saveDay`, and so the ones its update edits. */
  def isFor(saveDay: LocalDate): Boolean = ids.nonEmpty && day == saveDay.toString
}

object StatisticsMessages {

  val empty: StatisticsMessages = StatisticsMessages("", Nil)

  def decode(stored: String): StatisticsMessages =
    Option(stored).map(_.trim).filter(_.nonEmpty).map(_.split(":", 2)) match {
      case Some(Array(day, ids)) => StatisticsMessages(day, ids.split(",").map(_.trim).filter(_.nonEmpty).toList)
      case _                     => empty
    }
}

/** Posts the daily statistics once per world per server-save day, and brings
 *  that post up to date once, when the world's closing reading is in.
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
 *  @param update       hands the same day, rebuilt, to the caller to edit into the
 *                      post it already made, once its closing reading is in
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
    update: (StatisticsTarget, DailyReport, FragTally, List[ExperienceDelta]) => Unit = (_, _, _, _) => (),
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
    updateAll(currentTime)
  }

  // The day's one update, per guild and world, and what it waits on.
  private val updated = TrieMap.empty[(String, String, LocalDate), Unit]
  private val closingSeen = TrieMap.empty[(String, LocalDate), Instant]
  private val lastAsked = TrieMap.empty[String, Instant]

  /** Bring each post made today up to the world's closing reading, once.
   *
   *  The post goes out at server save with the last reading taken before it,
   *  about 09:40, and for worlds late in the sweep's alphabet the 08:40 one. It
   *  can't wait for better: experience reaches the highscores only when a
   *  character logs out, and server save is what logs everyone out, so the
   *  first reading to hold the whole day is the one after it — tibia.com's
   *  10:40, which the sweep files world by world until about 11:30 (see
   *  [[DailyStatistics.ClosingReading]]). When a world's is in, its day is
   *  built again and handed to `update` for every post of it made today, and
   *  each is edited in place, silently.
   *
   *  Once per post per day, held in memory: a restart before the cut-off
   *  updates again, which edits in the same figures. After [[UpdateUntil]] a
   *  world whose closing reading never came is left as posted, since any later
   *  reading already belongs to the next day.
   *
   *  A world is asked about at most once every [[AskEvery]]: the tick runs every
   *  fifteen seconds for the post's sake, and the question is only worth asking
   *  as often as the answer can change. */
  private def updateAll(currentTime: ZonedDateTime): Unit = {
    val at = currentTime.toInstant
    val save = ServerSaveSchedule.lastServerSave(currentTime).toInstant
    val day = DailyStatistics.reportedDay(currentTime)
    updated.keys.filter(_._3 != day).foreach(updated.remove)
    closingSeen.keys.filter(_._2 != day).foreach(closingSeen.remove)
    if (at.isBefore(save.plus(UpdateUntil))) {
      val waiting = targets().filter(t => !t.owes(day) && !updated.contains((t.guildId, t.world, day)))
      waiting.groupBy(_.world).foreach { case (world, due) =>
        if (closingReadingIn(world, day, save, at))
          report(world, day, killsFor(world, day)).foreach(built => due.foreach(target => updatePost(target, built, day)))
      }
    }
  }

  /** Whether `world`'s closing reading for `day` has been filed, and long
   *  enough ago that the rest of its lists are in too.
   *
   *  The experience list is one of a world's twelve, all read within a minute
   *  or so of each other; waiting [[Settle]] after first seeing it is what
   *  lets the update carry that reading's skill advances as well. */
  private def closingReadingIn(world: String, day: LocalDate, save: Instant, at: Instant): Boolean =
    closingSeen.get((world, day)) match {
      case Some(seen) => !at.isBefore(seen.plus(Settle))
      case None =>
        if (lastAsked.get(world).exists(asked => at.isBefore(asked.plus(AskEvery)))) false
        else {
          lastAsked.put(world, at)
          val filed =
            try experience.readingTimes(world, save, save.plus(DailyStatistics.ClosingReading)).nonEmpty
            catch {
              case NonFatal(error) =>
                logger.warn(s"Statistics: could not ask whether '$world' has its closing reading: ${error.getMessage}")
                false
            }
          if (filed) closingSeen.put((world, day), at)
          false
        }
    }

  /** One post's update: the day rebuilt with this guild's own frags, handed
   *  over the same way the post was. Marked done whether or not the edit
   *  works, for the reason [[post]] marks a day. */
  private def updatePost(target: StatisticsTarget, report: DailyReport, day: LocalDate): Unit = {
    try {
      val frags = tally(target, day)
      val losses = enemyLosses(target, day)
      if (report.nonEmpty || frags.nonEmpty) update(target, report, frags, losses)
    } catch {
      case NonFatal(error) =>
        logger.warn(s"Statistics: could not update '${target.world}' in ${target.guildLabel}: ${error.getMessage}")
    }
    updated.put((target.guildId, target.world, day), ())
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
      // One query for every boss on the world rather than seventy-four, over the
      // whole retained history: a world boss counts in months, so narrowing this
      // to recent days would hide exactly the bosses worth predicting.
      //
      // It includes the closing day itself, because the post waits for that
      // snapshot to be filed — so a boss killed yesterday reads as seen
      // yesterday rather than as never seen at all.
      val average = worldOnline.averages(world, day)
      val sightings = killStatistics.sightings(world)
      val predictions = BossPredictor.predictAll(sightings, day)
      // One query for both halves of the creature embed, and only where there is
      // an embed to fill: the day's rows are ordered by kills, so the creatures
      // are the head of the list and the specials are picked out of it by name.
      val killed = if (kills.isDefined) killStatistics.killsOn(world, day) else Nil
      Some(DailyReport(
        world = world,
        saveDay = day,
        gains = experience.dailyGains(world, day, DailyStatistics.TopGains),
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

  private val UpdateUntil = java.time.Duration.ofHours(2)
  private val Settle = java.time.Duration.ofMinutes(5)
  private val AskEvery = java.time.Duration.ofMinutes(1)

  private def markPosted(target: StatisticsTarget, day: LocalDate): Unit =
    try recordPosted(target, day)
    catch {
      // Costs a repeat post rather than a wrong one, and only if the write keeps
      // failing — so it is worth a line in the log and nothing more.
      case NonFatal(error) =>
        logger.warn(s"Statistics: could not record the post for '${target.world}' in ${target.guildLabel}: ${error.getMessage}")
    }

}
