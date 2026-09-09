package com.tibiabot.domain

import java.time.{Instant, LocalDate}

/** Which side of a guild's lists the victim of a player kill was on.
 *
 *  The killer's side is the opposite, and is not stored: a hunted player dying
 *  to somebody means that somebody was fighting for us, whether or not they are
 *  on any list. Recording the victim is what makes the row true — the victim was
 *  classified at the moment of the death, from that guild's own lists, which is
 *  exactly what the deaths channel already decided in order to colour the post.
 *
 *  Neutral victims are not frags and are never recorded. */
sealed abstract class FragSide(val stored: String)

object FragSide {
  /** A hunted player died. Somebody on our side got a frag. */
  case object Enemy extends FragSide("enemy")

  /** An allied player died. Somebody on their side got a frag. */
  case object Ally extends FragSide("ally")

  def fromStored(value: String): Option[FragSide] =
    List(Enemy, Ally).find(_.stored == value)
}

/** One player killing another, on one world, on one server-save day.
 *
 *  Guild-scoped and unbackfillable: nothing persisted deaths before this
 *  existed, so the tally starts on the day it deploys and there is no way to
 *  make it start earlier.
 *
 *  `killer` and `victim` are stored with the casing tibia.com showed, since that
 *  is what a post renders; grouping is done case-insensitively in the query, the
 *  same split every other name in this bot keeps. */
final case class FragEvent(
    world: String,
    saveDay: LocalDate,
    killer: String,
    victim: String,
    side: FragSide,
    occurredAt: Instant
)

/** A day's frags for one guild on one world.
 *
 *  Two counts and two leaderboards, because a war has two sides and a server
 *  wants to see both. `enemiesKilled` counts hunted players who died —
 *  our side's work — and `alliesKilled` counts allied players who died. The
 *  leaderboards name who did the killing in each case, so `topAllied` are the
 *  players who killed hunteds and `topEnemy` those who killed allies. */
final case class FragTally(
    enemiesKilled: Int,
    alliesKilled: Int,
    topAllied: List[(String, Int)],
    topEnemy: List[(String, Int)]
) {
  def isEmpty: Boolean = enemiesKilled == 0 && alliesKilled == 0
  def nonEmpty: Boolean = !isEmpty
}

object FragTally {
  val empty: FragTally = FragTally(0, 0, Nil, Nil)

  /** How many names each side's leaderboard shows. */
  val TopFraggers: Int = 10
}
