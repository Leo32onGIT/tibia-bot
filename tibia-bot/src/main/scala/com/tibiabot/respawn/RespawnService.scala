package com.tibiabot.respawn

import com.tibiabot.Config
import com.tibiabot.domain.{ClashVerdict, Respawn, RespawnClaim, RespawnSchedule, RespawnSettings, RespawnUserPrefs, Stamina}
import com.tibiabot.persistence.{RespawnRepository, SeedSync}
import com.tibiabot.presentation.RespawnEmbeds
import com.tibiabot.scheduler.ServerSaveSchedule
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.{ForumChannel, ThreadChannel}

import java.time.ZonedDateTime
import scala.util.Try

/** What a claim attempt did, for the command/button layer to render. Modelled
 *  as a result type rather than the handler poking at the repository itself, so
 *  the rules live in one place and `/respawn claim` and the Claim button can't
 *  drift apart. */
sealed trait ClaimOutcome
object ClaimOutcome {
  /** The caller now holds the spawn. */
  final case class Claimed(respawn: Respawn, claim: RespawnClaim) extends ClaimOutcome
  /** The spawn was taken, so the caller is in line behind it. */
  final case class Queued(respawn: Respawn, claim: RespawnClaim, position: Int) extends ClaimOutcome
  /** Granted, but cut short by a booked slot. `requested` is what they asked for
   *  and `reservedFrom` is when the booking takes over. */
  final case class Shortened(respawn: Respawn, claim: RespawnClaim, requested: Int,
                             reservedFrom: Option[ZonedDateTime]) extends ClaimOutcome
  /** Lost a race: somebody else's claim landed first. */
  final case class JustTaken(respawn: Respawn) extends ClaimOutcome
  /** Refused because a booked slot leaves too little time to be worth granting. */
  final case class Reserved(respawn: Respawn, from: ZonedDateTime) extends ClaimOutcome
  /** The caller already holds or is queued for this spawn. */
  final case class AlreadyHolding(respawn: Respawn, claim: RespawnClaim) extends ClaimOutcome
  final case class QueueFull(respawn: Respawn, limit: Int) extends ClaimOutcome
  /** Not enough stamina left today for a claim this long. */
  final case class NoStamina(respawn: Respawn, needed: Int, stamina: Stamina, resetsAt: ZonedDateTime) extends ClaimOutcome
  final case class UnknownSpawn(query: String) extends ClaimOutcome
  final case class BadDuration(requested: Int, max: Int) extends ClaimOutcome
  /** `/respawn` has never been set up in this guild. */
  case object NotConfigured extends ClaimOutcome
}

sealed trait ReleaseOutcome
object ReleaseOutcome {
  /** Gave up an active claim. `offered` is the handover offer that went out to
   *  the next person, if there was one. */
  final case class Released(respawn: Respawn, refundedMinutes: Int, offered: Option[RespawnClaim]) extends ReleaseOutcome
  final case class LeftQueue(respawn: Respawn) extends ReleaseOutcome
  /** The claim is already on its way out — its handover offer is outstanding, so
   *  there is nothing left to release. */
  final case class AlreadyHandingOver(spawnName: String) extends ReleaseOutcome
  case object NothingHeld extends ReleaseOutcome
  case object NotConfigured extends ReleaseOutcome
}

/** What booking a slot did.
 *
 *  Two outcomes rather than one because a booking that clashes with somebody
 *  else's is not always a refusal any more: a one-off over a slot nobody has
 *  asked about becomes a question for its owner, and the answer decides whether
 *  the booking happens at all. Nothing is written for the asker until then, so
 *  there is no half-made booking to explain or clean up. */
sealed trait ScheduleResult
object ScheduleResult {
  /** The slot was free and is now theirs. */
  final case class Booked(schedule: RespawnSchedule) extends ScheduleResult
  /** It clashed, and the clashing slot's owner has been asked whether they are
   *  actually hunting it. */
  final case class Requested(respawn: Respawn, slot: RespawnClaim, deadline: ZonedDateTime)
    extends ScheduleResult
}

/** The result of a slot owner answering "are you hunting tonight?". */
sealed trait SlotAnswer
object SlotAnswer {
  /** They are hunting it, so the request is refused and the slot stays theirs. */
  final case class Kept(respawn: Respawn) extends SlotAnswer
  /** They are not, so it passes to whoever asked. */
  final case class Passed(respawn: Respawn, toUserId: String) extends SlotAnswer
  /** They are not — but the asker had booked a longer window than the slot they
   *  asked about, and the rest of it is somebody else's now. The slot is given up
   *  all the same; it simply goes back to being free rather than to them. */
  final case class PassedUnclaimed(respawn: Respawn) extends SlotAnswer
  /** Already answered, lapsed, or the slot is gone. */
  case object Gone extends SlotAnswer
  case object NotYours extends SlotAnswer
}

/** The result of answering a handover offer DM. */
sealed trait OfferOutcome
object OfferOutcome {
  final case class Accepted(respawn: Respawn, claim: RespawnClaim) extends OfferOutcome
  final case class Declined(respawn: Respawn) extends OfferOutcome
  /** Their tank was committed elsewhere while the offer sat unanswered, so the
   *  spawn has moved on. */
  final case class NoStamina(needed: Int, stamina: Stamina, resetsAt: ZonedDateTime) extends OfferOutcome
  /** Lapsed, already answered, or the spawn is gone. */
  case object Gone extends OfferOutcome
  /** Somebody else's offer — only reachable if a button id were shared around. */
  case object NotYours extends OfferOutcome
}

/** The respawn claim system's rules and lifecycle.
 *
 *  Every piece of state lives in Postgres — there are no in-memory timers — so
 *  a restart mid-claim loses nothing and a claim that should have ended while
 *  the bot was down is simply resolved by the next sweep.
 *
 *  Stamina is reserved for a claim's whole duration at the moment it starts,
 *  not accrued as it's spent. That's deliberate: holding two spawns at once is
 *  supported, and up-front reservation is what stops that from being a way to
 *  book eight hours out of a four-hour tank. Releasing early refunds the
 *  unused remainder.
 *
 *  ==Scheduled claims (not built)==
 *  The recurring "same slot every day" feature isn't implemented, but the seams
 *  it needs exist: [[beginClaim]] is the single entry point that starts a claim
 *  and owns the thread/stamina side effects, and `RespawnClaim.kind` already
 *  distinguishes row types. A scheduler would add a `respawn_schedules` table,
 *  materialise a claim through [[beginClaim]] with `kind = KindScheduled`, and
 *  ride the existing [[sweep]]. The one rule that will need writing is what an
 *  ad-hoc claim does when it would overrun a reserved window — that decision
 *  belongs in [[endsAtFor]], which is why the deadline is computed there rather
 *  than inline at each call site.
 */
final class RespawnService(repository: RespawnRepository) extends StrictLogging {

  // --- settings -----------------------------------------------------------

  /** The guild's settings, or None when `/respawn` was never set up here. */
  def settings(guildId: String): Option[RespawnSettings] = repository.settings(guildId)

  /** The settings a guild gets on first setup — the bot's configured defaults,
   *  snapshotted into the guild's own row so later changes to the bot's
   *  defaults don't retune a guild that's already live. */
  def defaultSettings: RespawnSettings = RespawnSettings(
    forumChannel = "0",
    boardThread = "0",
    defaultDurationMinutes = Config.Respawn.defaultDurationMinutes,
    maxDurationMinutes = Config.Respawn.maxDurationMinutes,
    queueLimit = Config.Respawn.queueLimit,
    staminaMinutes = Config.Respawn.staminaMinutes,
    warnMinutes = Config.Respawn.warnMinutes,
    handoverMinutes = Config.Respawn.handoverMinutes
  )

  def saveSettings(guildId: String, settings: RespawnSettings): Unit =
    repository.saveSettings(guildId, settings)

  /** Apply a partial change to the guild's rules, validated.
   *
   *  Shared by `/respawn admin config` and the board's moderator panel so the two
   *  can't drift on what counts as a legal combination — a default claim longer
   *  than the maximum being the one that actually bites, since every later claim
   *  would be refused for exceeding a ceiling nobody set deliberately. */
  def updateSettings(guildId: String, defaultDuration: Option[Int], maxDuration: Option[Int],
                     queueLimit: Option[Int], stamina: Option[Int], warn: Option[Int],
                     handover: Option[Int]): Either[String, RespawnSettings] =
    settings(guildId) match {
      case None => Left("The respawn claim system isn't set up on this server yet.")
      case Some(current) =>
        val updated = current.copy(
          defaultDurationMinutes = defaultDuration.getOrElse(current.defaultDurationMinutes),
          maxDurationMinutes = maxDuration.getOrElse(current.maxDurationMinutes),
          queueLimit = queueLimit.getOrElse(current.queueLimit),
          staminaMinutes = stamina.getOrElse(current.staminaMinutes),
          warnMinutes = warn.getOrElse(current.warnMinutes),
          handoverMinutes = handover.getOrElse(current.handoverMinutes)
        )
        if (updated.defaultDurationMinutes < 5 || updated.maxDurationMinutes < 5)
          Left("A claim has to be at least 5 minutes long.")
        else if (updated.defaultDurationMinutes > updated.maxDurationMinutes)
          Left(s"The default claim (${RespawnEmbeds.humanDuration(updated.defaultDurationMinutes)}) can't be " +
            s"longer than the maximum (${RespawnEmbeds.humanDuration(updated.maxDurationMinutes)}).")
        else if (updated.queueLimit < 0 || updated.staminaMinutes < 0 || updated.warnMinutes < 0)
          Left("Queue limit, stamina and reminder time can't be negative.")
        else if (updated.handoverMinutes < 1)
          Left("The handover window has to be at least a minute, or nobody could ever accept one.")
        else {
          repository.saveSettings(guildId, updated)
          // Turning a limit on for the first time hands everybody a full tank.
          // While stamina was off nothing was being spent against a budget, so
          // whatever the rows say is an artefact of some earlier setting — and
          // starting people mid-day already in debt would refuse claims for a
          // rule that wasn't in force when they hunted. The other direction needs
          // nothing: switching stamina off ignores the numbers anyway.
          if (current.staminaMinutes <= 0 && updated.staminaMinutes > 0) {
            val cleared = repository.clearStamina(guildId)
            if (cleared > 0)
              logger.info(s"Stamina switched on in guild '$guildId' — refilled $cleared tanks")
          }
          Right(updated)
        }
    }

