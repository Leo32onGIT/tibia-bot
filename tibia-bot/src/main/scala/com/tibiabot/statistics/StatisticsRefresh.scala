package com.tibiabot.statistics

import java.time.{Duration, Instant}

/** What a press of the refresh button should do.
 *
 *  Every case carries what the answer needs, because a refusal is told to
 *  somebody: "nothing newer" and "not enough readings" are different situations
 *  with different waits behind them, and a reader who is told the wrong one
 *  presses again.
 */
sealed trait RefreshDecision

object RefreshDecision {

  /** Rebuild the experience embed over this window. */
  final case class Rebuild(window: ExperienceWindow) extends RefreshDecision

  /** The post already shows the last reading. `shown` is the reading it was
   *  built from, so the answer can say how current it is rather than only that
   *  the press did nothing. */
  final case class NothingNewer(shown: Instant) extends RefreshDecision

  /** Pressed again inside the floor.
   *
   *  The only refusal nobody is told about — see
   *  [[com.tibiabot.interactions.StatisticsButtons]] — since the press it
   *  catches is a finger on the button rather than a question. `retryAt` is
   *  carried anyway: it is what the decision actually is, and a decision that
   *  reports less than it knows because today's caller says nothing is a worse
   *  thing to test against. */
  final case class TooSoon(retryAt: Instant) extends RefreshDecision

  /** No window can be measured yet — see [[ExperienceWindow.choose]] for the
   *  three situations that reach here, all of which are answered by waiting. */
  case object NotEnoughReadings extends RefreshDecision

  /** The figures could not be read at all.
   *
   *  Never returned by [[StatisticsRefresh.decide]], which is a decision about
   *  data it has been handed; it comes from the service when the query behind
   *  it fails. It exists so a database that is briefly unavailable is not
   *  reported as a world with nothing to show — the reader would wait for
   *  something that had already happened. */
  case object Unavailable extends RefreshDecision
}

/** Whether a refresh runs, and over what.
 *
 *  ==The cooldown is the data==
 *  The interval nobody has to choose. A world gets one reading an hour, so a
 *  second press before the next one has nothing to show — and rather than a
 *  timer that says so approximately, this compares what the post was built from
 *  against what the table holds. It cannot drift, it needs no configuring, and
 *  the refusal can state a fact instead of a countdown somebody has to trust.
 *
 *  [[Floor]] sits underneath it for the case the comparison does not cover: the
 *  press that arrives before the last one has finished being applied, and the
 *  reader leaning on the button. It is a guard on the work, not on the answer.
 *
 *  ==Nothing here is persisted==
 *  What a channel currently shows is held in memory by the caller. Losing it in
 *  a restart costs one rebuild that changes nothing visible, which is cheaper
 *  than a column on 122 guild databases and an ALTER to add it. The figures
 *  themselves are never at risk: they come from the readings table either way.
 */
object StatisticsRefresh {

  /** The shortest gap between two presses in one channel.
   *
   *  A minute, which no honest reader will ever meet: the data behind the
   *  button moves once an hour, so anybody reaching this is holding it down. */
  val Floor: Duration = Duration.ofMinutes(1)

  /** How far back to ask for readings.
   *
   *  A day, the tolerance either side of it, and an hour of slack for a sweep
   *  that ran late. Bounding it matters — the readings table keeps a week, and
   *  a press should not scan six days of a world it cannot use. */
  val Lookback: Duration =
    ExperienceWindow.Span.plus(ExperienceWindow.Tolerance).plus(Duration.ofHours(1))

  /** When a channel that pressed at `lastPressed` may press again. */
  def retryAfter(lastPressed: Option[Instant], now: Instant, floor: Duration = Floor): Option[Instant] =
    lastPressed.map(_.plus(floor)).filter(_.isAfter(now))

  /** The decision.
   *
   *  @param times       readings for the world, as `readingTimes` returned them
   *  @param shown       the reading the post's experience embed was built from,
   *                     or None where this bot has not refreshed it — a fresh
   *                     daily post, or a restart since the last press
   *  @param lastPressed when this channel last pressed
   */
  def decide(
      times: List[Instant],
      shown: Option[Instant],
      lastPressed: Option[Instant],
      now: Instant,
      floor: Duration = Floor
  ): RefreshDecision =
    retryAfter(lastPressed, now, floor) match {
      case Some(at) => RefreshDecision.TooSoon(at)
      case None =>
        ExperienceWindow.choose(times, now) match {
          case None => RefreshDecision.NotEnoughReadings
          // Not `contains`: a post built from a reading later than anything the
          // table now holds is a table that was pruned or a clock that went
          // backwards, and rebuilding it to an older figure would be a worse
          // answer than leaving it alone.
          case Some(window) if shown.exists(!_.isBefore(window.to)) =>
            RefreshDecision.NothingNewer(window.to)
          case Some(window) => RefreshDecision.Rebuild(window)
        }
    }
}
