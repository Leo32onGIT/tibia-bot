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

  /** Every request to api.tibiadata.com, counted at
   *  [[com.tibiabot.tibiadata.TibiaDataClient]]'s single request choke point,
   *  so retries count as the separate calls they are.
   *
   *  Dimensions: `endpoint`, `status` and `cacheAge`, each summing to the total. */
  val tibiaData = new ApiCallMetrics()

  /** Every request to the TibiaData instance we run ourselves, counted at the
   *  same choke point and split by the host actually called.
   *
   *  Its own counter rather than a `host` dimension on `tibiaData`, for the
   *  reason `fansiteApi` has one: the same software behind two hosts is still
   *  two upstreams. The public one is Kong-cached, shared with everyone and
   *  costs tibia.com nothing extra from us; ours scrapes tibia.com from the VPS
   *  IP, on our own CPU, and what it risks is being blocked. A `host` dimension
   *  would show how much traffic each carries but could never cross that with
   *  `status`, since dimensions are independent and each sums to the whole
   *  total — so "is our own instance erroring" would stay unanswerable, which
   *  is the question the split exists to answer.
   *
   *  Only the vocation-filtered highscore lists and the two boosted endpoints
   *  come here; see [[com.tibiabot.tibiadata.HighscoreSource]].
   *
   *  Dimensions: as `tibiaData`. */
  val tibiaDataLocal = new ApiCallMetrics()

  /** Every request to CipSoft's fansite API, counted at
   *  [[com.tibiabot.fansiteapi.FansiteApiClient]]'s choke point.
   *
   *  Deliberately a separate counter from `tibiaData` rather than another
   *  `endpoint` dimension on it: the two are different upstreams with
   *  different failure modes and different budgets, and the question the
   *  dashboard has to answer while both are running is "which one is
   *  struggling", which one merged total cannot. */
  val fansiteApi = new ApiCallMetrics()

  /** Fansite character fetches this process decided not to make.
   *
   *  Deliberately not folded into `fansiteApi`: every record there increments a
   *  total that means "requests we put on their IP", which is the number that
   *  decides whether we get blocked. A refusal is the opposite of that, and
   *  counting it in the same place would inflate the one figure whose value
   *  comes from being literally true.
   *
   *  Only the refusals that mean something is wrong. A character passed over
   *  because it did not make the roster is not counted here -- that is the
   *  design working, happens tens of thousands of times a tick, and is reported
   *  as a gauge by [[com.tibiabot.fansiteapi.FansiteRoster]] instead.
   *
   *  Dimension: `reason`. */
  val fansiteRefused = new ApiCallMetrics()
}
