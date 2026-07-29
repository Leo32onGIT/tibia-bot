package com.tibiabot.respawn

import com.tibiabot.Config
import com.tibiabot.domain.{Respawn, RespawnClaim, RespawnSettings, RespawnUserPrefs, Stamina}
import com.tibiabot.persistence.RespawnRepository
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

  def addRespawn(guildId: String, code: String, name: String, creature: String, region: String,
                 world: String, mapperLink: String, addedBy: String): Respawn =
    repository.addRespawn(guildId, code, name, creature, region, world, mapperLink,
      Respawn.SourceCustom, addedBy)

  def editRespawn(guildId: String, respawnId: Long, name: Option[String], creature: Option[String],
                  world: Option[String], mapperLink: Option[String]): Unit =
    repository.updateRespawn(guildId, respawnId, name, creature, world, mapperLink)

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

  def setStaminaUsed(guildId: String, userId: String, usedMinutes: Int,
                     now: ZonedDateTime = ZonedDateTime.now()): Unit =
    repository.setStaminaUsed(guildId, userId, usedMinutes, resetBoundary(now))

  // --- claiming -----------------------------------------------------------

  /** When a claim starting at `startsAt` for `minutes` should end.
   *
   *  Trivial today. It exists as its own function because it is the single
   *  place a future scheduled-claim feature has to truncate an ad-hoc claim
   *  that would run into someone's reserved window — putting that logic here
   *  later means no call site changes.
   */
  def endsAtFor(startsAt: ZonedDateTime, minutes: Int): ZonedDateTime = startsAt.plusMinutes(minutes.toLong)

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
                  else if (repository.activeClaim(guildId, respawn.id).isDefined)
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
    // Re-check under the reservation itself rather than trusting the earlier
    // read: a second claim from the same user may have taken the room in
    // between, and reserveStamina writing nothing is the authoritative answer.
    if (!repository.reserveStamina(guildId, userId, minutes, config.staminaMinutes, boundary)) {
      val tank = repository.stamina(guildId, userId, config.staminaMinutes, boundary)
      ClaimOutcome.NoStamina(respawn, minutes, tank, ServerSaveSchedule.nextServerSave(now))
    } else {
      val claim = repository.insertActiveClaim(guildId, respawn.id, userId, userName, characterName,
        now, endsAtFor(now, minutes), minutes, kind)
      refreshThread(guild, respawn, config)
      ClaimOutcome.Claimed(respawn, claim)
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
              now: ZonedDateTime = ZonedDateTime.now()): ReleaseOutcome =
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
              // Declining an offer and leaving the queue are the same act, so
              // they take the same path.
              repository.cancelClaim(guildId, claim.id)
              respawn.foreach(r => beginHandover(guild, r, config, now, outgoing = activeHolder(guildId, r.id)))
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
              val offered = respawn.flatMap(beginHandover(guild, _, config, now, outgoing = Some(claim)))
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

  /** Force a spawn free regardless of who holds it (`/respawn admin clear`).
   *  Refunds the holder like a voluntary release would — an admin clearing a
   *  spawn isn't a penalty. */
  def adminClear(guild: Guild, respawn: Respawn, now: ZonedDateTime = ZonedDateTime.now()): Boolean =
    settings(guild.getId).exists { config =>
      val guildId = guild.getId
      val active = repository.activeClaim(guildId, respawn.id)
      active.foreach { claim =>
        val refunded = refundFor(claim, now)
        repository.cancelClaim(guildId, claim.id)
        if (refunded > 0) repository.refundStamina(guildId, claim.userId, refunded, resetBoundary(now))
      }
      // The pending handover offer goes too — otherwise accepting it would
      // resurrect a claim on a spawn an admin just forced free.
      repository.offeredClaim(guildId, respawn.id).foreach(offer => repository.cancelClaim(guildId, offer.id))
      repository.queueFor(guildId, respawn.id).foreach(entry => repository.cancelClaim(guildId, entry.id))
      refreshThread(guild, respawn, config)
      active.isDefined
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

      // Lapsed handover offers first. The offer window and the outgoing claim's
      // limbo window are set together and are the same length, so they elapse on
      // the same sweep — clearing the offer here means the claim below sees a
      // spawn with no pending offer and can move straight on to the next person.
      repository.expiredOffers(guildId, now).foreach { offer =>
        Try {
          repository.cancelClaim(guildId, offer.id)
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
              repository.finishClaim(guildId, claim.id)
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
                  imageFor(respawn), RespawnEmbeds.WarnColor),
                Some(RespawnThreads.reminderButtons(guildId, respawn.id)))
            }
          }
        }.failed.foreach { error =>
          logger.warn(s"Failed to warn respawn claim ${claim.id} in guild '$guildId'", error)
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
                            notifyOutgoing: Boolean = false): Option[RespawnClaim] = {
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
      repository.cancelQueued(guildId, respawn.id, cannotAfford.map(_.userId).toSet)
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
              RespawnEmbeds.handoverOffer(respawn, offer, guild.getName, expiresAt), imageFor(respawn)),
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
            repository.finishClaim(guildId, claim.id)
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
          refreshThread(guild, respawn, config).foreach { thread =>
            // Archived, not locked: people can still leave notes on a spawn
            // between hunts, and reviving it doesn't need a moderator.
            RespawnThreads.archive(thread)
          }
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
              repository.cancelClaim(guildId, claim.id)
              val tank = repository.stamina(guildId, userId, config.staminaMinutes, boundary)
              respawn.foreach(r => beginHandover(guild, r, config, now, outgoing = activeHolder(guildId, r.id)))
              OfferOutcome.NoStamina(claim.durationMinutes, tank, ServerSaveSchedule.nextServerSave(now))
            } else {
              // Close the outgoing holder before promoting, so a spawn never has
              // two active claims at once.
              respawn.foreach { r =>
                activeHolder(guildId, r.id).foreach { previous =>
                  repository.finishClaim(guildId, previous.id)
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
            repository.cancelClaim(guildId, claim.id)
            val respawn = repository.findById(guildId, claim.respawnId)
            respawn.foreach(r => beginHandover(guild, r, config, now, outgoing = activeHolder(guildId, r.id)))
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
      val card = RespawnEmbeds.claimCard(respawn, active, queue, config, imageFor(respawn))
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

  /** `/respawn list` — every claimed spawn with its queue depth. */
  def activeClaims(guildId: String): List[(Respawn, RespawnClaim, Int)] =
    repository.allActiveClaims(guildId).flatMap { claim =>
      repository.findById(guildId, claim.respawnId).map { respawn =>
        (respawn, claim, repository.queueFor(guildId, claim.respawnId).size)
      }
    }

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

  /** The spawn threads currently open, so a caller retiring the forum can close
   *  them before it stops tracking them. */
  def openThreadIds(guildId: String): List[String] =
    repository.listRespawns(guildId).map(_.threadId).filter(id => id.nonEmpty && id != "0")

  // --- member preferences -------------------------------------------------

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

  /** Autocomplete source: (code, name) for every spawn the guild knows. */
  def autocompleteCandidates(guildId: String): List[(String, String)] =
    repository.listRespawns(guildId).map(r => (r.code, r.name))

  /** Look up the guild's forum channel, for callers that need to link to it. */
  def forumChannel(guild: Guild): Option[ForumChannel] =
    settings(guild.getId).flatMap(RespawnThreads.findForum(guild, _))
}
