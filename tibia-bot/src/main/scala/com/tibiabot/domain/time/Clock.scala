package com.tibiabot.domain.time

import java.time.ZoneId

/** Time constants shared by the cycle math in this package.
 *
 *  This used to also declare a `Clock` port (trait + `SystemClock`) intended
 *  for incrementally moving wall-clock calls behind an injectable seam. Nothing
 *  ever implemented or consumed it in production — only `Berlin` below was
 *  ever referenced — so it has been dropped rather than left as an unused
 *  abstraction. The pure cycle math here (see [[DromeCycle]],
 *  [[DreamScarCycle]]) is already parameterized by explicit instants and needs
 *  no clock; if a future caller does need an injectable one, `java.time.Clock`
 *  covers it without a bespoke type. */
object Clock {
  /** The game's reference time zone, used throughout the bot. */
  val Berlin: ZoneId = ZoneId.of("Europe/Berlin")
}
