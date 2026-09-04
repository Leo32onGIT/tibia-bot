package com.tibiabot.web

import com.tibiabot.respawn.{ClaimOutcome, ReleaseOutcome}

/** What an action did, in the words the dashboard shows.
 *
 *  `ok` is whether anything changed, which is not the same as whether the
 *  request was valid: being put in a queue, or told a spawn is already yours,
 *  are both perfectly good answers to a well-formed request. Only genuine
 *  refusals carry `ok = false`, and the page uses it purely to pick a tone —
 *  nothing branches on it.
 */
final case class ActionResult(ok: Boolean, message: String)

/** Turns the respawn service's outcomes into something a browser can show.
 *
 *  Pure, and deliberately separate from the route: the mapping is a table with
 *  a dozen entries and every one of them is a sentence somebody will read, so
 *  it is worth being able to check them without standing up HTTP. The Discord
 *  side words these its own way in `RespawnEmbeds`; this is the same
 *  information for a surface with no embeds, no mentions and no markdown.
 */
object RespawnActions {

  private def minutes(m: Int): String =
    if (m % 60 == 0 && m >= 60) s"${m / 60}h" else if (m > 60) s"${m / 60}h${m % 60}" else s"${m}m"

  private def clock(when: java.time.ZonedDateTime): String =
    when.toInstant.toString

  def describe(outcome: ClaimOutcome): ActionResult = outcome match {
    case ClaimOutcome.Claimed(respawn, claim) =>
      ActionResult(ok = true, s"${respawn.displayName} is yours for ${minutes(claim.durationMinutes)}.")

    case ClaimOutcome.BookedNext(respawn, startsAt, booked) =>
      // Not a refusal: they asked for a spawn and got the first window on it
      // nobody else had. A time, not a place in a line — the whole reason this
      // books rather than queues is that it can say exactly when.
      ActionResult(ok = true,
        s"${respawn.displayName} is taken — ${minutes(booked)} on it is booked for you from ${clock(startsAt)}.")

    case ClaimOutcome.BookAsked(respawn, _, deadline) =>
      // Also not a refusal: the same question a hand-picked booking over
      // somebody's slot raises, and their answer decides it.
      ActionResult(ok = true,
        s"The next window on ${respawn.displayName} is somebody else's booking. They have been asked " +
          s"whether they are hunting it, and have until ${clock(deadline)} to answer.")

    case ClaimOutcome.BookRefused(respawn, reason) =>
      ActionResult(ok = false, s"${respawn.displayName} is taken, and the window after it could not be booked: $reason")

    case ClaimOutcome.Shortened(respawn, claim, requested, reservedFrom) =>
      val until = reservedFrom.map(from => s" — booked from ${clock(from)}").getOrElse("")
      ActionResult(ok = true,
        s"${respawn.displayName} is yours for ${minutes(claim.durationMinutes)} rather than the " +
          s"${minutes(requested)} you asked for$until. You are only charged for the shorter hunt.")

    case ClaimOutcome.JustTaken(respawn) =>
      ActionResult(ok = false, s"Somebody claimed ${respawn.displayName} a moment before you did.")

    case ClaimOutcome.Reserved(respawn, from) =>
      ActionResult(ok = false,
        s"${respawn.displayName} is booked from ${clock(from)}, which leaves too little time to be worth starting.")

    case ClaimOutcome.AlreadyHolding(respawn, _) =>
      ActionResult(ok = false, s"You already hold ${respawn.displayName}.")

    case ClaimOutcome.NoStamina(respawn, needed, stamina, resetsAt) =>
      ActionResult(ok = false,
        s"${minutes(needed)} is more than the ${minutes(stamina.remainingMinutes)} you have left today. " +
          s"Your stamina resets at ${clock(resetsAt)}.")

    case ClaimOutcome.UnknownSpawn(query) =>
      ActionResult(ok = false, s"No spawn matches '$query'.")

    case ClaimOutcome.BadDuration(requested, max) =>
      ActionResult(ok = false, s"${minutes(requested)} is longer than the ${minutes(max)} this server allows.")

    case ClaimOutcome.NotConfigured =>
      ActionResult(ok = false, "The respawn system is not set up on this server.")
  }

  def describe(outcome: ReleaseOutcome): ActionResult = outcome match {
    case ReleaseOutcome.Released(respawn, refunded, offered) =>
      val handover = offered.map(o => s" It has been offered to ${o.userName}.").getOrElse("")
      val refund = if (refunded > 0) s" ${minutes(refunded)} of stamina returned." else ""
      ActionResult(ok = true, s"You have left ${respawn.displayName}.$refund$handover")

    case ReleaseOutcome.LeftQueue(respawn) =>
      ActionResult(ok = true, s"You have left the queue for ${respawn.displayName}.")

    case ReleaseOutcome.AlreadyHandingOver(spawnName) =>
      ActionResult(ok = false, s"$spawnName is already being handed to the next person.")

    case ReleaseOutcome.NothingHeld =>
      ActionResult(ok = false, "You are not holding or queued for that.")

    case ReleaseOutcome.NotConfigured =>
      ActionResult(ok = false, "The respawn system is not set up on this server.")
  }
}
