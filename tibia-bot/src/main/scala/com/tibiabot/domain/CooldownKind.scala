package com.tibiabot.domain

import java.time.ZonedDateTime

/** A collectible this bot tracks a cooldown for.
 *
 *  Both kinds work the same way — a stamp per (user, tag), a DM when it runs
 *  out — and differ only in how long the timer runs and what the item is
 *  called, so they are one type with two cases rather than two parallel
 *  stacks.
 *
 *  `id` is a wire format twice over: it is what the `kind` column stores and
 *  what a component id carries, so it stays lowercase and fixed even if
 *  [[label]] is reworded.
 *
 *  Config-free like the rest of `domain` — the emoji, thumbnail and wiki link
 *  that go with a kind live in presentation.CooldownEmbeds.
 */
sealed abstract class CooldownKind(
  val id: String,
  val durationDays: Long,
  val label: String
) {

  /** Epoch-second (as a string, for Discord's `<t:..:R>`) at which a cooldown
   *  that started at `when` expires. */
  def expiresAtEpoch(when: ZonedDateTime): String =
    when.plusDays(durationDays).toEpochSecond.toString
}

object CooldownKind {

  case object Satchel extends CooldownKind("satchel", 30, "Galthen Satchel")
  case object DragonHead extends CooldownKind("dragonhead", 14, "Dragon Head")

  /** Satchel first: it is the older tracker, and this is the order the
   *  cooldown panel lays its buttons out in. */
  val all: List[CooldownKind] = List(Satchel, DragonHead)

  def parse(id: String): Option[CooldownKind] = all.find(_.id == id)
}
