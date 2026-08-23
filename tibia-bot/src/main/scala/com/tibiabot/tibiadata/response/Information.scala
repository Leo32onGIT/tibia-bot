package com.tibiabot.tibiadata.response

case class Api(version: Double, release: String, commit: String)

case class Status(http_code: Double)

/** The `information` block every v4 response carries.
 *
 *  `timestamp` is when the origin generated this data — NOT when the response
 *  was sent. On a response replayed from the upstream cache it stays pinned at
 *  the moment the cached copy was built while the Date header moves on, which
 *  makes it the one field that says how old the data actually is. Verified
 *  against the live API: it tracks `Date - Age` to within a second, and holds
 *  still across an entire cache entry's life.
 *
 *  Optional purely defensively — a response that omits it simply cannot be
 *  cached rather than failing to parse. See
 *  [[com.tibiabot.tibiadata.AgeCachedTibiaApi]] for what reads it. */
case class Information(api: Api, timestamp: Option[String], status: Status)
