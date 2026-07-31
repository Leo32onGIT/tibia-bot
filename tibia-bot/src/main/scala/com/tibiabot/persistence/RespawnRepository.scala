package com.tibiabot.persistence

import com.tibiabot.domain.{Respawn, RespawnClaim, RespawnSchedule, RespawnSettings, RespawnUserPrefs, Stamina}

import java.time.ZonedDateTime

/** What bringing a guild's catalogue in line with the bundled file did.
 *
 *  `inUse` is the honest part: a code dropped from the file that somebody is
 *  hunting, queued for or has booked is left where it is, because removing it
 *  would end a hunt in progress. It is counted rather than silently skipped, so
 *  whoever ran the repair knows there is something to come back to.
 */
final case class SeedSync(added: Int, updated: Int, retired: Int, inUse: Int) {
  def changedAnything: Boolean = added > 0 || updated > 0 || retired > 0
}

/** Persistence port for the respawn claim system's per-guild tables
 *  (`respawns`, `respawn_claims`, `respawn_settings`, `respawn_stamina`).
 *
 *  Everything is keyed by guildId because each guild has its own database and
 *  its own curated catalogue.
 */
trait RespawnRepository {

  // --- settings -----------------------------------------------------------

  /** The guild's respawn settings, or None if `/respawn` was never set up here. */
  def settings(guildId: String): Option[RespawnSettings]

  /** Create or replace the guild's settings row. */
  def saveSettings(guildId: String, settings: RespawnSettings): Unit

  /** Point the guild at a (re)created forum channel and board post. */
  def updateChannels(guildId: String, forumChannel: String, boardThread: String): Unit

  // --- catalogue ----------------------------------------------------------

  def listRespawns(guildId: String): List[Respawn]

  def findByCode(guildId: String, code: String): Option[Respawn]

  def findById(guildId: String, respawnId: Long): Option[Respawn]

  /** Insert a spawn, or return the existing one if its code is already taken.
   *  Returns the row that ended up in the table either way. */
  def addRespawn(guildId: String, code: String, name: String, creature: String, region: String,
                 world: String, mapperLink: String, source: String, addedBy: String): Respawn

  def updateRespawn(guildId: String, respawnId: Long, name: Option[String], creature: Option[String],
                    world: Option[String], mapperLink: Option[String]): Unit

  /** Remove a catalogue entry and every claim attached to it. */
  def removeRespawn(guildId: String, respawnId: Long): Unit

  /** Remember which forum post represents this spawn ("" to forget it). */
  def setThreadId(guildId: String, respawnId: Long, threadId: String): Unit

  /** Bulk-insert seed rows, skipping codes the guild already has. Returns how
   *  many were actually inserted. */
  def importSeed(guildId: String, spawns: List[(String, String, String, String)]): Int

  /** Bring the guild's seed-derived rows in line with the bundled file: add
   *  codes it lacks, correct the name and city of ones that changed, and retire
   *  ones the file no longer has.
   *
   *  Only rows whose `source` is seed are touched, so a spawn a guild added
   *  itself is never rewritten or removed by an edit to the bundled file. A
   *  retired code that somebody is still hunting or has booked is left where it
   *  is and counted in [[SeedSync.inUse]] — the catalogue can wait, a hunt in
   *  progress cannot. */
  def syncSeed(guildId: String, spawns: List[(String, String, String, String)]): SeedSync

  /** Bring seed-derived rows' `creature` back in line with the bundled list,
   *  returning how many actually changed.
   *
   *  Curating which monster represents each spawn is an ongoing job, and
   *  `importSeed` deliberately never touches a code the guild already has — so
   *  without this, an improved seed would only ever reach a brand-new guild.
   *
   *  Skips rows a guild added itself, and rows whose creature an admin has set by
   *  hand (see `updateRespawn`, which pins them): someone who fixed a monster in
   *  Discord should not have it reverted by the next deploy. */
  def syncSeedCreatures(guildId: String, creaturesByCode: List[(String, String)]): Int

  // --- claims -------------------------------------------------------------

  def activeClaim(guildId: String, respawnId: Long): Option[RespawnClaim]

