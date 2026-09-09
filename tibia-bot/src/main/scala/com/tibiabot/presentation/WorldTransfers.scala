package com.tibiabot.presentation

import com.tibiabot.Config
import com.tibiabot.domain.{Killers, WorldTransfer}

/** Deciding when a tracked character's former-world entry is an incoming transfer
 *  worth announcing.
 *
 *  Tibia clears the former-world field about 180 days after a transfer, so the
 *  field carries its own recency and no before/after baseline is needed: if it is
 *  set, they moved within the last six months. What it does not carry is *when* in
 *  that window, so the first sighting of a character cannot tell yesterday's move
 *  from one five months old — only that a later change to the list is a new move.
 *  Nor does it carry whether we have already said so, which is what the
 *  world-scoped `world_transfers` record is for. */
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

  /** The worlds to announce, or None when this is not an arrival or is one
   *  already posted for this world.
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

  /** The record this character's arrival was announced under, matched on their
   *  current name or any former one. A rename does not undo an announcement, but
   *  the record is keyed by whatever they were called when it was written — so
   *  looking only under the live name reads a renamed character as a stranger and
   *  reposts their months-old transfer. Former names come off the sheet fresh each
   *  poll, so this holds across a restart with nothing extra stored.
   *
   *  A record under the live name wins over one under a former name, and among
   *  former names the most recently detected wins. */
  def postedFor(records: List[WorldTransfer], charName: String, formerNames: List[String]): Option[WorldTransfer] = {
    val stale = staleKeys(records, charName, formerNames).map(_.toLowerCase).toSet
    records
      .find(_.name.equalsIgnoreCase(charName))
      .orElse(mostRecent(records.filter(r => stale.contains(r.name.trim.toLowerCase))))
  }

  /** The keys this character's arrival is filed under that are no longer their
   *  name — what a rename leaves behind, and what has to be deleted once the
   *  record has been moved.
   *
   *  A former name equal to the current one is dropped: the sheet does list a name
   *  a character has taken back, and treating that as stale would move a record
   *  onto itself. */
  def staleKeys(records: List[WorldTransfer], charName: String, formerNames: List[String]): List[String] = {
    val live = charName.trim.toLowerCase
    val previous = formerNames.map(_.trim.toLowerCase).filter(n => n.nonEmpty && n != live).toSet
    records.map(_.name).filter(n => previous.contains(n.trim.toLowerCase)).distinct
  }

  /** The list with every record filed under a former name of `charName` collapsed
   *  onto `charName`.
   *
   *  The worlds and the detection time are carried across untouched: it is the
   *  same arrival either way, and re-stamping it would push the retention prune
   *  out past the point where the former-world field it exists to suppress has
   *  cleared on its own. */
  def applyRename(records: List[WorldTransfer], charName: String, formerNames: List[String]): List[WorldTransfer] = {
    val stale = staleKeys(records, charName, formerNames).map(_.toLowerCase).toSet
    if (stale.isEmpty) records
    else {
      val isStale = (r: WorldTransfer) => stale.contains(r.name.trim.toLowerCase)
      val winner = records
        .find(_.name.equalsIgnoreCase(charName))
        .orElse(mostRecent(records.filter(isStale)))
        .map(_.copy(name = charName.toLowerCase))
      winner.toList ::: records.filterNot(r => isStale(r) || r.name.equalsIgnoreCase(charName))
    }
  }

  /** Latest by detection time, without needing an Ordering for ZonedDateTime. */
  private def mostRecent(records: List[WorldTransfer]): Option[WorldTransfer] =
    records.reduceOption((a, b) => if (b.detectedAt.isAfter(a.detectedAt)) b else a)

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

  /** Which side an arrival is announced as, and so which arrow it gets.
   *
   *  The same three cases the embed colour already distinguishes, and no more:
   *  the activity block only posts a transfer for somebody tracked or for a
   *  notable stranger, so a character who is neither hunted nor allied here is
   *  necessarily the stranger. That is also why activityColor's neutral yellow
   *  — a *tracked* player doing something involving an untracked guild — cannot
   *  come up on this path, and three thumbnails cover it. */
  sealed trait Side
  object Side {
    case object Hunted extends Side
    case object Allied extends Side
    case object Neutral extends Side
  }

  /** Hunted wins over allied, matching activityColor's ordering: a character on
   *  both lists reads as the threat in every other notification too. */
  def side(hunted: Boolean, allied: Boolean): Side =
    if (hunted) Side.Hunted else if (allied) Side.Allied else Side.Neutral

  /** The configured thumbnail for a side. */
  def thumbnail(side: Side): String = side match {
    case Side.Hunted  => Config.worldTransferRed
    case Side.Allied  => Config.worldTransferGreen
    case Side.Neutral => Config.worldTransferGrey
  }

  /** Classify and map to the configured thumbnail in one call — the form the
   *  activity call site uses. */
  def thumbnail(hunted: Boolean, allied: Boolean): String = thumbnail(side(hunted, allied))
}
