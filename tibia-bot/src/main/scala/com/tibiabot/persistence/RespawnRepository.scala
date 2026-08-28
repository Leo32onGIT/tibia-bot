package com.tibiabot.persistence

import com.tibiabot.domain.{Respawn, RespawnClaim, RespawnSchedule, RespawnSettings, RespawnUserPrefs, Stamina}

import java.time.ZonedDateTime

/** Somebody the guild's respawn system knows about, for a picker to offer.
 *
 *  Both names travel and neither is a mention: the account is what is unique and
 *  searchable, the nickname is what people actually call them. Either may be
 *  empty on a row written before it was recorded. */
final case class KnownMember(userId: String, userName: String, nickname: String)

/** One day a standing booking has given up.
 *
 *  A rule says "every day at eleven"; it cannot say what became of last
 *  Thursday. Only the row for that day can, and there is exactly one per
 *  `(schedule, start)` — the pair the database already treats as an
 *  occurrence's identity.
 *
 *  A day is given up when its row has stopped standing: cancelled by its owner,
 *  handed to whoever asked for it, missed, or taken off the calendar by a
 *  moderator. Everywhere a rule speaks for a day — the card, the week, the
 *  clash check, somebody's list of bookings — has to consult these first, or it
 *  goes on naming an evening its owner no longer has. */
final case class ScheduleOccurrence(scheduleId: Long, startsAt: ZonedDateTime)

/** What bringing a guild's catalogue in line with the bundled file did.
 *
 *  `retired` is the rows themselves rather than a count of them, because the
 *  caller has a second job to do with each one: the spawn's forum post has to
 *  come down too, and a post can only be found by the `threadId` on the row
 *  that has just been deleted. A count would leave every retired code as a
 *  card in Discord that nothing can resolve.
 */
final case class SeedSync(added: Int, updated: Int, retired: List[Respawn]) {
  def changedAnything: Boolean = added > 0 || updated > 0 || retired.nonEmpty
}

/** Persistence port for the respawn claim system's per-guild tables
 *  (`respawns`, `respawn_claims`, `respawn_settings`, `respawn_stamina`).
 *
 *  Everything is keyed by guildId because each guild has its own database and
 *  its own curated catalogue.
 */
trait RespawnRepository {

  // --- settings -----------------------------------------------------------

  /** The guild's respawn settings, or None if the respawn system was never set
   *  up here. */
  def settings(guildId: String): Option[RespawnSettings]

  /** Create or replace the guild's settings row. */
  def saveSettings(guildId: String, settings: RespawnSettings): Unit

  /** Point the guild at a (re)created forum channel and board post. */
  def updateChannels(guildId: String, forumChannel: String, boardThread: String): Unit

  /** Everybody this guild's respawn system has ever seen, newest first.
   *
   *  Exists because there is no member list to offer instead: the bot runs
   *  without the privileged GUILD_MEMBERS intent, so Discord's own user picker
   *  has no web counterpart here (see `DiscordGateway.memberAccess`). These are
   *  the people who have actually claimed, queued or booked something, which is
   *  the set a moderator ever needs to pick from — and the names come from the
   *  rows themselves, so somebody who has since left is still nameable.
   *
   *  One entry per account, carrying the most recent name and nickname recorded
   *  for them: people change both, and the newest is the one anybody would
   *  search by. */
  def knownMembers(guildId: String, limit: Int): List[KnownMember]

  /** The fingerprint of the catalogue the pinned board post was last drawn from,
   *  or None if it has never been recorded — which is every guild the first time
   *  a build that keeps it runs, and reads as "redraw it".
   *
   *  Persisted rather than held in memory precisely because the question is
   *  asked at boot: what is being remembered is the state of a message in
   *  Discord, which outlives this process. */
  def boardDigest(guildId: String): Option[String]

  def setBoardDigest(guildId: String, digest: String): Unit

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
  /** Set or clear one spawn's own ceiling on claim length. `None` clears it,
   *  putting the spawn back on the guild's — see `RespawnSettings.maxFor`. */
  def setRespawnMaxDuration(guildId: String, respawnId: Long, minutes: Option[Int]): Unit

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
   *  itself is never rewritten or removed by an edit to the bundled file.
   *
   *  A retired code goes immediately, taking its claims and its bookings with
   *  it. Waiting for one to be free first sounds kinder and is not: a code the
   *  file has dropped is usually one that has been split into sub-codes, so
   *  what a standing booking pins in place is a spawn nobody should be on any
   *  more — live on the board, on the calendar and claimable, beside the codes
   *  that replaced it, until whichever restart happens to find it idle. The
   *  hunt it ends is on a spawn that no longer exists. */
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

  /** Every spawn currently held, for the dashboard's board. */
  def allActiveClaims(guildId: String): List[RespawnClaim]

  /** Every queued claim in the guild, for callers that need the whole board at
   *  once. The per-spawn [[queueFor]] is right for one spawn; asking it once per
   *  spawn to draw a catalogue of a few hundred is not. */
  def allQueuedClaims(guildId: String): List[RespawnClaim]

