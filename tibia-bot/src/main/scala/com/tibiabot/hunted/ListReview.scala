package com.tibiabot.hunted

import com.tibiabot.domain.Players

/** Whether a listed entry has stopped being worth keeping, and why.
 *
 *  Four things retire one, and each means the character behind it is no longer
 *  the character somebody listed:
 *
 *   - '''Gone.''' The name resolves to nobody. Deleted, or renamed and not seen
 *     since — indistinguishable from the old name, and the same outcome either
 *     way: the entry will never match a live character again.
 *   - '''Scheduled for deletion.''' The sheet says so itself, with a date. The
 *     same end as Gone, seen coming rather than after the fact.
 *   - '''Traded.''' The account changed hands, so whatever the entry was recorded
 *     for — a war, a grudge, an alliance — no longer describes whoever plays it.
 *   - '''Gone from the worlds this server tracks.''' Nothing the bot watches will
 *     ever mention them again, so the entry can only sit there.
 *
 *  Nothing is removed on any of them. The finding goes to the admin channel and a
 *  person decides, because these are judgements about intent that the bot does
 *  not have: a traded character may be exactly who somebody meant to keep
 *  watching, a world move may be about to be reversed, and a scheduled deletion
 *  is cancelled by logging in.
 *
 *  Pure and free of JDA so the rules can be read on their own — which matters,
 *  because getting the traded one backwards proposes deleting entries somebody
 *  deliberately added.
 */
object ListReview {

  sealed trait Finding { def reason: String }
  object Finding {
    /** The character no longer resolves at all.
     *
     *  Deliberately not called "deleted". A name that answers to nobody has been
     *  deleted *or* renamed and not seen since, and from the old name the two are
     *  indistinguishable — so the notice says what is actually known. Either way
     *  the entry matches no live character and never will again. */
    case object Gone extends Finding { val reason = "gone" }
    /** Scheduled for deletion, and still fetchable. Positive evidence, unlike
     *  Gone: the sheet itself says so, with a date. */
    final case class ScheduledForDeletion(date: String) extends Finding { val reason = "deletion" }
    /** Not traded when they were added, traded now. */
    case object Traded extends Finding { val reason = "traded" }
    /** On a world this server does not track. */
    final case class MovedWorld(world: String) extends Finding { val reason = "world" }
  }

  /** What, if anything, retires this entry.
   *
   *  `traded`, `world` and `deletionDate` come from the character's current
   *  sheet; `trackedWorlds` is what the guild has set up. Ordered by how final
   *  each is: a scheduled deletion ends the character, a trade ends who they
   *  were, and a world move is the one thing that can simply be undone.
   */
  def review(entry: Players, traded: Boolean, world: String, trackedWorlds: Set[String],
             deletionDate: Option[String] = None): Option[Finding] =
    if (alreadyFlagged(entry)) None
    else deletionDate.filter(_.nonEmpty).map(Finding.ScheduledForDeletion.apply)
      .orElse(if (becameTraded(entry, traded)) Some(Finding.Traded) else None)
      .orElse(if (leftTrackedWorlds(world, trackedWorlds)) Some(Finding.MovedWorld(world)) else None)

  /** A lookup that came back saying there is no such character.
   *
   *  Only ever called for a *failed-to-find*, never for a failed request — the
   *  difference is what PlayerLookup exists to keep, and treating an unanswered
   *  lookup as a missing character would retire entries over a 503.
   */
  def reviewMissing(entry: Players): Option[Finding] =
    if (alreadyFlagged(entry)) None else Some(Finding.Gone)

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
