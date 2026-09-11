package com.tibiabot.scheduler

import com.tibiabot.domain.time.Clock

import java.time.{LocalDate, LocalTime, ZonedDateTime}

/** When tibia.com publishes its kill statistics, and which day they describe.
 *
 *  ==Not server save==
 *  The obvious assumption, and the wrong one. tibia.com rebuilds the kill
 *  statistics in a nightly batch somewhere around 03:00-03:20 Berlin, nowhere
 *  near the 10:00 server save — so the figures a reader sees at ten in the
 *  morning have been sitting there for seven hours.
 *
 *  That matters twice over. It is why the snapshot can be taken long before the
 *  daily post needs it rather than racing it, and it is why the day a
 *  publication describes is not a clean server-save day at all:
 *
 *  {{{
 *  published ~03:10 on day X   covers   03:10 on X-1  ->  03:10 on X
 *  save day X-1                covers   10:00 on X-1  ->  10:00 on X
 *  }}}
 *
 *  The overlap is seventeen hours of the twenty-four, so `X-1` is the closest
 *  single day to label it with and what everything here uses — but a day's
 *  creature figures genuinely carry a few hours of the day before and are
 *  missing a few hours of their own. CipSoft does not align the batch to server
 *  save and nothing here can make it so; the honest thing is to say so rather
 *  than to imply the count is a save day.
 *
 *  ==Why the exact time barely matters==
 *  [[com.tibiabot.statistics.KillStatisticsService]] recognises the roll by
 *  comparing a live read against the day it already filed, so these times decide
 *  only when it starts *asking*. Set the boundary late enough to be clear of the
 *  batch and the first ask usually succeeds; set it wrong and the retries find
 *  it anyway, hours before anything needs it.
 */
object KillStatisticsSchedule {

  /** When the nightly batch is believed to run, in Berlin time. Reported as
   *  03:00-03:20; [[com.tibiabot.statistics.RollProbe]] is what will tell us
   *  whether that holds. */
  val publishedFrom: LocalTime = LocalTime.of(3, 0)
  val publishedBy: LocalTime = LocalTime.of(3, 20)

  /** When to start asking for the new day.
   *
   *  Forty minutes past the far end of the batch window, which covers
   *  TibiaData's own five-minute cache and leaves room for the batch to run
   *  late. Still more than six hours before the daily post wants the rows. */
  val boundary: LocalTime = LocalTime.of(4, 0)

  /** The day the endpoint is publishing once it has rolled — the day before the
   *  batch that produced it.
   *
   *  Measured from [[boundary]] rather than from midnight or from server save,
   *  so the answer turns over when the figures do. Before the boundary it still
   *  names the day already filed, which is what stops a tick at two in the
   *  morning asking for a day that does not exist yet. */
  def reportedDay(at: ZonedDateTime): LocalDate = {
    val berlin = at.withZoneSameInstant(Clock.Berlin)
    val shifted = berlin.minusHours(boundary.getHour.toLong).minusMinutes(boundary.getMinute.toLong)
    shifted.toLocalDate.minusDays(1)
  }

  /** Whether `at` is past the point the day's figures should exist. */
  def published(at: ZonedDateTime): Boolean = {
    val berlin = at.withZoneSameInstant(Clock.Berlin)
    !berlin.toLocalTime.isBefore(boundary)
  }
}
