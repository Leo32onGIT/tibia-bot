package com.tibiabot.tracking

import scala.collection.mutable

/** Turns "who is on the online list" into "who just logged in", for the handful
 *  of characters somebody has put a bounty on.
 *
 *  A DM has to go out once per login, not once per sweep — so a plain "is this
 *  name online?" test is not enough, and neither is the online list's own
 *  `duration`: it is a wall-clock age, so a character who stays on for an hour
 *  keeps looking recent. This holds the previous observation of each target and
 *  reports the transitions.
 *
 *  A target seen for the very first time is only recorded, never reported. That
 *  covers both cold starts — every tracked character looks like an arrival after
 *  a restart — and someone adding a bounty on a character already online, where
 *  "has logged in" would simply be untrue.
 *
 *  A relog is caught by the duration going *backwards*: presence is rebuilt from
 *  the world poll, and a character who drops off and returns starts counting
 *  from zero again. That matters because the gap can easily be shorter than a
 *  sweep, so the absence itself is never observed.
 *
 *  One instance per world (the roster it reads is a world's), keyed by lowercase
 *  name. Not thread-safe: the online-list sweep is single-threaded by
 *  construction and is the only caller.
 */
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
