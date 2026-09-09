package com.tibiabot.highscores

import com.tibiabot.domain.{HighscoreEvent, HighscoreRecord}
import com.tibiabot.tibiadata.HighscoreCategory
import com.tibiabot.tibiadata.response.HighscoreEntry

import java.time.{Duration, Instant}

/** What one snapshot's reading of a character means against what was stored. */
sealed trait HighscoreChange {
  /** Only a genuine advance is announced. Everything else is recorded quietly. */
  def isAdvance: Boolean = false
}

object HighscoreChange {

  /** No stored score for this character in this list. Records, never posts: a
   *  character entering the top thousand because others dropped out has not
   *  advanced at anything, and on a cold start every row would look like one.
   *
   *  This is also what makes renames and world transfers need no handling of
   *  their own — both read as a departure plus a first sighting. */
  case object FirstSighting extends HighscoreChange

  /** Same score as last time — the common case by far, since only a handful of
   *  a thousand move in any hour. */
  case object Unchanged extends HighscoreChange

  final case class Advanced(from: Long, to: Long) extends HighscoreChange {
    override def isAdvance: Boolean = true
  }

  /** Higher than the stored score, but the stored score is too old to be a
   *  baseline: the character was out of the list and has come back. The jump is
   *  not one event, so it is taken as the new baseline in silence. */
  final case class Rebaselined(from: Long, to: Long) extends HighscoreChange

  /** Skill lost, almost always to a death. Real data for the Statistics
   *  channel, and not something to announce. */
  final case class Declined(from: Long, to: Long) extends HighscoreChange
}

/** The advance rules, with no database or network in sight.
 *
 *  Every rule here exists to answer one question — is this reading a thing that
 *  happened, or an artefact of a list only a thousand deep? */
object HighscoreDiff {

  /** How stale a stored score may be and still serve as a baseline.
   *
   *  Three days: comfortably more than the hour or so between snapshots (so an
   *  ordinary gap, a restart, or a few failed fetches never costs a real
   *  advance), and comfortably less than the time it takes someone to fall out
   *  of the top thousand and climb back with several levels in hand. */
  val MaxBaselineAge: Duration = Duration.ofDays(3)

  /** The stored key for a character name. Lowercased, because Tibia treats
   *  "Bubble" and "bubble" as one character and the table must too. */
  def key(name: String): String = name.toLowerCase

  def classify(
      previous: Option[HighscoreRecord],
      entry: HighscoreEntry,
      snapshotAt: Instant,
      maxBaselineAge: Duration = MaxBaselineAge
  ): HighscoreChange =
    previous match {
      case None => HighscoreChange.FirstSighting
      case Some(record) if entry.value == record.score => HighscoreChange.Unchanged
      // Checked before staleness: a decline from an old baseline is still just a
      // decline, and neither posts.
      case Some(record) if entry.value < record.score => HighscoreChange.Declined(record.score, entry.value)
      case Some(record) if isStale(record.lastSeen, snapshotAt, maxBaselineAge) =>
        HighscoreChange.Rebaselined(record.score, entry.value)
      case Some(record) => HighscoreChange.Advanced(record.score, entry.value)
    }

  /** Every advance in one list's page-set, in the order they should be
   *  announced.
   *
   *  Input order is preserved, and the endpoint returns rows by rank, so the
   *  highest-placed character reads first — which is the order a reader of the
   *  Levels channel would expect a batch of skill-ups to arrive in.
   *
   *  Yields nothing at all for a list that does not post (experience), so a
   *  caller cannot announce one by forgetting to check. */
  def advances(
      world: String,
      category: HighscoreCategory,
      posts: Boolean,
      previous: Map[String, HighscoreRecord],
      entries: List[HighscoreEntry],
      snapshotAt: Instant,
      maxBaselineAge: Duration = MaxBaselineAge
  ): List[HighscoreEvent] =
    if (!posts) Nil
    else
      entries.flatMap { entry =>
        classify(previous.get(key(entry.name)), entry, snapshotAt, maxBaselineAge) match {
          case HighscoreChange.Advanced(from, to) =>
            Some(HighscoreEvent(
              world = world,
              category = category.slug,
              name = key(entry.name),
              displayName = entry.name,
              vocation = entry.vocation,
              level = entry.level,
              previousScore = from,
              score = to,
              observed = snapshotAt
            ))
          case _ => None
        }
      }

  private def isStale(lastSeen: Instant, snapshotAt: Instant, maxBaselineAge: Duration): Boolean =
    lastSeen.isBefore(snapshotAt.minus(maxBaselineAge))
}
