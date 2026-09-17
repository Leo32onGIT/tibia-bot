package com.tibiabot.statistics

import java.time.{Duration, Instant}

/** The two readings a rolling experience figure is measured between.
 *
 *  Both ends are instants the readings table actually holds, never a computed
 *  "now minus a day": the query matches them on equality, and a window whose
 *  ends are real snapshots measures every character over the same span.
 *
 *  `span` is the honest length rather than the one asked for, since the nearest
 *  reading to a day ago is rarely exactly a day ago. Whoever prints this should
 *  print [[hours]] rather than the number it was asked for — see
 *  [[ExperienceWindow.choose]] for why that is the whole point.
 */
final case class ExperienceWindow(from: Instant, to: Instant) {

  def span: Duration = Duration.between(from, to)

  /** The span in whole hours, which is the figure a heading says.
   *
   *  Rounded rather than truncated: a window of 23h 58m is a day to anybody
   *  reading it, and calling it 23 hours would be precision about the wrong
   *  thing. */
  def hours: Long = Math.round(span.toMinutes.toDouble / 60.0)
}

/** Choosing the window, kept pure so the rules are testable without a database
 *  and without waiting for a sweep.
 *
 *  ==Why the span is measured from the last reading, not from now==
 *  Anchoring to `now` would move the window every time somebody pressed, so two
 *  presses a minute apart would report different figures for what a reader
 *  fairly considers the same thing. Anchoring to the last reading means the
 *  window only moves when the data does, which is also what makes refusing a
 *  second press honest rather than merely a cooldown.
 */
object ExperienceWindow {

  /** The window asked for. A day, because that is the span somebody can compare
   *  against yesterday without doing arithmetic. */
  val Span: Duration = Duration.ofHours(24)

  /** How far the anchor may sit from a day before the latest reading.
   *
   *  Six hours, which sounds generous against an hourly sweep and is only ever
   *  reached after one has been missing for most of a morning. The alternative
   *  to a tolerance is not precision, it is silence: refuse an 18-hour window
   *  and a world whose sweeps were interrupted shows nothing at all, which
   *  serves a reader worse than a figure whose own heading says 18 hours.
   *
   *  What it does rule out is a window so long it stops meaning "lately". After
   *  an outage the nearest reading to a day ago can be three days back, and
   *  three days of experience under a heading about today would be wrong in the
   *  one direction nobody would check. */
  val Tolerance: Duration = Duration.ofHours(6)

  /** The window to measure, or None when the readings cannot support one.
   *
   *  None means exactly one thing to the caller — there is nothing to say yet —
   *  and covers a world in its first day of readings, a world whose sweeps have
   *  been failing, and a cache that was cleared. All three are answered by
   *  waiting, which is why they are not distinguished here.
   *
   *  @param times readings for one world; order and duplicates do not matter
   *  @param now   anything later than this is ignored, so a clock that
   *               disagrees with the database cannot pull the window forward
   */
  def choose(
      times: List[Instant],
      now: Instant,
      span: Duration = Span,
      tolerance: Duration = Tolerance
  ): Option[ExperienceWindow] = {
    val taken = times.filterNot(_.isAfter(now)).distinct.sortWith(_.isBefore(_))
    taken.lastOption.flatMap { latest =>
      val ideal = latest.minus(span)
      // Ties go to the earlier reading, so a window that cannot be exact is long
      // rather than short. A short one under-reports the gain it is named for;
      // a long one reports a real figure over a span the heading states.
      taken.filter(_.isBefore(latest))
        .minByOption(at => (gap(at, ideal).toMillis, at.toEpochMilli))
        .filter(at => gap(at, ideal).compareTo(tolerance) <= 0)
        .map(ExperienceWindow(_, latest))
    }
  }

  private def gap(at: Instant, ideal: Instant): Duration = Duration.between(at, ideal).abs
}
