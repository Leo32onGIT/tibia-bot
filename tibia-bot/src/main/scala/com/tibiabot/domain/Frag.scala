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
 *  `victimLevel` is the level they died at, which is what "Top Enemy Killed"
 *  ranks on. `deathMessageId` is the deaths-channel post, so the daily summary
 *  can link back to it — empty when the death was never posted there, which
 *  happens whenever the channel is off, the level is under `deaths_min`, or the
 *  send failed.
 *
 *  `killer` and `victim` keep the casing tibia.com showed, since that is what a
 *  post renders; grouping is done case-insensitively in the query, the same
 *  split every other name in this bot keeps. */
final case class FragEvent(
    world: String,
    saveDay: LocalDate,
    killer: String,
    victim: String,
    victimLevel: Int,
    side: FragSide,
    occurredAt: Instant,
    deathMessageId: String
)

/** One row of a fragger list: who, and how many. */
final case class Fragger(name: String, side: FragSide, kills: Int)

/** An enemy who kept dying, and how often. */
final case class Repeat(name: String, level: Int, deaths: Int)

/** The biggest scalp of the day on one side.
 *
 *  `deathMessageId` is empty when that death was never posted, in which case the
 *  summary names the kill without offering a link to it. */
final case class TopKill(name: String, level: Int, side: FragSide, deathMessageId: String)

/** A day's frags for one guild on one world.
 *
 *  `enemiesKilled` and `alliesKilled` count *deaths* — a victim killed by eight
 *  people is one loss, not eight — while `fraggers` counts kills per killer, so
 *  the two deliberately do not sum to each other.
 *
 *  `fraggers` is already merged and ranked across both sides: each row carries
 *  its own side, so a reader can tell them apart without the list being split. */
final case class FragTally(
    enemiesKilled: Int,
    alliesKilled: Int,
    fraggers: List[Fragger],
    mostWanted: List[Repeat],
    topEnemyKilled: Option[TopKill],
    topAllyKilled: Option[TopKill]
) {
  def isEmpty: Boolean = enemiesKilled == 0 && alliesKilled == 0
  def nonEmpty: Boolean = !isEmpty
}

object FragTally {
  val empty: FragTally = FragTally(0, 0, Nil, Nil, None, None)

  /** How many names each side contributes to the merged fragger list, and how
   *  long Most Wanted runs. Five a side rather than ten overall, so a one-sided
   *  day cannot crowd the other side out of its own post. */
  val TopFraggers: Int = 5
  val TopRepeats: Int = 5
}
