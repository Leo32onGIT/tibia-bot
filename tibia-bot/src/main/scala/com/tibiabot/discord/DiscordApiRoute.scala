package com.tibiabot.discord

/** Names a Discord REST call by what it did, for the dashboard's throughput
 *  breakdown.
 *
 *  Derived from the HTTP method and URL rather than from any application-side
 *  tag, because it is applied in JDA's HTTP client (see
 *  [[com.tibiabot.app.Bootstrap]]) where the only thing available is the
 *  request itself — which is exactly why it sees every call, including the
 *  unpaced death posts and command replies that never reach
 *  [[RateLimitedSender]].
 *
 *  Deliberately coarse, and deliberately a closed set. Only the five writes
 *  worth watching get a name — chiefly `PATCH message` (an online-list edit)
 *  versus `PATCH channel` (a category rename), which dominate this bot's
 *  traffic and are rate-limited by Discord under completely different buckets,
 *  so they must never share a row. Everything else (gateway and user GETs,
 *  reaction PUTs, channel creation) becomes [[Other]] rather than a row of its
 *  own: it is a rounding error next to the named five, and one row per endpoint
 *  would bury the two that matter.
 *
 *  [[Other]] is still recorded rather than discarded so this dimension keeps
 *  summing to the overall call total; the dashboard leaves it out of the
 *  breakdown but the figures behind it stay whole. */
object DiscordApiRoute {

  /** The catch-all bucket. Named here so the dashboard can filter on it without
   *  hardcoding a string that only this file knows about. */
  val Other = "other"

  /** Discord snowflakes are 17-19 digits; five is comfortably clear of anything
   *  else that appears as a whole path segment (API versions carry a `v`). */
  private val SnowflakeSegment = "/\\d{5,}"

  def operation(method: String, path: String): String = {
    val normalized = path.replaceAll(SnowflakeSegment, "/{id}")
    // `/messages/@original` is the interaction-followup edit route, which is a
    // message edit like any other despite carrying no id.
    val isMessage = normalized.endsWith("/messages") ||
      normalized.endsWith("/messages/{id}") ||
      normalized.endsWith("/messages/@original")
    // Opening a DM is channel creation as far as the route goes, but it only
    // ever happens as the first half of sending someone a DM, so it belongs
    // with the send it pays for rather than with guild channel creation.
    val isDmOpen = normalized.endsWith("/users/@me/channels")
    // Strictly an existing channel: `/channels` without an id is creation,
    // which is not one of the named five.
    val isChannel = normalized.endsWith("/channels/{id}")

    method match {
      case "PATCH" if isMessage => "PATCH message"
      case "PATCH" if isChannel => "PATCH channel"
      case "POST" if isMessage || isDmOpen => "POST message"
      case "DELETE" if isMessage => "DELETE message"
      case "DELETE" if isChannel => "DELETE channel"
      case _ => Other
    }
  }
}
