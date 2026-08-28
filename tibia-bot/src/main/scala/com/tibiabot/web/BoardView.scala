package com.tibiabot.web

import com.tibiabot.domain.Stamina

/** Everything the board page needs that isn't a spawn: what the viewer has left
 *  to spend and the limits their claims are bounded by.
 *
 *  `remainingMinutes` is already floored at zero by
 *  [[com.tibiabot.domain.Stamina]], and is `None` when the guild has stamina
 *  turned off entirely — which the page reads as "no tank to show" rather than
 *  as zero, since the two mean opposite things.
 */
final case class BoardLimits(
  remainingMinutes: Option[Int],
  budgetMinutes: Option[Int],
  maxDurationMinutes: Int,
  defaultDurationMinutes: Int,
  resetsAt: java.time.ZonedDateTime
) {
  /** The longest claim this viewer could actually make right now: the guild's
   *  ceiling, or what is left in the tank if that is lower.
   *
   *  Both limits are real and either can bind, so the page shows whichever
   *  does rather than pretending there is only one.
   *
   *  Not rounded to a whole [[BoardLimits.Step]]. It used to be, which quietly
   *  stranded up to half an hour of somebody's tank — a hundred and nineteen
   *  minutes left offered ninety, and the other twenty-nine could not be spent
   *  at all. The service grants whatever remains against a claim of any length,
   *  so the ceiling here is the true one and it is the *stepper* that moves in
   *  half hours, clamped to this.
   */
  def claimableMinutes: Int =
    math.max(0, remainingMinutes.fold(maxDurationMinutes)(math.min(maxDurationMinutes, _)))

  /** Which limit is doing the stopping, for the wording beside the control. */
  def boundBy: String =
    if (remainingMinutes.exists(_ < maxDurationMinutes)) "stamina" else "server limit"
}

object BoardLimits {
  /** Claims move in half hours, matching the schedule picker's own granularity
   *  (`RespawnSchedule.upcomingStarts`) so a booking and an ad-hoc claim can
   *  never land on times that don't line up. */
  val Step = 30

  def from(stamina: Stamina, maxDuration: Int, defaultDuration: Int,
           resetsAt: java.time.ZonedDateTime): BoardLimits =
    BoardLimits(
      // Unlimited is absence, not a huge number: Stamina reports Int.MaxValue
      // remaining when a guild has it switched off, which would otherwise reach
      // the page as a nonsense figure.
      remainingMinutes = if (stamina.unlimited) None else Some(stamina.remainingMinutes),
      budgetMinutes = if (stamina.unlimited) None else Some(stamina.budgetMinutes),
      maxDurationMinutes = maxDuration,
      defaultDurationMinutes = defaultDuration,
      resetsAt = resetsAt
    )
}
