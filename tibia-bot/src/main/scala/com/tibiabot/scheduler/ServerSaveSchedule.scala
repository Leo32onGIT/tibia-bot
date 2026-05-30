package com.tibiabot.scheduler

import java.time.{DayOfWeek, Duration, Instant, LocalTime}

/** Pure scheduling decisions used by the periodic (server-save) job. */
object ServerSaveSchedule {

  /** The post-server-save notification window: after 10:00 and before 10:45 (Berlin time). */
  def isServerSaveWindow(time: LocalTime): Boolean =
    time.isAfter(LocalTime.of(10, 0)) && time.isBefore(LocalTime.of(10, 45))

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
