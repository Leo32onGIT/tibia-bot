package com.tibiabot.tracking

import scala.collection.mutable

/** Turns "who is on the online list" into "who just logged in", for the characters
 *  somebody has put a bounty on.
 *
 *  A DM goes out once per login, not once per sweep, so "is this name online?" is
 *  not enough — and neither is the list's own `duration`, a wall-clock age that
 *  keeps looking recent for an hour. This holds the previous observation of each
 *  target and reports the transitions.
 *
 *  A target seen for the first time is recorded, never reported: that covers both
 *  a cold start, where everything looks like an arrival, and a bounty added on
 *  somebody already online.
 *
 *  A relog is caught by the duration going *backwards*, since presence is rebuilt
 *  from the world poll and a returning character counts from zero. That matters
 *  because the gap is often shorter than a sweep, so the absence is never seen.
 *
 *  One instance per world, keyed by lowercase name. Not thread-safe: the
 *  online-list sweep is single-threaded and the only caller. */
final class BountyPresence {

  /** Last observation per target: `Some(duration)` online, `None` offline.
   *  A missing key means never observed, which is not the same as offline. */
  private val observed = mutable.Map.empty[String, Option[Long]]

  /** Record this pass and return the targets that just came online.
   *
   *  @param targets  lowercase names anyone is watching on this world
   *  @param online   lowercase name -> seconds online, for the whole roster
   */
  def logins(targets: Set[String], online: Map[String, Long]): Set[String] = {
    // Someone who dropped their last bounty on a character shouldn't leave a
    // row behind forever, and re-adding one later should start fresh rather
    // than fire off a stale transition.
    observed.keys.filterNot(targets.contains).toList.foreach(observed.remove)

    val loggedIn = Set.newBuilder[String]
    targets.foreach { target =>
      val now = online.get(target)
      observed.get(target) match {
        case None => () // first sighting: seed only, below
        case Some(before) =>
          val cameOnline = before.isEmpty && now.isDefined
          val relogged = (before, now) match {
            case (Some(was), Some(is)) => is < was
            case _                     => false
          }
          if (cameOnline || relogged) loggedIn += target
      }
      observed.update(target, now)
    }
    loggedIn.result()
  }
}
