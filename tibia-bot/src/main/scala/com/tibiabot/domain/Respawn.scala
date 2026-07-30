package com.tibiabot.domain

import java.time.ZonedDateTime

/** A claimable respawn in a guild's catalogue.
 *
 *  `threadId` is the guild's forum post for this spawn. It lives on the
 *  catalogue row rather than on a claim because one thread is reused for a
 *  spawn's whole life: created on the first claim, archived when the spawn goes
 *  free, un-archived on the next claim. That keeps thread count bounded by the
 *  catalogue size and keeps a spawn's history in one place.
 *
 *  `mapperLink` is stored but not rendered yet — the mapper button and minimap
 *  thumbnail are deliberately out of scope for now, so wiring them later is a
 *  display-only change with the data already present.
 */
final case class Respawn(
  id: Long,
  code: String,
  name: String,
  creature: String,
  region: String,
  world: String,
  mapperLink: String,
  threadId: String,
  source: String,
  addedBy: String
) {
  /** "415 — Cult Orcs" — the forum post title and the way a spawn is named
   *  everywhere it's displayed. */
  def displayName: String = s"$code — $name"
}

object Respawn {
  /** Catalogue rows that came from the bundled seed file. */
  val SourceSeed: String = "seed"
  /** Catalogue rows a guild's admins added themselves. `/respawn admin seed`
   *  never touches these. */
  val SourceCustom: String = "custom"
}

/** One person's hold on a respawn — either the active claim or a place in its
 *  queue. Rows are never deleted, only moved to a terminal status, so a thread
 *  can show who hunted a spawn before.
 *
 *  `kind` exists so the not-yet-built scheduled-claim feature can add rows of a
 *  different kind without a migration; today every row is [[KindAdHoc]].
 */
final case class RespawnClaim(
  id: Long,
  respawnId: Long,
  userId: String,
  userName: String,
  characterName: String,
  status: String,
  queuePosition: Int,
  claimedAt: ZonedDateTime,
  startsAt: Option[ZonedDateTime],
  endsAt: Option[ZonedDateTime],
  durationMinutes: Int,
  warned: Boolean,
  kind: String,
  limboUntil: Option[ZonedDateTime],
  offerExpiresAt: Option[ZonedDateTime],
  /** How this claim finished, once it has — see [[RespawnClaim.Outcome]]. Empty
   *  while it is still running, and on rows that ended before this was recorded. */
  outcome: Option[String],
  /** When it actually stopped, which is not `endsAt`: a claim released early, or
   *  taken over, ends before its deadline. The audit wants the real one. */
  endedAt: Option[ZonedDateTime]
) {
  def isActive: Boolean = status == RespawnClaim.StatusActive
  def isQueued: Boolean = status == RespawnClaim.StatusQueued
  def isOffered: Boolean = status == RespawnClaim.StatusOffered

  /** Whether this claim being given up means a handover is in flight and has to
   *  move on to the next person.
   *
   *  True only for an *offered* claim. Somebody abandoning a mere queue place
   *  changes nothing about the spawn — its holder is mid-hunt — and advancing a
   *  handover closes out whoever it is replacing, so treating the two alike ended
   *  a live hunt (or offered it away) because a third party left the queue. */
  def leavingAdvancesHandover: Boolean = isOffered

  /** Whether a handover may legitimately finish this claim. Limbo is the marker
   *  for "time up, next person deciding", so anything without it is still a
   *  running hunt and must be left alone. */
  def eligibleForHandover: Boolean = limboUntil.isDefined

  /** True while this claim's time is up but it is being held open because the
   *  next person in line still has an unanswered handover offer. The spawn goes
   *  on showing this claimant as its holder, and — because `endsAt` is left
   *  untouched — no extra stamina is charged for the wait. */
  def inLimbo(now: ZonedDateTime): Boolean = limboUntil.exists(_.isAfter(now))
}

object RespawnClaim {
  /** Holding the spawn right now; `startsAt`/`endsAt` are set. */
  val StatusActive: String = "active"
  /** Waiting for the current claim to end; `startsAt`/`endsAt` are empty
   *  because the start time isn't known until the claim ahead actually ends. */
  val StatusQueued: String = "queued"
  /** Reached the front of the queue and been sent a handover offer by DM, but
   *  hasn't pressed Claim yet. `offerExpiresAt` is the deadline; letting it
   *  lapse is treated exactly like leaving the queue.
   *
   *  A separate status from `queued` so an unanswered offer can't be handed out
   *  twice, and so the person isn't silently given a spawn they may have walked
   *  away from. */
  val StatusOffered: String = "offered"
  /** Ran to completion. */
  val StatusFinished: String = "finished"
  /** Released early, declined or ignored a handover offer, skipped for
   *  insufficient stamina, or force-cleared by an admin. */
  val StatusCancelled: String = "cancelled"

