package com.tibiabot
package tibiadata

import com.tibiabot.tibiadata.response.HighscoresResponse

import java.time.{Duration, Instant}
import scala.concurrent.Future
import scala.util.Try

/** Port over the highscores endpoint, implemented by [[TibiaDataClient]].
 *
 *  Deliberately separate from [[TibiaApi]] rather than another method on it.
 *  Six types implement `TibiaApi` — the Redis cache, the age cache, the
 *  shared-world publisher, the fansite dual-source, the client itself and the
 *  test stubs — and every one of them exists to wrap a character sheet. Adding
 *  `getHighscores` there would make all six grow a method that only delegates.
 *  `TibiaDataClient` implements both traits; nothing else needs to. */
trait HighscoresApi {

  /** One page of one list on one world.
   *
   *  Left on any failure, already logged, matching the rest of the client. The
   *  caller is on a snapshot cycle 45+ minutes long, so a page lost here is 50
   *  characters unseen until the next snapshot — the inline retry is worth
   *  taking, unlike on the character poll where the next tick is a minute away. */
  def getHighscores(world: String, list: HighscoreList, page: Int): Future[Either[String, HighscoresResponse]]
}

/** When tibia.com last rebuilt the highscores, derived from a response.
 *
 *  `information.timestamp` is when the data was generated and `highscore_age`
 *  is how many whole minutes before that tibia.com last updated, so the
 *  difference identifies the snapshot. Because the age is published floored to
 *  the minute, the estimate carries up to 59 seconds of jitter: two reads of
 *  the same underlying snapshot can differ by a minute. Callers deciding
 *  "is this new data?" must therefore compare with a tolerance rather than for
 *  equality — see [[HighscoreSnapshot.isNewerThan]]. */
object HighscoreSnapshot {

  /** How far apart two estimates may sit and still mean the same snapshot.
   *  Two minutes: one for the floored age, one for clock skew between reads. */
  val Tolerance: Duration = Duration.ofMinutes(2)

  /** None when the response omitted `information.timestamp`, or carried one
   *  that will not parse — the same defensive treatment
   *  [[AgeCachedTibiaApi]] gives it. */
  def of(response: HighscoresResponse): Option[Instant] =
    response.information.timestamp
      .flatMap(stamp => Try(Instant.parse(stamp)).toOption)
      .map(_.minus(Duration.ofMinutes(response.highscores.highscore_age.toLong)))

  /** Whether `candidate` is a genuinely later snapshot than `seen`, allowing
   *  for the minute of jitter above. No previous snapshot always counts as new. */
  def isNewerThan(candidate: Instant, seen: Option[Instant]): Boolean =
    seen.forall(previous => candidate.isAfter(previous.plus(Tolerance)))
}
