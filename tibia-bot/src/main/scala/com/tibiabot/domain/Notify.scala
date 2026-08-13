package com.tibiabot.domain

import java.time.Instant

/** The two DM subscriptions behind the notification-channel autoroles.
 *
 *  Both roles used to be decoration — pressing the button granted a role that
 *  nothing ever pinged. They now stand for a standing DM subscription instead,
 *  and the role is kept only as the visible marker of one, so the `<@&role>`
 *  lines in the notifications embed still mean something.
 *
 *  Scoped to a guild *and* a world: the button lives under a world's own
 *  notifications embed, and "enemies" is whatever that guild's hunted lists say
 *  — the same threshold means two different things in two servers.
 */
final case class MasslogSub(
  id: Long,
  guildId: String,
  world: String,
  userId: String,
  /** How many enemies logging in inside the window counts as a mass log. */
  threshold: Int,
  enabled: Boolean,
  mutedUntil: Option[Instant],
  lastNotified: Option[Instant]
)

/** One tracked character. A user may hold several — pressing the Bounty button
 *  again adds another rather than replacing the one before it. */
final case class BountySub(
  id: Long,
  guildId: String,
  world: String,
  userId: String,
  character: String,
  /** Silence after a login DM, so a relog or a flapping connection doesn't
   *  produce a stream of them. */
  cooldownMinutes: Int,
  enabled: Boolean,
  mutedUntil: Option[Instant],
  lastNotified: Option[Instant]
)

/** Bounds and parsing for the numbers these modals ask for. Kept apart from the
 *  handlers so the "what counts as valid" answer is in one place and testable —
 *  the same threshold is entered on subscribe and re-entered later from the DM's
 *  Threshold button, and those two must not drift. */
object NotifySettings {

  /** Below five people logging in together isn't a mass log, it's a guild
   *  hunting — the floor exists so nobody subscribes themselves to a DM every
   *  few minutes. */
  val MinThreshold: Int = 5
  val MaxThreshold: Int = 100
  val DefaultThreshold: Int = 8

  val MinCooldownMinutes: Int = 1
  val MaxCooldownMinutes: Int = 1440
  val DefaultCooldownMinutes: Int = 10

  /** Tibia caps character names at 29 characters. */
  val MaxCharacterName: Int = 29

  /** The suggestion under the threshold box. People have no feel for what a
   *  number means here until they have been woken by it once. */
  val ThresholdHelp: String =
    s"$MinThreshold-8 a small group, 9-14 a war party, 15+ a full mass log."

  val CooldownHelp: String =
    s"Silence after a login DM, in minutes. Default $DefaultCooldownMinutes."

  def parseThreshold(raw: String): Either[String, Int] =
    parseBounded(raw, MinThreshold, MaxThreshold, "threshold")

  def parseCooldown(raw: String): Either[String, Int] =
    parseBounded(raw, MinCooldownMinutes, MaxCooldownMinutes, "cooldown")

  private def parseBounded(raw: String, min: Int, max: Int, what: String): Either[String, Int] =
    raw.trim.toIntOption match {
      case None                          => Left(s"That $what needs to be a whole number.")
      case Some(value) if value < min    => Left(s"The lowest $what is **$min**.")
      case Some(value) if value > max    => Left(s"The highest $what is **$max**.")
      case Some(value)                   => Right(value)
    }

  /** Tibia names are letters, spaces, apostrophes and hyphens. Rejecting the
   *  rest here keeps a typo from becoming a subscription that can never fire. */
  def parseCharacter(raw: String): Either[String, String] = {
    val name = raw.trim.replaceAll("\\s+", " ")
    if (name.isEmpty) Left("That character name is empty.")
    else if (name.length > MaxCharacterName) Left(s"Character names stop at **$MaxCharacterName** characters.")
    else if (!name.matches("[A-Za-z'\\- ]+")) Left("That doesn't look like a character name.")
    else Right(name)
  }
}

/** The lengths a Mute button offers. One list for both notification kinds —
 *  muting a mass-log alert and muting a bounty are the same decision, and two
 *  different sets of options would only be two things to remember. */
object MuteScale {

  /** The picker value meaning "end the mute now". Offered alongside the lengths
   *  so a 24-hour mute has a way back that isn't switching the whole
   *  subscription off and on again. */
  val Unmute: Int = 0

  /** Minutes paired with what the picker calls them, shortest first. */
  val options: List[(Int, String)] = List(
    15   -> "15 minutes",
    30   -> "30 minutes",
    60   -> "1 hour",
    120  -> "2 hours",
    720  -> "12 hours",
    1440 -> "24 hours"
  )

  def label(minutes: Int): String =
    options.collectFirst { case (m, text) if m == minutes => text }.getOrElse(s"$minutes minutes")

  /** Reads a select-menu value back. Anything not on the list is refused rather
   *  than clamped — a value that isn't one of ours means a malformed
   *  interaction, not a preference. */
  def parse(value: String): Option[Int] =
    value.trim.toIntOption.filter(minutes => minutes == Unmute || options.exists(_._1 == minutes))
}

/** Whether a subscription is allowed to fire right now.
 *
 *  Three separate silences, deliberately kept distinct: `enabled` is the user
 *  switching the whole thing off from a DM, `mutedUntil` is them asking for
 *  quiet until a moment they picked, and the cooldown is this bot refusing to
 *  say the same thing twice in a row. Only the last one is automatic, so only
 *  the last one may be shortened without the user asking. */
object NotifyDecision {

  def due(
    enabled: Boolean,
    mutedUntil: Option[Instant],
    lastNotified: Option[Instant],
    cooldownMinutes: Int,
    now: Instant
  ): Boolean =
    enabled &&
      !mutedUntil.exists(_.isAfter(now)) &&
      !lastNotified.exists(_.isAfter(now.minusSeconds(cooldownMinutes.toLong * 60)))
}