  /** Why a claim ended.
   *
   *  The status alone can't answer that — "cancelled" covers a member leaving
   *  early, declining a handover, letting one lapse, running out of stamina and a
   *  moderator stepping in, which are very different things when somebody is
   *  auditing a dispute. Stored as a short string rather than an enum so an
   *  unrecognised value from a future version reads as itself instead of failing
   *  to parse. */
  object Outcome {
    /** Ran its full time. */
    val Completed: String = "completed"
    /** The holder gave it up early. */
    val Released: String = "released"
    /** A moderator moved the holder off it. */
    val Forced: String = "forced"
    /** An admin force-cleared the whole spawn. */
    val Cleared: String = "cleared"
    /** Someone else accepted the handover, so the previous claim ended. */
    val TakenOver: String = "taken-over"
    /** Left the queue before reaching the front. */
    val LeftQueue: String = "left-queue"
    /** Was offered the spawn and said no. */
    val Declined: String = "declined"
    /** Was offered the spawn and never answered. */
    val OfferLapsed: String = "offer-lapsed"
    /** Dropped from the queue because their stamina had gone elsewhere. */
    val NoStamina: String = "no-stamina"

    /** Plain-English form for the audit log. Unknown values are shown as-is
     *  rather than hidden, so a row written by a newer version still says
     *  something. */
    def label(outcome: String): String = outcome match {
      case Completed   => "ran its full time"
      case Released    => "left early"
      case Forced      => "moved off by a moderator"
      case Cleared     => "cleared by an admin"
      case TakenOver   => "handed over"
      case LeftQueue   => "left the queue"
      case Declined    => "declined the handover"
      case OfferLapsed => "didn't answer the handover"
      case NoStamina   => "dropped, out of stamina"
      case other       => other
    }
  }

  /** A claim someone made themselves via `/respawn claim` or the Next button. */
  val KindAdHoc: String = "adhoc"
  /** RESERVED — materialised from a recurring schedule. Not produced by any
   *  code path yet; see the scheduled-claim notes in RespawnService. */
  val KindScheduled: String = "scheduled"
}

/** A guild's respawn-system settings. Defaults come from Config.Respawn and are
 *  written into the row when the forum is first created, so a later change to
 *  the bot's defaults never silently re-tunes a guild that already went live. */
final case class RespawnSettings(
  forumChannel: String,
  boardThread: String,
  defaultDurationMinutes: Int,
  maxDurationMinutes: Int,
  queueLimit: Int,
  staminaMinutes: Int,
  warnMinutes: Int,
  /** How long someone has to accept a handover offer before it's assumed they
   *  walked away and the spawn moves on to the next person. */
  handoverMinutes: Int
)

/** One member's own preferences, overriding the guild defaults for their own
 *  claims. Set through the Config button on the spawns board.
 *
 *  Both are `None` until the member changes them, which is what lets a guild
 *  retune its defaults and have everyone who never expressed a preference
 *  follow along.
 */
final case class RespawnUserPrefs(
  userId: String,
  /** How long their claims run when they don't say otherwise. */
  defaultDurationMinutes: Option[Int],
  /** How far ahead of a claim ending they want their reminder DM. `Some(0)`
   *  means they've deliberately turned reminders off, which is why this is an
   *  Option of a possibly-zero value rather than just zero-means-unset. */
  warnMinutes: Option[Int]
) {
  def defaultDurationOr(guildDefault: Int): Int = defaultDurationMinutes.getOrElse(guildDefault)
  def warnMinutesOr(guildDefault: Int): Int = warnMinutes.getOrElse(guildDefault)
}

object RespawnUserPrefs {
  def none(userId: String): RespawnUserPrefs = RespawnUserPrefs(userId, None, None)

  /** Longest reminder lead time a member may ask for. A claim can only run for
   *  `maxDurationMinutes` anyway, so anything beyond this would fire the moment
   *  the claim started. */
  val MaxWarnMinutes: Int = 12 * 60
}

/** A user's remaining claim budget for the current server-save day.
 *
 *  Stamina is reserved up front for a claim's full duration rather than
 *  accrued as it's spent — that's what lets two spawns be held at once
 *  (explicitly wanted) while still bounding the total to the tank. Releasing
 *  early refunds the unused remainder.
 */
final case class Stamina(userId: String, usedMinutes: Int, budgetMinutes: Int, resetAt: ZonedDateTime) {
  /** Unlimited when the budget is zero (stamina disabled for the guild). */
  def unlimited: Boolean = budgetMinutes <= 0
  def remainingMinutes: Int = if (unlimited) Int.MaxValue else math.max(0, budgetMinutes - usedMinutes)
  def canAfford(minutes: Int): Boolean = unlimited || minutes <= remainingMinutes
}
