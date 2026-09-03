package com.tibiabot.respawn

import com.tibiabot.Config
import com.tibiabot.domain.{ClashVerdict, Respawn, RespawnClaim, RespawnSchedule, RespawnSettings, RespawnUserPrefs, Stamina}
import com.tibiabot.persistence.{RespawnRepository, ScheduleOccurrence, SeedSync}
import com.tibiabot.presentation.{RespawnBoardImage, RespawnEmbeds}
import com.tibiabot.scheduler.ServerSaveSchedule
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.{ForumChannel, ThreadChannel}

import java.time.ZonedDateTime
import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.control.NonFatal
import com.tibiabot.presentation.Names

/** What a claim attempt did. A result type rather than handlers poking at the
 *  repository, so the button, the board form and the dashboard can't drift apart. */
sealed trait ClaimOutcome
object ClaimOutcome {
  /** The caller now holds the spawn. */
  final case class Claimed(respawn: Respawn, claim: RespawnClaim) extends ClaimOutcome
  /** The spawn was taken, so the caller is in line behind it. */
  final case class Queued(respawn: Respawn, claim: RespawnClaim, position: Int) extends ClaimOutcome
  /** Granted but cut short by a booking: `requested` is what they asked for,
   *  `reservedFrom` when the booking takes over. */
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
  /** The respawn system has never been set up in this guild. */
  case object NotConfigured extends ClaimOutcome
}

sealed trait ReleaseOutcome
object ReleaseOutcome {
  /** Gave up an active claim. `offered` is the handover offer that went out to
   *  the next person, if there was one. */
  final case class Released(respawn: Respawn, refundedMinutes: Int, offered: Option[RespawnClaim]) extends ReleaseOutcome
  final case class LeftQueue(respawn: Respawn) extends ReleaseOutcome
  /** Already on its way out — a handover offer is outstanding, so there is
   *  nothing left to release. */
  final case class AlreadyHandingOver(spawnName: String) extends ReleaseOutcome
  case object NothingHeld extends ReleaseOutcome
  case object NotConfigured extends ReleaseOutcome
}

/** What booking a slot did. A clash is not always a refusal: a one-off over an
 *  unqueried slot becomes a question for its owner, whose answer decides whether
 *  the booking happens. Nothing is written for the asker until then. */
sealed trait ScheduleResult
object ScheduleResult {
  /** The slot was free and is now theirs. */
  final case class Booked(schedule: RespawnSchedule) extends ScheduleResult
  /** It clashed; the clashing slot's owner has been asked if they are hunting it. */
  final case class Requested(respawn: Respawn, slot: RespawnClaim, deadline: ZonedDateTime)
    extends ScheduleResult
}

/** What changing one window's length did.
 *
 *  `live` says whether this was a hunt under way (deadline moved under someone,
 *  worth telling them now) or an evening still to come. `cutInto` names whoever
 *  the new window runs into — set only on a live hunt, which is allowed to
 *  overrun, so it is a warning on a success, never a refusal.
 */
final case class SlotEdit(
  owner: String,
  ownerId: String,
  minutes: Int,
  endsAt: ZonedDateTime,
  live: Boolean,
  cutInto: Option[String]
)

/** One page of the moderator claim log. `hasOlder` comes from fetching one row
 *  past the page, so an Older button only appears when it would show something. */
final case class LogPage(entries: List[RespawnClaim], page: Int, hasOlder: Boolean) {
  def isEmpty: Boolean = entries.isEmpty
  def hasNewer: Boolean = page > 0
}

/** What a page of the claim log is about. Travels in the button id rather than
 *  being stored, because a Next press has to know what it is paging through and
 *  the message it edits cannot say. */
sealed trait LogScope {
  /** The id-safe form. Button ids are colon-separated, so no token may contain
   *  one; the member form carries a `u` since spawn ids and snowflakes are both
   *  bare digits. */
  def token: String
}

object LogScope {
  case object Everything extends LogScope { val token: String = "all" }
  final case class Spawn(respawnId: Long) extends LogScope { def token: String = respawnId.toString }
  final case class Member(userId: String) extends LogScope { def token: String = s"u$userId" }

  def fromToken(token: String): Option[LogScope] =
    if (token == "all") Some(Everything)
    else if (token.startsWith("u")) {
      val id = token.drop(1)
      // Digits only: this goes straight into a database lookup, and anything
      // else did not come from a button this bot drew.
      if (id.nonEmpty && id.forall(_.isDigit)) Some(Member(id)) else None
    }
    else scala.util.Try(token.toLong).toOption.map(Spawn(_))
}

/** One spawn as the web board shows it. Assembled in bulk by
 *  [[RespawnService.board]]. `lastActivity` drives the board's fade; a spawn
 *  nobody has claimed has none and reads as fully dormant. */
final case class RespawnBoardEntry(
  respawn: Respawn,
  active: Option[RespawnClaim],
  queue: List[RespawnClaim],
  reservations: List[RespawnClaim],
  lastActivity: Option[ZonedDateTime]
) {
  /** The soonest settled booking. A slot with an unanswered request is skipped:
   *  it may yet change hands, and neither answer changes the spawn this minute.
   *  The calendar still draws the request in full. */
  def nextReservation: Option[RespawnClaim] =
    reservations.find(_.requesterUserId.isEmpty)

  /** When each queued claim would start if every hunt ahead ran its full length.
   *
   *  A projection only — never written to `startsAt`, which means "this really
   *  began then" — so surfaces should mark it approximate: an early release or an
   *  expired offer brings it all forward. Computed here so every surface shows the
   *  same arithmetic. A queue with no live hunt counts from `now`. */
  def projectedQueueStarts(now: ZonedDateTime): List[(RespawnClaim, ZonedDateTime)] = {
    val first = active.flatMap(_.endsAt).filter(_.isAfter(now)).getOrElse(now)
    queue.foldLeft((first, List.empty[(RespawnClaim, ZonedDateTime)])) {
      case ((cursor, acc), claim) =>
        (cursor.plusMinutes(claim.durationMinutes.toLong), acc :+ (claim -> cursor))
    }._2
  }

  /** How this spawn reads on the board, as one of [[RespawnBoardEntry.States]].
   *  Being hunted now outranks anything booked for later. `askedAt` is set on the
   *  first query and never cleared while the requester fields are cleared once
   *  answered (see `keepOccurrence`), which is what separates the two booked
   *  states. */
  def state: String =
    if (active.isDefined) RespawnBoardEntry.Claimed
    else nextReservation match {
      case None => RespawnBoardEntry.Free
      // Settled either way: the owner said so outright, or somebody asked and it
      // was answered. Reading `askedAt` alone would show an explicitly confirmed
      // booking as merely booked.
      case Some(slot) if slot.confirmed || slot.askedAt.isDefined => RespawnBoardEntry.Confirmed
      case Some(_) => RespawnBoardEntry.Booked
    }

  /** Whoever the card should name: the holder, else whoever booked it next.
   *  Their Discord name, since the point of naming them is so somebody can go and
   *  ask them in Discord; the character shows on the calendar block and in the
   *  thread, where there is room for both. Falls back to the character only when
   *  there is no Discord name at all. */
  def holderLabel: Option[String] =
    active.orElse(nextReservation).map { claim =>
      val called = Names.calledPlain(claim.nickname, claim.userName)
      if (called.nonEmpty) called else claim.characterName
    }
}

object RespawnBoardEntry {
  val Free = "free"
  val Claimed = "claimed"
  val Booked = "booked"
  val Confirmed = "confirmed"
  /** A slot whose owner has been asked whether they are hunting it. Calendar
   *  state only — a board card describes now. */
  val Asked = "asked"

  /** What a board card can read as. Deliberately without [[Asked]]. */
  val States: Set[String] = Set(Free, Claimed, Booked, Confirmed)
}

/** The result of a slot owner answering "are you hunting tonight?". */
sealed trait SlotAnswer
object SlotAnswer {
  /** They are hunting it, so the request is refused and the slot stays theirs. */
  final case class Kept(respawn: Respawn) extends SlotAnswer
  /** They are not, so it passes to whoever asked. Carries both of the asker's
   *  names, not their id — they are only ever named in the reply, and it should
   *  read in the words the server uses. */
  final case class Passed(respawn: Respawn, toUserName: String, toNickname: String) extends SlotAnswer
  /** They are not, but the asker's window was longer than the slot and the rest
   *  is somebody else's now, so it goes back to free rather than to them. */
  final case class PassedUnclaimed(respawn: Respawn) extends SlotAnswer
  /** Already answered, lapsed, or the slot is gone. */
  case object Gone extends SlotAnswer
  case object NotYours extends SlotAnswer
}

/** The result of a booking's owner confirming they are there — Confirm on the
 *  reminder, or Take Claim once the slot has started. */
sealed trait ConfirmOutcome
object ConfirmOutcome {
  /** Confirmed before the start: settled, and nobody may ask for it now. */
  final case class Settled(respawn: Respawn, slot: RespawnClaim) extends ConfirmOutcome
  /** Confirmed a hunt already under way, so it can no longer be given up. */
  final case class Taken(respawn: Respawn, claim: RespawnClaim) extends ConfirmOutcome
  /** Already confirmed — a second press, which changes nothing. */
  final case class Already(respawn: Respawn) extends ConfirmOutcome
  /** The window went by and it was given up, or the slot is otherwise gone. */
  case object Gone extends ConfirmOutcome
  case object NotYours extends ConfirmOutcome
}

/** The result of answering a handover offer DM. */
sealed trait OfferOutcome
object OfferOutcome {
  final case class Accepted(respawn: Respawn, claim: RespawnClaim) extends OfferOutcome
  final case class Declined(respawn: Respawn) extends OfferOutcome
  /** Their tank went elsewhere while the offer sat unanswered. */
  final case class NoStamina(needed: Int, stamina: Stamina, resetsAt: ZonedDateTime) extends OfferOutcome
  /** Lapsed, already answered, or the spawn is gone. */
  case object Gone extends OfferOutcome
  /** Somebody else's offer — only reachable if a button id were shared around. */
  case object NotYours extends OfferOutcome
}

/** The respawn claim system's rules and lifecycle.
 *
 *  All state lives in Postgres — no in-memory timers — so a restart mid-claim
 *  loses nothing and anything that should have ended while the bot was down is
 *  resolved by the next [[sweep]].
 *
 *  Stamina is reserved for a claim's whole duration up front, not accrued as it
 *  is spent: holding two spawns at once is supported, and up-front reservation
 *  is what stops that being a way to book eight hours out of a four-hour tank.
 *  Releasing early refunds the remainder.
 */
final class RespawnService(repository: RespawnRepository) extends StrictLogging {

  // --- settings -----------------------------------------------------------

  /** The guild's settings, or None when the respawn system was never set up here. */
  def settings(guildId: String): Option[RespawnSettings] = repository.settings(guildId)

  /** Settings a guild gets on first setup: the bot's defaults, snapshotted into
   *  the guild's row so later changes don't retune a live guild. */
  def defaultSettings: RespawnSettings = RespawnSettings(
    forumChannel = "0",
    boardThread = "0",
    defaultDurationMinutes = Config.Respawn.defaultDurationMinutes,
    maxDurationMinutes = Config.Respawn.maxDurationMinutes,
    queueLimit = Config.Respawn.queueLimit,
    staminaMinutes = Config.Respawn.staminaMinutes,
    warnMinutes = Config.Respawn.warnMinutes,
    handoverMinutes = Config.Respawn.handoverMinutes,
    autoClaim = Config.Respawn.autoClaim
  )

  def saveSettings(guildId: String, settings: RespawnSettings): Unit =
    repository.saveSettings(guildId, settings)

  /** Give one spawn its own ceiling on claim length, or take it away.
   *
   *  `None` clears the override. The value replaces the guild's number rather
   *  than capping it, so a spawn can be set above the server ceiling as well as
   *  below — see [[com.tibiabot.domain.RespawnSettings.maxFor]].
   *
   *  Nothing already running is touched: the new ceiling binds the next claim,
   *  extension and booking. Shortening somebody mid-hunt would take time off a
   *  hunt that was legitimate when it started, and its stamina is already spent.
   */
  def setSpawnMaxDuration(guild: Guild, respawn: Respawn,
                          minutes: Option[Int]): Either[String, Respawn] =
    settings(guild.getId) match {
      case None => Left("The respawn claim system isn't set up on this server yet.")
      case Some(config) =>
        val guildId = guild.getId
        minutes match {
          case Some(value) if value < MinimumClaimMinutes =>
            Left(s"A claim ceiling has to be at least " +
              s"${RespawnEmbeds.humanDuration(MinimumClaimMinutes)}.")
          case Some(value) if value > MaxSpawnCeilingMinutes =>
            Left(s"A claim ceiling can be at most " +
              s"${RespawnEmbeds.humanDuration(MaxSpawnCeilingMinutes)}.")
          case _ =>
            repository.setRespawnMaxDuration(guildId, respawn.id, minutes)
            val updated = respawn.copy(maxDurationMinutes = minutes)
            // The card carries the ceiling in its footer, so it is stale the
            // moment this is written.
            refreshThread(guild, updated, config)
            Right(updated)
        }
    }

