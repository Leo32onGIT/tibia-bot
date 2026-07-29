package com.tibiabot.respawn

import com.tibiabot.Config
import com.tibiabot.domain.{Respawn, RespawnClaim, RespawnSettings, Stamina}
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
  final case class Released(respawn: Respawn, refundedMinutes: Int, promoted: Option[RespawnClaim]) extends ReleaseOutcome
  final case class LeftQueue(respawn: Respawn) extends ReleaseOutcome
  case object NothingHeld extends ReleaseOutcome
  case object NotConfigured extends ReleaseOutcome
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
    warnMinutes = Config.Respawn.warnMinutes
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
            val minutes = requestedMinutes.getOrElse(config.defaultDurationMinutes)
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
            if (claim.isQueued) {
              repository.cancelClaim(guildId, claim.id)
              respawn.foreach(refreshThread(guild, _, config))
              respawn.map(ReleaseOutcome.LeftQueue).getOrElse(ReleaseOutcome.NothingHeld)
            } else {
              val refunded = refundFor(claim, now)
              repository.finishClaim(guildId, claim.id)
              if (refunded > 0) repository.refundStamina(guildId, userId, refunded, resetBoundary(now))
              val promoted = respawn.flatMap(advance(guild, _, config, now))
              respawn.map(ReleaseOutcome.Released(_, refunded, promoted)).getOrElse(ReleaseOutcome.NothingHeld)
            }
        }
    }

  /** Unused whole minutes left on a claim — what a release gives back. Rounded
   *  down so ending a claim can never refund more than was reserved. */
  private def refundFor(claim: RespawnClaim, now: ZonedDateTime): Int =
    claim.endsAt.map { end =>
      val remaining = java.time.Duration.between(now, end).toMinutes
      math.max(0, math.min(claim.durationMinutes.toLong, remaining)).toInt
    }.getOrElse(0)

  /** Add time to the caller's active claim, within the guild's ceiling and
   *  their remaining stamina. Returns the new end time on success. */
  def extend(guild: Guild, userId: String, extraMinutes: Int,
             now: ZonedDateTime = ZonedDateTime.now()): Either[ClaimOutcome, (Respawn, ZonedDateTime)] =
    settings(guild.getId) match {
      case None => Left(ClaimOutcome.NotConfigured)
      case Some(config) =>
        val guildId = guild.getId
        repository.openClaimsForUser(guildId, userId).find(_.isActive) match {
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

      repository.expiredClaims(guildId, now).foreach { claim =>
        Try {
          repository.finishClaim(guildId, claim.id)
          repository.findById(guildId, claim.respawnId).foreach(advance(guild, _, config, now))
        }.failed.foreach { error =>
          logger.warn(s"Failed to close expired respawn claim ${claim.id} in guild '$guildId'", error)
        }
      }

      if (config.warnMinutes > 0) {
        repository.claimsNeedingWarning(guildId, now, config.warnMinutes).foreach { claim =>
          Try {
            repository.markWarned(guildId, claim.id)
            for {
              respawn <- repository.findById(guildId, claim.respawnId)
              forum <- RespawnThreads.findForum(guild, config)
              thread <- RespawnThreads.resolveThread(guild, forum, respawn.threadId)
            } {
              val remaining = claim.endsAt
                .map(end => math.max(1, java.time.Duration.between(now, end).toMinutes).toInt)
                .getOrElse(config.warnMinutes)
              RespawnThreads.announce(thread, RespawnEmbeds.expiryWarning(respawn, claim, remaining))
            }
          }.failed.foreach { error =>
            logger.warn(s"Failed to warn respawn claim ${claim.id} in guild '$guildId'", error)
          }
        }
      }
    }

  /** Hand a just-freed spawn to the next person in line, or put it to sleep.
   *
   *  Anyone at the head of the queue who can no longer afford their claim is
   *  skipped and cancelled rather than left blocking the line — their stamina
   *  was never reserved while queued, so by the time they reach the front their
   *  tank may be committed elsewhere. Returns the claim that took over, if any.
   */
  private def advance(guild: Guild, respawn: Respawn, config: RespawnSettings,
                      now: ZonedDateTime): Option[RespawnClaim] = {
    val guildId = guild.getId
    val boundary = resetBoundary(now)

    // Walk the queue from the front reserving as we go, and take the first
    // person whose reservation succeeds. Reserving *is* the affordability
    // check — a separate read-then-reserve could be overtaken by that user's
    // own claim on another spawn in between.
    val (skipped, reserved) = repository.queueFor(guildId, respawn.id)
      .foldLeft((List.empty[RespawnClaim], Option.empty[RespawnClaim])) {
        case (found @ (_, Some(_)), _) => found
        case ((cannotAfford, None), entry) =>
          if (repository.reserveStamina(guildId, entry.userId, entry.durationMinutes, config.staminaMinutes, boundary))
            (cannotAfford, Some(entry))
          else
            (cannotAfford :+ entry, None)
      }

    // Clear the unaffordable entries out of the queue whether or not anyone
    // took the spawn, so the next sweep doesn't re-evaluate them forever.
    repository.cancelQueued(guildId, respawn.id, skipped.map(_.userId).toSet)

    val promoted = reserved.flatMap { head =>
      repository.promoteClaim(guildId, head.id, now).orElse {
        // They left the queue between the reservation and the promotion. Give
        // the stamina straight back rather than stranding it until server save.
        repository.refundStamina(guildId, head.userId, head.durationMinutes, boundary)
        None
      }
    }

    skipped.foreach { entry =>
      logger.info(s"Skipped queued respawn claim ${entry.id} on '${respawn.code}' in guild '$guildId' — " +
        s"user ${entry.userId} no longer has ${entry.durationMinutes}m of stamina")
    }

    // Use the thread refreshThread just resolved rather than looking it up
    // again from `respawn` — that's a snapshot taken before the refresh, so on
    // the first claim of a spawn its threadId is still empty here and a second
    // lookup would find nothing and silently skip the announcement.
    refreshThread(guild, respawn, config).foreach { thread =>
      promoted match {
        case Some(claim) =>
          RespawnThreads.announce(thread, RespawnEmbeds.promotionNotice(respawn, claim))
        case None =>
          RespawnThreads.announce(thread, RespawnEmbeds.freedNotice(respawn))
          // Archived, not locked: people can still leave notes on a spawn
          // between hunts, and reviving it doesn't need a moderator.
          RespawnThreads.archive(thread)
      }
    }

    promoted
  }

  /** Rewrite a spawn's post to match the database — the one function that keeps
   *  Discord and the claim state in step, called after every mutation. Creates
   *  the post on first claim and revives it if the spawn was idle. */
  def refreshThread(guild: Guild, respawn: Respawn, config: RespawnSettings): Option[ThreadChannel] =
    RespawnThreads.findForum(guild, config).flatMap { forum =>
      val guildId = guild.getId
      val active = repository.activeClaim(guildId, respawn.id)
      val queue = repository.queueFor(guildId, respawn.id)
      val card = RespawnEmbeds.claimCard(respawn, active, queue, config, imageFor(respawn))
      val buttons = RespawnThreads.claimButtons(respawn.id, active.isDefined)

      // Re-read after a possible create so the row carries the new thread id;
      // the create callback writes it, but the local `respawn` is a snapshot.
      val thread = RespawnThreads.openThread(guild, forum, respawn, card, buttons,
        threadId => repository.setThreadId(guildId, respawn.id, threadId))

      thread.foreach { channel =>
        RespawnThreads.applyTag(forum, channel,
          RespawnThreads.tagFor(claimed = active.isDefined, queued = queue.nonEmpty))
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