  def queueFor(guildId: String, respawnId: Long): List[RespawnClaim]

  /** Every spawn currently held, for `/respawn list`. */
  def allActiveClaims(guildId: String): List[RespawnClaim]

  /** Active or queued claims belonging to one user, for `/respawn release` and
   *  the per-user stamina display. */
  def openClaimsForUser(guildId: String, userId: String): List[RespawnClaim]

  /** The expiry sweep's work list — active claims that are due to end.
   *
   *  Two cases, and neither reduces to the other:
   *   - `limboUntil` unset and `endsAt` passed: the claim just ran out, so a
   *     handover starts.
   *   - `limboUntil` passed: the claim was already on its way out and its
   *     handover window has elapsed, so it finishes now. `endsAt` may still be
   *     in the *future* here, because a voluntary early release also enters
   *     limbo without moving the deadline.
   *
   *  A claim inside an unelapsed limbo window is excluded: it is still showing
   *  as the spawn's holder while the next person decides. */
  def expiredClaims(guildId: String, now: ZonedDateTime): List[RespawnClaim]

  /** Active claims still running that haven't had their reminder yet.
   *
   *  Returns the whole set rather than filtering by a lead time in SQL, because
   *  the lead time is now per member (see [[RespawnUserPrefs]]) — there is no
   *  single window to query by. The set is bounded by how many spawns are held
   *  at once, so filtering the rest in Scala is cheap. */
  def unwarnedActiveClaims(guildId: String, now: ZonedDateTime): List[RespawnClaim]

  /** Start a claim immediately. Returns the stored row. */
  def insertActiveClaim(guildId: String, respawnId: Long, userId: String, userName: String,
                        characterName: String, startsAt: ZonedDateTime, endsAt: ZonedDateTime,
                        durationMinutes: Int, kind: String): RespawnClaim

  /** Append to a spawn's queue, taking the next free position. Returns the
   *  stored row, or None if the queue is already at `queueLimit` or the user is
   *  already in it. */
  def enqueueClaim(guildId: String, respawnId: Long, userId: String, userName: String,
                   characterName: String, durationMinutes: Int, queueLimit: Int,
                   kind: String): Option[RespawnClaim]

  def findClaimById(guildId: String, claimId: Long): Option[RespawnClaim]

  /** Make one specific offered claim active, running from `startsAt`.
   *
   *  Targets a claim id rather than "whatever is at the head" so the caller can
   *  reserve that person's stamina first and then promote exactly them. Returns
   *  None if the row is no longer in the offered state — which is what makes
   *  this safe against the offer lapsing, or being declined, in between. */
  def promoteClaim(guildId: String, claimId: Long, startsAt: ZonedDateTime): Option[RespawnClaim]

  /** Move a queued claim to `offered`, meaning its owner has been DMed and has
   *  until `offerExpiresAt` to accept. Returns None if the row is no longer
   *  queued (they left in the meantime). */
  def offerClaim(guildId: String, claimId: Long, offerExpiresAt: ZonedDateTime): Option[RespawnClaim]

  /** The unanswered handover offer on a spawn, if any. While one exists the
   *  spawn must not offer itself to anybody else. */
  def offeredClaim(guildId: String, respawnId: Long): Option[RespawnClaim]

  /** Offers whose deadline has passed — the owner is assumed away, so these are
   *  cancelled and the spawn moves on. */
  def expiredOffers(guildId: String, now: ZonedDateTime): List[RespawnClaim]

  /** Hold a finished-but-not-yet-handed-over claim open until `limboUntil`, so
   *  the spawn keeps showing its previous holder while the next person decides.
   *
   *  Deliberately does **not** touch `ends_at` or `duration_minutes`: stamina
   *  was reserved from the duration up front, so moving the deadline would
   *  either charge for the wait or (via the release refund, which is capped by
   *  time remaining) hand back minutes that were never spent. */
  def setLimbo(guildId: String, claimId: Long, limboUntil: ZonedDateTime): Unit