  /** Apply a partial change to the guild's rules, validated here rather than in
   *  the caller so a second caller can't drift on what combinations are legal.
   *  A default claim longer than the maximum is the one that bites: every later
   *  claim would be refused against a ceiling nobody set deliberately.
   *
   *  `warnMinutes` is absent on purpose — it is the per-member fallback and
   *  lives at `Config.Respawn.warnMinutes` for everybody. */
  def updateSettings(guildId: String, defaultDuration: Option[Int], maxDuration: Option[Int],
                     queueLimit: Option[Int], stamina: Option[Int],
                     handover: Option[Int]): Either[String, RespawnSettings] =
    settings(guildId) match {
      case None => Left("The respawn claim system isn't set up on this server yet.")
      case Some(current) =>
        val updated = current.copy(
          defaultDurationMinutes = defaultDuration.getOrElse(current.defaultDurationMinutes),
          maxDurationMinutes = maxDuration.getOrElse(current.maxDurationMinutes),
          queueLimit = queueLimit.getOrElse(current.queueLimit),
          staminaMinutes = stamina.getOrElse(current.staminaMinutes),
          handoverMinutes = handover.getOrElse(current.handoverMinutes)
        )
        if (updated.defaultDurationMinutes < 5 || updated.maxDurationMinutes < 5)
          Left("A claim has to be at least 5 minutes long.")
        else if (updated.defaultDurationMinutes > updated.maxDurationMinutes)
          Left(s"The default claim (${RespawnEmbeds.humanDuration(updated.defaultDurationMinutes)}) can't be " +
            s"longer than the maximum (${RespawnEmbeds.humanDuration(updated.maxDurationMinutes)}).")
        else if (updated.queueLimit < 0 || updated.staminaMinutes < 0)
          Left("Queue limit and stamina can't be negative.")
        else if (updated.handoverMinutes < 1)
          Left("The handover window has to be at least a minute, or nobody could ever accept one.")
        else {
          repository.saveSettings(guildId, updated)
          // Turning a limit on hands everybody a full tank: whatever the rows say
          // is an artefact of an earlier setting, and starting people mid-day in
          // debt would refuse claims under a rule that wasn't in force. Switching
          // off needs nothing, since the numbers are then ignored.
          if (current.staminaMinutes <= 0 && updated.staminaMinutes > 0) {
            val cleared = repository.clearStamina(guildId)
            if (cleared > 0)
              logger.info(s"Stamina switched on in guild '$guildId' — refilled $cleared tanks")
          }
          Right(updated)
        }
    }

  /** Turn autoclaim on or off for the guild. Its own method rather than a sixth
   *  field on `updateSettings`, since the Claim rules form is already at
   *  Discord's five-component limit — see `RespawnThreads.boardModeratorButtons`.
   *
   *  Switching **on** settles hunts already under way, so nobody sitting on an
   *  unanswered Take Claim loses a spawn to a deadline just abolished. Switching
   *  **off** touches nothing running; the new rule binds the next slot. */
  def setAutoClaim(guildId: String, enabled: Boolean,
                   now: ZonedDateTime = ZonedDateTime.now()): Either[String, RespawnSettings] =
    settings(guildId) match {
      case None => Left("The respawn claim system isn't set up on this server yet.")
      case Some(current) if current.autoClaim == enabled => Right(current)
      case Some(current) =>
        val updated = current.copy(autoClaim = enabled)
        repository.saveSettings(guildId, updated)
        if (enabled) {
          val settled = repository.confirmPendingClaims(guildId, now)
          if (settled > 0)
            logger.info(s"Autoclaim switched on in guild '$guildId' — settled $settled hunt(s) " +
              "that were still waiting to be confirmed")
        }
        logger.info(s"Autoclaim ${if (enabled) "enabled" else "disabled"} in guild '$guildId'")
        Right(updated)
    }

  def updateChannels(guildId: String, forumChannel: String, boardThread: String): Unit =
    repository.updateChannels(guildId, forumChannel, boardThread)

  // --- catalogue ----------------------------------------------------------

  def listRespawns(guildId: String): List[Respawn] = repository.listRespawns(guildId)

  /** Everybody this guild's respawn system knows, for a moderator to pick from.
   *  Capped because it feeds a picker, not a report; anyone beyond the cap is
   *  still reachable by Discord id, which the same field accepts. */
  def knownMembers(guildId: String): List[com.tibiabot.persistence.KnownMember] =
    repository.knownMembers(guildId, RespawnService.MaxKnownMembers)

  /** Resolve what a user typed — a code ("415"), a name, or the creature — to a
   *  catalogue entry. Autocomplete sends the code back, so that path is the
   *  common one. Creature is tried last and settles a query only on exactly one
   *  match, since several spawns can share a monster. */
  def resolve(guildId: String, query: String): Option[Respawn] = {
    val trimmed = query.trim
    if (trimmed.isEmpty) None
    else repository.findByCode(guildId, trimmed)
      .orElse(RespawnService.resolveIn(repository.listRespawns(guildId), trimmed))
  }

  def removeRespawn(guildId: String, respawnId: Long): Unit = repository.removeRespawn(guildId, respawnId)

  /** Push the bundled list's improved creature choices to a guild that already
   *  imported it, and report how many changed. Run at boot, since `importSeed`
   *  never revisits a code the guild has and an improved list would otherwise
   *  only reach new guilds. Leaves custom rows and hand-picked creatures alone.
   *
   *  Only `creature` is synced — names and regions are a server's to reword. */
  def syncSeedCreatures(guildId: String): Int =
    repository.syncSeedCreatures(guildId, RespawnCatalogue.seed.map(s => (s.code, s.creature)))

  /** Import the bundled seed catalogue, skipping codes the guild already has.
   *  Safe to run repeatedly — it never overwrites a guild's own edits. Returns
   *  how many entries were added. */
  def importSeed(guildId: String): Int =
    repository.importSeed(guildId, RespawnCatalogue.seed.map(s => (s.code, s.region, s.name, s.creature)))

  /** Bring a guild's catalogue in line with the bundled file: missing codes,
   *  changed names and cities, and codes the file has dropped. What `/repair`
   *  runs, and the only way a respawns.json edit reaches an existing guild.
   *
   *  Half the job on its own — hand the rows it returns to
   *  [[deleteRetiredThreads]], or every dropped code stays in Discord as a card
   *  offering Claim on a spawn nothing can resolve. */
  def syncSeed(guildId: String): SeedSync =
    repository.syncSeed(guildId, RespawnCatalogue.seed.map(s => (s.code, s.region, s.name, s.creature)))

  /** Take down the forum posts of codes [[syncSeed]] has just retired, and say
   *  how many went. Split off from it only because the repository has no Discord
   *  to delete with: a retired code whose card is still in the forum is a spawn
   *  members can find, open and press Claim on, for nothing but a refusal. */
  def deleteRetiredThreads(guild: Guild, config: RespawnSettings, retired: List[Respawn]): Int =
    RespawnThreads.deleteThreads(guild, config, retired.map(_.threadId))

  /** Delete respawn-forum posts that no catalogue row points at. What
   *  [[deleteRetiredThreads]] cannot reach: a post is found from its row's
   *  `threadId`, so a row deleted without its post leaves nothing that knows the
   *  post exists. Those are found from the other side instead.
   *
   *  An empty catalogue is treated as a failed read, not a guild with no spawns
   *  — the two are indistinguishable here and acting on the first would delete
   *  the whole forum. [[RespawnThreads.deleteUnknownThreads]] holds the rest of
   *  the guards. */
  def deleteOrphanedThreads(guild: Guild, config: RespawnSettings,
                            limit: Int = OrphanSweepLimit): Int = {
    val known = repository.listRespawns(guild.getId)
    if (known.isEmpty) 0
    else RespawnThreads.deleteUnknownThreads(guild, config,
      known.map(_.threadId).filter(_.nonEmpty).toSet, limit)
  }

  /** How many orphaned posts one sweep will take. Generous next to what a seed
   *  edit strands, small enough that a sweep gone wrong is a log line, not an
   *  empty forum. */
  private val OrphanSweepLimit = 25

  /** Add a spawn the bundled list does not have. Written as a `custom` row, which
   *  keeps `syncSeed` off it — the bundled file must not retire a code it never
   *  shipped.
   *
   *  Refusals are worded for the person typing, since this comes from a form. A
   *  code already in use is refused rather than overwritten, which would rename
   *  whatever people are already claiming under it. */
  def addCustomSpawn(guildId: String, addedBy: String, code: String, region: String,
                     name: String, creature: String): Either[String, Respawn] = {
    val trimmedCode = code.trim
    val trimmedName = name.trim
    val trimmedRegion = region.trim
    val trimmedCreature = creature.trim

    RespawnService.spawnFault(trimmedCode, trimmedRegion, trimmedName, trimmedCreature) match {
      case Some(fault) => Left(fault)
      case None => repository.findByCode(guildId, trimmedCode) match {
        case Some(existing) =>
          Left(s"${existing.displayName} already uses that code.")
        case None =>
          val added = repository.addRespawn(guildId, trimmedCode, trimmedName, trimmedCreature,
            trimmedRegion, world = "", mapperLink = "", source = Respawn.SourceCustom, addedBy = addedBy)
          logger.info(s"'$addedBy' added respawn '${added.code}' (${added.name}) to guild '$guildId'")
          Right(added)
      }
    }
  }

  /** Take a spawn a guild added back out of its catalogue. Custom rows only: a
   *  seed code deleted here would reappear at the next boot, which reads as the
   *  button not working.
   *
   *  Refused while anybody holds, is queued for or has booked it, since
   *  `removeRespawn` would take those rows with it. `syncSeed` retires a dropped
   *  code without asking — the file dropping a code means the spawn is gone,
   *  where a moderator pressing Remove is tidying and can come back later. */
  def removeCustomSpawn(guildId: String, code: String): Either[String, Respawn] =
    resolve(guildId, code) match {
      case None => Left(s"No spawn matches '$code'.")
      case Some(respawn) if respawn.source != Respawn.SourceCustom =>
        Left(s"${respawn.displayName} comes from the bundled list, so it can't be removed here.")
      case Some(respawn) =>
        val now = ZonedDateTime.now()
        val busy = repository.activeClaim(guildId, respawn.id).isDefined ||
          repository.queueFor(guildId, respawn.id).nonEmpty ||
          repository.reservationsFor(guildId, respawn.id, now).nonEmpty ||
          repository.schedulesForRespawn(guildId, respawn.id).nonEmpty
        if (busy)
          Left(s"${respawn.displayName} is claimed, queued for or booked. " +
            "Clear those first — removing it would take somebody's hunt with it.")
        else {
          repository.removeRespawn(guildId, respawn.id)
          logger.info(s"Removed custom respawn '${respawn.code}' (${respawn.name}) from guild '$guildId'")
          Right(respawn)
        }
    }

  /** Record that the board post now matches the catalogue. For paths that redraw
   *  unconditionally — `/repair` — since otherwise the next boot finds no record
   *  of that redraw and does it again. */
  def recordBoardDrawn(guildId: String): Unit =
    repository.setBoardDigest(guildId, RespawnBoardImage.digestOf(repository.listRespawns(guildId)))

