package com.tibiabot.tibiadata

import com.tibiabot.tibiadata.response.Information

import java.time.Instant
import scala.util.control.NonFatal

/** When the origin generated the data in a response, read off the `information`
 *  block every v4 endpoint carries.
 *
 *  The one field that survives the upstream cache unchanged: `Date` is restamped
 *  on every response, but `timestamp` stays pinned to when the copy was built for
 *  as long as it is handed out — verified against the live API, tracking
 *  `Date - Age` to within a second. That makes it the basis for deciding when a
 *  copy turns over ([[AgeCachedTibiaApi]]) and how long one is worth sharing
 *  ([[SharedWorldTibiaApi]]).
 *
 *  Absent or unparseable yields None, and every caller degrades to unknown
 *  freshness rather than guessing. */
object OriginTimestamp {
  def of(information: Information): Option[Instant] =
    information.timestamp.flatMap { raw =>
      try Some(Instant.parse(raw))
      catch { case NonFatal(_) => None }
    }
}
