package com.tibiabot.domain

import java.time.Instant

/** A member's Tibia Observer link, scoped to one guild and one Discord user.
 *
 *  The 5-char access token itself is never carried here — it is stored encrypted
 *  in `observer_tokens` and only the observer client ever handles the plaintext.
 *  This is the panel-facing view: whose link it is, what world it resolved to
 *  once verified, and its health. */
final case class ObserverToken(
  id: Long,
  guildId: String,
  userId: String,
  world: Option[String],
  accountLabel: Option[String],
  status: ObserverStatus,
  createdAt: Instant,
  updatedAt: Instant
) {

  /** The worlds the linked account has characters on — what its link covers. Kept
   *  as the label it was linked with ("Antica, Secura"); empty until verified. */
  def worlds: List[String] = world.toList.flatMap(_.split(",").map(_.trim).filter(_.nonEmpty))
}

/** Lifecycle of a stored link. Phase 1 (mode off) only ever produces `Pending`;
 *  live verification in a later phase moves it to `Linked` / `NeedsRelink`. */
sealed trait ObserverStatus { def code: String }

object ObserverStatus {
  case object Pending extends ObserverStatus { val code = "pending" }
  case object Linked extends ObserverStatus { val code = "linked" }
  case object NeedsRelink extends ObserverStatus { val code = "needs_relink" }
  case object Error extends ObserverStatus { val code = "error" }

  def fromCode(code: String): ObserverStatus = code match {
    case Linked.code      => Linked
    case NeedsRelink.code => NeedsRelink
    case Error.code       => Error
    case _                => Pending
  }
}
