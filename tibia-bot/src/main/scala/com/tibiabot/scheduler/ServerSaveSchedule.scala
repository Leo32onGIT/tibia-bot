package com.tibiabot.scheduler

import com.tibiabot.domain.time.Clock

import java.time.{DayOfWeek, Duration, Instant, LocalTime, ZonedDateTime}

/** Pure scheduling decisions used by the periodic (server-save) job. */
object ServerSaveSchedule {

  /** Server save itself: 10:00 in the game's reference zone. The window below
   *  and the respawn system's daily stamina reset both hang off this one time. */
  val serverSaveTime: LocalTime = LocalTime.of(10, 0)

  /** The post-server-save notification window: after 10:00 and before 10:45 (Berlin time). */
  def isServerSaveWindow(time: LocalTime): Boolean =
    time.isAfter(serverSaveTime) && time.isBefore(LocalTime.of(10, 45))

  /** The most recent server save at or before `now` — i.e. the start of the
   *  current "server save day". Used as the epoch a user's claim stamina is
   *  measured against: a stamina row stamped with an older boundary than this
   *  is stale and resets to a full tank on next read.
   *
   *  Resolved in [[Clock.Berlin]], so the boundary follows the game's clock
   *  through daylight-saving changes rather than drifting an hour twice a year.
   *  Between midnight and 10:00 the current day's save hasn't happened yet, so
   *  the boundary is still yesterday's. */
  def lastServerSave(now: ZonedDateTime): ZonedDateTime = {
    val berlin = now.withZoneSameInstant(Clock.Berlin)
    val todaysSave = berlin.toLocalDate.atTime(serverSaveTime).atZone(Clock.Berlin)
    if (berlin.isBefore(todaysSave)) todaysSave.minusDays(1) else todaysSave
  }

  /** A time expressed the way Tibia players talk about it: hours either side of
   *  server save. 10:00 Berlin is `SS+0`, 11:00 is `SS+1`, and the late hours
   *  count down to the next save instead — 06:00 is `SS-4`, not `SS+20`.
   *
   *  The switch happens at the halfway point, which is where the shorter of the
   *  two readings changes over. Nobody says "SS+20" when "SS-4" means the same
   *  evening.
   *
   *  Clamped to a day's worth of hours because a daylight-saving day is 23 or 25
   *  hours long, and an `SS+24` would be nonsense on the long one. */
  def serverSaveOffsetLabel(when: ZonedDateTime): String = {
    val sinceSave = Duration.between(lastServerSave(when), when).toHours
    val hours = math.max(0L, math.min(23L, sinceSave)).toInt
    if (hours <= 12) s"SS+$hours" else s"SS-${24 - hours}"
  }

  /** The next server save strictly after `now` — when a spent stamina tank
   *  refills. Rendered as a Discord relative timestamp in the "out of stamina"
   *  replies. */
  def nextServerSave(now: ZonedDateTime): ZonedDateTime =
    lastServerSave(now).plusDays(1)

  /** The city where Rashid can be found on a given (Berlin minus 10h) weekday. */
  def rashidLocation(day: DayOfWeek): String = day match {
    case DayOfWeek.MONDAY    => "Svargrond"
    case DayOfWeek.TUESDAY   => "Liberty Bay"
    case DayOfWeek.WEDNESDAY => "Port Hope"
    case DayOfWeek.THURSDAY  => "Ankrahmun"
    case DayOfWeek.FRIDAY    => "Darashia"
    case DayOfWeek.SATURDAY  => "Edron"
    case DayOfWeek.SUNDAY    => "Carlin"
  }

  /** Show the Drome countdown only when it is in the future and within the next 3 days. */
  def shouldShowDrome(now: Instant, dromeTime: Instant): Boolean =
    dromeTime.isAfter(now) && Duration.between(now, dromeTime).toDays <= 3
}
