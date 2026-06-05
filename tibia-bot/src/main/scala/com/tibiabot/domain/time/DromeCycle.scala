package com.tibiabot.domain.time

import java.time.Instant

/** The Tibiadrome cycle: a server-save anchor that advances in 2-week steps.
 *  Pure math extracted verbatim from `BotApp.advanceDromeTime`. */
object DromeCycle {

  /** The initial anchor: 27 May 2026 server save. */
  val initial: Instant = Instant.ofEpochSecond(1779868800L)

  /** Advance `current` in 2-week (Europe/Berlin) steps until it is no longer
   *  before `target`, returning that instant. */
  def advanceFrom(current: Instant, target: Instant): Instant =
    Iterator
      .iterate(current)(t => t.atZone(Clock.Berlin).plusWeeks(2).toInstant)
      .dropWhile(_.isBefore(target))
      .next()
}
