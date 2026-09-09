package com.tibiabot
package tibiadata

import com.tibiabot.tibiadata.response.KillStatisticsResponse

import scala.concurrent.Future

/** Port over the kill statistics endpoint, implemented by [[TibiaDataClient]].
 *
 *  Separate from [[TibiaApi]] for the same reason [[HighscoresApi]] is: six
 *  types implement that trait and every one of them exists to wrap a character
 *  sheet, so putting this there would give all six a method that only delegates.
 *
 *  One request per world per day, against the public endpoint. That is nothing
 *  beside the highscore sweep's ninety thousand — but TibiaData is currently
 *  failing a large share of requests with 503s, and a miss here is a permanent
 *  hole in one world's history rather than a page that the next snapshot re-reads.
 *  The client's retry policy is what makes that unlikely; the caller has to cope
 *  with it anyway. */
trait KillStatisticsApi {

  /** One world's kill statistics as tibia.com currently reports them.
   *
   *  The `last_day_*` figures cover the server-save day that has just closed and
   *  reset at the next save, so when this is read decides which day it describes
   *  — see [[com.tibiabot.statistics.KillStatisticsService]]. */
  def getKillStatistics(world: String): Future[Either[String, KillStatisticsResponse]]
}
