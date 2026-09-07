package com.tibiabot.hunted

import com.tibiabot.domain.Players

/** Whether a listed entry has stopped being worth keeping, and why.
 *
 *  Two things retire an entry, and both mean the character behind it is no longer
 *  the character somebody listed:
 *
 *   - '''Traded.''' The account changed hands, so whatever the entry was recorded
 *     for — a war, a grudge, an alliance — no longer describes whoever plays it.
 *   - '''Gone from the worlds this server tracks.''' Nothing the bot watches will
 *     ever mention them again, so the entry can only sit there.
 *
 *  Nothing is removed on either. The finding goes to the admin channel and a
 *  person decides, because both are judgements about intent that the bot does not
 *  have: a traded character may be exactly who somebody meant to keep watching,
 *  and a world move may be about to be reversed.
 *
 *  Pure and free of JDA so the rules can be read on their own — which matters,
 *  because getting the traded one backwards proposes deleting entries somebody
 *  deliberately added.
 */
object ListReview {

  sealed trait Finding { def reason: String }
  object Finding {
    /** Not traded when they were added, traded now. */
    case object Traded extends Finding { val reason = "traded" }
    /** On a world this server does not track. */
    final case class MovedWorld(world: String) extends Finding { val reason = "world" }
  }

  /** What, if anything, retires this entry.
   *
   *  `world` and `traded` come from the character's current sheet;
   *  `trackedWorlds` is what the guild has set up. Traded is checked first only
   *  because it is the more final of the two — a world move can be undone by
   *  moving back, an account changing hands cannot.
   */
  def review(entry: Players, traded: Boolean, world: String, trackedWorlds: Set[String]): Option[Finding] =
    if (alreadyFlagged(entry)) None
    else if (becameTraded(entry, traded)) Some(Finding.Traded)
    else if (leftTrackedWorlds(world, trackedWorlds)) Some(Finding.MovedWorld(world))
    else None

  /** Said once. An entry carrying a reason has already been announced, and a
   *  second notice for the same doomed entry says nothing new. */
  private def alreadyFlagged(entry: Players): Boolean = entry.flaggedReason.nonEmpty

  /** The transition, not the state.
   *
   *  A player already traded when they were listed is never flagged for it,
   *  however long they stay on the list — somebody added them knowing, and the
   *  bot has no business second-guessing that. Only crossing from not-traded to
   *  traded while listed says the entry has outlived what it was for.
   */
  private def becameTraded(entry: Players, traded: Boolean): Boolean =
    traded && !entry.tradedWhenAdded

  /** An empty world never counts: it means the sheet did not say, not that the
   *  character is nowhere. Flagging on a missing field would retire entries over
   *  a parse gap. */
  private def leftTrackedWorlds(world: String, trackedWorlds: Set[String]): Boolean =
    world.nonEmpty && trackedWorlds.nonEmpty && !trackedWorlds.exists(_.equalsIgnoreCase(world))
}
