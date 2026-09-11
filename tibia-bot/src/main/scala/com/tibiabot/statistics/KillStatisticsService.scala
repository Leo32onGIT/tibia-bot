package com.tibiabot.statistics

import com.tibiabot.domain.time.Clock
import com.tibiabot.persistence.KillStatisticsRepository
import com.tibiabot.scheduler.KillStatisticsSchedule
import com.tibiabot.tibiadata.KillStatisticsApi
import com.tibiabot.tibiadata.response.KillStatisticsData
import com.typesafe.scalalogging.StrictLogging

import java.time.{Duration, LocalDate, ZonedDateTime}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Takes one kill statistics snapshot per world per published day and files the
 *  catalogued bosses, the day's creatures and a summary row.
 *
 *  Posts nothing itself. The daily statistics post reads what it writes, and
 *  reads it in the same message as everything else — the batch below runs around
 *  four in the morning and the post goes out at ten, so there is six hours of
 *  slack between them and nothing has to wait on anything.
 *
 *  Fleet-wide work, so the primary alone does it: two bots reading the same 68
 *  pages through the same TibiaData instance would be pure duplication, and the
 *  rows land in the shared cache either way. The secondary sees this work only
 *  as rows appearing.
 *
 *  ==Knowing the day has rolled==
 *  tibia.com rebuilds its kill statistics in a nightly batch at around 03:10
 *  Berlin — not at server save, which is what this originally assumed — so the
 *  figures are in place hours before anything reads them. See
 *  [[com.tibiabot.scheduler.KillStatisticsSchedule]]
 *  for which day a publication describes, since it is not quite a save day.
 *
 *  The hazard is the roll itself: the figures carry no date, so a fetch taken a
 *  minute early looks exactly like one taken a minute late and would be filed
 *  under the wrong day, which is the kind of error that is invisible now and
 *  wrong forever.
 *
 *  Rather than hedge that with a timer, this asks the database. The previous
 *  day's summary is the figures the endpoint is *still showing* until it rolls,
 *  so a live read that differs from it has rolled and one that matches has not.
 *  That is evidence rather than a guess, which is why getting the batch time
 *  wrong for a while cost nothing: the times below decide only when this starts
 *  asking, never what it believes.
 *
 *  ==Probe, then sweep==
 *  The roll is one event across tibia.com, not sixty-eight of them, so
 *  discovering it costs one request rather than one per world: each tick probes
 *  a single world and only sweeps the rest once that world has turned over. A
 *  world with no previous day of its own — one tracked for the first time —
 *  inherits the answer, which is why a new world does not have to sit out its
 *  first day.
 *
 *  That inheritance assumes worlds roll together, and the assumption watches
 *  itself: every world that *does* have a previous day is compared against it
 *  during the sweep, so a world that lagged behind the probe is refused and
 *  logged rather than filed under the wrong date.
 *
 *  There is no deadline. A bot that was down all morning still catches the day
 *  when it comes back, because the comparison works just as well at two in the
 *  afternoon as at four in the morning.
 *
 *  @param gap    paced between worlds. 68 requests once a day is nothing beside
 *                the highscore sweep's ninety thousand, but they are all to one
 *                host and there is no reason to burst them
 *  @param settle the fallback for a database with no previous day anywhere in
 *                it, where there is nothing to recognise the roll against
 */
