package com.tibiabot.statistics

import com.tibiabot.domain.time.Clock
import com.tibiabot.tibiadata.KillStatisticsApi
import com.typesafe.scalalogging.StrictLogging

import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalTime, ZonedDateTime}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Watches one world across the small hours and logs the minute tibia.com's kill
 *  statistics actually change.
 *
 *  A measurement, not a feature. Everything else about this bot's kill
 *  statistics was built on the belief that the figures roll at server save;
 *  they do not — they are rebuilt in a nightly batch reported as running between
 *  03:00 and 03:20 Berlin — and rather than take the new time on trust the way
 *  the old one was taken, this writes down what it sees for a few days.
 *
 *  It costs one request a minute inside its window and nothing at all outside
 *  it, and it stops for the day the moment it sees the change. Turn it off once
 *  the log has said the same thing a few mornings running: nothing reads its
 *  output but a person.
 *
 *  ==Why this is not the ordinary probe==
 *  [[KillStatisticsService]] also notices the roll, but it cannot time it: it
 *  starts asking at [[com.tibiabot.scheduler.KillStatisticsSchedule.boundary]],
 *  well after the batch, so by the time it looks the change has already
 *  happened. Timing it means watching from before.
 *
 *  ==What the reading is worth==
 *  TibiaData caches for five minutes and this polls once a minute, so an
 *  observation is an upper bound within about six minutes of the truth. Ample
 *  for telling 03:10 from 10:00, which is the question; not enough to pin the
 *  batch to the minute, which nothing needs.
 */
final class RollProbe(
    api: KillStatisticsApi,
    world: () => Option[String],
    from: LocalTime,
    to: LocalTime,
    now: () => ZonedDateTime = () => ZonedDateTime.now(Clock.Berlin)
)(implicit ec: ExecutionContext) extends StrictLogging {

  private val running = new AtomicBoolean(false)

  /** The last figure seen, and the day we last reported a change for. Both in
   *  memory: a restart costs one morning's reading, and the log already holds
   *  every morning before it. */
  @volatile private var lastSeen: Option[Long] = None
  @volatile private var reported: Option[LocalDate] = None

  private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

  def inWindow(at: ZonedDateTime): Boolean = {
    val time = at.withZoneSameInstant(Clock.Berlin).toLocalTime
    !time.isBefore(from) && time.isBefore(to)
  }

  /** One look. Does nothing outside the window, and nothing once the day's
   *  change has been seen and written down. */
  def tick(): Future[Unit] = {
    val at = now()
    val today = at.withZoneSameInstant(Clock.Berlin).toLocalDate
    if (!inWindow(at) || reported.contains(today)) Future.unit
    else world() match {
      case None => Future.unit
      case Some(name) if !running.compareAndSet(false, true) => Future.unit
      case Some(name) => look(name, at, today).map(_ => running.set(false))
    }
  }

  private def look(name: String, at: ZonedDateTime, today: LocalDate): Future[Unit] =
    api.getKillStatistics(name).map {
      case Left(_) => () // already logged by the client
      case Right(response) =>
        val total = response.killstatistics.total.last_day_killed.toLong
        val clock = at.withZoneSameInstant(Clock.Berlin).toLocalTime.format(hhmm)
        lastSeen match {
          case Some(previous) if previous != total =>
            logger.info(s"Kill statistics roll probe: '$name' changed by $clock Berlin " +
              s"($previous -> $total). Polling every minute from ${from.format(hhmm)}, " +
              "so the batch ran within about six minutes of that.")
            reported = Some(today)
            lastSeen = Some(total)
          case Some(_) => ()
          case None =>
            // The first look of the morning is the baseline, not a reading: with
            // nothing to compare against, a change cannot be seen.
            logger.info(s"Kill statistics roll probe: watching '$name' from $clock Berlin (total $total)")
            lastSeen = Some(total)
        }
    }.recover {
      case NonFatal(error) =>
        logger.debug(s"Kill statistics roll probe: could not read '$name': ${error.getMessage}")
    }
}

object RollProbe {

  /** Start before the batch is reported to run, so the first look is a baseline
   *  taken while the old figures are still up. */
  val From: LocalTime = LocalTime.of(2, 40)

  /** Stop well after it, so a batch running late is still caught. */
  val To: LocalTime = LocalTime.of(5, 0)
}