  /** Every booked slot still ahead of `now`, across the guild. Bulk counterpart
   *  to [[reservationsFor]], for the same reason. */
  def allReservations(guildId: String, now: ZonedDateTime): List[RespawnClaim]

  /** When each spawn was last touched — the most recent claim on it, whether it
   *  ended or is still running.
   *
   *  Drives how far a spawn has faded on the board, so it has to cover every
   *  spawn ever claimed in one query rather than a history read per spawn.
   *  Spawns never claimed at all are simply absent. */
  def lastActivityByRespawn(guildId: String): List[(Long, ZonedDateTime)]

  /** Active or queued claims belonging to one user, for the Release and Leave
   *  buttons and `/stamina`'s display. */
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
   *  the lead time is now per member (see `RespawnUserPrefs`) — there is no
   *  single window to query by. The set is bounded by how many spawns are held
   *  at once, so filtering the rest in Scala is cheap. */
  def unwarnedActiveClaims(guildId: String, now: ZonedDateTime): List[RespawnClaim]

  /** Start a claim immediately.
   *
   *  None when somebody already holds the spawn — the caller's earlier "is it
   *  free" read is not enough on its own, since two people pressing Claim at once
   *  both pass it. Whoever loses is told the spawn was taken rather than ending up
   *  with a second claim on the same hunt. */
  def insertActiveClaim(guildId: String, respawnId: Long, userId: String, userName: String, nickname: String,
                        characterName: String, startsAt: ZonedDateTime, endsAt: ZonedDateTime,
                        durationMinutes: Int, kind: String): Option[RespawnClaim]