final class KillStatisticsService(
    api: KillStatisticsApi,
    repository: KillStatisticsRepository,
    trackedWorlds: () => List[String],
    gap: () => FiniteDuration,
    delay: FiniteDuration => Future[Unit],
    settle: Duration = KillStatisticsService.Settle,
    probeCandidates: Int = KillStatisticsService.ProbeCandidates,
    now: () => ZonedDateTime = () => ZonedDateTime.now(Clock.Berlin)
)(implicit ec: ExecutionContext) extends StrictLogging {

  import KillStatisticsService.Probe

  private val running = new AtomicBoolean(false)

  /** Worlds this process has already filed, per day. Saves a query per world per
   *  tick once the morning's work is done — which is almost all of the time,
   *  since the tick runs all day and the work happens once. The database is
   *  still the real guard, for a restart. */
  private val filed = TrieMap.empty[(String, LocalDate), Unit]

  /** The day this process has watched the endpoint roll over to.
   *
   *  Once one world has turned over there is nothing left to discover, so the
   *  worlds still outstanding — the ones a 503 cost us — are swept straight away
   *  instead of paying for another probe every tick. Lost on a restart, which
   *  costs exactly one more probe. */
  @volatile private var rolled: Option[LocalDate] = None

  /** The day the endpoint publishes once the nightly batch has run.
   *
   *  Always answerable, because it is a statement about the clock. Whether the
   *  endpoint has actually caught up is the separate question [[tick]] answers
   *  with evidence.
   *
   *  Agrees with [[DailyStatistics.reportedDay]] throughout the server-save
   *  window, which is what lets the daily post look the row up by the day it is
   *  reporting. They are computed from different boundaries — this one from the
   *  batch, that one from server save — and only coincide because the batch
   *  lands in between. */
  def dayToFetch(at: ZonedDateTime): LocalDate = KillStatisticsSchedule.reportedDay(at)

  /** One pass. Safe on a frequent schedule: once the day is filed this costs a
   *  clock comparison and a map lookup per world, and nothing else. Before the
   *  roll it costs a single request, however many worlds are tracked. */
  def tick(): Future[Unit] = {
    val day = dayToFetch(now())
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
    val outstanding = worlds.filterNot(alreadyFiled(_, day))

    if (outstanding.isEmpty) Future.unit
    else if (rolled.contains(day)) fileAll(outstanding, day)
    else probe(outstanding, day).flatMap {
      case Probe.Rolled(world) =>
        rolled = Some(day)
        val rest = outstanding.filterNot(_ == world)
        logger.info(s"Kill statistics: '$world' has rolled over to $day" +
          (if (rest.isEmpty) "" else s"; reading ${rest.size} more world(s)"))
        fileAll(rest, day)

      case Probe.NotRolled(world) =>
        // Past the hour the batch should have run, but tibia.com is still
        // showing the day we filed yesterday. Nothing to do but ask again.
        logger.debug(s"Kill statistics: '$world' is still reporting the day before $day")
        Future.unit

      case Probe.Unreachable =>
        logger.debug(s"Kill statistics: no probe world answered for $day, trying again next tick")
        Future.unit

      case Probe.NoBaseline =>
        // A database with no previous day anywhere in it — the first morning
        // after this shipped, or a wiped cache. There is nothing to recognise
        // the roll against, so this is the one case that falls back to the
        // clock, and a world only reaches it once.
        if (settled(now())) {
          logger.info(s"Kill statistics: no previous day to compare $day against, trusting the clock")
          fileAll(outstanding, day)
        } else {
          logger.debug(s"Kill statistics: nothing filed for the day before $day; waiting out the settle delay")
          Future.unit
        }
    }
  }

  /** Ask one world whether tibia.com has rolled.
   *
   *  Candidates are drawn from the worlds we still owe a snapshot for, so a
   *  probe that finds the roll also files the world it asked rather than
   *  spending the request on information alone. Worlds already filed are only
   *  drawn on when none of the outstanding ones has a previous day to be
   *  compared against.
   *
   *  Several candidates because one is a coin flip: TibiaData 503s a large share
   *  of requests, and a probe that cannot reach the endpoint costs a whole tick
   *  of waiting. Three makes losing a tick unlikely without making a quiet
   *  morning expensive — the first candidate normally answers. */
  private def probe(outstanding: List[String], day: LocalDate): Future[Probe] = {
    val fallback = trackedWorlds().distinct.sorted.filterNot(outstanding.contains)
    // Lazily, so a database that has the previous day — every morning but the
    // first — reads one row rather than one per tracked world.
    val candidates = (outstanding ++ fallback).view
      .flatMap(world => baseline(world, day).map(world -> _))
      .take(probeCandidates)
      .toList
    if (candidates.isEmpty) Future.successful(Probe.NoBaseline) else ask(candidates, day)
  }

  private def ask(candidates: List[(String, DayKillSummary)], day: LocalDate): Future[Probe] =
    candidates match {
      case Nil => Future.successful(Probe.Unreachable)
      case (world, previous) :: rest =>
        read(world).flatMap {
          case None => ask(rest, day)
          case Some(data) =>
            val live = KillStatistics.summary(data, day)
            if (live.figures == previous.figures) Future.successful(Probe.NotRolled(world))
            else {
              file(world, data, day)
              Future.successful(Probe.Rolled(world))
            }
        }
    }

  /** Read and file every world, paced. Failures are simply left unfiled: the
   *  next tick sweeps them again, and by then the roll is already known so they
   *  are retried without another probe. */
  private def fileAll(worlds: List[String], day: LocalDate): Future[Unit] =
    if (worlds.isEmpty) Future.unit
    else {
      logger.info(s"Kill statistics: reading ${worlds.size} world(s) for $day")
      worlds.foldLeft(Future.successful(0)) { case (acc, world) =>
        acc.flatMap { stored =>
          delay(gap()).flatMap(_ => fetchAndStore(world, day)).map(ok => if (ok) stored + 1 else stored)
        }
      }.map(stored => logger.info(s"Kill statistics: filed $stored of ${worlds.size} world(s) for $day"))
    }

  /** Read one world and file it. False on anything that went wrong, which leaves
   *  the world unmarked so a later tick tries again — there is all day to.
   *
   *  The comparison against the previous day is made again here rather than
   *  taken on trust from the probe. It is free, the row is already to hand, and
   *  it is what turns "worlds roll together" from an assumption into something
   *  that fails loudly: a world still showing yesterday is refused instead of
   *  being filed a day out. A world with no previous day has nothing to check
   *  and takes the probe's word for it. */
  private def fetchAndStore(world: String, day: LocalDate): Future[Boolean] =
    read(world).map {
      case None => false
      case Some(data) =>
        val summary = KillStatistics.summary(data, day)
        if (baseline(world, day).exists(_.figures == summary.figures)) {
          logger.warn(s"Kill statistics: '$world' is still reporting the day before $day " +
            "while other worlds have rolled, not filing it")
          false
        } else {
          file(world, data, day)
          true
        }
    }

  /** One world's live figures, or None if the read failed or is not believable.
   *
   *  A whole world killing nothing in a day is not a quiet day, it is a bad
   *  read — and seventy-four zeroes filed as fact would later read as "no boss
   *  spawned", which is exactly the thing this history is for. It is also what
   *  tibia.com serves part-way through its own maintenance, so this guard is
   *  what stops a probe mistaking a broken page for a rolled one. */
  private def read(world: String): Future[Option[KillStatisticsData]] =
    api.getKillStatistics(world).map {
      case Left(_) => None // already logged by the client
      case Right(response) =>
        val data = response.killstatistics
        if (KillStatistics.isPlausible(data)) Some(data)
        else {
          logger.warn(s"Kill statistics: '$world' reported no kills at all, not filing it")
          None
        }
    }.recover {
      case NonFatal(error) =>
        logger.warn(s"Kill statistics: reading '$world' failed: ${error.getMessage}")
        None
    }

  /** Races first, summary last: `hasDay` reads the summary, and so does the
   *  daily post's gate, so a failure between the two leaves the day looking
   *  unfiled and it is simply read again. The other order would mark a day done
   *  with its rows missing, and would release a post whose creature list and
   *  boss predictions were both reading a half-written day. */
  private def file(world: String, data: KillStatisticsData, day: LocalDate): Unit = {
    repository.recordBossKills(KillStatistics.dayRaces(data, day))
    repository.recordSummary(KillStatistics.summary(data, day))
    filed.put((world, day), ())
  }

  /** What we filed for the day before `day` — the figures the endpoint goes on
   *  showing until it rolls. None where that day was never filed, which is the
   *  whole of the cold-start case. */
  private def baseline(world: String, day: LocalDate): Option[DayKillSummary] =
    try repository.summary(world, day.minusDays(1))
    catch {
      case NonFatal(error) =>
        logger.warn(s"Kill statistics: could not read the day before $day for '$world': ${error.getMessage}")
        None
    }

  private def alreadyFiled(world: String, day: LocalDate): Boolean = {
    val done = try repository.hasDay(world, day) catch {
      case NonFatal(error) =>
        logger.warn(s"Kill statistics: could not tell whether '$world' was filed for $day: ${error.getMessage}")
        false
    }
    if (done) filed.put((world, day), ())
    done
  }

  /** Whether enough time has passed since the nightly batch to trust a read we
   *  have nothing to compare against. Only the cold-start path asks. */
  private def settled(at: ZonedDateTime): Boolean = {
    val berlin = at.withZoneSameInstant(Clock.Berlin)
    val boundary = berlin.toLocalDate.atTime(KillStatisticsSchedule.boundary).atZone(Clock.Berlin)
    val since = if (berlin.isBefore(boundary)) boundary.minusDays(1) else boundary
    Duration.between(since, berlin).compareTo(settle) >= 0
  }
}

object KillStatisticsService {

  /** How long after server save a read is believed when there is nothing to
   *  compare it against.
   *
   *  An hour, and deliberately generous, because it is only reachable on a
   *  database holding no previous day at all — the first morning after this
   *  shipped, or a wiped cache. Every other morning the roll is recognised
   *  rather than waited out. */
  val Settle: Duration = Duration.ofHours(1)

  /** How many worlds one tick will ask before giving up and waiting for the
   *  next one. */
  val ProbeCandidates: Int = 3

  /** What one tick's probe established about the endpoint. */
  private[statistics] sealed trait Probe

  private[statistics] object Probe {

    /** `world` has turned over, and has been filed. Everything else can follow. */
    final case class Rolled(world: String) extends Probe

    /** `world` is still showing the day we filed yesterday. */
    final case class NotRolled(world: String) extends Probe

    /** Nobody answered — every candidate failed or read implausibly. */
    case object Unreachable extends Probe

    /** No world has a previous day to be recognised against. */
    case object NoBaseline extends Probe
  }
}
