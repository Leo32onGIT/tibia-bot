package com.tibiabot.presentation

import com.tibiabot.domain.PlayerCache

import java.time.ZonedDateTime

/** Pure mappings for the guild-tracking activity notifications (a player
 *  joining / leaving / swapping a hunted or allied guild). Extracted from the
 *  activity block in TibiaBot, where both were repeated for each activity case. */
object GuildActivity {

  /** Embed colour for a guild-join/swap activity: a hunted guild is red, an
   *  allied guild is green, and anything else is yellow. */
  def activityColor(huntedGuild: Boolean, alliedGuild: Boolean): Int =
    if (huntedGuild) 13773097 else if (alliedGuild) 36941 else 14397256

  /** Dark grey — what a neutral with no guild gets in the deaths channel (the
   *  `embedColor` default in TibiaBot's death block), reused for an event about
   *  somebody in no tracked guild and on no tracked list, so a stranger reads
   *  the same way here as their deaths do.
   *
   *  Not activityColor's neutral yellow, which is the colour of a *tracked*
   *  player doing something involving an untracked guild — a different thing
   *  from someone the server has no interest in at all. */
  val untrackedColor: Int = 3092790

  /** The guild's tracked-status label, used in the activity description. */
  def guildType(huntedGuild: Boolean, alliedGuild: Boolean): String =
    if (huntedGuild) "hunted" else if (alliedGuild) "allied" else "neutral"

  /** The tracked-activity row a rename moves: the name it is stored under now,
   *  when it was last touched (the delay the rename notice waits out), and the
   *  guild it is still recorded in. A rename carries the recorded guild across
   *  untouched — deciding whether the character has swapped guild belongs to the
   *  next poll, which is the only place that posts the swap. */
  final case class Rename(oldName: String, previousUpdate: ZonedDateTime, guild: String)

  /** Whether one of `charName`'s former names identifies a tracked-activity row
   *  that is really this character under an older name.
   *
   *  A row matching a former name is not proof of a rename: renaming frees the old
   *  name for somebody else, and renaming *their* row drops them from the list —
   *  so the next poll sees them as untracked, posts them as joining, adds them
   *  back, and posts the join forever. Two checks rule that out:
   *
   *   - the character is already tracked under their current name, so a row under
   *     a former name must belong to somebody else;
   *   - the former name is online right now, which only a different living
   *     character can be.
   *
   *  Also skips a character carrying their own current name in former_names (cause
   *  unclear, possibly a namelock), which is not a rename. */
  def renameFromFormerNames(
    activity: List[PlayerCache],
    charName: String,
    formerNames: List[String],
    stillOnline: String => Boolean
  ): Option[Rename] = {
    val trackedUnderCurrentName = activity.exists(_.name.equalsIgnoreCase(charName))
    val selfReferential = formerNames.exists(_.equalsIgnoreCase(charName))
    if (charName.isEmpty || trackedUnderCurrentName || selfReferential) None
    else {
      val reclaimable = formerNames.filter(name => name.nonEmpty && !stillOnline(name))
      activity
        .find(row => reclaimable.exists(_.equalsIgnoreCase(row.name)))
        .map(row => Rename(row.name, row.updatedTime, row.guild))
    }
  }

  /** Move the `oldName` row onto `newName`, applied to whatever the list looks
   *  like at write time rather than to the snapshot the decision was made from.
   *  Collapses any same-name rows the move would otherwise leave behind. */
  def applyRename(
    activity: List[PlayerCache],
    oldName: String,
    newName: String,
    formerNames: List[String],
    now: ZonedDateTime
  ): List[PlayerCache] =
    activity.find(_.name.equalsIgnoreCase(oldName)) match {
      case None => activity
      case Some(row) =>
        val renamed = row.copy(name = newName, formerNames = formerNames, updatedTime = now)
        renamed :: activity.filterNot(other =>
          other.name.equalsIgnoreCase(oldName) || other.name.equalsIgnoreCase(newName)
        )
    }
}
