package com.tibiabot.highscores

import com.tibiabot.tibiadata.{HighscoreList, HighscoreSnapshot, Highscores, HighscoresApi}
import com.typesafe.scalalogging.StrictLogging

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.concurrent.{ExecutionContext, Future}

/** The pacing figure the sweep reads between requests.
 *
 *  A holder rather than a constructor argument because it is recomputed once
 *  per snapshot — the number of tracked worlds changes as guilds come and go,
 *  and a gap sized for ten worlds is a burst at seventy. Written by the service
 *  before a sweep starts, read by every lane inside it. */
final class HighscoreGap(initial: FiniteDuration) {
  @volatile private var current: FiniteDuration = initial
  def get: FiniteDuration = current
  def set(gap: FiniteDuration): Unit = current = gap
}

/** @param seedLatency what to assume a page costs beyond its sleep until a
 *                     sweep has measured it. Defaulted rather than configured:
 *                     it is replaced within one sweep, so it is a starting
 *                     point and not a setting anybody has to keep right. */
final case class HighscoreSettings(
    window: FiniteDuration,
    workers: Int,
    minRequestGap: FiniteDuration,
    seedLatency: FiniteDuration = HighscorePace.SeedLatency
)

/** What a whole snapshot's sweep did, for the log line and the dashboard.
 *
 *  `latency` is what one page cost beyond its sleep, which is the figure the
 *  next sweep paces itself by — see [[HighscorePace.observedLatency]]. It is
 *  here rather than kept privately because a sweep that no longer fits its
 *  window shows up in this number first. */
final case class SweepSummary(
    snapshotAt: Instant,
    worlds: Int,
    lists: Int,
    pagesRead: Int,
    pagesFailed: Int,
    advances: Int,
    took: java.time.Duration,
    latency: FiniteDuration
)

/** Drives the highscore sweep: notices when tibia.com has rebuilt the
 *  highscores, then walks every tracked world's lists at a deliberate pace.
 *
 *  Sweeping only; the posting is [[HighscoreFeed]]'s, since a bot can only
 *  write to its own guilds and this runs on the primary alone.
 *
 *  Two things it will not do. It never overlaps itself — a sweep still running
 *  when the next probe fires means the pacing was too slow for the work, and
 *  starting a second one would double the request rate at exactly the wrong
 *  moment. And it never fires the whole snapshot's work at rollover, which is
 *  the behaviour most likely to earn our IP a Cloudflare challenge and take the
 *  boosted feed and a neighbouring droplet with it. */
/** @param lists which catalogue this install can read. Everything with its own
 *               TibiaData instance gets [[HighscoreLists.all]]; one without gets
 *               the public-only catalogue, since the vocation-filtered lists in
 *               the full one can only be refused there — see
 *               [[HighscoreLists.forInstance]]. */
