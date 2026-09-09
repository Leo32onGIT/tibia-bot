package com.tibiabot.persistence

import com.tibiabot.domain.{FragEvent, FragTally}

import java.time.LocalDate

/** Persistence port for the per-guild `frag_event` table.
 *
 *  Guild-scoped rather than world-scoped, unlike the highscore and kill
 *  statistics tables beside it in the statistics feature. It has to be: whether
 *  a death was a frag at all, and which side got it, is decided by *that
 *  guild's* hunted and allied lists. Two servers watching the same war from
 *  opposite sides read the same death as opposite frags, and both are right.
 *
 *  So the rows live in the guild's own database, next to `hunted_players` and
 *  `worlds`, and a guild is served by exactly one bot so nothing contends. */
trait FragRepository {

  /** File one death's frags — one row per player killer.
   *
   *  Idempotent on (world, killer, victim, occurred_at), which is a natural key:
   *  one player cannot kill the same player twice in the same instant. That
   *  makes a reprocessed death a no-op rather than a doubled tally. */
  def record(guildId: String, events: List[FragEvent]): Unit

  /** Attach the deaths-channel message to a death already filed.
   *
   *  Separate from [[record]] because the id does not exist yet when the frags
   *  are written: the post is still queued, and Discord only hands the id back
   *  once it has been sent. A death that is never posted simply keeps the empty
   *  string it was filed with. */
  def attachDeathMessage(guildId: String, world: String, victim: String,
                         occurredAt: java.time.Instant, messageId: String): Unit

  /** One world's whole day for one guild: both counts, the merged fragger list,
   *  the repeat victims, and the biggest kill on each side.
   *
   *  Returns [[com.tibiabot.domain.FragTally.empty]] rather than None for a
   *  quiet day, since "no frags" and "no rows" are the same fact here. */
  def tally(guildId: String, world: String, saveDay: LocalDate,
            topFraggers: Int, topRepeats: Int): FragTally

  def removeExpired(guildId: String, before: LocalDate): Unit
}
