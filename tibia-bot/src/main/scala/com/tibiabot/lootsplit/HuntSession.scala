package com.tibiabot.lootsplit

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** One party member's block in a Tibia party hunt analyser.
 *
 *  `balance` is `loot - supplies` as the client reported it rather than as this
 *  recomputes it: the client is the authority on what a member actually made, and
 *  a session where the two disagree is a session where the paste was edited.
 */
final case class HuntMember(
  name: String,
  loot: Long,
  supplies: Long,
  balance: Long,
  damage: Long,
  healing: Long,
  leader: Boolean
)

/** Who owes what to whom, in the form the game accepts: the payer types
 *  `transfer <amount> to <name>` into the party channel. */
final case class HuntTransfer(from: String, to: String, amount: Long) {
  /** Exactly what gets typed in-game — no thousands separators, because this is a
   *  command rather than a number to read. */
  def command: String = s"transfer $amount to $to"
}

/** A parsed party hunt analyser, plus everything derived from it.
 *
 *  Every derived value is computed here rather than in the embed, so the split can
 *  be tested without a Discord message — and so the numbers cannot drift between
 *  the DM's reply and `/lootsplit`'s.
 *
 *  ==Whole gold, floored==
 *  A four-way split of an odd balance does not come out even, and the game only
 *  moves whole gold. Every division here floors, and the leftover — at most one
 *  gold per member — stays with whoever was holding it. That is what the client
 *  itself does, and matching it is the point: a split that disagreed with the
 *  game's own arithmetic by a gold would be argued about.
 */
final case class HuntSession(
  from: Option[LocalDateTime],
  to: Option[LocalDateTime],
  /** The client's own `Session:` line, e.g. "02:17h". Kept verbatim rather than
   *  re-rendered from the timestamps: it is what the person pasting is looking at. */
  sessionLabel: String,
  lootType: String,
  loot: Long,
  supplies: Long,
  balance: Long,
  members: List[HuntMember]
) {

  /** Seconds between the two header timestamps.
   *
   *  Not taken from the `Session:` label, which is rounded down to the minute: a
   *  2h17m40s hunt reads "02:17h" there, and pricing the loot against 2h17m00s
   *  overstates the hourly rate by half a percent. Empty when the header did not
   *  parse, which is the only reason the hourly figures can be missing.
   */
  def durationSeconds: Option[Long] =
    for {
      start <- from
      end <- to
      seconds = ChronoUnit.SECONDS.between(start, end)
      if seconds > 0
    } yield seconds

  def lootPerHour: Option[Long] = durationSeconds.map(seconds => Math.floorDiv(loot * 3600, seconds))

  /** What each member walks away with once the party is square — the number the
   *  transfers below are all working towards. */
  def individualBalance: Long =
    if (members.isEmpty) balance else Math.floorDiv(balance, members.size)

  def totalDamage: Long = members.map(_.damage).sum

  def totalHealing: Long = members.map(_.healing).sum

  /** Each member's share of the party's damage, as a percentage, biggest first.
   *
   *  Empty when nobody dealt any — a fishing or a shopping session — rather than a
   *  list of zeroes, so the caller can leave the field off entirely instead of
   *  printing a column that says nothing. `sortBy` is stable, so members who dealt
   *  exactly the same stay in the order they were pasted in.
   */
  def damageShares: List[(HuntMember, Double)] = shares(_.damage, totalDamage)

  def healingShares: List[(HuntMember, Double)] = shares(_.healing, totalHealing)

  private def shares(of: HuntMember => Long, total: Long): List[(HuntMember, Double)] =
    if (total <= 0) Nil
    else members.map(member => member -> (of(member) * 100.0 / total)).sortBy(-_._2)

  /** What one member is owed (positive) or must pay out (negative), in whole gold.
   *
   *  `floorDiv` of the scaled-up difference rather than two separate divisions:
   *  computing `individualBalance - member.balance` from the already-floored
   *  individual figure loses the half-gold that the real split carries, and with
   *  four members that is enough to move every transfer by one.
   */
  private def owed(member: HuntMember): Long =
    Math.floorDiv(balance - members.size * member.balance, members.size)

  /** The transfers that square the party up, grouped in the order the members were
   *  pasted — which is the order the client lists them, and so the order the person
   *  reading recognises.
   *
   *  Greedy rather than optimal: each payer covers the members still short, in turn,
   *  until their surplus runs out. For the overwhelmingly common shape — one leader
   *  holding the loot and everybody else short — that is one transfer per member,
   *  which is already the fewest possible. A minimal-transfer solution for the rare
   *  many-payer session is a subset-sum problem, and the gain would be at most a
   *  line or two off a list nobody is paying per-line for.
   *
   *  Rounding leaves the payers owing up to one gold more in total than the
   *  receivers are short. That gold is simply never moved: no transfer is emitted
   *  for a surplus with nobody left to pay it to.
   */
  def transfers: List[HuntTransfer] = {
    if (members.size < 2) Nil
    else {
      val payers = members.filter(member => owed(member) < 0)
      // Mutable only within this method: a fold threading both the remaining
      // shortfalls and the growing list reads worse than the two lines it saves.
      var shortfalls = members.collect { case member if owed(member) > 0 => member.name -> owed(member) }
      val settled = List.newBuilder[HuntTransfer]
      payers.foreach { payer =>
        var budget = -owed(payer)
        shortfalls = shortfalls.flatMap { case (name, short) =>
          val amount = math.min(budget, short)
          if (amount <= 0) Some(name -> short)
          else {
            settled += HuntTransfer(payer.name, name, amount)
            budget -= amount
            if (short > amount) Some(name -> (short - amount)) else None
          }
        }
      }
      settled.result()
    }
  }

  /** The transfers one payer has to type, in payer order. What the embed draws a
   *  field from — a payer with nothing to send never appears. */
  def transfersByPayer: List[(String, List[HuntTransfer])] = {
    val grouped = transfers.groupBy(_.from)
    members.map(_.name).distinct.flatMap(name => grouped.get(name).map(name -> _))
  }
}
