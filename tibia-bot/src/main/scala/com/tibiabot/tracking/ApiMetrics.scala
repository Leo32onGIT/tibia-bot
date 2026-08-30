package com.tibiabot.tracking

/** The process's outbound API call counters, one per upstream.
 *
 *  Process-wide rather than injected because what these measure *is* the
 *  process: "how much traffic is this bot sending to Discord/TibiaData in
 *  total". The producers are scattered by design and can't share an instance
 *  any other way — [[com.tibiabot.tibiadata.TibiaDataClient]] alone is built in
 *  three places (BotApp, TibiaBot, WorldManager), and the Discord counter is
 *  fed from an OkHttp interceptor inside JDA's own client, which has no route
 *  back to application wiring.
 *
 *  [[ApiCallMetrics]] itself takes an injectable clock and holds no global
 *  state, so tests construct their own instance and never touch these. */
object ApiMetrics {

  /** Every Discord REST call this process makes, counted at the HTTP layer (see
   *  [[com.tibiabot.app.Bootstrap.buildReadyJda]]) so it covers traffic that
   *  bypasses [[com.tibiabot.discord.RateLimitedSender]] entirely — death posts
   *  and the boosted-channel server-save post are sent straight to JDA, and
   *  command replies never go near the queue.
   *
   *  Dimensions: `operation` (see
   *  [[com.tibiabot.discord.DiscordApiRoute]]) and `status`. */
  val discord = new ApiCallMetrics()

  /** Every TibiaData request, counted at
   *  [[com.tibiabot.tibiadata.TibiaDataClient]]'s single request choke point,
   *  so retries count as the separate calls they are.
   *
   *  Dimensions: `endpoint` and `status`, each summing to the total. */
  val tibiaData = new ApiCallMetrics()

  /** Every request to CipSoft's fansite API, counted at
   *  [[com.tibiabot.fansiteapi.FansiteApiClient]]'s choke point.
   *
   *  Deliberately a separate counter from `tibiaData` rather than another
   *  `endpoint` dimension on it: the two are different upstreams with
   *  different failure modes and different budgets, and the question the
   *  dashboard has to answer while both are running is "which one is
   *  struggling", which one merged total cannot. */
  val fansiteApi = new ApiCallMetrics()
}
