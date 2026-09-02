package com.tibiabot.highscores

import com.tibiabot.domain.HighscoreEvent
import com.tibiabot.tibiadata.{HighscoreList, HighscoreSnapshot, Highscores, HighscoresApi}
import com.typesafe.scalalogging.StrictLogging

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.FiniteDuration
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

final case class HighscoreSettings(
    window: FiniteDuration,
    workers: Int,
    minRequestGap: FiniteDuration
)

/** What a whole snapshot's sweep did, for the log line and the dashboard. */
final case class SweepSummary(
    snapshotAt: Instant,
    worlds: Int,
    lists: Int,
    pagesRead: Int,
    pagesFailed: Int,
    advances: Int,
    took: java.time.Duration
)

/** Drives the highscore sweep: notices when tibia.com has rebuilt the
 *  highscores, then walks every tracked world's lists at a deliberate pace.
 *
 *  Two things it will not do. It never overlaps itself — a sweep still running
 *  when the next probe fires means the pacing was too slow for the work, and
 *  starting a second one would double the request rate at exactly the wrong
 *  moment. And it never fires the whole snapshot's work at rollover, which is
 *  the behaviour most likely to earn our IP a Cloudflare challenge and take the
 *  boosted feed and a neighbouring droplet with it. */
final class HighscoreService(
    api: HighscoresApi,
    sweep: HighscoreSweep,
    pace: HighscoreGap,
    trackedWorlds: () => List[String],
    announce: (String, HighscoreList, List[HighscoreEvent]) => Unit,
    settings: HighscoreSettings,
    now: () => Instant = () => Instant.now()
)(implicit ec: ExecutionContext) extends StrictLogging {

  private val running = new AtomicBoolean(false)
  @volatile private var lastSnapshot: Option[Instant] = None
  @volatile private var lastSummary: Option[SweepSummary] = None

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
    val items = for { world <- sweptWorlds; list <- HighscoreLists.all } yield (world, list)

    val requests = HighscorePace.requestsFor(sweptWorlds.size, HighscoreLists.all.size, Highscores.MaxPages)
    val gap = HighscorePace.perRequestGap(requests, settings.window, settings.workers, settings.minRequestGap)
    pace.set(gap)

    val estimate = HighscorePace.estimatedDuration(requests, gap, settings.workers)
    logger.info(
      s"Highscores: snapshot $snapshotAt is new — sweeping ${sweptWorlds.size} world(s), " +
        s"$requests page(s) at ${gap.toMillis}ms across ${settings.workers} lane(s), ~${estimate.toMinutes}m")

    // Round-robin rather than contiguous blocks, so no lane ends up holding all
    // of the local-instance lists while the others sit on public ones.
    val lanes = items.zipWithIndex.groupBy(_._2 % settings.workers).values.map(_.map(_._1)).toList

    Future.sequence(lanes.map(lane => runLane(lane, snapshotAt))).map { laneResults =>
      val results = laneResults.flatten
      val summary = SweepSummary(
        snapshotAt = snapshotAt,
        worlds = sweptWorlds.size,
        lists = results.size,
        pagesRead = results.map(_.pagesRead).sum,
        pagesFailed = results.map(_.pagesFailed).sum,
        advances = results.map(_.advances.size).sum,
        took = java.time.Duration.between(startedAt, now())
      )
      lastSummary = Some(summary)
      logger.info(
        s"Highscores: swept ${summary.lists} list(s) over ${summary.worlds} world(s) in ${summary.took.toMinutes}m — " +
          s"${summary.pagesRead} page(s) read, ${summary.pagesFailed} failed, ${summary.advances} advance(s)")
    }
  }

  /** One lane's items, strictly one after another. The gap between requests
   *  lives inside [[HighscoreSweep]]; a lane's job is only to not run its own
   *  items concurrently. */
  private def runLane(items: List[(String, HighscoreList)], snapshotAt: Instant): Future[List[ListSweep]] =
    items.foldLeft(Future.successful(List.empty[ListSweep])) { case (acc, (world, list)) =>
      acc.flatMap { done =>
        sweep.sweepList(world, list, snapshotAt).map { result =>
          if (result.advances.nonEmpty) announce(world, list, result.advances)
          result :: done
        }.recover { case error =>
          logger.warn(s"Highscores: sweeping '$list' for '$world' failed: ${error.getMessage}")
          done
        }
      }
    }.map(_.reverse)
}
