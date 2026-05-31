package com.tibiabot.presentation

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import scala.util.Try

/** Renders a character's last-login time for the `/allies list` and
 *  `/hunted list` output. Extracted from BotApp.dateStringToEpochSeconds so it
 *  is testable with a fixed clock and so malformed input can't crash the list
 *  command. Pinned by RecentLoginSpec. */
object RecentLogin {

  /** If `dateString` (an ISO-8601 instant from the API) is within 24h of `now`,
   *  render the Discord "daily" emoji plus a relative timestamp; otherwise "".
   *  Empty or unparseable input yields "" rather than throwing. */
  def stamp(dateString: String, now: Instant): String =
    Try(Instant.from(DateTimeFormatter.ISO_INSTANT.parse(dateString))).toOption
      .filter(instant => Math.abs(instant.until(now, ChronoUnit.HOURS)) <= 24)
      .map(instant => s"<:daily:1133349016814485584><t:${instant.getEpochSecond.toString}:R>")
      .getOrElse("")
}