final class HighscoreService(
    api: HighscoresApi,
    sweep: HighscoreSweep,
    pace: HighscoreGap,
    trackedWorlds: () => List[String],
    settings: HighscoreSettings,
    lists: List[HighscoreList] = HighscoreLists.all,
    now: () => Instant = () => Instant.now()
)(implicit ec: ExecutionContext) extends StrictLogging {

  private val running = new AtomicBoolean(false)
  @volatile private var lastSnapshot: Option[Instant] = None
  @volatile private var lastSummary: Option[SweepSummary] = None

  /** What a page cost beyond its sleep, the last time a sweep measured it.
   *
   *  Seeded rather than configured, and replaced by every sweep that finishes,
   *  so the pacing tracks however far away tibia.com is today without a knob
   *  anybody has to keep right. A restart costs one sweep at the seed. */
  @volatile private var latency: FiniteDuration = settings.seedLatency

  def snapshotSeen: Option[Instant] = lastSnapshot
  def lastSweep: Option[SweepSummary] = lastSummary

  /** The worlds this sweep covers: everything the process tracks.
   *
   *  Sorted so a snapshot's work is enumerated in the same order every time,
   *  which makes two sweeps' logs comparable. */
  def worlds(): List[String] = trackedWorlds().distinct.sorted

  /** One probe, and a full sweep behind it if the data is new. Safe to call on a
   *  schedule far shorter than a snapshot: the probe is a single request, and
   *  everything behind it is skipped until the snapshot actually rolls over. */
  def tick(): Future[Unit] =
    if (!running.compareAndSet(false, true)) {
      logger.info("Highscores: previous sweep still running, skipping this probe")
      Future.unit
    } else {
      val work = probe().flatMap {
        case Some(snapshotAt) if HighscoreSnapshot.isNewerThan(snapshotAt, lastSnapshot) =>
          lastSnapshot = Some(snapshotAt)
          runSweep(snapshotAt)
        case Some(_) => Future.unit
        case None => Future.unit
      }
      work.recover { case error => logger.error("Highscores: sweep failed", error) }
        .map(_ => running.set(false))
    }

  /** When tibia.com last rebuilt the highscores, from one cheap request.
   *
   *  Any list on any world answers this — the snapshot is global, every world
   *  and category reporting the same age within a minute of each other. The
   *  experience list is used because it is public, so the probe never touches
   *  our own instance. */
  private def probe(): Future[Option[Instant]] =
    worlds().headOption match {
      case None => Future.successful(None)
      case Some(world) =>
        api.getHighscores(world, HighscoreLists.experience, 1).map {
          case Right(response) => HighscoreSnapshot.of(response)
          case Left(_) => None // already logged by the client
        }.recover { case error =>
          logger.warn(s"Highscores: snapshot probe failed: ${error.getMessage}")
          None
        }
    }

  private def runSweep(snapshotAt: Instant): Future[Unit] = {
    val startedAt = now()
    val sweptWorlds = worlds()
    val items = for { world <- sweptWorlds; list <- lists } yield (world, list)

    val requests = HighscorePace.requestsFor(sweptWorlds.size, lists.size, Highscores.MaxPages)
    val perRequest = latency
    val gap = HighscorePace.perRequestGap(
      requests, settings.window, settings.workers, settings.minRequestGap, perRequest)
    pace.set(gap)

    val estimate = HighscorePace.estimatedDuration(requests, gap, settings.workers, perRequest)
    logger.info(
      s"Highscores: snapshot $snapshotAt is new — sweeping ${sweptWorlds.size} world(s), " +
        s"$requests page(s) at ${gap.toMillis}ms + ~${perRequest.toMillis}ms each " +
        s"across ${settings.workers} lane(s), ~${estimate.toMinutes}m")

    // Said out loud, because a sweep that outlives its window does not fail —
    // it finishes late, the next probe finds it still running and skips, and
    // the one after that starts later still, until whole snapshots go unread.
    // That is what happened through September 2026 while the estimate said 44
    // minutes and the sweep took 72, so the estimate saying so is the guard.
    // Nothing is adjusted here, and the floor is half the condition rather than
    // an aside: above it the arithmetic has already sized the gap to the window
    // and any excess is the rounding of a part-page, while at it there is no
    // gap left to give back and the only way to go faster is a burst.
    if (gap <= settings.minRequestGap && estimate > settings.window)
      logger.warn(
        s"Highscores: ${sweptWorlds.size} world(s) will not fit — ~${estimate.toMinutes}m of work " +
          s"in a ${settings.window.toMinutes}m window, held at the ${gap.toMillis}ms floor. " +
          "Snapshots will start being missed; raise highscores.workers or window-fraction")

    // Round-robin rather than contiguous blocks, so no lane ends up holding all
    // of the local-instance lists while the others sit on public ones.
    val lanes = items.zipWithIndex.groupBy(_._2 % settings.workers).values.map(_.map(_._1)).toList

    Future.sequence(lanes.map(lane => runLane(lane, snapshotAt))).map { laneResults =>
      val results = laneResults.flatten
      val took = java.time.Duration.between(startedAt, now())

      // Only the pages actually asked for: readPages stops at the end of a short
      // list rather than walking to 20, and the ones it never asked for cost
      // nothing to divide into the time this took.
      val attempted = results.map(one => one.pagesRead + one.pagesFailed).sum
      val measured = HighscorePace.observedLatency(
        FiniteDuration(took.toMillis, MILLISECONDS), attempted, gap, settings.workers)
      measured.foreach(latency = _)

      val summary = SweepSummary(
        snapshotAt = snapshotAt,
        worlds = sweptWorlds.size,
        lists = results.size,
        pagesRead = results.map(_.pagesRead).sum,
        pagesFailed = results.map(_.pagesFailed).sum,
        advances = results.map(_.advances.size).sum,
        took = took,
        latency = measured.getOrElse(perRequest)
      )
      lastSummary = Some(summary)
      logger.info(
        s"Highscores: swept ${summary.lists} list(s) over ${summary.worlds} world(s) in ${summary.took.toMinutes}m — " +
          s"${summary.pagesRead} page(s) read, ${summary.pagesFailed} failed, ${summary.advances} advance(s), " +
          s"${summary.latency.toMillis}ms per page")
    }
  }

  /** One lane's items, strictly one after another. The gap between requests
   *  lives inside [[HighscoreSweep]]; a lane's job is only to not run its own
   *  items concurrently. */
  private def runLane(items: List[(String, HighscoreList)], snapshotAt: Instant): Future[List[ListSweep]] =
    items.foldLeft(Future.successful(List.empty[ListSweep])) { case (acc, (world, list)) =>
      acc.flatMap { done =>
        // Nothing is announced here. The advances are filed, and every bot in
        // the fleet — this one included — posts them from that table, because
        // each can only write to the guilds it is itself in.
        sweep.sweepList(world, list, snapshotAt).map(_ :: done).recover { case error =>
          logger.warn(s"Highscores: sweeping '$list' for '$world' failed: ${error.getMessage}")
          done
        }
      }
    }.map(_.reverse)
}