  /** Put the pinned board post back in step with the catalogue, if it isn't.
   *
   *  The board post *is* the catalogue, so a stale one is a list of codes people
   *  will type and be refused for. Guarded by a fingerprint rather than run
   *  unconditionally — a redraw is a REST edit per guild, and restarts are far
   *  more frequent than catalogue changes. A missing post is reposted instead.
   *
   *  Returns whether Discord was touched. Nothing here is fatal, and the digest
   *  is recorded only on success, so a failure retries on the next boot. */
  def redrawBoardIfChanged(guild: Guild, config: RespawnSettings): Boolean = {
    val guildId = guild.getId
    val spawns = repository.listRespawns(guildId)
    val digest = RespawnBoardImage.digestOf(spawns)
    if (repository.boardDigest(guildId).contains(digest)) false
    else RespawnThreads.findForum(guild, config).exists { forum =>
      val drawn = RespawnThreads.resolveThread(guild, forum, config.boardThread) match {
        case Some(_) => RespawnThreads.redrawBoard(guild, config, spawns)
        case None =>
          // The channel survived and the post did not. Nothing to edit, so it is
          // posted afresh and the guild pointed at the new thread.
          Try {
            val boardId = RespawnThreads.postBoard(forum, config, spawns)
            repository.updateChannels(guildId, forum.getId, boardId)
            logger.info(s"Reposted the missing respawn board in guild '$guildId'")
            true
          }.recover { case NonFatal(error) =>
            logger.warn(s"Could not repost the respawn board in guild '$guildId'", error)
            false
          }.get
      }
      if (drawn) repository.setBoardDigest(guildId, digest)
      drawn
    }
  }

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

  /** When a claim starting at `startsAt` for `minutes` should end, stopping short
   *  of `nextReservation` rather than running into it. The single place that
   *  truncation happens, so no call site has to repeat it. */
  def endsAtFor(startsAt: ZonedDateTime, minutes: Int,
                nextReservation: Option[ZonedDateTime] = None): ZonedDateTime = {
    val wanted = startsAt.plusMinutes(minutes.toLong)
    // Taking the reservation as a parameter keeps this pure and testable.
    nextReservation.filter(_.isBefore(wanted)).getOrElse(wanted)
  }

  /** The shortest claim worth granting. Below this, truncating against a booked
   *  slot gives somebody a hunt that is over before they have walked there, so
   *  the claim is refused with an explanation instead. */
  val MinimumClaimMinutes: Int = 5

  /** The longest a moderator may drag a window out to. Not the guild's maximum
   *  claim length, which deliberately does not apply here (see [[editSlot]]) —
   *  only a guard against a number that could not be a hunt. */
  val MaxModeratorSlotMinutes: Int = 12 * 60

  /** The longest ceiling a single spawn may be given. A day, matching
   *  `RespawnSchedule.Daily`: `addSchedule` refuses anything longer, so a higher
   *  ceiling would be usable from one door and not the other. */
  val MaxSpawnCeilingMinutes: Int = RespawnSchedule.Daily


  /** When the next booked slot on a spawn starts, if there is one. */
  def nextReservationStart(guildId: String, respawnId: Long,
                           now: ZonedDateTime = ZonedDateTime.now()): Option[ZonedDateTime] =
    repository.reservationsFor(guildId, respawnId, now).flatMap(_.startsAt).headOption

  /** Claim a spawn, or join its queue if someone already holds it. The claim is
   *  committed before any Discord side effect runs, so a Discord failure leaves a
   *  valid claim with a stale-looking post rather than losing the claim. */
  def claim(guild: Guild, userId: String, userName: String, nickname: String, characterName: String,
            query: String, requestedMinutes: Option[Int],
            now: ZonedDateTime = ZonedDateTime.now()): ClaimOutcome =
    settings(guild.getId) match {
      case None => ClaimOutcome.NotConfigured
      case Some(config) =>
        resolve(guild.getId, query) match {
          case None => ClaimOutcome.UnknownSpawn(query)
          case Some(respawn) =>
            // Explicit request, then the member's default, then the guild's — so
            // the forum buttons (which pass no duration) honour the member's.
            val minutes = requestedMinutes.getOrElse(
              repository.userPrefs(guild.getId, userId).defaultDurationOr(config.defaultDurationMinutes))
            val ceiling = config.maxFor(respawn)
            if (minutes <= 0 || minutes > ceiling)
              ClaimOutcome.BadDuration(minutes, ceiling)
            else {
              val guildId = guild.getId
              val alreadyHeld = repository.openClaimsForUser(guildId, userId).find(_.respawnId == respawn.id)
              alreadyHeld match {
                case Some(existing) => ClaimOutcome.AlreadyHolding(respawn, existing)
                case None =>
                  val boundary = resetBoundary(now)
                  val tank = repository.stamina(guildId, userId, config.staminaMinutes, boundary)
                  // Not affording the whole hunt is no refusal — beginClaim
                  // shortens it. Only an empty tank is refused, and here, so
                  // nobody joins a queue they could never take a turn in.
                  if (!tank.unlimited && tank.remainingMinutes < MinimumClaimMinutes)
                    ClaimOutcome.NoStamina(respawn, minutes, tank, ServerSaveSchedule.nextServerSave(now))
                  // An outstanding offer means the spawn is spoken for even if its
                  // previous holder is closed out; without this, claiming outright
                  // would leave two live claims once the offer was accepted.
                  else if (repository.activeClaim(guildId, respawn.id).isDefined ||
                           repository.offeredClaim(guildId, respawn.id).isDefined)
                    enqueue(guild, respawn, config, userId, userName, nickname, characterName, minutes)
                  else
                    beginClaim(guild, respawn, config, userId, userName, nickname, characterName, minutes,
                      RespawnClaim.KindAdHoc, now)
              }
            }
        }
    }

  /** Start a claim right now. The single place a claim becomes active — command,
   *  Claim button, queue promotion and scheduled occurrence all come through here,
   *  so none of them can forget the stamina reservation or the thread update. */
  def beginClaim(guild: Guild, respawn: Respawn, config: RespawnSettings, userId: String,
                 userName: String, nickname: String, characterName: String, minutes: Int, kind: String,
                 now: ZonedDateTime): ClaimOutcome = {
    val guildId = guild.getId
    val boundary = resetBoundary(now)

    // A booked slot cuts an ad-hoc claim short, and stamina is charged for the
    // shorter hunt. A scheduled occurrence starting its own slot is exempt, or it
    // would truncate against itself.
    val reservation =
      if (kind == RespawnClaim.KindScheduled) None else nextReservationStart(guildId, respawn.id, now)
    val untilBooking = endsAtFor(now, minutes, reservation)
    val allowedByBooking = math.max(0, java.time.Duration.between(now, untilBooking).toMinutes).toInt
    if (allowedByBooking < MinimumClaimMinutes)
      return ClaimOutcome.Reserved(respawn, reservation.getOrElse(untilBooking))

    // A tank that cannot cover the whole hunt shortens it rather than refusing —
    // the same rule a booking gets. Refusing would leave the spawn empty and the
    // member hunting nothing.
    val tank = repository.stamina(guildId, userId, config.staminaMinutes, boundary)
    val granted = RespawnService.grantedMinutes(allowedByBooking, tank)
    // Below the floor there is nothing worth starting — a stamina refusal, not a
    // booking one.
    if (granted < MinimumClaimMinutes)
      return ClaimOutcome.NoStamina(respawn, minutes, tank, ServerSaveSchedule.nextServerSave(now))
    val end = now.plusMinutes(granted.toLong)

    // Re-check under the reservation rather than trusting the read above: a second
    // claim may have taken the room, and reserveStamina writing nothing is the
    // authoritative answer.
    if (!repository.reserveStamina(guildId, userId, granted, config.staminaMinutes, boundary)) {
      val fresh = repository.stamina(guildId, userId, config.staminaMinutes, boundary)
      ClaimOutcome.NoStamina(respawn, granted, fresh, ServerSaveSchedule.nextServerSave(now))
    } else {
      repository.insertActiveClaim(guildId, respawn.id, userId, userName, nickname, characterName,
        now, end, granted, kind) match {
        case None =>
          // Somebody claimed it between the check and this insert. Refund rather
          // than strand the stamina until server save, and say so — silently
          // queueing them would answer a question they did not ask.
          repository.refundStamina(guildId, userId, granted, boundary)
          ClaimOutcome.JustTaken(respawn)
        case Some(claim) =>
          refreshThread(guild, respawn, config)
          // Name the booking only when the booking is what shortened it: a hunt
          // cut short by an empty tank has none, and saying otherwise sends
          // somebody looking for a slot that isn't there.
          val shortenedByBooking = reservation.isDefined && granted >= allowedByBooking
          if (granted < minutes)
            ClaimOutcome.Shortened(respawn, claim, minutes, if (shortenedByBooking) reservation else None)
          else ClaimOutcome.Claimed(respawn, claim)
      }
    }
  }