  def updateChannels(guildId: String, forumChannel: String, boardThread: String): Unit =
    repository.updateChannels(guildId, forumChannel, boardThread)

  // --- catalogue ----------------------------------------------------------

  def listRespawns(guildId: String): List[Respawn] = repository.listRespawns(guildId)

  /** Resolve what a user typed — a code ("415"), or a name — to a catalogue
   *  entry. Autocomplete sends the code back, so the code path is the common
   *  one; the name fallbacks exist for people who type it out by hand. */
  def resolve(guildId: String, query: String): Option[Respawn] = {
    val trimmed = query.trim
    if (trimmed.isEmpty) None
    else repository.findByCode(guildId, trimmed).orElse {
      val all = repository.listRespawns(guildId)
      val lower = trimmed.toLowerCase
      // "415 — Cult Orcs" comes back from autocomplete as the display name in
      // some clients, so match that shape too before falling back to a
      // substring search.
      all.find(_.displayName.equalsIgnoreCase(trimmed))
        .orElse(all.find(_.name.equalsIgnoreCase(trimmed)))
        .orElse(all.filter(_.name.toLowerCase.contains(lower)) match {
          case single :: Nil => Some(single)
          case _             => None // ambiguous: make them pick rather than guessing
        })
    }
  }

  def removeRespawn(guildId: String, respawnId: Long): Unit = repository.removeRespawn(guildId, respawnId)

  /** Push improvements to the bundled list's creature choices out to a guild that
   *  already imported it, and report how many changed.
   *
   *  Run at boot rather than behind a command: which monster represents a spawn is
   *  curated over time, and `importSeed` never revisits a code the guild already
   *  has, so otherwise an improved list would only ever reach new guilds. Rows the
   *  guild added itself, and rows whose creature an admin picked by hand, are left
   *  alone.
   *
   *  Only `creature` is synced. Names and regions are left as the guild has them,
   *  since those are things a server may reasonably reword for itself. */
  def syncSeedCreatures(guildId: String): Int =
    repository.syncSeedCreatures(guildId, RespawnCatalogue.seed.map(s => (s.code, s.creature)))

  /** Import the bundled seed catalogue, skipping codes the guild already has.
   *  Safe to run repeatedly — it never overwrites a guild's own edits. Returns
   *  how many entries were added. */
  def importSeed(guildId: String): Int =
    repository.importSeed(guildId, RespawnCatalogue.seed.map(s => (s.code, s.region, s.name, s.creature)))

  /** Bring a guild's catalogue in line with the bundled file: codes it lacks,
   *  names and cities that have changed, and codes the file has dropped.
   *
   *  What `/repair` runs, and — with no catalogue commands left — the only way an
   *  edit to respawns.json reaches a guild that was set up before it. */
  def syncSeed(guildId: String): SeedSync =
    repository.syncSeed(guildId, RespawnCatalogue.seed.map(s => (s.code, s.region, s.name, s.creature)))

  // --- stamina ------------------------------------------------------------

  /** The start of the current server-save day — the epoch a tank is measured
   *  against. */
  private def resetBoundary(now: ZonedDateTime): ZonedDateTime = ServerSaveSchedule.lastServerSave(now)

  def stamina(guildId: String, userId: String, settings: RespawnSettings,
              now: ZonedDateTime = ZonedDateTime.now()): Stamina =
    repository.stamina(guildId, userId, settings.staminaMinutes, resetBoundary(now))

