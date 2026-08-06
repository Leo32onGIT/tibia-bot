package com.tibiabot.presentation

import com.tibiabot.domain.Killers

/** Deciding when a tracked character's former-world entry is an incoming transfer
 *  worth announcing.
 *
 *  Tibia clears the former-world field about 180 days after a transfer, so the
 *  field carries its own recency and no before/after baseline is needed: if it is
 *  set, they moved within the last six months. What it does not carry is *when* in
 *  that window, so the first sighting of a character cannot tell yesterday's move
 *  from one five months old — only that a later change to the list is a new move.
 *  Nor does it carry whether we have already said so, which is what the per-guild
 *  `world_transfers` record is for. */
object WorldTransfers {

  /** The worlds a character on `streamWorld` has recently come from.
   *
   *  Empty unless the character sheet still agrees they are on this world — a
   *  character who transferred *out* stays in the recently-online set for a few
   *  more polls, and their former worlds by then can include this one, which
   *  would otherwise read as an arrival. */
  def sources(charWorld: String, streamWorld: String, formerWorlds: List[String]): List[String] =
    if (!charWorld.equalsIgnoreCase(streamWorld)) Nil
    else formerWorlds.map(_.trim).filter(w => w.nonEmpty && !w.equalsIgnoreCase(streamWorld)).distinct

  /** The worlds to announce, or None when this is not an arrival or is one this
   *  guild has already posted.
   *
   *  `alreadyPosted` is the former-worlds list as it read when we last posted for
   *  this character, so a second transfer — which changes the list — is told apart
   *  from the one already announced. Compared as a set: the API's ordering is not
   *  guaranteed stable. */
  def unreported(
    charWorld: String,
    streamWorld: String,
    formerWorlds: List[String],
    alreadyPosted: Option[List[String]]
  ): Option[List[String]] = {
    val arrivedFrom = sources(charWorld, streamWorld, formerWorlds)
    val posted = alreadyPosted.map(_.map(_.toLowerCase).toSet)
    if (arrivedFrom.isEmpty || posted.contains(arrivedFrom.map(_.toLowerCase).toSet)) None
    else Some(arrivedFrom)
  }

  /** The level at or above which a character in no tracked guild and on no
   *  tracked list is still worth announcing the arrival of.
   *
   *  Flat, and deliberately not tied to the levels/deaths channel filters: those
   *  are set for a different purpose and accept values from 1, so borrowing them
   *  would let a server that wants to see every level-up turn this into a feed
   *  of every stranger who moved house. A stranger arriving earns a line in a
   *  channel about hunted and allied players only when they are the kind of
   *  character the whole world would notice. */
  val UntrackedMinLevel = 1000

  /** "Nefera", "Nefera and Antica", "Nefera, Antica and Bona". */
  def sourceText(worlds: List[String]): String = Killers.joinNatural(worlds)
}