  private def enqueue(guild: Guild, respawn: Respawn, config: RespawnSettings, userId: String,
                      userName: String, nickname: String, characterName: String, minutes: Int): ClaimOutcome = {
    // Queueing deliberately does NOT reserve stamina, or people could park their
    // whole tank in queues that never reach the front. Reserved at promotion
    // instead; anyone who can't afford it by then is skipped (see sweepGuild).
    repository.enqueueClaim(guild.getId, respawn.id, userId, userName, nickname, characterName, minutes,
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
              // Only an *offered* claim leaving means a handover must move on.
              // Giving up a queue place changes nothing about the spawn, so
              // advancing here would hand the mid-hunt holder's spawn away.
              if (claim.leavingAdvancesHandover)
                respawn.foreach(r => beginHandover(guild, r, config, now, outgoing = handingOverHolder(guildId, r.id)))
              else
                respawn.foreach(refreshThread(guild, _, config))
              respawn.map(ReleaseOutcome.LeftQueue).getOrElse(ReleaseOutcome.NothingHeld)
            } else if (claim.limboUntil.isDefined) {
              // Already waiting on a handover. A second release must not refund
              // again: `ends_at` is left untouched during limbo, so the cap below
              // would hand back the same minutes twice.
              ReleaseOutcome.AlreadyHandingOver(respawn.map(_.displayName).getOrElse("that respawn"))
            } else {
              val refunded = refundFor(claim, now)
              if (refunded > 0) repository.refundStamina(guildId, userId, refunded, resetBoundary(now))
              // NOT finished here: the next person gets their answer window, and
              // the spawn stays this claimant's until then so a third party can't
              // snipe it. beginHandover finishes the claim if nobody is waiting.
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

  /** The holder of a spawn *already on its way out* — the only claim a handover
   *  may finish. A handover closes out whoever it replaces, so passing it a claim
   *  that isn't being handed over would end a hunt still running. Requiring limbo
   *  makes that impossible rather than merely unlikely. */
  private def handingOverHolder(guildId: String, respawnId: Long): Option[RespawnClaim] =
    repository.activeClaim(guildId, respawnId).filter(_.eligibleForHandover)

  /** Add time to the caller's active claim, within the ceiling that applies to
   *  the spawn it is on and their remaining stamina. Returns the new end time on
   *  success. */
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
            // Read before the bound is checked, since the bound depends on which
            // spawn this is (see `RespawnSettings.maxFor`). A spawn dropped from
            // the catalogue under a live claim falls back to the guild's number.
            val respawn = repository.findById(guildId, claim.respawnId)
            val newTotal = claim.durationMinutes + extraMinutes
            val ceiling = respawn.fold(config.maxDurationMinutes)(config.maxFor)
            if (extraMinutes <= 0 || newTotal > ceiling)
              Left(ClaimOutcome.BadDuration(newTotal, ceiling))
            else {
              val boundary = resetBoundary(now)
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
        val respawn = repository.findById(guildId, respawnId)
        val ceiling = respawn.fold(config.maxDurationMinutes)(config.maxFor)
        if (newTotalMinutes < MinimumClaimMinutes || newTotalMinutes > ceiling)
          Left(s"A claim has to be between ${RespawnEmbeds.humanDuration(MinimumClaimMinutes)} and " +
            s"${RespawnEmbeds.humanDuration(ceiling)}" +
            s"${if (respawn.exists(_.maxDurationMinutes.isDefined)) " on this spawn." else " on this server."}")
        else repository.openClaimsForUser(guildId, userId).find(_.respawnId == respawnId) match {
          case None => Left("You aren't holding or waiting for that respawn.")
          case Some(claim) =>
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

  /** End whoever holds a spawn, as a moderator. Deliberately the same path as
   *  them pressing Leave — stamina refunded, next in line offered — rather than
   *  `adminClear`, which also wipes the queue. The holder is told, or the spawn
   *  vanishes from under them with no explanation. */
  def forceLeave(guild: Guild, respawn: Respawn,
                 now: ZonedDateTime = ZonedDateTime.now()): Option[RespawnClaim] = {
    val guildId = guild.getId
    repository.activeClaim(guildId, respawn.id).map { holder =>
      release(guild, holder.userId, Some(respawn.code), now, RespawnClaim.Outcome.Forced)
      RespawnThreads.dm(guild, holder.userId,
        RespawnEmbeds.dmEmbed("Claim ended by a moderator",
          s"A moderator has freed ${RespawnEmbeds.spawnLink(respawn)}, so it's no longer yours.",
          imageFor(respawn), RespawnEmbeds.RedColor))
      holder
    }
  }

  /** Give the holder more time, for a moderator putting right a hunt that lost
   *  some. An override, not a request, so it differs from a member's own extend:
   *  no stamina is charged (granting tank time is a separate tool), and the
   *  guild's maximum claim length does not apply — enforcing it would refuse
   *  exactly the long hunts most likely to need rescuing.
   *
   *  Everyone else is affected only by the claim running longer: the queue waits,
   *  and a booking inside the new window is cut into, as with any extend. */
  def extendHolder(guild: Guild, respawn: Respawn, extraMinutes: Int,
                   now: ZonedDateTime = ZonedDateTime.now()): Either[String, (RespawnClaim, ZonedDateTime)] = {
    val guildId = guild.getId
    if (extraMinutes <= 0) Left("That would add no time.")
    else settings(guildId) match {
      case None => Left("The respawn claim system isn't set up on this server.")
      case Some(config) =>
        // A claim in limbo is already offered on; extending it would stretch a
        // hunt on its way out while the offer still stands.
        repository.activeClaim(guildId, respawn.id).filter(_.limboUntil.isEmpty) match {
          case None => Left(s"Nobody is hunting ${respawn.displayName} right now.")
          case Some(claim) =>
            val newEnd = claim.endsAt.getOrElse(now).plusMinutes(extraMinutes.toLong)
            repository.extendClaim(guildId, claim.id, newEnd, claim.durationMinutes + extraMinutes)
            refreshThread(guild, respawn, config)
            RespawnThreads.dm(guild, claim.userId,
              RespawnEmbeds.dmEmbed("Your hunt was extended",
                s"A moderator has added **${RespawnEmbeds.humanDuration(extraMinutes)}** to your claim on " +
                  s"${RespawnEmbeds.spawnLink(respawn)}. It now runs until <t:${newEnd.toEpochSecond}:t>, and it " +
                  "hasn't cost you any stamina.",
                imageFor(respawn), RespawnEmbeds.FreeColor))
            Right((claim, newEnd))
        }
    }
  }

  /** Hand a running hunt to somebody else, for a moderator sorting out a dispute
   *  or a mistaken claim. The stamina goes with it — charged to the new holder,
   *  refunded to the old — and it is refused if they cannot afford it, since the
   *  alternative is a hunt the next sweep cuts short. Both are told. */
  def reassignClaim(guild: Guild, respawnId: Long, toUserId: String, toUserName: String,
                    toNickname: String,
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
              Left(s"${Names.user(toNickname, toUserName)} has " +
                s"**${RespawnEmbeds.humanDuration(tank.remainingMinutes)}** of " +
                s"stamina left, and the rest of this hunt needs " +
                s"**${RespawnEmbeds.humanDuration(remaining)}**.")
            } else repository.reassignClaim(guildId, claim.id, toUserId, toUserName, toNickname) match {
              case None => Left("That hunt has already ended.")
              case Some(moved) =>
                if (remaining > 0) repository.refundStamina(guildId, claim.userId, remaining, boundary)
                RespawnThreads.dm(guild, claim.userId,
                  RespawnEmbeds.dmEmbed("Your hunt was reassigned",
                    RespawnEmbeds.claimReassignedFrom(respawn, toUserName), imageFor(respawn),
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

  // --- claim log ------------------------------------------------------------

  /** Entries shown on one page of the Log panel. */
  val LogPageSize: Int = 10

  /** How far back the Log panel will page. The rows are all still there; walking
   *  years of them ten at a time is just the wrong tool. */
  val LogMaxPages: Int = 10

  /** One page of the claim log, newest first, for the given [[LogScope]]. Fetches
   *  one row past the page so "there is more" needs no second count query;
   *  [[LogPage.hasOlder]] consumes it and hands back only the page. */
  def claimLog(guildId: String, scope: LogScope, page: Int): LogPage = {
    val safePage = math.max(0, math.min(page, LogMaxPages - 1))
    val respawnId = scope match { case LogScope.Spawn(id) => Some(id); case _ => None }
    val userId = scope match { case LogScope.Member(id) => Some(id); case _ => None }
    val fetched = repository.claimHistory(guildId, respawnId, userId, LogPageSize + 1, safePage * LogPageSize)
    LogPage(
      entries = fetched.take(LogPageSize),
      page = safePage,
      hasOlder = fetched.size > LogPageSize && safePage < LogMaxPages - 1
    )
  }

  def schedulesForRespawn(guildId: String, respawnId: Long): List[RespawnSchedule] =
    repository.schedulesForRespawn(guildId, respawnId)

  def schedulesForUser(guildId: String, userId: String): List[RespawnSchedule] =
    repository.schedulesForUser(guildId, userId)

  /** Standing bookings paired with the spawn each is on. Resolved here so a
   *  schedule whose spawn has been removed is dropped rather than rendered as a
   *  blank line. */
  def scheduleListing(guildId: String, userId: Option[String]): List[(RespawnSchedule, Respawn)] = {
    val schedules = userId.fold(repository.allSchedules(guildId))(repository.schedulesForUser(guildId, _))
    schedules.flatMap(schedule => repository.findById(guildId, schedule.respawnId).map(schedule -> _))
  }

  def findSchedule(guildId: String, scheduleId: Long): Option[RespawnSchedule] =
    repository.findSchedule(guildId, scheduleId)

  /** Book a slot on a spawn, repeating on chosen weekdays or happening once.
   *
   *  A clash is never simply allowed — two bookings at once is not a state the
   *  handover rules can resolve. A one-off over a single unqueried slot becomes a
   *  question for its owner (see `askForClash`); anything else is refused. */
  def addSchedule(guild: Guild, respawn: Respawn, userId: String, userName: String, nickname: String,
                  characterName: String, firstStart: ZonedDateTime, durationMinutes: Int,
                  daysOfWeek: Int = RespawnSchedule.EveryDay,
                  now: ZonedDateTime = ZonedDateTime.now()): Either[String, ScheduleResult] =
    settings(guild.getId) match {
      case None => Left("The respawn claim system isn't set up on this server yet.")
      case Some(config) =>
        val guildId = guild.getId
        val ceiling = config.maxFor(respawn)
        if (durationMinutes < MinimumClaimMinutes || durationMinutes > ceiling)
          Left(s"A slot has to be between ${RespawnEmbeds.humanDuration(MinimumClaimMinutes)} and " +
            s"${RespawnEmbeds.humanDuration(ceiling)}" +
            s"${if (respawn.maxDurationMinutes.isDefined) " on this spawn." else " on this server."}")
        else if (!firstStart.isAfter(now))
          Left("The first slot has to start in the future.")
        else if (durationMinutes >= RespawnSchedule.Daily)
          Left("A slot has to be shorter than a day.")
        else {
          // A clashing booking never becomes a row of its own, so this stand-in is
          // the only record of who is asking — hence the nickname on it too.
          val candidate = RespawnSchedule(0L, respawn.id, userId, userName, characterName,
            firstStart, RespawnSchedule.Daily, durationMinutes, active = true, now, daysOfWeek,
            nickname = nickname)
          // The clash check and the write are serialised on the spawn: without the
          // lock, two people booking the same evening each read the picture from
          // before the other wrote, and nothing downstream catches it (the unique
          // index on an occurrence sees two schedule ids, not one evening).
          //
          // Only those two. Materialising, the card and any DM stay outside, since
          // holding a row lock across a Discord round trip would stall every other
          // claim on the spawn. A rule is visible to the next booker as soon as it
          // is written, so nothing is lost by letting go early.
          repository.withRespawnLock(guildId, respawn.id) {
            // One read of the spawn's booked evenings, used by both halves of the
            // check: which of them this booking runs over, and which days their
            // rules have therefore stopped speaking for.
            val booked = repository.reservationsFor(guildId, respawn.id, now)
            val schedules = repository.schedulesForRespawn(guildId, respawn.id)
              .filter(overlaps(_, candidate))
              .filterNot(surrendered(guildId, booked, _, candidate, now))
            val slots = clashingReservations(booked, candidate, now)
            if (schedules.isEmpty && slots.isEmpty)
              Right(repository.addSchedule(guildId, respawn.id, userId, userName, nickname,
                characterName, firstStart, RespawnSchedule.Daily, durationMinutes, daysOfWeek))
            else Left((schedules, slots))
          } match {
            case Right(saved) =>
              materialise(guildId, saved, now)
              refreshThread(guild, respawn, config)
              Right(ScheduleResult.Booked(saved))
            case Left((schedules, slots)) =>
              askForClash(guild, respawn, config, candidate, schedules, slots, now)
          }
        }
    }

  /** A booking that clashes: ask the other person, or refuse.
   *
   *  Only one shape is worth asking about — a one-off landing on exactly one
   *  booked slot, owned by somebody else, nobody has asked about yet. The rest are
   *  refused, each saying why: a repeating booking, because an answer for tonight
   *  cannot stand for every Tuesday; two slots, because that is a negotiation
   *  rather than a request; an unmaterialised occurrence, because there is no slot
   *  to attach the question to yet.
   *
   *  Nothing is written for the asker here — their booking exists only as the
   *  window recorded against the slot, so a refused request leaves nothing. */
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
      case ClashVerdict.Confirmed =>
        refuse(" Its owner has confirmed they're hunting it, so there's nothing to ask.")
      case ClashVerdict.ManySlots =>
        refuse(" It runs over more than one booking, so there's nobody single to ask.")

      case ClashVerdict.Ask(slot) =>
        val deadline = RespawnService.answerDeadline(slot.startsAt.getOrElse(now),
          Config.Respawn.bookingRequestGraceMinutes)
        val theirs = Some((candidate.anchorAt, candidate.durationMinutes))

        repository.requestOccurrence(guildId, slot.id, candidate.userId, candidate.userName,
          candidate.nickname, now, deadline, theirs) match {
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
      case Some(slot) => (Names.user(slot.nickname, slot.userName), slot.startsAt, slot.durationMinutes)
      case None =>
        val schedule = schedules.head
        (Names.user(schedule.nickname, schedule.userName),
          schedule.nextStartAtOrAfter(now), schedule.durationMinutes)
    }
    val at = when.map(start => s"<t:${start.toInstant.getEpochSecond}:t>").getOrElse("soon")
    s"That clashes with $who's slot on this respawn " +
      s"($at for ${RespawnEmbeds.humanDuration(minutes)})."
  }

  /** Booked slots on a spawn that any occurrence of `candidate` would run over.
   *  Checked alongside the schedule-to-schedule rule, not instead of it: a slot
   *  handed to an asker has no schedule behind it, and a rule beyond the
   *  look-ahead has no slot yet, so either check alone misses one of them. */
  private def clashingReservations(booked: List[RespawnClaim], candidate: RespawnSchedule,
                                   now: ZonedDateTime): List[RespawnClaim] = {
    val horizon = now.plusMinutes(Config.Respawn.scheduleLookAheadMinutes.toLong)
    booked.filter(candidate.overlapsSlot(_, now, horizon))
  }

  /** Whether two bookings on the same spawn ever run at the same time. The rule
   *  itself lives in the domain, where it is testable without a database. */
  private[respawn] def overlaps(a: RespawnSchedule, b: RespawnSchedule): Boolean =
    RespawnSchedule.clash(a, b)

  /** Whether `schedule` has stopped deciding every day it would contest with
   *  `candidate`.
   *
   *  A rule speaks for every day, but what became of one day lives in that day's
   *  row — so comparing rules alone leaves a surrendered rule still defending an
   *  evening nobody will hunt. Two ways a day stops being the rule's to speak
   *  for: it was given up, or it has been written down as a slot. A written-down
   *  day is whatever its row now says — the length can have been edited since —
   *  and that row is checked directly by `clashingReservations`, so leaving the
   *  rule to defend it as well is how an evening shortened from three hours to
   *  two still refused the hour it had let go.
   *
   *  All-or-nothing: a rule that settled one Thursday still owns the rest, and a
   *  day too far ahead to have a row has settled nothing, which keeps
   *  `TooFarAhead` from quietly becoming a yes. */
  private def surrendered(guildId: String, booked: List[RespawnClaim], schedule: RespawnSchedule,
                          candidate: RespawnSchedule, now: ZonedDateTime): Boolean = {
    val horizon = now.plusMinutes(Config.Respawn.scheduleLookAheadMinutes.toLong)
    val settled = daysGivenUp(guildId, now, Some(horizon), Some(schedule.respawnId))
      .getOrElse(schedule.id, Set.empty)
    val written = booked.collect {
      case slot if slot.scheduleId.contains(schedule.id) => slot.startsAt.map(_.toInstant)
    }.flatten.toSet
    RespawnSchedule.surrendered(schedule, candidate, settled ++ written, now, horizon)
  }

  /** Drop every booking one member holds anywhere in the guild. Each spawn's card
   *  is rewritten once, not once per booking. */
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

  /** Drop every booking one member holds on one spawn — all together, since a
   *  button per booking made the panel a row of near-identical red buttons.
   *  Rewrites the card once at the end. */
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

  /** Retire a schedule and drop the slots it had booked but not yet started. */
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

  /** Book every slot of a schedule starting within the look-ahead, which is what
   *  makes a slot visible and requestable before it begins. Idempotent: the
   *  (schedule, start) pair is unique, so re-running each sweep books nothing
   *  twice. */
  private def materialise(guildId: String, schedule: RespawnSchedule, now: ZonedDateTime): Int = {
    val horizon = now.plusMinutes(Config.Respawn.scheduleLookAheadMinutes.toLong)
    schedule.occurrencesBetween(now, horizon).count { start =>
      repository.reserveOccurrence(guildId, schedule.id, schedule.respawnId, schedule.userId,
        schedule.userName, schedule.nickname, schedule.characterName, start, schedule.durationMinutes).isDefined
    }
  }

  // --- asking for a booked slot -------------------------------------------

  /** The owner says they are hunting it: the request is refused and the slot
   *  stays theirs. */
  def keepSlot(guild: Guild, userId: String, claimId: Long): SlotAnswer =
    withOwnedSlot(guild, userId, claimId) { (config, respawn, slot) =>
      if (settleRequestOn(guild, respawn, slot)) SlotAnswer.Kept(respawn) else SlotAnswer.Gone
    }

  /** Take a pending request off a slot its owner is keeping, and tell whoever
   *  asked. Shared by both ways of saying yes: **Keep** on the request DM and
   *  **Confirm** on the reminder. `askedAt` deliberately survives (see
   *  `keepOccurrence`) so the slot cannot be asked about twice. No card rewrite —
   *  only the *asked* note goes. */
  private def settleRequestOn(guild: Guild, respawn: Respawn, slot: RespawnClaim): Boolean =
    repository.keepOccurrence(guild.getId, slot.id) match {
      case None => false
      case Some(_) =>
        slot.requesterUserId.foreach { requester =>
          RespawnThreads.dm(guild, requester,
            RespawnEmbeds.dmEmbed("Slot request declined",
              RespawnEmbeds.slotRequestDeclined(respawn, slot), imageFor(respawn),
              RespawnEmbeds.RedColor))
        }
        true
    }

  /** The owner isn't hunting it, or never answered: the slot passes to whoever
   *  asked, as a booking of their own. They get the window they asked for, which
   *  may be longer than the slot given up, so it is checked against the rest of
   *  the evening first — the owner's slot goes either way, but the asker only
   *  gets what is genuinely free. */
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
            // A booking of their own, not a rewritten row: the slot is no longer
            // an occurrence of anybody's rule, and the audit trail keeps both halves.
            repository.reserveFor(guildId, respawn.id, requester,
              slot.requesterUserName.getOrElse(""), slot.requesterNickname.getOrElse(""),
              start, minutes)
            RespawnThreads.dm(guild, requester,
              RespawnEmbeds.dmEmbed("The hunt is yours",
                RespawnEmbeds.slotRequestGranted(respawn, start, minutes, config.autoClaim,
                  Config.Respawn.slotConfirmMinutes), imageFor(respawn)))
            refreshThread(guild, respawn, config)
            SlotAnswer.Passed(respawn, slot.requesterUserName.getOrElse(""),
              slot.requesterNickname.getOrElse(""))
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

  /** Resolve everything whose time has come for one guild: expired claims closed
   *  and queues advanced, claims near their deadline warned once. Runs on a fixed
   *  interval rather than per-claim timers, so nothing is scheduled in memory and
   *  anything that lapsed while the bot was down is caught by the first sweep. */
  def sweep(guild: Guild, now: ZonedDateTime = ZonedDateTime.now()): Unit =
    settings(guild.getId).foreach { config =>
      val guildId = guild.getId

      // Book upcoming slots, so they show on the card before they begin.
      repository.activeSchedules(guildId).foreach { schedule =>
        Try {
          materialise(guildId, schedule, now)
          // A one-off whose slot has gone by is spent; retiring it keeps it out of
          // the owner's list and allowance. The slot it booked is its own row.
          if (schedule.nextStartAtOrAfter(now).isEmpty)
            repository.deactivateSchedule(guildId, schedule.id)
        }.failed.foreach { error =>
          logger.warn(s"Failed to book slots for respawn schedule ${schedule.id} in guild '$guildId'", error)
        }
      }

      // Nudge whoever booked a slot about to start. Not under autoclaim: this DM
      // is the Confirm button and the reason to press it, and a self-claiming slot
      // has neither.
      if (!config.autoClaim && Config.Respawn.slotReminderMinutes > 0) {
        repository.slotsNeedingReminder(guildId, now, Config.Respawn.slotReminderMinutes).foreach { slot =>
          Try {
            repository.markWarned(guildId, slot.id)
            repository.findById(guildId, slot.respawnId).foreach { respawn =>
              // Blue, not warning yellow: blue means booking on the board too (see
              // RespawnEmbeds.BookedColor), and nothing is wrong here.
              RespawnThreads.dm(guild, slot.userId,
                RespawnEmbeds.dmEmbed("Your hunt starts soon",
                  RespawnEmbeds.slotReminder(respawn, slot), imageFor(respawn),
                  RespawnEmbeds.BookedColor),
                // Optional: settles the slot early, so nobody can ask for it.
                Some(RespawnThreads.confirmSlotButtons(guildId, slot.id, "Confirm")))
            }
          }.failed.foreach { error =>
            logger.warn(s"Failed to remind about respawn slot ${slot.id} in guild '$guildId'", error)
          }
        }
      }

      // Requests the owner never answered. Silence reads as "not tonight", so a
      // slot cannot be held hostage by somebody not reading their DMs.
      repository.expiredRequests(guildId, now).foreach { slot =>
        Try(passSlot(guild, "", slot.id, RespawnClaim.Outcome.NoAnswer, now)).failed.foreach { error =>
          logger.warn(s"Failed to pass on unanswered slot request ${slot.id} in guild '$guildId'", error)
        }
      }

      // Slots whose whole window went by without starting — the bot was down.
      // Closing them stops the due-slot query below starting a finished hunt.
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

      // Lapsed handover offers first. The offer and the outgoing claim's limbo
      // window are the same length, so they elapse on the same sweep; clearing the
      // offer here lets the claim below move straight on to the next person.
      repository.expiredOffers(guildId, now).foreach { offer =>
        Try {
          repository.cancelClaim(guildId, offer.id, RespawnClaim.Outcome.OfferLapsed)
          repository.findById(guildId, offer.respawnId).foreach { respawn =>
            RespawnThreads.dm(guild, offer.userId,
              RespawnEmbeds.dmEmbed("Handover expired", RespawnEmbeds.handoverLapsed(respawn),
                imageFor(respawn), RespawnEmbeds.RedColor))
            logger.info(s"Handover offer ${offer.id} on '${respawn.code}' in guild '$guildId' lapsed unanswered")
            // Usually a limbo claim sits behind the offer and the pass below moves
            // the spawn on. But a claim gets only one handover window, so a second
            // lapse leaves the pass nothing to find — advance it here, or the spawn
            // sits free with a lapsed offer on its card and the queue never asked.
            if (repository.activeClaim(guildId, respawn.id).isEmpty)
              beginHandover(guild, respawn, config, now, outgoing = None)
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
              // Time's up: start the handover. The claim stays the spawn's holder
              // until someone accepts or the window lapses; beginHandover finishes
              // it if nobody is waiting.
              beginHandover(guild, respawn, config, now, outgoing = Some(claim), notifyOutgoing = true)
            }
          }
        }.failed.foreach { error =>
          logger.warn(s"Failed to close expired respawn claim ${claim.id} in guild '$guildId'", error)
        }
      }

      // Bookings that started on their own and whose owner never said they were
      // there. Given up as if they had pressed Leave — unused minutes back in the
      // tank, spawn on through the ordinary handover. Otherwise a booking nobody
      // turned up for holds the spawn for its whole window.
      repository.unconfirmedClaims(guildId, now).foreach { claim =>
        Try {
          repository.findById(guildId, claim.respawnId).foreach { respawn =>
            val refunded = refundFor(claim, now)
            if (refunded > 0) repository.refundStamina(guildId, claim.userId, refunded, resetBoundary(now))
            RespawnThreads.dm(guild, claim.userId,
              RespawnEmbeds.dmEmbed("Hunt given up", RespawnEmbeds.slotUnconfirmed(respawn, refunded),
                imageFor(respawn), RespawnEmbeds.RedColor))
            logger.info(s"Gave up unconfirmed respawn claim ${claim.id} on '${respawn.code}' in " +
              s"guild '$guildId' — user ${claim.userId} never took it")
            // Not finished here, same as an early release — see `release`.
            beginHandover(guild, respawn, config, now, outgoing = Some(claim),
              outgoingOutcome = RespawnClaim.Outcome.Unconfirmed)
          }
        }.failed.foreach { error =>
          logger.warn(s"Failed to give up unconfirmed respawn claim ${claim.id} in guild '$guildId'", error)
        }
      }

      // The claim-ending reminder, kept but not sent: three DMs for one evening's
      // hunt was more than the feature is worth. Uncomment to restore —
      // `unwarnedActiveClaims`, `markWarned` and RespawnEmbeds.expiryWarning are
      // all still in place and still tested.
      //
      // // Reminder lead time is per member, so every running claim is considered
      // // and each one's own owner decides whether it is due yet.
      // repository.unwarnedActiveClaims(guildId, now).foreach { claim =>
      //   Try {
      //     val lead = repository.userPrefs(guildId, claim.userId).warnMinutesOr(config.warnMinutes)
      //     val due = lead > 0 && claim.endsAt.exists(!_.isAfter(now.plusMinutes(lead.toLong)))
      //     if (due) {
      //       repository.markWarned(guildId, claim.id)
      //       repository.findById(guildId, claim.respawnId).foreach { respawn =>
      //         // DM only, with no thread fallback: a nudge about your own claim
      //         // isn't worth pinging a shared thread for, and missing it costs
      //         // nothing — the claim ends the same way either way.
      //         RespawnThreads.dm(guild, claim.userId,
      //           RespawnEmbeds.dmEmbed("Claim ending soon", RespawnEmbeds.expiryWarning(respawn, claim),
      //             imageFor(respawn), RespawnEmbeds.WarnColor))
      //       }
      //     }
      //   }.failed.foreach { error =>
      //     logger.warn(s"Failed to warn respawn claim ${claim.id} in guild '$guildId'", error)
      //   }
      // }
    }

  /** Turn a booked slot into a live claim. Three ways it goes: the spawn is free
   *  and affordable, so they take it; somebody else is on it, so they queue and
   *  get it through the ordinary handover; or their tank is spent, so the slot is
   *  dropped and they are told. */
  private def startSlot(guild: Guild, config: RespawnSettings, slot: RespawnClaim,
                        now: ZonedDateTime): Unit = {
    val guildId = guild.getId
    repository.findById(guildId, slot.respawnId).foreach { respawn =>
      val boundary = resetBoundary(now)
      val holder = repository.activeClaim(guildId, respawn.id)
      // A slot ends when it was booked to end, however late it starts: a booking
      // is a window, not a stopwatch. Running the full length from a late start
      // would push into whatever is booked next, which would then find the spawn
      // held and drop its own owner into the queue.
      val bookedEnd = slot.bookedEnd.getOrElse(now.plusMinutes(slot.durationMinutes.toLong))
      // Charged for what they actually get. A window fully gone by never reaches
      // here — the sweep closes those as missed first.
      val remaining = slot.minutesLeftAt(now)

      if (holder.exists(_.userId == slot.userId)) {
        // They are already on it themselves: an ad-hoc claim is cut short at the
        // booking's start, so a sweep at that moment finds both. Treating it as a
        // collision would queue them behind themselves — instead the booking folds
        // into the hunt they are having and carries its end forward.
        val current = holder.get
        val extra = current.endsAt
          .map(end => math.max(0, java.time.Duration.between(end, bookedEnd).toMinutes).toInt)
          .getOrElse(remaining)

        if (extra > 0 && !repository.reserveStamina(guildId, slot.userId, extra,
              config.staminaMinutes, boundary)) {
          // Tank won't cover the extension: the existing hunt stands and the slot
          // is closed rather than half-applied.
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
        repository.enqueueClaim(guildId, respawn.id, slot.userId, slot.userName, slot.nickname, slot.characterName,
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
        // Capped at the slot's own end, so a booking shorter than the confirm
        // window is never outlived by its own deadline — a 10-minute slot with a
        // 15-minute window would simply run out first and be swept as finished,
        // which reads as the deadline never having applied.
        val window = now.plusMinutes(Config.Respawn.slotConfirmMinutes.toLong)
        val confirmBy = if (window.isBefore(bookedEnd)) window else bookedEnd
        // Autoclaim answers the deadline as the slot starts, which puts every
        // booking down the already-confirmed branch below: the hunt is theirs and
        // nothing is asked of them. `confirm_by` is still stamped — it records
        // that this claim began as a booking either way.
        //
        // A slot with an open question never reaches here — `dueReservations`
        // holds it back — so an unanswered ask still passes to whoever asked,
        // autoclaim or not.
        val autoConfirmed = if (config.autoClaim) Some(now) else None
        repository.startReservation(guildId, slot.id, now, bookedEnd, confirmBy, autoConfirmed) match {
          case None =>
            // Something else already started it; hand the stamina straight back.
            repository.refundStamina(guildId, slot.userId, remaining, boundary)
          case Some(started) if started.confirmed =>
            // Nothing left to ask: either autoclaim just settled it, or its owner
            // pressed Confirm on the reminder before it started.
            refreshThread(guild, respawn, config)
            RespawnThreads.dm(guild, slot.userId,
              RespawnEmbeds.dmEmbed("Your hunt has started",
                RespawnEmbeds.slotStarted(respawn, started), imageFor(respawn),
                RespawnEmbeds.FreeColor))
          case Some(started) =>
            // Genuinely theirs from now — nobody else can take it — but only
            // until the deadline above, which the sweep enforces.
            refreshThread(guild, respawn, config)
            RespawnThreads.dm(guild, slot.userId,
              RespawnEmbeds.dmEmbed("Your hunt has started",
                RespawnEmbeds.slotStartedUnconfirmed(respawn, started, confirmBy), imageFor(respawn),
                RespawnEmbeds.BookedColor),
              Some(RespawnThreads.confirmSlotButtons(guildId, started.id, "Take Claim")))
        }
      }
    }
  }

  /** Offer a spawn that's changing hands to the next person in line, or shut it
   *  down if nobody is waiting. Returns the offer that went out, if any.
   *
   *  The next person is **asked**, not given: a DM with Claim/Cancel and
   *  `handoverMinutes` to answer, so a spawn is never silently handed to somebody
   *  who walked away. Meanwhile `outgoing` sits in limbo — still the holder, so
   *  nobody takes it in the gap, at no stamina cost since its deadline is
   *  untouched. Anyone at the front who can no longer afford their claim is
   *  dropped rather than left blocking the line. */
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
      // Checked but NOT reserved: reserving would tie up the tank of somebody who
      // may never answer. That happens on accept.
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
          // Same length as the offer, so the spawn stays with its previous holder
          // while the next person decides and both windows lapse on one sweep.
          outgoing.foreach(claim => repository.setLimbo(guildId, claim.id, expiresAt))
          // No card refresh: holder unchanged and the offered member still renders
          // at the head of the queue, so the card would be byte-identical.
          val delivered = RespawnThreads.dm(guild, offer.userId,
            RespawnEmbeds.dmEmbed("Your turn on a respawn",
              RespawnEmbeds.handoverOffer(respawn, offer, guild.getName, expiresAt), imageFor(respawn),
              RespawnEmbeds.FreeColor),
            Some(RespawnThreads.offerButtons(guildId, offer.id)))
          if (!delivered) {
            // No thread fallback — spawn threads stay clean. The offer lapses on
            // schedule and moves on, so an unreachable member loses their turn but
            // the spawn keeps moving. Logged because it is otherwise invisible.
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
          // The card already flips to free with a Claim button, so a "now free"
          // post would be noise. refreshThread also puts the post to sleep.
          refreshThread(guild, respawn, config)
          None
      }
    }
  }

  /** Someone pressed **Confirm** on a booking reminder, or **Take Claim** on the
   *  hunt it grew into. One method for both: it is one answer to one question, and
   *  which they pressed follows from where the claim had got to.
   *
   *  Confirming early puts the slot out of reach of anyone booking over it (see
   *  `RespawnClaim.requestable`); confirming a started hunt is what keeps it. No
   *  card rewrite either way — nothing a card shows has changed. */
  def confirmSlot(guild: Guild, userId: String, claimId: Long,
                  now: ZonedDateTime = ZonedDateTime.now()): ConfirmOutcome = {
    val guildId = guild.getId
    repository.findClaimById(guildId, claimId) match {
      case None => ConfirmOutcome.Gone
      case Some(claim) if claim.userId != userId => ConfirmOutcome.NotYours
      case Some(claim) =>
        repository.findById(guildId, claim.respawnId) match {
          case None => ConfirmOutcome.Gone
          case Some(respawn) =>
            repository.confirmClaim(guildId, claimId, now) match {
              // Either already confirmed (a second press) or no longer reserved or
              // active (the deadline went by while the DM sat open). Different
              // answers to the presser.
              case None =>
                if (claim.confirmed) ConfirmOutcome.Already(respawn) else ConfirmOutcome.Gone
              case Some(confirmed) if confirmed.isActive => ConfirmOutcome.Taken(respawn, confirmed)
              case Some(confirmed) =>
                // Confirming is the same yes to an outstanding "are you hunting
                // tonight?". Left alone the sweep would hand the slot to whoever
                // asked, and `dueReservations` would never start it at all.
                if (claim.requestPending) settleRequestOn(guild, respawn, claim)
                ConfirmOutcome.Settled(respawn, confirmed)
            }
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
              // Tank went elsewhere while the offer sat. Treated as a decline so
              // the spawn moves on rather than stalling.
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

  /** Rewrite a spawn's post to match the database — the one function keeping
   *  Discord and claim state in step, called after every mutation. Creates the
   *  post on first claim, revives it if idle, and sleeps it once nobody holds it.
   *
   *  Sleeping belongs here, not at the call sites that end a hunt: a lapsed offer,
   *  a missed slot and a cancelled diary all free a spawn too, and each comes
   *  through here. Tying it to `active` makes the post's state a function of the
   *  claim state, like the card and the tag. */
  def refreshThread(guild: Guild, respawn: Respawn, config: RespawnSettings): Option[ThreadChannel] = {
    // A forum this bot cannot see used to end the refresh in silence: the claim
    // landed, the reply said so, and nothing in Discord moved. It is the failure
    // that leaves a guild's posts permanently stale, so it is logged like the rest.
    val forum = RespawnThreads.findForum(guild, config)
    if (forum.isEmpty) {
      logger.warn(s"No respawn forum to update for '${respawn.code}' in guild '${guild.getId}': " +
        s"configured channel '${config.forumChannel}' is not one this bot can see. " +
        "Claims and bookings will keep working; their posts will not change.")
    }
    forum.flatMap { forum =>
      val guildId = guild.getId
      val active = repository.activeClaim(guildId, respawn.id)
      // Whoever holds an unanswered offer still shows at the head of the queue —
      // truthful, and what makes an offer going out need no card edit.
      val queue = repository.offeredClaim(guildId, respawn.id).toList ++ repository.queueFor(guildId, respawn.id)
      val now = ZonedDateTime.now()
      val reservations = repository.reservationsFor(guildId, respawn.id, now)
      // Bookings that exist only as a rule so far: a slot is written when its
      // start comes within the look-ahead, so anything booked further out would
      // otherwise leave the card reading "nothing booked" right after somebody
      // booked it. Only rules with no slot at all, or a repeating booking whose
      // next evening is already written would be listed twice.
      val written = reservations.flatMap(_.scheduleId).toSet
      val upcoming = repository.schedulesForRespawn(guildId, respawn.id).filterNot(s => written.contains(s.id))
      // A rule whose next evening was given away must name the one after it, or
      // the card lists tonight twice — as the new booking and as the old rule.
      val givenUp = daysGivenUp(guildId, now, respawnId = Some(respawn.id))
      val card = RespawnEmbeds.claimCard(respawn, active, queue, reservations, config,
        imageFor(respawn), upcoming, now, givenUp)
      val buttons = RespawnThreads.claimButtons(respawn.id, active.isDefined)

      // Re-read after a possible create so the row carries the new thread id;
      // the create callback writes it, but the local `respawn` is a snapshot.
      val opened = RespawnThreads.openThread(guild, forum, respawn, card, buttons,
        threadId => repository.setThreadId(guildId, respawn.id, threadId))

      opened.foreach { post =>
        // Card, tag and sleep in that order — see RespawnThreads.settle for why
        // they cannot be asked for side by side.
        //
        // Archived, not locked: people can still leave notes between hunts, and
        // reviving needs no moderator. A free spawn is claimed from the pinned
        // board, since Discord disables this post's Claim button while it sleeps.
        //
        // Keyed on the holder alone — bookings without a holder are still nobody's
        // hunt. `active` covers limbo, so a post stays awake across a handover.
        RespawnThreads.settle(forum, post.thread,
          if (post.created) None else Some(card -> buttons),
          RespawnThreads.tagFor(claimed = active.isDefined),
          sleep = active.isEmpty)
      }
      opened.map(_.thread)
    }
  }

  // --- putting posts back to sleep ------------------------------------------

  /** Archive posts that have gone quiet since somebody clicked them, and say how
   *  many. The other half of [[RespawnSleep]]: presses are recorded on JDA's event
   *  thread, and this acts on them from the sweep, where blocking is fine.
   *
   *  The holder is re-read here rather than trusted from the press: five minutes
   *  is long enough for somebody to have claimed it, and closing a held spawn's
   *  post takes the Leave button away from its holder. */
  def closeIdleThreads(guild: Guild, config: RespawnSettings,
                       now: java.time.Instant = java.time.Instant.now()): Int = {
    val guildId = guild.getId
    val ready = RespawnSleep.due(guildId, now)
    if (ready.isEmpty) 0
    else RespawnThreads.findForum(guild, config).fold(0) { _ =>
      // One catalogue read for the batch, keyed by thread so a due entry
      // belonging to no spawn is simply dropped.
      val byThread = repository.listRespawns(guildId)
        .filter(_.threadId.nonEmpty).map(respawn => respawn.threadId -> respawn).toMap
      ready.count { entry =>
        entry.threadId != config.boardThread &&
          byThread.get(entry.threadId).exists { respawn =>
            repository.activeClaim(guildId, respawn.id).isEmpty &&
              RespawnThreads.closeThread(guild, entry.threadId)
          }
      }
    }
  }

  /** Close any post that is awake with nobody on the spawn, and say how many.
   *
   *  The backstop under [[RespawnSleep]], which is in memory: a restart forgets
   *  every pending close, and it also catches posts stuck open from before any of
   *  this existed. `getThreadChannels` is cache-only and lists exactly the
   *  un-archived posts, so the scan is free and only the spawns it finds cost a
   *  query. Posts the debounce is about to handle are skipped.
   *
   *  `limit` caps archives per pass; the lookups ahead of it are lazy, so a forum
   *  of held spawns stops at no requests rather than spending the cap. */
  def reconcileThreads(guild: Guild, config: RespawnSettings, limit: Int = 10): Int = {
    val guildId = guild.getId
    RespawnThreads.findForum(guild, config).fold(0) { forum =>
      val open = forum.getThreadChannels.asScala.iterator
        .filterNot(_.isArchived)
        .filterNot(thread => thread.getId == config.boardThread)
        .filterNot(thread => RespawnSleep.isPending(thread.getId))
        .toList
      if (open.isEmpty) 0
      else {
        val byThread = repository.listRespawns(guildId)
          .filter(_.threadId.nonEmpty).map(respawn => respawn.threadId -> respawn).toMap
        open.iterator
          .flatMap(thread => byThread.get(thread.getId).map(thread -> _))
          .filter { case (_, respawn) => repository.activeClaim(guildId, respawn.id).isEmpty }
          .take(limit)
          .count { case (thread, _) => RespawnThreads.closeThread(thread) }
      }
    }
  }

  /** Booked slots on a spawn that haven't started yet. */
  def reservationsFor(guildId: String, respawnId: Long,
                      now: ZonedDateTime = ZonedDateTime.now()): List[RespawnClaim] =
    repository.reservationsFor(guildId, respawnId, now)

  /** Every row a guild's calendars are drawn from, over one window, in five reads
   *  rather than six per spawn per week. The same rows the per-spawn calls return,
   *  asked guild-wide and grouped here. Nothing is decided at this level —
   *  `JdaRespawnActions.assembleCalendar` still does that. */
  def calendarRows(guildId: String, from: ZonedDateTime,
                   to: ZonedDateTime): com.tibiabot.web.CalendarRows =
    com.tibiabot.web.CalendarRows(
      respawns = repository.listRespawns(guildId),
      active = repository.allActiveClaims(guildId).map(claim => claim.respawnId -> claim).toMap,
      // Anchored at the window's own start rather than at now, so a grid showing
      // earlier in the week still draws what was booked then.
      reservations = repository.allReservations(guildId, from).groupBy(_.respawnId),
      schedules = repository.allSchedules(guildId).groupBy(_.respawnId),
      givenUp = daysGivenUp(guildId, from, Some(to)),
      from = from, to = to)

  /** What has already finished on one spawn between two instants — see
   *  [[com.tibiabot.persistence.RespawnRepository.claimsBetween]]. */
  def historyFor(guildId: String, respawnId: Long,
                 from: ZonedDateTime, to: ZonedDateTime): List[RespawnClaim] =
    repository.claimsBetween(guildId, respawnId, from, to)

  /** Days each rule has given up, keyed by schedule so "when is this one next"
   *  costs a lookup rather than a scan. Both bounds narrow the same question: one
   *  spawn's week for the calendar, every spawn from now on for a booking list. */
  def daysGivenUp(guildId: String, from: ZonedDateTime,
                  to: Option[ZonedDateTime] = None,
                  respawnId: Option[Long] = None): Map[Long, Set[java.time.Instant]] =
    repository.settledOccurrences(guildId, from, to, respawnId)
      .groupBy(_.scheduleId)
      .map { case (id, days) => id -> days.map(_.startsAt.toInstant).toSet }

  // --- putting the calendar right ------------------------------------------

  /** Take one day off the calendar, leaving the rule behind it alone — a
   *  repeating booking keeps repeating.
   *
   *  A materialised day is cancelled; one that is not is written down as already
   *  cancelled, which is the only way to record "not this day" about a rule.
   *  Either way the day ends up settled. */
  def dropSlot(guild: Guild, respawn: Respawn, startsAt: ZonedDateTime,
               now: ZonedDateTime = ZonedDateTime.now()): Either[String, String] = {
    val guildId = guild.getId
    settings(guildId) match {
      case None => Left("The respawn claim system isn't set up on this server yet.")
      case Some(config) =>
        val outcome = repository.withRespawnLock(guildId, respawn.id) {
          repository.slotAt(guildId, respawn.id, startsAt) match {
            // Ending a running hunt has a queue to advance behind it; Remove Claim
            // on the board is what does that.
            case Some(slot) if slot.isActive =>
              Left("That one is being hunted now. Use Remove Claim on the board to end a running hunt.")
            case Some(slot) =>
              repository.cancelClaim(guildId, slot.id, RespawnClaim.Outcome.SlotRemoved)
              Right(Names.plain(slot.nickname, slot.userName))
            case None =>
              predictedOwnerOf(guildId, respawn.id, startsAt, now) match {
                case None => Left("Nothing is booked at that time.")
                case Some(schedule) =>
                  repository.skipOccurrence(guildId, schedule.id, respawn.id, schedule.userId,
                    schedule.userName, schedule.nickname, schedule.characterName, startsAt,
                    schedule.durationMinutes, RespawnClaim.Outcome.SlotRemoved)
                  Right(Names.plain(schedule.nickname, schedule.userName))
              }
          }
        }
        outcome.foreach(_ => refreshThread(guild, respawn, config))
        outcome
    }
  }

  /** Put one day in somebody else's name: the old owner's day is settled and a
   *  fresh booking written beside it, rather than the occurrence being rewritten.
   *  The same shape a slot takes when given up to whoever asked, and for the same
   *  reason — it is nobody's standing arrangement any more, and the trail keeps
   *  both halves.
   *
   *  Refused for a day already running; moving a live hunt is [[reassignClaim]],
   *  which is what the board's Hand To does. */
  def reassignSlot(guild: Guild, respawn: Respawn, startsAt: ZonedDateTime,
                   toUserId: String, toUserName: String, toNickname: String,
                   now: ZonedDateTime = ZonedDateTime.now()): Either[String, String] = {
    val guildId = guild.getId
    settings(guildId) match {
      case None => Left("The respawn claim system isn't set up on this server yet.")
      case Some(config) =>
        val outcome = repository.withRespawnLock(guildId, respawn.id) {
          repository.slotAt(guildId, respawn.id, startsAt) match {
            case Some(slot) if slot.isActive =>
              Left("That one is being hunted now. Use Hand To on the board to move a running claim.")
            case Some(slot) if slot.userId == toUserId =>
              Left(s"That slot is already ${Names.plain(slot.nickname, slot.userName)}'s.")
            case Some(slot) =>
              // An occurrence of a rule is settled and replaced, so the rule
              // stops speaking for the day; a booking that belongs to nobody's
              // rule is simply renamed, since there is nothing behind it to
              // leave a record against.
              if (slot.scheduleId.isDefined) {
                repository.cancelClaim(guildId, slot.id, RespawnClaim.Outcome.SlotMoved)
                repository.reserveFor(guildId, respawn.id, toUserId, toUserName, toNickname,
                  startsAt, slot.durationMinutes)
                Right(Names.plain(slot.nickname, slot.userName))
              } else
                repository.reassignReservation(guildId, slot.id, toUserId, toUserName, toNickname)
                  .map(_ => Names.user(slot.nickname, slot.userName))
                  .toRight("That booking has already gone.")
            case None =>
              predictedOwnerOf(guildId, respawn.id, startsAt, now) match {
                case None => Left("Nothing is booked at that time.")
                case Some(schedule) if schedule.userId == toUserId =>
                  Left(s"That slot is already ${Names.plain(schedule.nickname, schedule.userName)}'s.")
                case Some(schedule) =>
                  repository.skipOccurrence(guildId, schedule.id, respawn.id, schedule.userId,
                    schedule.userName, schedule.nickname, schedule.characterName, startsAt,
                    schedule.durationMinutes, RespawnClaim.Outcome.SlotMoved)
                  repository.reserveFor(guildId, respawn.id, toUserId, toUserName, toNickname,
                    startsAt, schedule.durationMinutes)
                  Right(Names.plain(schedule.nickname, schedule.userName))
              }
          }
        }
        outcome.foreach(_ => refreshThread(guild, respawn, config))
        outcome
    }
  }

  /** Change how long one window on the calendar runs, for a moderator. Aimed at
   *  whatever is selected on the grid, named by the instant it starts on, whether
   *  or not it has a row yet. Three cases:
   *
   *  - '''A live hunt''': its deadline moves, charging nobody, for the same reason
   *    [[extendHolder]] charges nobody. It cannot go below what has already
   *    elapsed; setting it to exactly that ends the hunt at the next sweep.
   *  - '''An evening already booked''': the row is rewritten in place. A repeating
   *    rule keeps its own length, so one evening runs longer and next week does
   *    not — the same narrowness [[dropSlot]] has.
   *  - '''An evening not yet written down''': the day is settled and a booking in
   *    the same name is written beside it, as [[reassignSlot]] does. There is
   *    nowhere else to record "this day, but longer" about a rule.
   *
   *  The guild's maximum claim length does not apply — it governs what members may
   *  ask for, and would refuse exactly the repairs worth making —  but
   *  [[MaxModeratorSlotMinutes]] does. A future window is refused if it runs into
   *  the next thing on the spawn; a live hunt is not, since it overruns what
   *  follows exactly as a member's own extend does, and the answer names whose
   *  evening it reaches into. */
  def editSlot(guild: Guild, respawn: Respawn, startsAt: ZonedDateTime, minutes: Int,
               now: ZonedDateTime = ZonedDateTime.now()): Either[String, SlotEdit] = {
    val guildId = guild.getId
    if (minutes < MinimumClaimMinutes)
      Left(s"A window has to be at least ${RespawnEmbeds.humanDuration(MinimumClaimMinutes)} long.")
    else if (minutes > MaxModeratorSlotMinutes)
      Left(s"${RespawnEmbeds.humanDuration(minutes)} is longer than the " +
        s"${RespawnEmbeds.humanDuration(MaxModeratorSlotMinutes)} a single window can run.")
    else settings(guildId) match {
      case None => Left("The respawn claim system isn't set up on this server yet.")
      case Some(config) =>
        // Read and write on one picture of the spawn, like every other decision
        // about a slot. What follows the window is part of that picture: without
        // the lock, a booking made in between would be refused against on one
        // path and quietly run into on the other.
        val outcome = repository.withRespawnLock(guildId, respawn.id) {
          repository.slotAt(guildId, respawn.id, startsAt) match {
            case Some(slot) if slot.isActive => resizeRunning(guildId, respawn, slot, minutes, now)
            case Some(slot)                  => resizeReserved(guildId, respawn, slot, startsAt, minutes, now)
            case None                        => resizePredicted(guildId, respawn, startsAt, minutes, now)
          }
        }
        // Both outside the lock: the card is a Discord round trip, and holding
        // a spawn's row across one would stall every other claim on it for as
        // long as Discord felt like taking.
        outcome.foreach { edit =>
          refreshThread(guild, respawn, config)
          if (edit.live) tellHolderOfResize(guild, respawn, edit)
        }
        outcome
    }
  }

  /** The running-hunt case of [[editSlot]]. */
  private def resizeRunning(guildId: String, respawn: Respawn, slot: RespawnClaim,
                            minutes: Int, now: ZonedDateTime): Either[String, SlotEdit] =
    // A claim in limbo has already been offered on to the next person. Moving
    // its deadline would extend a hunt on its way out of somebody's hands, and
    // the offer would still be standing.
    if (slot.limboUntil.isDefined)
      Left("That hunt is already being handed to the next person.")
    else {
      val start = slot.startsAt.getOrElse(slot.claimedAt)
      val elapsed = math.max(0L, java.time.Duration.between(start, now).toMinutes).toInt
      if (minutes < elapsed)
        Left(s"That hunt has already run ${RespawnEmbeds.humanDuration(elapsed)}. Use Remove claim to " +
          "end it now — those minutes are spent either way.")
      else {
        val newEnd = start.plusMinutes(minutes.toLong)
        val delta = minutes - slot.durationMinutes
        // Only ever downwards. Growing one is the moderator's gift and costs the
        // holder nothing; shrinking it hands back minutes they will never use.
        if (delta < 0) repository.refundStamina(guildId, slot.userId, -delta, resetBoundary(now))
        repository.setClaimDuration(guildId, slot.id, minutes, Some(newEnd))
        Right(SlotEdit(Names.plain(slot.nickname, slot.userName), slot.userId, minutes, newEnd,
          live = true, cutInto = nextUpOn(guildId, respawn.id, start, newEnd, now)))
      }
    }

  /** The booked-evening case of [[editSlot]]. */
  private def resizeReserved(guildId: String, respawn: Respawn, slot: RespawnClaim,
                             startsAt: ZonedDateTime, minutes: Int,
                             now: ZonedDateTime): Either[String, SlotEdit] = {
    val newEnd = startsAt.plusMinutes(minutes.toLong)
    nextUpOn(guildId, respawn.id, startsAt, newEnd, now) match {
      case Some(who) => Left(clashRefusal(who))
      case None =>
        // Nothing to settle with stamina: a booking reserves none until it
        // starts, so its length is a stored number until then.
        repository.setClaimDuration(guildId, slot.id, minutes, Some(newEnd))
        Right(SlotEdit(Names.plain(slot.nickname, slot.userName), slot.userId, minutes, newEnd,
          live = false, cutInto = None))
    }
  }

  /** The not-yet-written-down case of [[editSlot]]. */
  private def resizePredicted(guildId: String, respawn: Respawn, startsAt: ZonedDateTime,
                              minutes: Int, now: ZonedDateTime): Either[String, SlotEdit] =
    predictedOwnerOf(guildId, respawn.id, startsAt, now) match {
      case None => Left("Nothing is booked at that time.")
      case Some(schedule) =>
        val newEnd = startsAt.plusMinutes(minutes.toLong)
        nextUpOn(guildId, respawn.id, startsAt, newEnd, now) match {
          case Some(who) => Left(clashRefusal(who))
          case None =>
            repository.skipOccurrence(guildId, schedule.id, respawn.id, schedule.userId,
              schedule.userName, schedule.nickname, schedule.characterName, startsAt,
              schedule.durationMinutes, RespawnClaim.Outcome.SlotResized)
            repository.reserveFor(guildId, respawn.id, schedule.userId, schedule.userName,
              schedule.nickname, startsAt, minutes)
            Right(SlotEdit(Names.plain(schedule.nickname, schedule.userName), schedule.userId,
              minutes, newEnd, live = false, cutInto = None))
        }
    }

  private def clashRefusal(who: String): String =
    s"That would run into $who's slot on this respawn. Move or remove theirs first."

  /** Whoever is on this spawn next, if a window ending at `until` reaches them.
   *  Both written-down bookings and days a rule has not got to yet, for the reason
   *  `clashingReservations` gives. Strictly after `after`, so the window being
   *  edited never finds itself. */
  private def nextUpOn(guildId: String, respawnId: Long, after: ZonedDateTime,
                       until: ZonedDateTime, now: ZonedDateTime): Option[String] = {
    val booked = repository.reservationsFor(guildId, respawnId, after)
      .filter(_.startsAt.exists(_.isBefore(until)))
      .map(slot => Names.plain(slot.nickname, slot.userName))
    if (booked.nonEmpty) booked.headOption
    else {
      // A settled day is in nobody's way, so an evening just taken off the
      // calendar cannot block an edit to the one before it.
      val settled = daysGivenUp(guildId, after, Some(until), Some(respawnId))
      repository.schedulesForRespawn(guildId, respawnId).iterator.flatMap { schedule =>
        schedule.occurrencesBetween(after.plusMinutes(1), until)
          .filterNot(start => settled.getOrElse(schedule.id, Set.empty).contains(start.toInstant))
          .map(_ => Names.plain(schedule.nickname, schedule.userName))
      }.toList.headOption
    }
  }

  /** Tell somebody their hunt just got longer or shorter under them. Worth a
   *  message for the same reason [[forceLeave]] is: an unexplained deadline move
   *  is indistinguishable from a bug. Only a live hunt earns one. */
  private def tellHolderOfResize(guild: Guild, respawn: Respawn, edit: SlotEdit): Unit =
    RespawnThreads.dm(guild, edit.ownerId,
      RespawnEmbeds.dmEmbed("Your hunt was re-timed",
        s"A moderator has set your claim on ${RespawnEmbeds.spawnLink(respawn)} to " +
          s"**${RespawnEmbeds.humanDuration(edit.minutes)}**, so it now runs until " +
          s"<t:${edit.endsAt.toEpochSecond}:t>. It hasn't cost you any stamina.",
        imageFor(respawn), RespawnEmbeds.FreeColor))

  /** The rule that would put somebody on this spawn at this exact time, for a
   *  day the sweep has not written down yet. None when no rule names it — which
   *  is what tells a moderator they are pointing at nothing. */
  private def predictedOwnerOf(guildId: String, respawnId: Long, startsAt: ZonedDateTime,
                               now: ZonedDateTime): Option[RespawnSchedule] =
    repository.schedulesForRespawn(guildId, respawnId)
      .filter(_.startsAt(startsAt))
      .find(_ => !startsAt.isBefore(now))

  /** One spawn's current state, for its card and for the dashboard. */
  def status(guildId: String, respawn: Respawn): (Option[RespawnClaim], List[RespawnClaim]) =
    (repository.activeClaim(guildId, respawn.id), repository.queueFor(guildId, respawn.id))

  /** The whole catalogue with its live state, for the dashboard's board. Five
   *  queries whatever the catalogue's size, rather than three per spawn — the
   *  whole performance story on a polling page with a few hundred spawns.
   *  `lastActivity` is absent for a never-claimed spawn, which renders as the
   *  most faded state rather than an error. */
  def board(guildId: String, now: ZonedDateTime = ZonedDateTime.now()): List[RespawnBoardEntry] =
    RespawnService.assembleBoard(
      repository.listRespawns(guildId),
      repository.allActiveClaims(guildId),
      repository.allQueuedClaims(guildId),
      repository.allReservations(guildId, now),
      repository.lastActivityByRespawn(guildId).toMap
    )

  /** Stop tracking respawns for this guild — claims, catalogue and settings all
   *  go. Called when the last world is removed; the forum is retired as read-only
   *  history rather than deleted (see ChannelService.retireSpawnsForum). Its
   *  threads are deliberately orphaned, so a later `/setup` builds a fresh forum
   *  instead of reviving posts in what is now an archive. */
  def teardown(guildId: String): Unit = repository.dropGuildData(guildId)

  def userPrefs(guildId: String, userId: String): RespawnUserPrefs =
    repository.userPrefs(guildId, userId)

  /** Save a member's own defaults, clamped to what the guild allows. Validated
   *  here, not in the modal handler, so the bounds hold wherever it is set from:
   *  an over-long duration would be refused at claim time anyway, and a lead past
   *  `RespawnUserPrefs.MaxWarnMinutes` would fire the instant a claim started. */
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

object RespawnService {

  /** [[RespawnService.resolve]] against rows already in hand — code first, then
   *  the ladder below — for a caller holding the guild's catalogue already. The
   *  dashboard's calendar is that caller. */
  def resolveAmong(all: List[Respawn], query: String): Option[Respawn] = {
    val trimmed = query.trim
    if (trimmed.isEmpty) None
    else all.find(_.code.equalsIgnoreCase(trimmed)).orElse(resolveIn(all, trimmed))
  }

  /** Match a typed query against a catalogue, when it wasn't a code: a ladder from
   *  exact to loose, stopping at the first rung that lands on exactly one row. A
   *  rung matching several answers `None` rather than guessing.
   *
   *  The last rung takes every word of the query in any order, so "fire library"
   *  finds "Secret Library (Fire)", which no substring does. Multi-word queries
   *  only — on one word it duplicates the substring rungs above. Pure, so it is
   *  testable against a handful of rows. */
  private[respawn] def resolveIn(all: List[Respawn], query: String): Option[Respawn] = {
    val trimmed = query.trim
    if (trimmed.isEmpty) return None
    val lower = trimmed.toLowerCase

    val words = lower.split("\\s+").filter(_.nonEmpty).toList
    def allWordsIn(respawn: Respawn): Boolean = {
      val haystack = s"${respawn.name} ${respawn.creature}".toLowerCase
      words.forall(haystack.contains)
    }

    // Some clients send autocomplete back as the display name ("415 — Cult Orcs"),
    // so match that shape first. `creature.nonEmpty` guards the substring rung:
    // "" is a substring of everything, and most rows have no creature set.
    val rungs = List(
      all.filter(_.displayName.equalsIgnoreCase(trimmed)),
      all.filter(_.name.equalsIgnoreCase(trimmed)),
      all.filter(_.name.toLowerCase.contains(lower)),
      all.filter(_.creature.equalsIgnoreCase(trimmed)),
      all.filter(r => r.creature.nonEmpty && r.creature.toLowerCase.contains(lower)),
      if (words.sizeIs < 2) Nil else all.filter(allWordsIn)
    )

    // Nothing on a rung tries the next; several stops and stays unresolved.
    // "cult" naming two spawns is a real ambiguity, and resolving it from a
    // looser field below would be the guess this refuses to make.
    rungs.iterator
      .map {
        case Nil           => None
        case single :: Nil => Some(Some(single))
        case _             => Some(None)
      }
      .collectFirst { case Some(answer) => answer }
      .flatten
  }

  /** How many people the stamina picker is willing to offer. Generous for a
   *  guild and small enough that the payload stays a list rather than a dump. */
  val MaxKnownMembers: Int = 500

  /** Limits on a hand-added spawn. Well under what the columns hold — these are
   *  about readability: every name is drawn in full on the board image, so one
   *  long entry widens the picture for everybody. */
  val MaxCodeLength: Int = 16
  val MaxSpawnNameLength: Int = 60
  val MaxRegionLength: Int = 40

  /** What is wrong with a spawn somebody typed, or None. Everything decidable
   *  from the fields alone, so the rules are testable without a database;
   *  whether the code is taken is the caller's question. Expects trimmed fields,
   *  and is worded for whoever typed them. */
  def spawnFault(code: String, region: String, name: String, creature: String): Option[String] =
    if (code.isEmpty) Some("A spawn needs a code — it is what people type to claim it.")
    // Letters, digits and hyphens — a code travels in a URL and a thread title.
    else if (!code.forall(c => c.isLetterOrDigit || c == '-'))
      Some(s"'$code' has characters I can't use in a code — letters, numbers and hyphens only.")
    else if (code.length > MaxCodeLength)
      Some(s"That code is longer than $MaxCodeLength characters.")
    else if (name.isEmpty) Some("A spawn needs a name, so the code means something on the board.")
    else if (name.length > MaxSpawnNameLength)
      Some(s"That name is longer than $MaxSpawnNameLength characters. The board post draws every " +
        "name in full, so a long one pushes the whole image wider.")
    else if (region.length > MaxRegionLength)
      Some(s"That city is longer than $MaxRegionLength characters.")
    // Checked here rather than failing quietly, which would leave somebody
    // wondering why their spawn is the only one without a picture.
    else if (creature.nonEmpty && com.tibiabot.web.CreatureSprites.safeFileName(creature).isEmpty)
      Some(s"I can't fetch a picture for '$creature'. Use the creature's wiki name, " +
        "or leave it empty and the spawn goes without one.")
    else None

  /** When an owner has to answer by: a little way into the slot itself, whenever
   *  they were asked. The clock runs to the hunt rather than from the asking,
   *  since "are you hunting tonight" is a question only the evening can answer.
   *
   *  Past the start rather than on it, so somebody logging in on time does not
   *  arrive to find the slot already gone. Always in the future when set: a slot
   *  stops being requestable once it starts. */
  def answerDeadline(slotStart: ZonedDateTime, graceMinutes: Int): ZonedDateTime =
    slotStart.plusMinutes(graceMinutes.toLong)

  /** How long a claim actually runs, given what a booking leaves and what is in
   *  the tank. Both limits shorten rather than refuse — refusing left the spawn
   *  empty and the member hunting nothing. Pure, so which limit binds is testable;
   *  the floor and what the shortfall is blamed on stay with the caller. */
  def grantedMinutes(allowedByBooking: Int, tank: Stamina): Int =
    if (tank.unlimited) allowedByBooking else math.min(allowedByBooking, tank.remainingMinutes)

  /** Stitch a board together from the bulk reads that feed it. Pure, so the parts
   *  that can be wrong — which claim wins if a spawn has two active, queue order,
   *  a never-claimed spawn — are testable without a database. */
  private[respawn] def assembleBoard(
    respawns: List[Respawn],
    active: List[RespawnClaim],
    queued: List[RespawnClaim],
    reserved: List[RespawnClaim],
    lastActivity: Map[Long, ZonedDateTime]
  ): List[RespawnBoardEntry] = {
    val activeByRespawn = active.groupBy(_.respawnId)
    val queuedByRespawn = queued.groupBy(_.respawnId)
    val reservedByRespawn = reserved.groupBy(_.respawnId)

    respawns.map { respawn =>
      RespawnBoardEntry(
        respawn = respawn,
        // A spawn should only ever have one active claim; if the data says
        // otherwise the earliest-ending one is the honest answer, since that is
        // the one whose end is about to change the spawn's state.
        active = activeByRespawn.getOrElse(respawn.id, Nil)
          .sortBy(_.endsAt.map(_.toInstant.toEpochMilli).getOrElse(Long.MaxValue)).headOption,
        queue = queuedByRespawn.getOrElse(respawn.id, Nil).sortBy(_.queuePosition),
        reservations = reservedByRespawn.getOrElse(respawn.id, Nil)
          .sortBy(_.startsAt.map(_.toInstant.toEpochMilli).getOrElse(Long.MaxValue)),
        lastActivity = lastActivity.get(respawn.id)
      )
    }
  }
}