  def nextStaminaReset(now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime =
    ServerSaveSchedule.nextServerSave(now)

  def openClaimsForUser(guildId: String, userId: String): List[(Respawn, RespawnClaim)] =
    repository.openClaimsForUser(guildId, userId).flatMap { claim =>
      repository.findById(guildId, claim.respawnId).map(_ -> claim)
    }

  /** Hand a member stamina back, or take some away with a negative number.
   *
   *  Expressed as a change to what is *left* rather than to what is used, since
   *  that is what a moderator is looking at when they decide to do it. Clamped at
   *  both ends: a tank cannot go below empty, and cannot be given more than the
   *  guild's budget — a moderator putting somebody above the daily limit would be
   *  a rule nobody could see in the settings. */
  def grantStamina(guildId: String, userId: String, minutes: Int, settings: RespawnSettings,
                   now: ZonedDateTime = ZonedDateTime.now()): Stamina = {
    val boundary = resetBoundary(now)
    val tank = repository.stamina(guildId, userId, settings.staminaMinutes, boundary)
    val wanted = math.max(0, math.min(settings.staminaMinutes, tank.remainingMinutes + minutes))
    repository.setStaminaUsed(guildId, userId, settings.staminaMinutes - wanted, boundary)
    repository.stamina(guildId, userId, settings.staminaMinutes, boundary)
  }

  /** When a claim starting at `startsAt` for `minutes` should end.
   *
   *  Trivial today. It exists as its own function because it is the single
   *  place a future scheduled-claim feature has to truncate an ad-hoc claim
   *  that would run into someone's reserved window — putting that logic here
   *  later means no call site changes.
   */
  def endsAtFor(startsAt: ZonedDateTime, minutes: Int,
                nextReservation: Option[ZonedDateTime] = None): ZonedDateTime = {
    val wanted = startsAt.plusMinutes(minutes.toLong)
    // Stop short of a booked slot rather than running into it. Taking the
    // reservation as a parameter keeps this pure and directly testable, which is
    // the whole reason the deadline is computed here rather than at each caller.
    nextReservation.filter(_.isBefore(wanted)).getOrElse(wanted)
  }

  /** The shortest claim worth granting. Below this, truncating against a booked
   *  slot gives somebody a hunt that is over before they have walked there, so
   *  the claim is refused with an explanation instead. */
  val MinimumClaimMinutes: Int = 5

  /** How far into their own hunt a slot's owner may still answer a request.
   *
   *  A deadline landing exactly on the start punishes somebody who is logging in
   *  on time: they arrive to find the slot already gone. A few minutes past it
   *  costs the asker very little — the hunt they take over runs its full length
   *  from whenever it starts — and turns "you were a minute late" into "you never
   *  turned up", which is the thing the question was actually asking. */
  val RequestGraceMinutes: Int = 5

  /** When an owner has to answer by: `minutes` from now, but never longer than a
   *  short grace past the start of the slot in question.
   *
   *  Both request paths clamp the same way. The clamp is what stops a request
   *  made hours ahead from waiting hours, and the grace is what stops one made
   *  minutes ahead from being no time at all. */
  private[respawn] def answerDeadline(now: ZonedDateTime, slotStart: ZonedDateTime,
                                      minutes: Int): ZonedDateTime = {
    val latest = slotStart.plusMinutes(RequestGraceMinutes.toLong)
    val wanted = now.plusMinutes(minutes.toLong)
    if (wanted.isAfter(latest)) latest else wanted
  }

  /** When the next booked slot on a spawn starts, if there is one. */
  def nextReservationStart(guildId: String, respawnId: Long,
                           now: ZonedDateTime = ZonedDateTime.now()): Option[ZonedDateTime] =
    repository.reservationsFor(guildId, respawnId, now).flatMap(_.startsAt).headOption

  /** Claim a spawn, or join its queue if someone already holds it.
   *
   *  Discord side effects (creating/reviving the post, rewriting the card) are
   *  applied through `withDiscord`, which the caller supplies. The claim is
   *  committed to the database before any of that runs, so a Discord failure
   *  leaves a valid claim with a stale-looking post rather than losing the
   *  claim.
   */
  def claim(guild: Guild, userId: String, userName: String, characterName: String,
            query: String, requestedMinutes: Option[Int],
            now: ZonedDateTime = ZonedDateTime.now()): ClaimOutcome =
    settings(guild.getId) match {
      case None => ClaimOutcome.NotConfigured
      case Some(config) =>
        resolve(guild.getId, query) match {
          case None => ClaimOutcome.UnknownSpawn(query)
          case Some(respawn) =>
            // Explicit request wins, then the member's own default, then the
            // guild's — so the forum buttons (which never pass a duration) honour
            // whatever the member set through Config.
            val minutes = requestedMinutes.getOrElse(
              repository.userPrefs(guild.getId, userId).defaultDurationOr(config.defaultDurationMinutes))
            if (minutes <= 0 || minutes > config.maxDurationMinutes)
              ClaimOutcome.BadDuration(minutes, config.maxDurationMinutes)
            else {
              val guildId = guild.getId
              val alreadyHeld = repository.openClaimsForUser(guildId, userId).find(_.respawnId == respawn.id)
              alreadyHeld match {
                case Some(existing) => ClaimOutcome.AlreadyHolding(respawn, existing)
                case None =>
                  val boundary = resetBoundary(now)
                  val tank = repository.stamina(guildId, userId, config.staminaMinutes, boundary)
                  if (!tank.canAfford(minutes))
                    ClaimOutcome.NoStamina(respawn, minutes, tank, ServerSaveSchedule.nextServerSave(now))
                  // An outstanding offer means the spawn is already spoken for,
                  // even though its previous holder may already have been closed
                  // out. Without this, claiming it outright would leave two live
                  // claims the moment the offer was accepted.
                  else if (repository.activeClaim(guildId, respawn.id).isDefined ||
                           repository.offeredClaim(guildId, respawn.id).isDefined)
                    enqueue(guild, respawn, config, userId, userName, characterName, minutes)
                  else
                    beginClaim(guild, respawn, config, userId, userName, characterName, minutes,
                      RespawnClaim.KindAdHoc, now)
              }
            }
        }
    }

  /** Start a claim right now. The single place a claim becomes active — the
   *  command path, the Claim button, queue promotion on expiry, and any future
   *  scheduled claim all come through here, so stamina reservation and the
   *  thread update can't be forgotten by one of them. */
  def beginClaim(guild: Guild, respawn: Respawn, config: RespawnSettings, userId: String,
                 userName: String, characterName: String, minutes: Int, kind: String,
                 now: ZonedDateTime): ClaimOutcome = {
    val guildId = guild.getId
    val boundary = resetBoundary(now)

    // A booked slot cuts an ad-hoc claim short. Stamina is then charged for the
    // shorter hunt, not the one that was asked for — nobody should pay for time
    // a reservation takes back. A scheduled occurrence starting its own slot is
    // exempt, or it would truncate against itself.
    val reservation =
      if (kind == RespawnClaim.KindScheduled) None else nextReservationStart(guildId, respawn.id, now)
    val end = endsAtFor(now, minutes, reservation)
    val granted = math.max(0, java.time.Duration.between(now, end).toMinutes).toInt
    if (granted < MinimumClaimMinutes)
      return ClaimOutcome.Reserved(respawn, reservation.getOrElse(end))
    // Re-check under the reservation itself rather than trusting the earlier
    // read: a second claim from the same user may have taken the room in
    // between, and reserveStamina writing nothing is the authoritative answer.
    if (!repository.reserveStamina(guildId, userId, granted, config.staminaMinutes, boundary)) {
      val tank = repository.stamina(guildId, userId, config.staminaMinutes, boundary)
      ClaimOutcome.NoStamina(respawn, granted, tank, ServerSaveSchedule.nextServerSave(now))
    } else {
      repository.insertActiveClaim(guildId, respawn.id, userId, userName, characterName,
        now, end, granted, kind) match {
        case None =>
          // Somebody claimed it between the check above and this insert. Hand the
          // stamina straight back rather than stranding it until server save, and
          // say so — silently queueing them would be answering a question they
          // did not ask.
          repository.refundStamina(guildId, userId, granted, boundary)
          ClaimOutcome.JustTaken(respawn)
        case Some(claim) =>
          refreshThread(guild, respawn, config)
          // The caller is told when the hunt was shortened, rather than quietly
          // getting less than they asked for.
          if (granted < minutes) ClaimOutcome.Shortened(respawn, claim, minutes, reservation)
          else ClaimOutcome.Claimed(respawn, claim)
      }
    }
  }

  private def enqueue(guild: Guild, respawn: Respawn, config: RespawnSettings, userId: String,
                      userName: String, characterName: String, minutes: Int): ClaimOutcome = {
    // Queueing deliberately does NOT reserve stamina. A queue that may never
    // reach the front would otherwise let people park their whole tank in other
    // people's queues; the reservation happens at promotion instead, and
    // someone who can't afford it by then is skipped rather than blocking the
    // line (see sweepGuild).
    repository.enqueueClaim(guild.getId, respawn.id, userId, userName, characterName, minutes,
      config.queueLimit, RespawnClaim.KindAdHoc) match {
      case None =>
        repository.openClaimsForUser(guild.getId, userId).find(_.respawnId == respawn.id) match {
          case Some(existing) => ClaimOutcome.AlreadyHolding(respawn, existing)
          case None           => ClaimOutcome.QueueFull(respawn, config.queueLimit)
        }
      case Some(queued) =>
        refreshThread(guild, respawn, config)
        ClaimOutcome.Queued(respawn, queued, queued.queuePosition)
    }
  }

  /** Give up an active claim (refunding the unused time) or a queue slot.
   *  `query` narrows it to one spawn when the caller holds several. */
  def release(guild: Guild, userId: String, query: Option[String],
              now: ZonedDateTime = ZonedDateTime.now(),
              // What the audit log should say. Overridden by forceLeave, which is
              // the same operation performed by somebody else.
              outcome: String = RespawnClaim.Outcome.Released): ReleaseOutcome =
    settings(guild.getId) match {
      case None => ReleaseOutcome.NotConfigured
      case Some(config) =>
        val guildId = guild.getId
        val held = repository.openClaimsForUser(guildId, userId)
        val target = query.flatMap(resolve(guildId, _)) match {
          case Some(respawn) => held.find(_.respawnId == respawn.id)
          // No spawn named: release the active claim if there's exactly one to
          // be unambiguous about, otherwise the first by deadline.
          case None          => held.find(_.isActive).orElse(held.headOption)
        }

        target match {
          case None => ReleaseOutcome.NothingHeld
          case Some(claim) =>
            val respawn = repository.findById(guildId, claim.respawnId)
            if (claim.isQueued || claim.isOffered) {
              repository.cancelClaim(guildId, claim.id,
                if (claim.isOffered) RespawnClaim.Outcome.Declined else RespawnClaim.Outcome.LeftQueue)
              // Only an *offered* claim leaving means a handover is in flight and
              // has to move on. Somebody merely giving up a queue place changes
              // nothing about the spawn itself — the holder is mid-hunt — so
              // advancing here would hand their spawn away, or end it outright
              // when they were the last one queued.
              if (claim.leavingAdvancesHandover)
                respawn.foreach(r => beginHandover(guild, r, config, now, outgoing = handingOverHolder(guildId, r.id)))
              else
                respawn.foreach(refreshThread(guild, _, config))
              respawn.map(ReleaseOutcome.LeftQueue).getOrElse(ReleaseOutcome.NothingHeld)
            } else if (claim.limboUntil.isDefined) {
              // Already released or expired and waiting on a handover. Releasing
              // again must not refund a second time — the refund below is capped
              // by time remaining, and `ends_at` is deliberately left untouched
              // during limbo, so a second call would hand back the same minutes.
              ReleaseOutcome.AlreadyHandingOver(respawn.map(_.displayName).getOrElse("that respawn"))
            } else {
              val refunded = refundFor(claim, now)
              if (refunded > 0) repository.refundStamina(guildId, userId, refunded, resetBoundary(now))
              // The claim is NOT finished here: an early release still gives the
              // next person their answer window, and the spawn stays this
              // claimant's until they take it or it lapses, so it can't be
              // sniped by a third party in between. beginHandover finishes the
              // claim itself when there's nobody to hand it to.
              val offered = respawn.flatMap(
                beginHandover(guild, _, config, now, outgoing = Some(claim), outgoingOutcome = outcome))
              respawn.map(ReleaseOutcome.Released(_, refunded, offered)).getOrElse(ReleaseOutcome.NothingHeld)
            }
        }
    }

  /** Unused whole minutes left on a claim — what a release gives back. Rounded
   *  down so ending a claim can never refund more than was reserved, and zero
   *  once the claim is in limbo, since by then its refund has already been
   *  settled (or its full time was used). */
  private def refundFor(claim: RespawnClaim, now: ZonedDateTime): Int =
    if (claim.limboUntil.isDefined) 0
    else claim.endsAt.map { end =>
      val remaining = java.time.Duration.between(now, end).toMinutes
      math.max(0, math.min(claim.durationMinutes.toLong, remaining)).toInt
    }.getOrElse(0)

  /** The claim still shown as holding a spawn, including one in limbo. */
  private def activeHolder(guildId: String, respawnId: Long): Option[RespawnClaim] =
    repository.activeClaim(guildId, respawnId)

  /** The holder of a spawn *that is already on its way out* — the only claim a
   *  handover may legitimately finish.
   *
   *  A handover closes out whoever it is replacing, so passing it a claim that
   *  isn't being handed over ends a hunt that is still running. Limbo is exactly
   *  the marker for "this claim's time is up and the next person is deciding", so
   *  requiring it makes the mistake impossible rather than merely unlikely. */
  private def handingOverHolder(guildId: String, respawnId: Long): Option[RespawnClaim] =
    repository.activeClaim(guildId, respawnId).filter(_.eligibleForHandover)

  /** Add time to the caller's active claim, within the guild's ceiling and
   *  their remaining stamina. Returns the new end time on success. */
  def extend(guild: Guild, userId: String, extraMinutes: Int,
             now: ZonedDateTime = ZonedDateTime.now()): Either[ClaimOutcome, (Respawn, ZonedDateTime)] =
    settings(guild.getId) match {
      case None => Left(ClaimOutcome.NotConfigured)
      case Some(config) =>
        val guildId = guild.getId
        // A claim in limbo is on its way out; adding time to it would charge
        // stamina for a spawn that has already been offered to someone else.
        repository.openClaimsForUser(guildId, userId).find(c => c.isActive && c.limboUntil.isEmpty) match {
          case None => Left(ClaimOutcome.UnknownSpawn(""))
          case Some(claim) =>
            val newTotal = claim.durationMinutes + extraMinutes
            if (extraMinutes <= 0 || newTotal > config.maxDurationMinutes)
              Left(ClaimOutcome.BadDuration(newTotal, config.maxDurationMinutes))
            else {
              val boundary = resetBoundary(now)
              val respawn = repository.findById(guildId, claim.respawnId)
              if (!repository.reserveStamina(guildId, userId, extraMinutes, config.staminaMinutes, boundary)) {
                val tank = repository.stamina(guildId, userId, config.staminaMinutes, boundary)
                Left(respawn
                  .map(ClaimOutcome.NoStamina(_, extraMinutes, tank, ServerSaveSchedule.nextServerSave(now)))
                  .getOrElse(ClaimOutcome.NotConfigured))
              } else {
                val newEnd = claim.endsAt.getOrElse(now).plusMinutes(extraMinutes.toLong)
                repository.extendClaim(guildId, claim.id, newEnd, newTotal)
                respawn.foreach(refreshThread(guild, _, config))
                respawn.map(r => Right((r, newEnd))).getOrElse(Left(ClaimOutcome.NotConfigured))
              }
            }
        }
    }

  /** Change how long the caller's claim on one spawn runs.
   *
   *  Behind the Config button on a spawn's card, so someone who has misjudged a
   *  hunt can adjust without releasing and re-claiming. Works for a queued or
   *  offered claim too — a duration only matters once it starts, and it is the
   *  same thing the member is choosing.
   *
   *  Stamina is settled on the difference, not the new total: growing a claim
   *  reserves the extra (and is refused if it doesn't fit), shrinking one hands
   *  the remainder back. The new total can't go below what has already elapsed —
   *  those minutes are spent whatever the member now says — so shortening past
   *  that point simply ends the hunt as soon as the next sweep runs.
   */
  def setClaimDuration(guild: Guild, userId: String, respawnId: Long, newTotalMinutes: Int,
                       now: ZonedDateTime = ZonedDateTime.now()): Either[String, (Respawn, Int)] =
    settings(guild.getId) match {
      case None => Left("The respawn claim system isn't set up on this server yet.")
      case Some(config) =>
        val guildId = guild.getId
        if (newTotalMinutes < 5 || newTotalMinutes > config.maxDurationMinutes)
          Left(s"A claim has to be between 5 minutes and " +
            s"${RespawnEmbeds.humanDuration(config.maxDurationMinutes)} on this server.")
        else repository.openClaimsForUser(guildId, userId).find(_.respawnId == respawnId) match {
          case None => Left("You aren't holding or waiting for that respawn.")
          case Some(claim) =>
            val respawn = repository.findById(guildId, respawnId)
            if (!claim.isActive) {
              // Queued and offered claims have reserved nothing yet, so this is
              // just a stored number until they start.
              repository.setClaimDuration(guildId, claim.id, newTotalMinutes, None)
              respawn.foreach(refreshThread(guild, _, config))
              respawn.map(r => Right((r, newTotalMinutes))).getOrElse(Left("That respawn is gone."))
            } else {
              val start = claim.startsAt.getOrElse(claim.claimedAt)
              val elapsed = math.max(0L, java.time.Duration.between(start, now).toMinutes).toInt
              val total = math.max(newTotalMinutes, elapsed)
              val delta = total - claim.durationMinutes
              val boundary = resetBoundary(now)

              val affordable =
                if (delta <= 0) true
                else repository.reserveStamina(guildId, userId, delta, config.staminaMinutes, boundary)

              if (!affordable) {
                val tank = repository.stamina(guildId, userId, config.staminaMinutes, boundary)
                Left(s"That would need **${RespawnEmbeds.humanDuration(delta)}** more stamina but you only have " +
                  s"**${RespawnEmbeds.humanDuration(tank.remainingMinutes)}** left. " +
                  s"Your tank refills at server save <t:${ServerSaveSchedule.nextServerSave(now).toInstant.getEpochSecond}:R>.")
              } else {
                if (delta < 0) repository.refundStamina(guildId, userId, -delta, boundary)
                repository.setClaimDuration(guildId, claim.id, total, Some(start.plusMinutes(total.toLong)))
                respawn.foreach(refreshThread(guild, _, config))
                respawn.map(r => Right((r, total))).getOrElse(Left("That respawn is gone."))
              }
            }
        }
    }

  /** End whoever currently holds a spawn, as a moderator.
   *
   *  Deliberately the same path as that person pressing Leave themselves — unused
   *  stamina refunded, the next in line offered their window — rather than the
   *  blunter `adminClear`, which also wipes the queue. Someone being moved off a
   *  spawn is no reason to punish everybody waiting behind them.
   *
   *  The holder is told, since otherwise the spawn simply vanishes from under
   *  them with no explanation. */
  def forceLeave(guild: Guild, respawn: Respawn,
                 now: ZonedDateTime = ZonedDateTime.now()): Option[RespawnClaim] = {
    val guildId = guild.getId
    repository.activeClaim(guildId, respawn.id).map { holder =>
      release(guild, holder.userId, Some(respawn.code), now, RespawnClaim.Outcome.Forced)
      RespawnThreads.dm(guild, holder.userId,
        RespawnEmbeds.dmEmbed("Claim ended by a moderator",
          s"A moderator has freed **${respawn.displayName}**, so it's no longer yours.",
          imageFor(respawn), RespawnEmbeds.RedColor))
      holder
    }
  }

  /** Hand a running hunt to somebody else, for a moderator sorting out a
   *  dispute or a mistaken claim.
   *
   *  The stamina goes with the hunt: whatever is left of it is charged to the new
   *  holder and refunded to the old. Refused outright if the new holder cannot
   *  afford it, because the alternative is handing somebody a hunt that the next
   *  sweep would cut short — and leaving the cost on the person who no longer has
   *  the spawn would be worse still.
   *
   *  Both are told. The one losing it especially: their hunt ending is not
   *  something they should have to notice from the card. */
  def reassignClaim(guild: Guild, respawnId: Long, toUserId: String, toUserName: String,
                    now: ZonedDateTime = ZonedDateTime.now()): Either[String, (Respawn, RespawnClaim)] =
    settings(guild.getId) match {
      case None => Left("The respawn claim system isn't set up on this server yet.")
      case Some(config) =>
        val guildId = guild.getId
        (repository.activeClaim(guildId, respawnId), repository.findById(guildId, respawnId)) match {
          case (None, _) => Left("Nobody is holding that respawn.")
          case (_, None) => Left("That respawn is no longer in the catalogue.")
          case (Some(claim), Some(respawn)) if claim.userId == toUserId =>
            Right((respawn, claim))
          case (Some(claim), Some(respawn)) =>
            val boundary = resetBoundary(now)
            val remaining = claim.minutesLeftAt(now)
            if (remaining > 0 &&
                !repository.reserveStamina(guildId, toUserId, remaining, config.staminaMinutes, boundary)) {
              val tank = repository.stamina(guildId, toUserId, config.staminaMinutes, boundary)
              Left(s"<@$toUserId> has **${RespawnEmbeds.humanDuration(tank.remainingMinutes)}** of " +
                s"stamina left, and the rest of this hunt needs " +
                s"**${RespawnEmbeds.humanDuration(remaining)}**.")
            } else repository.reassignClaim(guildId, claim.id, toUserId, toUserName) match {
              case None => Left("That hunt has already ended.")
              case Some(moved) =>
                if (remaining > 0) repository.refundStamina(guildId, claim.userId, remaining, boundary)
                RespawnThreads.dm(guild, claim.userId,
                  RespawnEmbeds.dmEmbed("Your hunt was reassigned",
                    RespawnEmbeds.claimReassignedFrom(respawn, toUserId), imageFor(respawn),
                    RespawnEmbeds.RedColor))
                RespawnThreads.dm(guild, toUserId,
                  RespawnEmbeds.dmEmbed("The hunt is yours",
                    RespawnEmbeds.claimReassignedTo(respawn, moved), imageFor(respawn),
                    RespawnEmbeds.FreeColor))
                refreshThread(guild, respawn, config)
                Right((respawn, moved))
            }
        }
    }

  /** The claim currently holding a spawn, for callers that need to act on its
   *  owner rather than on themselves. */
  def holderOf(guildId: String, respawnId: Long): Option[RespawnClaim] =
    repository.activeClaim(guildId, respawnId)

  def schedulesForRespawn(guildId: String, respawnId: Long): List[RespawnSchedule] =
    repository.schedulesForRespawn(guildId, respawnId)

  def schedulesForUser(guildId: String, userId: String): List[RespawnSchedule] =
    repository.schedulesForUser(guildId, userId)

  /** Standing bookings paired with the spawn each is on, ready to render.
   *
   *  Resolves the spawn here rather than leaving the caller to: a schedule whose
   *  spawn has since been removed would otherwise render as a blank line, and
   *  dropping it is the honest answer. */
  def scheduleListing(guildId: String, userId: Option[String]): List[(RespawnSchedule, Respawn)] = {
    val schedules = userId.fold(repository.allSchedules(guildId))(repository.schedulesForUser(guildId, _))
    schedules.flatMap(schedule => repository.findById(guildId, schedule.respawnId).map(schedule -> _))
  }

  def findSchedule(guildId: String, scheduleId: Long): Option[RespawnSchedule] =
    repository.findSchedule(guildId, scheduleId)

  /** Book a slot on a spawn, repeating on chosen weekdays or happening once.
   *
   *  Two bookings on the same spawn at the same time is not a state the handover
   *  rules can resolve, and silently letting the second win would take a slot from
   *  somebody who had it first — so a clash is never simply allowed. What it does
   *  instead depends on the clash: a one-off over a single booked slot nobody has
   *  asked about becomes a question for that slot's owner (see [[askForClash]]),
   *  and anything else is still refused outright. */
  def addSchedule(guild: Guild, respawn: Respawn, userId: String, userName: String,
                  characterName: String, firstStart: ZonedDateTime, durationMinutes: Int,
                  daysOfWeek: Int = RespawnSchedule.EveryDay,
                  now: ZonedDateTime = ZonedDateTime.now()): Either[String, ScheduleResult] =
    settings(guild.getId) match {
      case None => Left("The respawn claim system isn't set up on this server yet.")
      case Some(config) =>
        val guildId = guild.getId
        if (durationMinutes < MinimumClaimMinutes || durationMinutes > config.maxDurationMinutes)
          Left(s"A slot has to be between ${RespawnEmbeds.humanDuration(MinimumClaimMinutes)} and " +
            s"${RespawnEmbeds.humanDuration(config.maxDurationMinutes)} on this server.")
        else if (!firstStart.isAfter(now))
          Left("The first slot has to start in the future.")
        else if (durationMinutes >= RespawnSchedule.Daily)
          Left("A slot has to be shorter than a day.")
        else {
          val candidate = RespawnSchedule(0L, respawn.id, userId, userName, characterName,
            firstStart, RespawnSchedule.Daily, durationMinutes, active = true, now, daysOfWeek)
          val schedules = repository.schedulesForRespawn(guildId, respawn.id).filter(overlaps(_, candidate))
          val slots = clashingReservations(guildId, respawn.id, candidate, now)
          if (schedules.isEmpty && slots.isEmpty) {
            val saved = repository.addSchedule(guildId, respawn.id, userId, userName, characterName,
              firstStart, RespawnSchedule.Daily, durationMinutes, daysOfWeek)
            materialise(guildId, saved, now)
            refreshThread(guild, respawn, config)
            Right(ScheduleResult.Booked(saved))
          } else askForClash(guild, respawn, config, candidate, schedules, slots, now)
        }
    }

  /** A booking that clashes: ask the other person, or refuse.
   *
   *  Only one shape of clash is worth asking about — a booking that happens once,
   *  landing on exactly one booked slot, owned by somebody else, that nobody has
   *  asked about yet. Everything else is refused, and each refusal says which
   *  rule it fell foul of:
   *
   *  A repeating booking is refused because the question is about one evening,
   *  and an answer for tonight cannot stand in for every Tuesday from now on. A
   *  clash across two slots is refused because it would mean asking two people
   *  and granting the booking only if both said yes, which is a negotiation
   *  rather than a request. A clash with a schedule whose occurrence has not been
   *  booked yet is refused because there is no slot to attach the question to —
   *  it is simply too far ahead, and nearer the day it becomes askable.
   *
   *  Nothing is written for the asker here. Their booking exists only as the
   *  window recorded against the slot they asked for, and is created if and when
   *  the answer goes their way — so a refused request leaves nothing behind. */
  private def askForClash(guild: Guild, respawn: Respawn, config: RespawnSettings,
                          candidate: RespawnSchedule, schedules: List[RespawnSchedule],
                          slots: List[RespawnClaim], now: ZonedDateTime): Either[String, ScheduleResult] = {
    val guildId = guild.getId
    def refuse(why: String): Either[String, ScheduleResult] =
      Left(clashMessage(schedules, slots, now) + why)

    RespawnSchedule.verdict(candidate, schedules, slots) match {
      case ClashVerdict.Yours =>
        Left("You already have a booking on this respawn over that time.")
      case ClashVerdict.Repeats =>
        refuse(" A booking that repeats has to go on a time nobody else has taken.")
      case ClashVerdict.TooFarAhead =>
        refuse(" It's too far ahead to ask them about — try again nearer the day.")
      case ClashVerdict.AlreadyAsked =>
        refuse(" Its owner has already been asked about that slot once, which is the limit.")
      case ClashVerdict.ManySlots =>
        refuse(" It runs over more than one booking, so there's nobody single to ask.")

      case ClashVerdict.Ask(slot) =>
        val deadline = answerDeadline(now, slot.startsAt.getOrElse(now),
          Config.Respawn.bookingRequestResponseMinutes)
        val theirs = Some((candidate.anchorAt, candidate.durationMinutes))

        repository.requestOccurrence(guildId, slot.id, candidate.userId, candidate.userName,
          now, deadline, theirs) match {
          case None => refuse(" Somebody else asked about it first.")
          case Some(asked) =>
            RespawnThreads.dm(guild, asked.userId,
              RespawnEmbeds.dmEmbed("Are you hunting tonight?",
                RespawnEmbeds.slotRequest(respawn, asked, deadline, theirs),
                imageFor(respawn), RespawnEmbeds.WarnColor),
              Some(RespawnThreads.slotAnswerButtons(guildId, asked.id)))
            // No card rewrite. Asking changes nothing about who holds the spawn or
            // what is booked — only the *asked* note and whether Request is
            // offered — and card edits are the system's scarcest resource. Both
            // catch up on the next refresh something real causes, and a Request
            // button pressed in the meantime answers "already asked" for free.
            Right(ScheduleResult.Requested(respawn, asked, deadline))
        }
    }
  }

  /** Who a clash is with, preferring a booked slot over the rule behind it — the
   *  slot knows the night it is actually on. */
  private def clashMessage(schedules: List[RespawnSchedule], slots: List[RespawnClaim],
                           now: ZonedDateTime): String = {
    val (who, when, minutes) = slots.headOption match {
      case Some(slot) => (slot.userId, slot.startsAt, slot.durationMinutes)
      case None =>
        val schedule = schedules.head
        (schedule.userId, schedule.nextStartAtOrAfter(now), schedule.durationMinutes)
    }
    val at = when.map(start => s"<t:${start.toInstant.getEpochSecond}:t>").getOrElse("soon")
    s"That clashes with <@$who>'s slot on this respawn ($at for ${RespawnEmbeds.humanDuration(minutes)})."
  }

  /** Booked slots on a spawn that any occurrence of `candidate` would run over.
   *
   *  Checked alongside the schedule-to-schedule rule rather than instead of it,
   *  because the two see different things. A slot handed to whoever asked for it
   *  is a booking with no schedule behind it, so comparing rules alone would let
   *  somebody book straight over it; and a rule whose next occurrence is beyond
   *  the look-ahead has no slot yet, so comparing slots alone would let two
   *  standing bookings collide the day they finally meet. */
  private def clashingReservations(guildId: String, respawnId: Long, candidate: RespawnSchedule,
                                   now: ZonedDateTime): List[RespawnClaim] = {
    val horizon = now.plusMinutes(Config.Respawn.scheduleLookAheadMinutes.toLong)
    repository.reservationsFor(guildId, respawnId, now)
      .filter(candidate.overlapsSlot(_, now, horizon))
  }

  /** Whether two bookings on the same spawn ever run at the same time. The rule
   *  itself lives in the domain, where it is testable without a database. */
  private[respawn] def overlaps(a: RespawnSchedule, b: RespawnSchedule): Boolean =
    RespawnSchedule.clash(a, b)

  /** Retire a schedule and drop the slots it had booked but not yet started. */
  /** Drop every booking one member holds anywhere in the guild.
   *
   *  Each spawn's card is rewritten once, not once per booking — somebody
   *  clearing five bookings across three spawns costs three edits, not five. */
  def cancelAllBookings(guild: Guild, userId: String): Int = {
    val guildId = guild.getId
    val mine = repository.schedulesForUser(guildId, userId)
    mine.foreach { schedule =>
      repository.deactivateSchedule(guildId, schedule.id)
      repository.cancelReservationsOf(guildId, schedule.id, RespawnClaim.Outcome.ScheduleCancelled)
    }
    for {
      config <- settings(guildId)
      respawnId <- mine.map(_.respawnId).distinct
      respawn <- repository.findById(guildId, respawnId)
    } refreshThread(guild, respawn, config)
    mine.size
  }

  /** Drop every booking on one spawn, whoever owns it — a moderator clearing a
   *  respawn's diary. Returns how many went. */
  def cancelAllBookingsOn(guild: Guild, respawnId: Long): Int = {
    val guildId = guild.getId
    val all = repository.schedulesForRespawn(guildId, respawnId)
    all.foreach { schedule =>
      repository.deactivateSchedule(guildId, schedule.id)
      repository.cancelReservationsOf(guildId, schedule.id, RespawnClaim.Outcome.ScheduleCancelled)
    }
    if (all.nonEmpty) {
      for {
        config <- settings(guildId)
        respawn <- repository.findById(guildId, respawnId)
      } refreshThread(guild, respawn, config)
    }
    all.size
  }

  /** Drop every booking one member holds on one spawn.
   *
   *  All of them together rather than one at a time: a member's bookings on a
   *  spawn are one decision to them, and a button per booking made the panel a
   *  row of near-identical red buttons. Returns how many went, and rewrites the
   *  spawn's card once at the end rather than once per booking. */
  def cancelBookingsOn(guild: Guild, respawnId: Long, userId: String): Int = {
    val guildId = guild.getId
    val mine = repository.schedulesForUser(guildId, userId).filter(_.respawnId == respawnId)
    mine.foreach { schedule =>
      repository.deactivateSchedule(guildId, schedule.id)
      repository.cancelReservationsOf(guildId, schedule.id, RespawnClaim.Outcome.ScheduleCancelled)
    }
    if (mine.nonEmpty) {
      for {
        config <- settings(guildId)
        respawn <- repository.findById(guildId, respawnId)
      } refreshThread(guild, respawn, config)
    }
    mine.size
  }

  def cancelSchedule(guild: Guild, scheduleId: Long): Option[RespawnSchedule] = {
    val guildId = guild.getId
    repository.findSchedule(guildId, scheduleId).map { schedule =>
      repository.deactivateSchedule(guildId, scheduleId)
      repository.cancelReservationsOf(guildId, scheduleId, RespawnClaim.Outcome.ScheduleCancelled)
      for {
        config <- settings(guildId)
        respawn <- repository.findById(guildId, schedule.respawnId)
      } refreshThread(guild, respawn, config)
      schedule
    }
  }

  /** Book every slot of a schedule that starts within the look-ahead.
   *
   *  Booking ahead is what makes a slot visible on the card — and, from phase 2,
   *  requestable — before it begins. Idempotent: the (schedule, start) pair is
   *  unique in the database, so re-running on every sweep books nothing twice. */
  private def materialise(guildId: String, schedule: RespawnSchedule, now: ZonedDateTime): Int = {
    val horizon = now.plusMinutes(Config.Respawn.scheduleLookAheadMinutes.toLong)
    schedule.occurrencesBetween(now, horizon).count { start =>
      repository.reserveOccurrence(guildId, schedule.id, schedule.respawnId, schedule.userId,
        schedule.userName, schedule.characterName, start, schedule.durationMinutes).isDefined
    }
  }

  // --- asking for a booked slot -------------------------------------------

  /** The owner says they are hunting it: the request is refused and the slot
   *  stays theirs. */
  def keepSlot(guild: Guild, userId: String, claimId: Long): SlotAnswer =
    withOwnedSlot(guild, userId, claimId) { (config, respawn, slot) =>
      repository.keepOccurrence(guild.getId, claimId) match {
        case None => SlotAnswer.Gone
        case Some(_) =>
          slot.requesterUserId.foreach { requester =>
            RespawnThreads.dm(guild, requester,
              RespawnEmbeds.dmEmbed("Slot request declined",
                RespawnEmbeds.slotRequestDeclined(respawn, slot), imageFor(respawn),
                RespawnEmbeds.RedColor))
          }
          // Nor here: the slot stays exactly where it was, with the same owner
          // and the same time. Only the *asked* note goes, which is not worth an
          // edit of its own.
          SlotAnswer.Kept(respawn)
      }
    }

  /** The owner isn't hunting it, or never answered: the slot passes to whoever
   *  asked, as a booking of their own.
   *
   *  What they get is the window they asked for, which is this slot when they
   *  pressed Request and their own when they asked by trying to book over it.
   *  The second can be longer than the slot being given up, so it is checked
   *  against the rest of the evening first: the owner has said they aren't
   *  hunting either way, so their slot goes regardless, but the asker is only
   *  given a window that is genuinely free. */
  def passSlot(guild: Guild, userId: String, claimId: Long, outcome: String,
               now: ZonedDateTime = ZonedDateTime.now()): SlotAnswer =
    withOwnedSlot(guild, userId, claimId) { (config, respawn, slot) =>
      val guildId = guild.getId
      val granted = slot.requestedSlot.orElse(slot.startsAt.map(_ -> slot.durationMinutes))
      (slot.requesterUserId, granted) match {
        case (None, _) | (_, None) => SlotAnswer.Gone
        case (Some(requester), Some((start, minutes))) =>
          val end = start.plusMinutes(minutes.toLong)
          val inTheWay = repository.reservationsFor(guildId, respawn.id, now)
            .filter(_.id != slot.id)
            .filter(other => other.startsAt.exists { otherStart =>
              otherStart.isBefore(end) && start.isBefore(otherStart.plusMinutes(other.durationMinutes.toLong))
            })
          repository.cancelClaim(guildId, slot.id, outcome)
          if (inTheWay.nonEmpty) {
            RespawnThreads.dm(guild, requester,
              RespawnEmbeds.dmEmbed("The slot is free, but not yours",
                RespawnEmbeds.slotRequestBlocked(respawn, start, minutes), imageFor(respawn),
                RespawnEmbeds.WarnColor))
            refreshThread(guild, respawn, config)
            SlotAnswer.PassedUnclaimed(respawn)
          } else {
            // A booking of their own rather than a rewritten row: the slot is no
            // longer an occurrence of anybody's standing rule, and the audit trail
            // keeps both halves of what happened.
            repository.reserveFor(guildId, respawn.id, requester,
              slot.requesterUserName.getOrElse(""), start, minutes)
            RespawnThreads.dm(guild, requester,
              RespawnEmbeds.dmEmbed("The hunt is yours",
                RespawnEmbeds.slotRequestGranted(respawn, start, minutes), imageFor(respawn)))
            refreshThread(guild, respawn, config)
            SlotAnswer.Passed(respawn, requester)
          }
      }
    }

  /** Shared guard for the two answers: the slot has to still be pending, and only
   *  its owner may answer for it. `userId` empty means the sweep is answering on
   *  a lapsed deadline, which bypasses the ownership check. */
  private def withOwnedSlot(guild: Guild, userId: String, claimId: Long)
                           (body: (RespawnSettings, Respawn, RespawnClaim) => SlotAnswer): SlotAnswer =
    settings(guild.getId) match {
      case None => SlotAnswer.Gone
      case Some(config) =>
        val guildId = guild.getId
        repository.findClaimById(guildId, claimId) match {
          case Some(slot) if userId.nonEmpty && slot.userId != userId => SlotAnswer.NotYours
          case Some(slot) if !slot.requestPending => SlotAnswer.Gone
          case Some(slot) =>
            repository.findById(guildId, slot.respawnId)
              .map(respawn => body(config, respawn, slot))
              .getOrElse(SlotAnswer.Gone)
          case None => SlotAnswer.Gone
        }
    }

  // --- lifecycle sweep ----------------------------------------------------

  /** Resolve everything whose time has come for one guild: expired claims get
   *  closed and their queues advanced, and claims nearing their deadline get a
   *  one-off warning.
   *
   *  Called on a fixed interval rather than driven by per-claim timers, which
   *  is what makes it survive restarts: nothing is scheduled in memory, so a
   *  claim that lapsed while the bot was down is picked up by the first sweep
   *  after it comes back.
   */
  def sweep(guild: Guild, now: ZonedDateTime = ZonedDateTime.now()): Unit =
    settings(guild.getId).foreach { config =>
      val guildId = guild.getId

      // Book upcoming slots, so they show on the card before they begin.
      repository.activeSchedules(guildId).foreach { schedule =>
        Try {
          materialise(guildId, schedule, now)
          // A one-off whose slot has gone by is spent. Retiring it keeps it out
          // of the owner's booking list and off their allowance — the slot it
          // booked is a claim row of its own and is untouched by this.
          if (schedule.nextStartAtOrAfter(now).isEmpty)
            repository.deactivateSchedule(guildId, schedule.id)
        }.failed.foreach { error =>
          logger.warn(s"Failed to book slots for respawn schedule ${schedule.id} in guild '$guildId'", error)
        }
      }

      // Nudge whoever booked a slot that is about to start.
      if (Config.Respawn.slotReminderMinutes > 0) {
        repository.slotsNeedingReminder(guildId, now, Config.Respawn.slotReminderMinutes).foreach { slot =>
          Try {
            repository.markWarned(guildId, slot.id)
            repository.findById(guildId, slot.respawnId).foreach { respawn =>
              RespawnThreads.dm(guild, slot.userId,
                RespawnEmbeds.dmEmbed("Your hunt starts soon",
                  RespawnEmbeds.slotReminder(respawn, slot), imageFor(respawn),
                  RespawnEmbeds.WarnColor))
            }
          }.failed.foreach { error =>
            logger.warn(s"Failed to remind about respawn slot ${slot.id} in guild '$guildId'", error)
          }
        }
      }

      // Requests the owner never answered. Silence is treated as "not tonight",
      // which is the point of the deadline — a slot cannot be held hostage by
      // somebody who has stopped reading their DMs.
      repository.expiredRequests(guildId, now).foreach { slot =>
        Try(passSlot(guild, "", slot.id, RespawnClaim.Outcome.NoAnswer, now)).failed.foreach { error =>
          logger.warn(s"Failed to pass on unanswered slot request ${slot.id} in guild '$guildId'", error)
        }
      }

      // Slots whose whole window went by without starting — the bot was down over
      // them. Closing them keeps the card honest and stops the due-slot query
      // below trying to start a hunt that should already have finished.
      repository.missedReservations(guildId, now).foreach { slot =>
        Try {
          repository.cancelClaim(guildId, slot.id, RespawnClaim.Outcome.Missed)
          repository.findById(guildId, slot.respawnId).foreach(refreshThread(guild, _, config))
        }.failed.foreach { error =>
          logger.warn(s"Failed to close missed respawn slot ${slot.id} in guild '$guildId'", error)
        }
      }

      // Slots whose time has come.
      repository.dueReservations(guildId, now).foreach { slot =>
        Try(startSlot(guild, config, slot, now)).failed.foreach { error =>
          logger.warn(s"Failed to start respawn slot ${slot.id} in guild '$guildId'", error)
        }
      }

      // Lapsed handover offers first. The offer window and the outgoing claim's
      // limbo window are set together and are the same length, so they elapse on
      // the same sweep — clearing the offer here means the claim below sees a
      // spawn with no pending offer and can move straight on to the next person.
      repository.expiredOffers(guildId, now).foreach { offer =>
        Try {
          repository.cancelClaim(guildId, offer.id, RespawnClaim.Outcome.OfferLapsed)
          repository.findById(guildId, offer.respawnId).foreach { respawn =>
            RespawnThreads.dm(guild, offer.userId,
              RespawnEmbeds.dmEmbed("Handover expired", RespawnEmbeds.handoverLapsed(respawn),
                imageFor(respawn), RespawnEmbeds.RedColor))
            logger.info(s"Handover offer ${offer.id} on '${respawn.code}' in guild '$guildId' lapsed unanswered")
          }
        }.failed.foreach { error =>
          logger.warn(s"Failed to expire handover offer ${offer.id} in guild '$guildId'", error)
        }
      }

      repository.expiredClaims(guildId, now).foreach { claim =>
        Try {
          repository.findById(guildId, claim.respawnId).foreach { respawn =>
            if (claim.limboUntil.isDefined) {
              // Its handover window is up and nobody took it, so the claim ends
              // for real. Whoever is next in the queue gets their own offer.
              repository.finishClaim(guildId, claim.id, RespawnClaim.Outcome.Completed)
              notifyClaimEnded(guild, respawn, claim)
              beginHandover(guild, respawn, config, now, outgoing = None)
            } else {
              // Time's up: start the handover. The claim stays active — and so
              // stays the spawn's holder — until someone accepts or the window
              // lapses. beginHandover finishes it if there's nobody waiting.
              beginHandover(guild, respawn, config, now, outgoing = Some(claim), notifyOutgoing = true)
            }
          }
        }.failed.foreach { error =>
          logger.warn(s"Failed to close expired respawn claim ${claim.id} in guild '$guildId'", error)
        }
      }

      // Reminder lead time is per member, so every running claim is considered
      // and each one's own owner decides whether it is due yet.
      repository.unwarnedActiveClaims(guildId, now).foreach { claim =>
        Try {
          val lead = repository.userPrefs(guildId, claim.userId).warnMinutesOr(config.warnMinutes)
          val due = lead > 0 && claim.endsAt.exists(!_.isAfter(now.plusMinutes(lead.toLong)))
          if (due) {
            repository.markWarned(guildId, claim.id)
            repository.findById(guildId, claim.respawnId).foreach { respawn =>
              // DM only, with no thread fallback: a nudge about your own claim
              // isn't worth pinging a shared thread for, and missing it costs
              // nothing — the claim ends the same way either way.
              RespawnThreads.dm(guild, claim.userId,
                RespawnEmbeds.dmEmbed("Claim ending soon", RespawnEmbeds.expiryWarning(respawn, claim),
                  imageFor(respawn), RespawnEmbeds.WarnColor))
            }
          }
        }.failed.foreach { error =>
          logger.warn(s"Failed to warn respawn claim ${claim.id} in guild '$guildId'", error)
        }
      }
    }

  /** Turn a booked slot into a live claim.
   *
   *  Three ways this goes. If the spawn is free and the owner can afford it, they
   *  simply take it. If somebody else is on it — an ad-hoc claim made before the
   *  schedule existed, so truncation never applied — the owner goes to the front
   *  of the queue and gets the spawn through the ordinary handover when that
   *  claim ends. If their tank is spent, the slot is dropped and they are told,
   *  the same as anyone else who cannot afford a claim.
   */
  private def startSlot(guild: Guild, config: RespawnSettings, slot: RespawnClaim,
                        now: ZonedDateTime): Unit = {
    val guildId = guild.getId
    repository.findById(guildId, slot.respawnId).foreach { respawn =>
      val boundary = resetBoundary(now)
      val holder = repository.activeClaim(guildId, respawn.id)
      // A slot ends when it was booked to end, however late it starts.
      //
      // Starting it late and running the full length instead would push it past
      // its booked end and into whatever is booked next — and when that slot came
      // due it would find the spawn held, cancel its owner's booking and drop them
      // into the queue. A booking is a window, not a stopwatch: confirming one
      // takes what is left of it, and the minutes lost to a late start are lost.
      val bookedEnd = slot.bookedEnd.getOrElse(now.plusMinutes(slot.durationMinutes.toLong))
      // Charged for what they actually get. A window already fully gone by never
      // reaches here — the sweep closes those as missed first.
      val remaining = slot.minutesLeftAt(now)

      if (holder.exists(_.userId == slot.userId)) {
        // They are already on it themselves — they claimed it ahead of their own
        // booking, and an ad-hoc claim is cut short at the booking's start, so a
        // sweep at exactly that moment finds both. Queueing them behind
        // themselves and DMing "your slot is taken by you" is the nonsense that
        // falls out of treating this as a collision. The booking's job is done;
        // it folds into the hunt they are having and carries its end forward.
        val current = holder.get
        val extra = current.endsAt
          .map(end => math.max(0, java.time.Duration.between(end, bookedEnd).toMinutes).toInt)
          .getOrElse(remaining)

        if (extra > 0 && !repository.reserveStamina(guildId, slot.userId, extra,
              config.staminaMinutes, boundary)) {
          // Their tank won't cover the extension, so the hunt they already have
          // stands as it is and the slot is closed rather than half-applied.
          repository.cancelClaim(guildId, slot.id, RespawnClaim.Outcome.NoStamina)
          val tank = repository.stamina(guildId, slot.userId, config.staminaMinutes, boundary)
          RespawnThreads.dm(guild, slot.userId,
            RespawnEmbeds.dmEmbed("Slot skipped",
              RespawnEmbeds.slotNoStamina(respawn, extra, tank, ServerSaveSchedule.nextServerSave(now)),
              imageFor(respawn), RespawnEmbeds.RedColor))
        } else {
          repository.cancelClaim(guildId, slot.id, RespawnClaim.Outcome.Merged)
          if (extra > 0) {
            repository.extendClaim(guildId, current.id, bookedEnd, current.durationMinutes + extra)
            RespawnThreads.dm(guild, slot.userId,
              RespawnEmbeds.dmEmbed("Your booking has started",
                RespawnEmbeds.slotMerged(respawn, bookedEnd), imageFor(respawn),
                RespawnEmbeds.FreeColor))
          }
        }
        refreshThread(guild, respawn, config)
      } else if (holder.isDefined) {
        // Somebody else is on it. Cancel the booking and take a queue place
        // instead, so the existing hunt is not interrupted mid-flight.
        repository.cancelClaim(guildId, slot.id, RespawnClaim.Outcome.TakenOver)
        repository.enqueueClaim(guildId, respawn.id, slot.userId, slot.userName, slot.characterName,
          slot.durationMinutes, config.queueLimit, RespawnClaim.KindScheduled)
        RespawnThreads.dm(guild, slot.userId,
          RespawnEmbeds.dmEmbed("Your slot is taken", RespawnEmbeds.slotOccupied(respawn, holder),
            imageFor(respawn), RespawnEmbeds.WarnColor))
        refreshThread(guild, respawn, config)
      } else if (!repository.reserveStamina(guildId, slot.userId, remaining,
                   config.staminaMinutes, boundary)) {
        repository.cancelClaim(guildId, slot.id, RespawnClaim.Outcome.NoStamina)
        val tank = repository.stamina(guildId, slot.userId, config.staminaMinutes, boundary)
        RespawnThreads.dm(guild, slot.userId,
          RespawnEmbeds.dmEmbed("Slot skipped",
            RespawnEmbeds.slotNoStamina(respawn, remaining, tank, ServerSaveSchedule.nextServerSave(now)),
            imageFor(respawn), RespawnEmbeds.RedColor))
        refreshThread(guild, respawn, config)
      } else {
        // Only what is left of the booked window is charged for, so a late start
        // costs its owner nothing out of the day's tank beyond the hunt they get.
        repository.startReservation(guildId, slot.id, now, bookedEnd) match {
          case None =>
            // Something else already started it; hand the stamina straight back.
            repository.refundStamina(guildId, slot.userId, remaining, boundary)
          case Some(started) =>
            refreshThread(guild, respawn, config)
            RespawnThreads.dm(guild, slot.userId,
              RespawnEmbeds.dmEmbed("Your hunt has started",
                RespawnEmbeds.slotStarted(respawn, started), imageFor(respawn),
                RespawnEmbeds.FreeColor))
        }
      }
    }
  }

  /** Offer a spawn that's changing hands to the next person in line, or shut it
   *  down if nobody is waiting.
   *
   *  The next person is **asked**, not given: they get a DM with Claim/Cancel and
   *  `handoverMinutes` to answer, so a spawn is never silently handed to somebody
   *  who has walked away. While that offer is outstanding `outgoing` (if any) is
   *  held in limbo — still shown as the spawn's holder, so nobody else can take
   *  it in the gap, and at no extra stamina cost because its deadline is left
   *  untouched.
   *
   *  Anyone at the front of the queue who can no longer afford their claim is
   *  dropped rather than left blocking the line: stamina isn't reserved while
   *  queued, so by the time someone reaches the front their tank may be
   *  committed elsewhere.
   *
   *  Returns the offer that went out, if any.
   */
  private def beginHandover(guild: Guild, respawn: Respawn, config: RespawnSettings, now: ZonedDateTime,
                            outgoing: Option[RespawnClaim],
                            notifyOutgoing: Boolean = false,
                            outgoingOutcome: String = RespawnClaim.Outcome.Completed): Option[RespawnClaim] = {
    val guildId = guild.getId
    val boundary = resetBoundary(now)

    // An unanswered offer already covers this spawn — offering it to a second
    // person would promise it twice.
    if (repository.offeredClaim(guildId, respawn.id).isDefined) None
    else {
      // Affordability is checked but NOT reserved here: reserving would tie up
      // the tank of somebody who may never answer. That happens on accept.
      val queue = repository.queueFor(guildId, respawn.id)
      val (cannotAfford, next) = queue.span { entry =>
        !repository.stamina(guildId, entry.userId, config.staminaMinutes, boundary).canAfford(entry.durationMinutes)
      }
      repository.cancelQueued(guildId, respawn.id, cannotAfford.map(_.userId).toSet,
        RespawnClaim.Outcome.NoStamina)
      cannotAfford.foreach { entry =>
        logger.info(s"Dropped queued respawn claim ${entry.id} on '${respawn.code}' in guild '$guildId' — " +
          s"user ${entry.userId} no longer has ${entry.durationMinutes}m of stamina")
      }

      val expiresAt = now.plusMinutes(config.handoverMinutes.toLong)
      val offered = next.headOption.flatMap(entry => repository.offerClaim(guildId, entry.id, expiresAt))

      offered match {
        case Some(offer) =>
          // Hold the outgoing claim open for exactly as long as the offer, so the
          // spawn stays assigned to its previous holder while the next person
          // decides — and so both windows lapse on the same sweep.
          outgoing.foreach(claim => repository.setLimbo(guildId, claim.id, expiresAt))
          // No card refresh: the holder is unchanged and the offered member is
          // still rendered at the head of the queue, so the card would come out
          // byte-identical. Skipping it keeps a handover to zero Discord edits.
          val delivered = RespawnThreads.dm(guild, offer.userId,
            RespawnEmbeds.dmEmbed("Your turn on a respawn",
              RespawnEmbeds.handoverOffer(respawn, offer, guild.getName, expiresAt), imageFor(respawn),
              RespawnEmbeds.FreeColor),
            Some(RespawnThreads.offerButtons(guildId, offer.id)))
          if (!delivered) {
            // Nothing is posted in the thread as a fallback — spawn threads stay
            // clean. The offer just lapses on schedule and moves to the next
            // person, so an unreachable member loses their turn but the spawn
            // keeps moving. Logged because it is invisible to everyone otherwise.
            logger.warn(s"Handover offer ${offer.id} on '${respawn.code}' in guild '$guildId' could not be " +
              s"delivered to user ${offer.userId}; it will lapse and pass to the next person")
          }
          Some(offer)

        case None =>
          // Nobody to hand it to, so the spawn really is done.
          outgoing.foreach { claim =>
            repository.finishClaim(guildId, claim.id, outgoingOutcome)
            // Only when the claim ran out on its own — someone who pressed Leave
            // does not need telling that it ended.
            if (notifyOutgoing) notifyClaimEnded(guild, respawn, claim)
          }
          // Use the thread refreshThread just resolved rather than re-deriving it
          // from `respawn`: that is a snapshot taken before the refresh, so on a
          // spawn's first claim its threadId is still empty here.
          // The card itself already flips to free with a Claim button on it, so
          // there is nothing to say — a "now free" post would just be noise in a
          // thread meant to stay readable.
          //
          // The post is not archived here. It used to be, on the reasoning that
          // a spawn nobody holds is asleep — but Discord disables message
          // components in an archived thread, so that killed the spawn's own
          // Claim button at exactly the moment the spawn became claimable (the
          // same reasoning behind RespawnThreads.postBoard leaving the board
          // post open). Discord's own auto-archive still closes an idle post in
          // its own time; that is left alone, and openThread revives whatever
          // it finds archived on the next claim.
          refreshThread(guild, respawn, config)
          None
      }
    }
  }

  /** Someone pressed **Claim** on their handover offer DM. */
  def acceptOffer(guild: Guild, userId: String, claimId: Long,
                  now: ZonedDateTime = ZonedDateTime.now()): OfferOutcome =
    settings(guild.getId) match {
      case None => OfferOutcome.Gone
      case Some(config) =>
        val guildId = guild.getId
        repository.findClaimById(guildId, claimId) match {
          case None => OfferOutcome.Gone
          case Some(claim) if claim.userId != userId => OfferOutcome.NotYours
          // Not offered any more: it lapsed, or they already answered. This is
          // also what stops a double-click reserving stamina twice.
          case Some(claim) if !claim.isOffered => OfferOutcome.Gone
          case Some(claim) =>
            val respawn = repository.findById(guildId, claim.respawnId)
            val boundary = resetBoundary(now)
            if (!repository.reserveStamina(guildId, userId, claim.durationMinutes, config.staminaMinutes, boundary)) {
              // Their tank went elsewhere while the offer sat unanswered. Treat it
              // as a decline so the spawn moves on rather than stalling on them.
              repository.cancelClaim(guildId, claim.id, RespawnClaim.Outcome.NoStamina)
              val tank = repository.stamina(guildId, userId, config.staminaMinutes, boundary)
              respawn.foreach(r => beginHandover(guild, r, config, now, outgoing = handingOverHolder(guildId, r.id)))
              OfferOutcome.NoStamina(claim.durationMinutes, tank, ServerSaveSchedule.nextServerSave(now))
            } else {
              // Close the outgoing holder before promoting, so a spawn never has
              // two active claims at once.
              respawn.foreach { r =>
                activeHolder(guildId, r.id).foreach { previous =>
                  repository.finishClaim(guildId, previous.id, RespawnClaim.Outcome.TakenOver)
                  notifyClaimEnded(guild, r, previous)
                }
              }
              repository.promoteClaim(guildId, claim.id, now) match {
                case None =>
                  // Lost a race with the offer expiring. Hand the stamina back
                  // rather than stranding it until server save.
                  repository.refundStamina(guildId, userId, claim.durationMinutes, boundary)
                  OfferOutcome.Gone
                case Some(active) =>
                  respawn.foreach(refreshThread(guild, _, config))
                  respawn.map(OfferOutcome.Accepted(_, active)).getOrElse(OfferOutcome.Gone)
              }
            }
        }
    }

  /** Someone pressed **Cancel** on their handover offer DM. Identical to leaving
   *  the queue: they give up their place and the spawn moves to whoever is
   *  behind them. */
  def declineOffer(guild: Guild, userId: String, claimId: Long,
                   now: ZonedDateTime = ZonedDateTime.now()): OfferOutcome =
    settings(guild.getId) match {
      case None => OfferOutcome.Gone
      case Some(config) =>
        val guildId = guild.getId
        repository.findClaimById(guildId, claimId) match {
          case None => OfferOutcome.Gone
          case Some(claim) if claim.userId != userId => OfferOutcome.NotYours
          case Some(claim) if !claim.isOffered => OfferOutcome.Gone
          case Some(claim) =>
            repository.cancelClaim(guildId, claim.id, RespawnClaim.Outcome.Declined)
            val respawn = repository.findById(guildId, claim.respawnId)
            respawn.foreach(r => beginHandover(guild, r, config, now, outgoing = handingOverHolder(guildId, r.id)))
            respawn.map(OfferOutcome.Declined).getOrElse(OfferOutcome.Gone)
        }
    }

  /** Tell a holder their claim is over. Only for claims that ended on their own —
   *  by running out, or by being taken over once the handover window closed. */
  private def notifyClaimEnded(guild: Guild, respawn: Respawn, claim: RespawnClaim): Unit =
    RespawnThreads.dm(guild, claim.userId,
      RespawnEmbeds.dmEmbed("Claim ended", RespawnEmbeds.claimEnded(respawn),
        imageFor(respawn), RespawnEmbeds.RedColor))

  /** Rewrite a spawn's post to match the database — the one function that keeps
   *  Discord and the claim state in step, called after every mutation. Creates
   *  the post on first claim and revives it if the spawn was idle. */
  def refreshThread(guild: Guild, respawn: Respawn, config: RespawnSettings): Option[ThreadChannel] =
    RespawnThreads.findForum(guild, config).flatMap { forum =>
      val guildId = guild.getId
      val active = repository.activeClaim(guildId, respawn.id)
      // The person holding an unanswered offer is still shown at the head of the
      // queue, exactly where they were while queued. That's both truthful — they
      // are next — and what makes an offer going out change nothing on the card,
      // so it needs no edit at all.
      val queue = repository.offeredClaim(guildId, respawn.id).toList ++ repository.queueFor(guildId, respawn.id)
      val reservations = repository.reservationsFor(guildId, respawn.id, ZonedDateTime.now())
      val card = RespawnEmbeds.claimCard(respawn, active, queue, reservations, config, imageFor(respawn))
      val buttons = RespawnThreads.claimButtons(respawn.id, active.isDefined)

      // Re-read after a possible create so the row carries the new thread id;
      // the create callback writes it, but the local `respawn` is a snapshot.
      val thread = RespawnThreads.openThread(guild, forum, respawn, card, buttons,
        threadId => repository.setThreadId(guildId, respawn.id, threadId))

      thread.foreach { channel =>
        RespawnThreads.applyTag(forum, channel, RespawnThreads.tagFor(claimed = active.isDefined))
      }
      thread
    }

  /** Booked slots on a spawn that haven't started yet. */
  def reservationsFor(guildId: String, respawnId: Long,
                      now: ZonedDateTime = ZonedDateTime.now()): List[RespawnClaim] =
    repository.reservationsFor(guildId, respawnId, now)

  /** `/respawn status <spawn>` — one spawn's current state. */
  def status(guildId: String, respawn: Respawn): (Option[RespawnClaim], List[RespawnClaim]) =
    (repository.activeClaim(guildId, respawn.id), repository.queueFor(guildId, respawn.id))

  /** Stop tracking respawns for this guild entirely — claims, catalogue and
   *  settings all go. Called when the guild's last world is removed; the forum
   *  channel itself is retired as read-only history rather than deleted (see
   *  ChannelService.retireSpawnsForum).
   *
   *  The threads that remain in that channel are deliberately orphaned from the
   *  bot's point of view: nothing references them, so a later `/setup` builds a
   *  fresh forum and catalogue instead of trying to revive posts in a channel
   *  that is now just an archive.
   */
  def teardown(guildId: String): Unit = repository.dropGuildData(guildId)

  def userPrefs(guildId: String, userId: String): RespawnUserPrefs =
    repository.userPrefs(guildId, userId)

  /** Save a member's own defaults, clamped to what the guild actually allows.
   *
   *  Validation lives here rather than in the modal handler so the bounds are
   *  the same wherever the preference is set from. A duration longer than the
   *  guild's maximum would be refused at claim time anyway, and a reminder lead
   *  beyond [[RespawnUserPrefs.MaxWarnMinutes]] would fire the instant a claim
   *  started. */
  def saveUserPrefs(guildId: String, userId: String, defaultDuration: Option[Int],
                    warnMinutes: Option[Int]): Either[String, RespawnUserPrefs] =
    settings(guildId) match {
      case None => Left("The respawn claim system isn't set up on this server yet.")
      case Some(config) =>
        val badDuration = defaultDuration.exists(m => m < 5 || m > config.maxDurationMinutes)
        val badWarn = warnMinutes.exists(m => m < 0 || m > RespawnUserPrefs.MaxWarnMinutes)
        if (badDuration)
          Left(s"A claim has to be between 5 minutes and " +
            s"${RespawnEmbeds.humanDuration(config.maxDurationMinutes)} on this server.")
        else if (badWarn)
          Left(s"A reminder can be at most " +
            s"${RespawnEmbeds.humanDuration(RespawnUserPrefs.MaxWarnMinutes)} before your claim ends.")
        else {
          val prefs = RespawnUserPrefs(userId, defaultDuration, warnMinutes)
          repository.saveUserPrefs(guildId, prefs)
          Right(prefs)
        }
    }

  /** The claim card's main image for a spawn. The one place the presentation
   *  layer's config-free image builder is fed the bot's actual creature-name
   *  mappings and fallback. */
  def imageFor(respawn: Respawn): String =
    RespawnEmbeds.imageFor(respawn, Config.creatureUrlMappings, Config.Respawn.fallbackImage)

  /** Look up the guild's forum channel, for callers that need to link to it. */
  def forumChannel(guild: Guild): Option[ForumChannel] =
    settings(guild.getId).flatMap(RespawnThreads.findForum(guild, _))
}