  /** Cancel the queued claims of `userIds` on one spawn. Used to clear people
   *  who can no longer afford their claim out of the way rather than leaving
   *  them stuck at the head blocking everyone behind them. */
  def cancelQueued(guildId: String, respawnId: Long, userIds: Set[String], outcome: String): Unit

  /** The most recent finished claims on a spawn, newest first — the audit trail
   *  behind `/respawn log`.
   *
   *  No separate history table: claim rows are never deleted, only moved to a
   *  terminal status, so the trail is simply the rows that already exist. At a
   *  few thousand claims a year per guild there is nothing to prune. */
  def claimHistory(guildId: String, respawnId: Long, limit: Int): List[RespawnClaim]

  /** Close a claim that ran to its end. `outcome` records why, for the audit log
   *  (see RespawnClaim.Outcome); `ended_at` is stamped by the database. */
  def finishClaim(guildId: String, claimId: Long, outcome: String): Unit

  def cancelClaim(guildId: String, claimId: Long, outcome: String): Unit

  /** Move a running claim to a different member, keeping its start, end and
   *  everything else. Returns the stored row, or None if it is no longer active
   *  — which is what stops a moderator reassigning a hunt that just ended. */
  def reassignClaim(guildId: String, claimId: Long, userId: String, userName: String): Option[RespawnClaim]

  def markWarned(guildId: String, claimId: Long): Unit

  def extendClaim(guildId: String, claimId: Long, newEndsAt: ZonedDateTime, newDurationMinutes: Int): Unit

  /** Set a claim's length outright, rather than adding to it.
   *
   *  `newEndsAt` is None for a claim that hasn't started — a queued or offered
   *  row has no deadline yet, and inventing one would make it look active to the
   *  expiry sweep. */
  def setClaimDuration(guildId: String, claimId: Long, durationMinutes: Int,
                       newEndsAt: Option[ZonedDateTime]): Unit

  // --- stamina ------------------------------------------------------------

  /** A user's stamina for the server-save day starting at `resetAt`, resetting
   *  the tank if the stored row belongs to an older day. */
  def stamina(guildId: String, userId: String, budgetMinutes: Int, resetAt: ZonedDateTime): Stamina

  /** Reserve `minutes` against a user's tank. Returns false (and writes
   *  nothing) if it no longer fits — the caller must treat that as the claim
   *  being refused, since a concurrent claim may have taken the room since the
   *  check. */
  def reserveStamina(guildId: String, userId: String, minutes: Int, budgetMinutes: Int,
                     resetAt: ZonedDateTime): Boolean

  /** Give back unused minutes when a claim ends early. Never drops below zero. */
  def refundStamina(guildId: String, userId: String, minutes: Int, resetAt: ZonedDateTime): Unit

  /** Empty every tank in the guild, so everybody starts the day full again.
   *
   *  Rows are deleted rather than zeroed: a missing row already reads as a full
   *  tank, and leaving one behind would pin it to whichever server-save day it
   *  was written on. Returns how many were cleared. */
  def clearStamina(guildId: String): Int

  /** Admin override — set a user's consumed minutes directly. */
  def setStaminaUsed(guildId: String, userId: String, usedMinutes: Int, resetAt: ZonedDateTime): Unit

  // --- schedules ----------------------------------------------------------

  def addSchedule(guildId: String, respawnId: Long, userId: String, userName: String,
                  characterName: String, anchorAt: ZonedDateTime, periodMinutes: Int,
                  durationMinutes: Int,
                  daysOfWeek: Int = RespawnSchedule.EveryDay): RespawnSchedule

  def findSchedule(guildId: String, scheduleId: Long): Option[RespawnSchedule]

  /** Every live schedule in the guild — the materialiser's work list. */
  def activeSchedules(guildId: String): List[RespawnSchedule]

  def schedulesForRespawn(guildId: String, respawnId: Long): List[RespawnSchedule]

  def schedulesForUser(guildId: String, userId: String): List[RespawnSchedule]

  /** Retire a schedule. Kept rather than deleted so occurrences already in the
   *  claim history still point at something. */
  def deactivateSchedule(guildId: String, scheduleId: Long): Unit

  // --- reserved occurrences -----------------------------------------------