  /** Append to a spawn's queue, taking the next free position. Returns the
   *  stored row, or None if the queue is already at `queueLimit` or the user is
   *  already in it. */
  def enqueueClaim(guildId: String, respawnId: Long, userId: String, userName: String, nickname: String,
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

  /** The most recent finished claims, newest first — the audit trail behind the
   *  moderator Log panel. `respawnId` empty reads the whole guild, which is what
   *  the board's Log shows; a spawn's own Log passes its id.
   *
   *  No separate history table: claim rows are never deleted, only moved to a
   *  terminal status, so the trail is simply the rows that already exist. At a
   *  few thousand claims a year per guild there is nothing to prune.
   *
   *  `offset` pages backwards through it. Callers keep that bounded (see
   *  RespawnService.LogMaxPages) rather than letting somebody walk years of
   *  history one page at a time. */
  def claimHistory(guildId: String, respawnId: Option[Long], userId: Option[String],
                   limit: Int, offset: Int): List[RespawnClaim]

  /** One spawn's finished business over a window, for drawing the past on the
   *  calendar.
   *
   *  The same rows [[claimHistory]] reads and a different question of them: not
   *  "what happened here lately" in pages, but "what happened here between these
   *  two instants" — which is what a grid showing a week of last month needs.
   *
   *  Rows with no start are left out. A claim that only ever sat in a queue —
   *  left it, declined the offer, ran out of stamina — has no window and belongs
   *  to nobody's evening; the calendar draws time, and those never occupied any.
   *
   *  Overlap is measured against when a claim *actually* ended rather than when
   *  it was due to: a hunt given up after twenty minutes of a two-hour window is
   *  twenty minutes of history, and drawing it to its deadline would be drawing
   *  something that did not happen. */
  def claimsBetween(guildId: String, respawnId: Long,
                    from: ZonedDateTime, to: ZonedDateTime): List[RespawnClaim]

  /** Close a claim that ran to its end. `outcome` records why, for the audit log
   *  (see RespawnClaim.Outcome); `ended_at` is stamped by the database. */
  def finishClaim(guildId: String, claimId: Long, outcome: String): Unit

  def cancelClaim(guildId: String, claimId: Long, outcome: String): Unit

  /** Move a running claim to a different member, keeping its start, end and
   *  everything else. Returns the stored row, or None if it is no longer active
   *  — which is what stops a moderator reassigning a hunt that just ended.
   *
   *  Both of the new owner's names, like [[reassignReservation]]: a row that
   *  took the account name alone kept whatever nickname the previous holder
   *  left on it, and so named the hunt after one person in the guild's words
   *  and another in Discord's. */
  def reassignClaim(guildId: String, claimId: Long, userId: String, userName: String,
                    nickname: String): Option[RespawnClaim]

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

  def addSchedule(guildId: String, respawnId: Long, userId: String, userName: String, nickname: String,
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
                        userName: String, nickname: String, characterName: String, startsAt: ZonedDateTime,
                        durationMinutes: Int): Option[RespawnClaim]

  /** Slots booked on a spawn that haven't started, soonest first — what the card
   *  shows and what an ad-hoc claim has to stop short of. */
  def reservationsFor(guildId: String, respawnId: Long, now: ZonedDateTime): List[RespawnClaim]

  /** Days a rule has given up — see [[ScheduleOccurrence]].
   *
   *  Distinct from [[reservationsFor]], which answers "what is booked": this
   *  answers "which days are no longer anybody's to speak for", and the
   *  difference is the whole point. A day handed to somebody else is missing
   *  from the first and present here, and reading only the first is what let a
   *  rule name an evening beside the booking that had replaced it.
   *
   *  Both bounds are optional because the callers want different shapes of the
   *  same question: the week wants one spawn between two instants, and somebody
   *  reading their bookings wants every spawn from now on. */
  def settledOccurrences(guildId: String, from: ZonedDateTime,
                         to: Option[ZonedDateTime] = None,
                         respawnId: Option[Long] = None): List[ScheduleOccurrence]

  /** Write off one day of a rule without touching the rule.
   *
   *  A cancelled occurrence row is how "not this day" is recorded — there is no
   *  separate exception table, and there does not need to be one, because the
   *  row is what both the calendar and the clash check already consult. Used for
   *  a day that was never materialised: the moderator is taking a slot off the
   *  calendar before the sweep would have written it.
   *
   *  False when the day already had a row, which makes it safe to call against
   *  a calendar that may be a few seconds out of date. */
  def skipOccurrence(guildId: String, scheduleId: Long, respawnId: Long, userId: String,
                     userName: String, nickname: String, characterName: String,
                     startsAt: ZonedDateTime, durationMinutes: Int, outcome: String): Boolean

  /** Put a booked slot in somebody else's name, keeping its time and length.
   *
   *  Any pending question goes with it: the answer would be about a slot that is
   *  no longer theirs to answer for. The schedule behind it is dropped for the
   *  same reason — one day of a rule, handed on, stops being an occurrence of
   *  that rule, exactly as it does when the owner gives it up to whoever asked.
   *
   *  None when the slot is no longer reserved. */
  def reassignReservation(guildId: String, claimId: Long, toUserId: String,
                          toUserName: String, toNickname: String): Option[RespawnClaim]

  /** A booked or running claim starting at exactly this instant, whoever owns
   *  it — how a moderator names one day on the calendar without knowing any
   *  row id. */
  def slotAt(guildId: String, respawnId: Long, startsAt: ZonedDateTime): Option[RespawnClaim]

  /** Run `body` holding the spawn's row lock, so two people deciding about the
   *  same spawn at once take turns rather than both deciding on the same
   *  picture.
   *
   *  The one thing that makes a read-then-write safe here. Booking checks for a
   *  clash and then inserts, across separate statements, so without this two
   *  bookings arriving together both found the evening free and both took it —
   *  and nothing downstream would catch it, since the unique index on
   *  `(schedule_id, starts_at)` sees two ids and the one on `respawn_id` only
   *  covers a claim that is already running.
   *
   *  It has to be the database's lock rather than a mutex: dashboard writes are
   *  relayed, so the two racers need not even be in the same process. */
  def withRespawnLock[A](guildId: String, respawnId: Long)(body: => A): A

  /** Booked slots whose start has arrived, across the guild. */
  def dueReservations(guildId: String, now: ZonedDateTime): List[RespawnClaim]

  /** Booked slots whose whole window has already gone by without starting —
   *  which means the bot was down over them. */
  def missedReservations(guildId: String, now: ZonedDateTime): List[RespawnClaim]

  /** Turn a booked slot into the live claim it was always going to be. Returns
   *  None if it is no longer reserved, which is the guard against two sweeps
   *  starting the same slot.
   *
   *  `confirmBy` is the deadline its owner has to say they are actually there
   *  (see [[unconfirmedClaims]]). Stamped even when the slot was confirmed
   *  ahead of time, since it also records that this claim began as a booking. */
  def startReservation(guildId: String, claimId: Long, startsAt: ZonedDateTime,
                       endsAt: ZonedDateTime, confirmBy: ZonedDateTime): Option[RespawnClaim]

  /** Record that a slot's owner has said they are hunting it — Confirm on the
   *  reminder while it is still reserved, or Take Claim once it has started.
   *
   *  Returns None if it is already confirmed, or in neither of those states,
   *  which is what makes a second press a no-op rather than a restamp. */
  def confirmClaim(guildId: String, claimId: Long, at: ZonedDateTime): Option[RespawnClaim]

  /** Running claims that began as a booking, whose confirmation deadline has
   *  gone by with nobody saying they were there. The caller gives these up on
   *  the owner's behalf — see RespawnService's sweep. */
  def unconfirmedClaims(guildId: String, now: ZonedDateTime): List[RespawnClaim]

  /** Book a slot for somebody with no schedule of their own — used when a booked
   *  slot passes to whoever asked for it. */
  def reserveFor(guildId: String, respawnId: Long, userId: String, userName: String, nickname: String,
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
                        requesterUserName: String, requesterNickname: String, askedAt: ZonedDateTime,
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
