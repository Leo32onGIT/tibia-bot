package com.tibiabot.domain.time

import java.time.ZonedDateTime

/** Galthen's Satchel has a fixed 30-day cooldown. Single source of that duration
 *  (and the expiry computation) shared by the /galthen command, its button and
 *  its modal — previously the 30 and the `plusDays(30).toEpochSecond` were
 *  duplicated across all three. */
object SatchelCooldown {
  val durationDays: Long = 30

  /** Epoch-second (as a string, for Discord's `<t:..:R>`) at which a cooldown
   *  that started at `when` expires. */
  def expiresAtEpoch(when: ZonedDateTime): String =
    when.plusDays(durationDays).toEpochSecond.toString
}