  /** Book one slot of a schedule, unless that exact slot is already booked.
   *
   *  Returns None when it exists, which is what makes the materialiser safe to
   *  run on every sweep — the (schedule, start) pair is the identity of an
   *  occurrence. */
  def reserveOccurrence(guildId: String, scheduleId: Long, respawnId: Long, userId: String,
                        userName: String, characterName: String, startsAt: ZonedDateTime,
                        durationMinutes: Int): Option[RespawnClaim]

  /** Slots booked on a spawn that haven't started, soonest first — what the card
   *  shows and what an ad-hoc claim has to stop short of. */
  def reservationsFor(guildId: String, respawnId: Long, now: ZonedDateTime): List[RespawnClaim]

  /** Booked slots whose start has arrived, across the guild. */
  def dueReservations(guildId: String, now: ZonedDateTime): List[RespawnClaim]

  /** Booked slots whose whole window has already gone by without starting —
   *  which means the bot was down over them. */
  def missedReservations(guildId: String, now: ZonedDateTime): List[RespawnClaim]

  /** Turn a booked slot into the live claim it was always going to be. Returns
   *  None if it is no longer reserved, which is the guard against two sweeps
   *  starting the same slot. */
  def startReservation(guildId: String, claimId: Long, startsAt: ZonedDateTime,
                       endsAt: ZonedDateTime): Option[RespawnClaim]

  /** Book a slot for somebody with no schedule of their own — used when a booked
   *  slot passes to whoever asked for it. */
  def reserveFor(guildId: String, respawnId: Long, userId: String, userName: String,
                 startsAt: ZonedDateTime, durationMinutes: Int): RespawnClaim

  /** Ask the owner of a booked slot whether they are actually hunting it.
   *
   *  Returns None if the slot is gone or has already been asked about — which is
   *  what enforces "asked once per slot", and stops two people racing to ask.
   *
   *  `wanted` is the window the asker booked, which overlaps this slot without
   *  necessarily matching it. None only for a request raised before booking over
   *  a slot became the one way to ask. */
  def requestOccurrence(guildId: String, claimId: Long, requesterUserId: String,
                        requesterUserName: String, askedAt: ZonedDateTime,
                        deadline: ZonedDateTime,
                        wanted: Option[(ZonedDateTime, Int)] = None): Option[RespawnClaim]

  /** Booked slots starting within `leadMinutes` whose owner hasn't been nudged.
   *
   *  Reuses the `warned` flag, which is otherwise meaningless on a reserved row —
   *  `startReservation` clears it, so the claim-end reminder still fires normally
   *  once the slot is running. */
  def slotsNeedingReminder(guildId: String, now: ZonedDateTime, leadMinutes: Int): List[RespawnClaim]

  /** Every live schedule with the spawn it belongs to, for moderators. */
  def allSchedules(guildId: String): List[RespawnSchedule]

  /** Clear the pending request from a slot its owner has confirmed they want.
   *  `askedAt` stays set, so the slot cannot be asked about again. */
  def keepOccurrence(guildId: String, claimId: Long): Option[RespawnClaim]

  /** Requests whose deadline has gone by with no answer. */
  def expiredRequests(guildId: String, now: ZonedDateTime): List[RespawnClaim]

  /** Drop the not-yet-started slots of a schedule, for when it is cancelled. */
  def cancelReservationsOf(guildId: String, scheduleId: Long, outcome: String): Unit

  // --- member preferences -------------------------------------------------

  def userPrefs(guildId: String, userId: String): RespawnUserPrefs

  def saveUserPrefs(guildId: String, prefs: RespawnUserPrefs): Unit

  // --- teardown -----------------------------------------------------------

  /** Forget everything the respawn system knows about this guild: claims,
   *  catalogue and settings.
   *
   *  Used when the guild's last world is `/remove`d. The forum channel itself
   *  is kept as read-only history (see ChannelService.retireSpawnsForum), but
   *  none of it is tracked any more — a later `/setup` starts from the bundled
   *  seed again rather than inheriting a catalogue whose threads all point into
   *  a retired channel. */
  def dropGuildData(guildId: String): Unit
}
