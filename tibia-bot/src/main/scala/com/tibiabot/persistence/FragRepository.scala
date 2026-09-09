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

  /** One world's frags for one day, counted and ranked.
   *
   *  Returns [[com.tibiabot.domain.FragTally.empty]] rather than None for a
   *  quiet day, since "no frags" and "no rows" are the same fact here. */
  def tally(guildId: String, world: String, saveDay: LocalDate, topFraggers: Int): FragTally

  def removeExpired(guildId: String, before: LocalDate): Unit
}
