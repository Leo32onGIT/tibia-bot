package com.tibiabot.statistics

import com.tibiabot.domain.time.Clock
import com.tibiabot.persistence.KillStatisticsRepository
import com.tibiabot.scheduler.ServerSaveSchedule
import com.tibiabot.tibiadata.KillStatisticsApi
import com.typesafe.scalalogging.StrictLogging

import java.time.{Duration, LocalDate, ZonedDateTime}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Takes one kill statistics snapshot per world per server-save day and files
 *  the catalogued bosses plus a summary row.
 *
 *  Posts nothing. It exists so that a spawn prediction has a history to read
 *  when it is built — and every day this runs is a day banked, which is why it
 *  ships well ahead of anything that reads it.
 *
 *  Fleet-wide work, so the primary alone does it: two bots reading the same 68
 *  pages through the same TibiaData instance would be pure duplication, and the
 *  rows land in the shared cache either way.
 *
 *  ==When a day can be read==
 *  `last_day` is the save day that has just closed — tibia.com rolls the figures
 *  at server save — so [[DailyStatistics.reportedDay]] names what the endpoint is
 *  currently showing at any hour. The one hazard is the roll itself: a fetch a
 *  minute after 10:00 may still see the previous day's figures and would file
 *  them under the wrong date, which is the kind of error that is invisible now
 *  and wrong forever. Hence the `settle` delay — nothing is read until well
 *  clear of the boundary. There is no matching deadline, so a bot that was down
 *  all morning still catches the day when it comes back.
 *
 *  @param gap   paced between worlds. 68 requests once a day is nothing beside
 *               the highscore sweep's ninety thousand, but they are all to one
 *               host and there is no reason to burst them
 *  @param settle how long after server save to wait before believing the figures
 */
final class KillStatisticsService(
    api: KillStatisticsApi,
    repository: KillStatisticsRepository,
    trackedWorlds: () => List[String],
    gap: () => FiniteDuration,
    delay: FiniteDuration => Future[Unit],
    settle: Duration = KillStatisticsService.Settle,
    now: () => ZonedDateTime = () => ZonedDateTime.now(Clock.Berlin)
)(implicit ec: ExecutionContext) extends StrictLogging {

  private val running = new AtomicBoolean(false)

  /** Worlds this process has already filed, per day. Saves a query per world per
   *  tick once the morning's work is done — which is almost all of the time,
   *  since the tick runs all day and the work happens once. The database is
   *  still the real guard, for a restart. */
  private val filed = TrieMap.empty[(String, LocalDate), Unit]

  /** The day the endpoint is currently reporting, or None while it is too soon
   *  after server save to trust the figures. */
  def dayToFetch(at: ZonedDateTime): Option[LocalDate] = {
    val save = ServerSaveSchedule.lastServerSave(at)
    if (Duration.between(save, at).compareTo(settle) >= 0) Some(save.toLocalDate.minusDays(1))
    else None
  }

  /** One pass. Safe on a frequent schedule: once the day is filed this costs a
   *  clock comparison and a map lookup per world, and nothing else. */
  def tick(): Future[Unit] = dayToFetch(now()) match {
    case None => Future.unit
    case Some(day) =>
      val wanted = trackedWorlds().distinct.sorted.filterNot(world => filed.contains((world, day)))
      if (wanted.isEmpty) Future.unit
      else if (!running.compareAndSet(false, true)) {
        logger.debug("Kill statistics: previous sweep still running, skipping this tick")
        Future.unit
      } else
        sweep(wanted, day)
          .recover { case error => logger.error("Kill statistics: sweep failed", error) }
          .map(_ => running.set(false))
  }

  private def sweep(worlds: List[String], day: LocalDate): Future[Unit] = {
    // The database check is per world and only for worlds this process has not
    // already done, so a restart pays it once rather than every tick.
    val outstanding = worlds.filterNot { world =>
      val done = try repository.hasDay(world, day) catch {
        case NonFatal(error) =>
          logger.warn(s"Kill statistics: could not tell whether '$world' was filed for $day: ${error.getMessage}")
          false
      }
      if (done) filed.put((world, day), ())
      done
    }

    if (outstanding.isEmpty) Future.unit
    else {
      logger.info(s"Kill statistics: reading ${outstanding.size} world(s) for $day")
      outstanding.foldLeft(Future.successful(0)) { case (acc, world) =>
        acc.flatMap { stored =>
          delay(gap()).flatMap(_ => fetchAndStore(world, day)).map(ok => if (ok) stored + 1 else stored)
        }
      }.map(stored => logger.info(s"Kill statistics: filed $stored of ${outstanding.size} world(s) for $day"))
    }
  }

  /** Read one world and file it. False on anything that went wrong, which leaves
   *  the world unmarked so a later tick tries again — there is all day to. */
  private def fetchAndStore(world: String, day: LocalDate): Future[Boolean] =
    api.getKillStatistics(world).map {
      case Left(_) => false // already logged by the client
      case Right(response) =>
        val data = response.killstatistics
        if (!KillStatistics.isPlausible(data)) {
          // A whole world killing nothing in a day is not a quiet day, it is a
          // bad read — and seventy-four zeroes filed as fact would later read as
          // "no boss spawned", which is exactly the thing this history is for.
          logger.warn(s"Kill statistics: '$world' reported no kills at all for $day, not filing it")
          false
        } else {
          // Bosses first, summary last: `hasDay` reads the summary, so a failure
          // between the two leaves the day looking unfiled and it is simply read
          // again. The other order would mark a day done with its rows missing.
          repository.recordBossKills(KillStatistics.bossKills(data, day))
          repository.recordSummary(KillStatistics.summary(data, day))
          filed.put((world, day), ())
          true
        }
    }.recover {
      case NonFatal(error) =>
        logger.warn(s"Kill statistics: reading '$world' for $day failed: ${error.getMessage}")
        false
    }
}

object KillStatisticsService {

  /** How long after server save the figures are believed.
   *
   *  An hour. tibia.com's roll is not instant and TibiaData caches on top of it,
   *  so a read at 10:01 can still be showing the previous day — which would file
   *  it under today's date and put a permanent off-by-one in the history. There
   *  is no cost to waiting: the same figures are there all day. */
  val Settle: Duration = Duration.ofHours(1)
}
