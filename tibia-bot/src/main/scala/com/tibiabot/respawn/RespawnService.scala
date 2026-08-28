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

/** What a claim attempt did, for the command/button layer to render. Modelled
 *  as a result type rather than the handler poking at the repository itself, so
 *  the rules live in one place and the Claim button, the board's claim form and
 *  the dashboard can't drift apart. */
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
  /** The respawn system has never been set up in this guild. */
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

/** What changing one window's length did, in the words the surfaces need.
 *
 *  `live` separates the two things this can be: a hunt somebody is on now,
 *  whose deadline just moved under them, and an evening still to come. Only the
 *  first is worth telling anybody about immediately, which is why the caller
 *  needs to know which it was rather than reading it back off the row.
 *
 *  `cutInto` names whoever the new window now runs into. Present only where the
 *  window was allowed to run into them at all — a live hunt, which overruns
 *  what follows exactly as a member's own extend always has — so it is a
 *  warning attached to a success, never a refusal wearing one.
 */
final case class SlotEdit(
  owner: String,
  ownerId: String,
  minutes: Int,
  endsAt: ZonedDateTime,
  live: Boolean,
  cutInto: Option[String]
)

/** One page of the moderator claim log, and whether there is more behind it.
 *
 *  `hasOlder` is answered by fetching one row past the page rather than by
 *  counting the whole trail, so an Older button is only ever offered when
 *  pressing it actually shows something. */
final case class LogPage(entries: List[RespawnClaim], page: Int, hasOlder: Boolean) {
  def isEmpty: Boolean = entries.isEmpty
  def hasNewer: Boolean = page > 0
}

/** What a page of the claim log is about.
 *
 *  The board's Log opens on [[LogScope.Everything]], a spawn's own post on that
 *  spawn, and Find produces either a spawn or a member. It travels in the button
 *  id rather than being remembered anywhere, because a Next press has to know
 *  what it is paging through and the message it edits cannot say. */
sealed trait LogScope {
  /** The id-safe form. A colon separates the parts of a button id, so no token
   *  may contain one — and since a spawn id and a Discord snowflake are both
   *  bare digits, the member form carries a `u` to tell them apart. */
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
      // Digits only: the token is echoed straight back into a database lookup,
      // and anything else in there did not come from a button this bot drew.
      if (id.nonEmpty && id.forall(_.isDigit)) Some(Member(id)) else None
    }
    else scala.util.Try(token.toLong).toOption.map(Spawn(_))
}

/** One spawn as the web board shows it: the catalogue row, whoever holds it,
 *  who is waiting, what is booked ahead, and when it was last touched.
 *
 *  Assembled in bulk by [[RespawnService.board]]. `lastActivity` is what the
 *  board's fade is computed from — a spawn nobody has ever claimed has none,
 *  and reads as fully dormant rather than as missing data.
 */
final case class RespawnBoardEntry(
  respawn: Respawn,
  active: Option[RespawnClaim],
  queue: List[RespawnClaim],
  reservations: List[RespawnClaim],
  lastActivity: Option[ZonedDateTime]
) {
  /** The soonest booking the card has anything to say about.
   *
   *  A slot with an unanswered request is skipped. The question is between two
   *  people about a future evening, and the card is about now — its owner may
   *  yet say they aren't hunting it, at which point it belongs to somebody
   *  else. Neither answer changes anything about the spawn this minute, so the
   *  card looks past it to the next booking that is actually settled, and says
   *  "free" if there is none.
   *
   *  The request is still there, and the calendar still draws it in full: that
   *  is the surface where a future evening is the subject.
   */
  def nextReservation: Option[RespawnClaim] =
    reservations.find(_.requesterUserId.isEmpty)

  /** When each queued claim would start, if every hunt ahead of it ran its full
   *  length: the current hunt's end, then each person in turn.
   *
   *  A projection and nothing more, which is why it is offered as one rather
   *  than written to a claim's `startsAt` — that field means "this really began
   *  then". Somebody releasing early brings every one of these forward, an offer
   *  that goes unanswered expires and does the same, and a hunt run to the wire
   *  still hands over a minute or two late. Surfaces that show it are expected to
   *  mark it as approximate.
   *
   *  Computed here rather than in the page so that everything showing a queue is
   *  showing the same arithmetic. A spawn with a queue and no live hunt — which
   *  should not happen, but does if a claim ends between the two reads — starts
   *  counting from `now`, since the next person's turn is not in the past.
   */
  def projectedQueueStarts(now: ZonedDateTime): List[(RespawnClaim, ZonedDateTime)] = {
    val first = active.flatMap(_.endsAt).filter(_.isAfter(now)).getOrElse(now)
    queue.foldLeft((first, List.empty[(RespawnClaim, ZonedDateTime)])) {
      case ((cursor, acc), claim) =>
        (cursor.plusMinutes(claim.durationMinutes.toLong), acc :+ (claim -> cursor))
    }._2
  }

  /** How this spawn reads on the board, as one of
   *  [[RespawnBoardEntry.States]] other than
   *  [[RespawnBoardEntry.Asked]], which is a calendar state only.
   *
   *  Being hunted right now outranks anything booked for later: a card has one
   *  state, and "somebody is in there" is the one that matters.
   *
   *  The two booked states come out of how a slot records having been asked
   *  about (see `keepOccurrence`): `askedAt` is set the moment somebody asks
   *  and never cleared, while the requester fields are cleared once it is
   *  answered. So a booking nobody has queried and one that has been queried
   *  and settled are both bookings — the second simply has its question closed.
   */
  def state: String =
    if (active.isDefined) RespawnBoardEntry.Claimed
    else nextReservation match {
      case None => RespawnBoardEntry.Free
      // Two ways a booking reads as settled, and the card does not distinguish
      // them: its owner said so outright, or somebody asked and the question has
      // been answered. Before Confirm existed only the second was possible, and
      // reading the word off `askedAt` alone would now leave a booking whose
      // owner has explicitly confirmed it showing as merely booked.
      case Some(slot) if slot.confirmed || slot.askedAt.isDefined => RespawnBoardEntry.Confirmed
      case Some(_) => RespawnBoardEntry.Booked
    }

  /** Whoever the card should name: the holder if it is being hunted, otherwise
   *  whoever booked it next. The one Discord name everybody calls them by —
   *  the guild's name for them, or their account name where the guild has none.
   *
   *  Their Tibia character led this once, on the reasoning that a character is
   *  who the team recognises. A card names one person for one purpose, though,
   *  and that purpose is going and asking them about the spawn — which is done
   *  in Discord, under the name they answer to there. The character is still on
   *  the calendar block and in the thread, where there is room for both names.
   *
   *  Falls back to the character only when there is no Discord name at all,
   *  which no live row has: better a name of some kind than a blank card.
   */
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
  /** A slot whose owner has been asked whether they are actually hunting it.
   *  A calendar state only: it describes a question about a future evening, and
   *  a board card describes now. */
  val Asked = "asked"

  /** What a board card can read as. Deliberately without [[Asked]]. */
  val States: Set[String] = Set(Free, Claimed, Booked, Confirmed)
}

/** The result of a slot owner answering "are you hunting tonight?". */
sealed trait SlotAnswer
object SlotAnswer {
  /** They are hunting it, so the request is refused and the slot stays theirs. */
  final case class Kept(respawn: Respawn) extends SlotAnswer
  /** They are not, so it passes to whoever asked. Carries the asker's names
   *  rather than their id: the only thing done with them is naming them in the
   *  reply, and the row already knows what they are called. Both, so the person
   *  who just gave a slot up is told who has it in the words their server uses. */
  final case class Passed(respawn: Respawn, toUserName: String, toNickname: String) extends SlotAnswer
  /** They are not — but the asker had booked a longer window than the slot they
   *  asked about, and the rest of it is somebody else's now. The slot is given up
   *  all the same; it simply goes back to being free rather than to them. */
  final case class PassedUnclaimed(respawn: Respawn) extends SlotAnswer
  /** Already answered, lapsed, or the slot is gone. */
  case object Gone extends SlotAnswer
  case object NotYours extends SlotAnswer
}

/** The result of a booking's owner confirming they are there — Confirm on the
 *  reminder, or Take Claim once the slot has started. */
sealed trait ConfirmOutcome
object ConfirmOutcome {
  /** Confirmed ahead of the start: the slot is settled and nobody may ask for
   *  it now. */
  final case class Settled(respawn: Respawn, slot: RespawnClaim) extends ConfirmOutcome
  /** Confirmed a hunt already under way, so it is no longer at risk of being
   *  given up. */
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

  /** The guild's settings, or None when the respawn system was never set up here. */
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
   *  Validated here rather than in the board's moderator panel — its only caller
   *  today — so a second one can't drift on what counts as a legal combination,
   *  a default claim longer than the maximum being the one that actually bites,
   *  since every later claim would be refused for exceeding a ceiling nobody set
   *  deliberately. */
  /** `warnMinutes` is deliberately absent: it is the fallback reminder for
   *  members who have not set their own, and there is no longer anywhere to
   *  change it per guild — it sits at `Config.Respawn.warnMinutes` for everybody.
   *  Members set their own in Config, which is the setting that was ever
   *  actually used. */
  /** Give one spawn its own ceiling on claim length, or take it away.
   *
   *  `None` clears the override and puts the spawn back on the guild's number.
   *  The value replaces that number rather than capping it, so a spawn worth a
   *  long session can be set above the server's ceiling as well as below — see
   *  [[com.tibiabot.domain.RespawnSettings.maxFor]].
   *
   *  Nothing already running is touched. A claim under way keeps the end time it
   *  was granted and a repeating booking keeps firing at its stored length; the
   *  new ceiling binds the next claim, the next extension and the next booking.
   *  Shortening somebody mid-hunt because a moderator retuned a spawn would take
   *  time off a hunt that was legitimate when it started, and the stamina for it
   *  is already reserved.
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

  /** Everybody this guild's respawn system knows, for a moderator to pick from.
   *
   *  Capped because it feeds a picker rather than a report: a guild that has run
   *  this for years has thousands of rows behind it and nobody scrolls a list
   *  that long — they type a name. Anyone beyond the cap is still reachable by
   *  their Discord id, which the same field accepts. */
  def knownMembers(guildId: String): List[com.tibiabot.persistence.KnownMember] =
    repository.knownMembers(guildId, RespawnService.MaxKnownMembers)

  /** Resolve what a user typed — a code ("415"), a name, or the creature the
   *  place is known for — to a catalogue entry. Autocomplete sends the code back,
   *  so the code path is the common one; the rest exist for people typing by
   *  hand.
   *
   *  The creature is tried last and only as a whole word or a substring, after
   *  every name test has missed. It is the loosest of the three — several spawns
   *  can share a monster — so it settles a query only when exactly one row
   *  matches, the same rule the name substring already answered to. */
  def resolve(guildId: String, query: String): Option[Respawn] = {
    val trimmed = query.trim
    if (trimmed.isEmpty) None
    else repository.findByCode(guildId, trimmed)
      .orElse(RespawnService.resolveIn(repository.listRespawns(guildId), trimmed))
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
   *  edit to respawns.json reaches a guild that was set up before it.
   *
   *  Only half the job on its own: hand the rows it returns to
   *  [[deleteRetiredThreads]], or every dropped code stays in Discord as a card
   *  offering Claim, Book and Config on a spawn nothing can resolve. */
  def syncSeed(guildId: String): SeedSync =
    repository.syncSeed(guildId, RespawnCatalogue.seed.map(s => (s.code, s.region, s.name, s.creature)))

  /** Take down the forum posts of codes [[syncSeed]] has just retired, and say
   *  how many went.
   *
   *  Its other half, split off only because the repository has no Discord to
   *  delete with. To a member the row and the post are one thing: a code that
   *  has been retired but whose card is still in the forum is a spawn they can
   *  still find, still open and still press Claim on, and all they get for it is
   *  a refusal. This is the same call `/repair`'s Remove button already makes
   *  when a moderator deletes a spawn by hand. */
  def deleteRetiredThreads(guild: Guild, config: RespawnSettings, retired: List[Respawn]): Int =
    RespawnThreads.deleteThreads(guild, config, retired.map(_.threadId))

  /** Delete respawn-forum posts that no catalogue row points at, and say how
   *  many went.
   *
   *  What [[deleteRetiredThreads]] cannot reach. A post is found from the
   *  `threadId` on its spawn's row, so the moment a row goes without its post
   *  going too there is nothing left that knows the post exists — which is the
   *  state every code retired before this existed is in. Those are found from
   *  the other side: by being a post of ours that nothing claims.
   *
   *  Cheap to be wrong about in one direction and expensive in the other, so an
   *  empty catalogue is treated as a failed read rather than as a guild with no
   *  spawns. The two are indistinguishable from here, and acting on the first
   *  would delete the whole forum. [[RespawnThreads.deleteUnknownThreads]]
   *  carries the rest of the guards. */
  def deleteOrphanedThreads(guild: Guild, config: RespawnSettings,
                            limit: Int = OrphanSweepLimit): Int = {
    val known = repository.listRespawns(guild.getId)
    if (known.isEmpty) 0
    else RespawnThreads.deleteUnknownThreads(guild, config,
      known.map(_.threadId).filter(_.nonEmpty).toSet, limit)
  }

  /** How many orphaned posts one sweep will take. Generous next to the handful
   *  a seed edit can strand, and small enough that a sweep gone wrong is a log
   *  line somebody reads rather than an empty forum. */
  private val OrphanSweepLimit = 25

  /** Put the pinned board post back in step with the catalogue, if it isn't.
   *
   *  The board post *is* the catalogue — every code somebody can claim is on
   *  that image and nowhere else — so a catalogue that has changed and a board
   *  that has not is a list of codes people will type and be refused for.
   *
   *  Called at boot, which is the moment a new `respawns.json` reaches a guild
   *  that already exists, and again whenever a moderator adds a code. Guarded by
   *  a fingerprint rather than run unconditionally: a redraw is a REST edit per
   *  guild, restarts are frequent and catalogue changes are not, so it follows
   *  the codes rather than the process. A board post that has gone missing is
   *  reposted instead, since there is nothing left to edit.
   *
   *  Returns whether Discord was actually touched. Nothing here is fatal — a
   *  board that fails to redraw is out of date, not broken — and the digest is
   *  only recorded on success, so a failure is retried on the next boot rather
   *  than remembered as done.
   */
  /** Add a spawn a guild wants that the bundled list does not have.
   *
   *  The same four fields `respawns.json` carries, because it is the same thing:
   *  a code to claim by, the city it is in, what it is called and which monster
   *  represents it. Written as a `custom` row, which is what keeps `syncSeed`
   *  off it — the bundled file has no opinion about a code it never shipped, and
   *  must not retire one because of that.
   *
   *  Refusals are worded for the person typing, since this is reached from a
   *  form rather than from code. A code already in the catalogue is the common
   *  one and is refused rather than overwritten: quietly rewriting an existing
   *  spawn would rename whatever people are already claiming under that code.
   */
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

  /** Take a spawn a guild added back out of its catalogue.
   *
   *  Only a `custom` row. A seed code cannot be removed this way and is refused
   *  rather than quietly ignored: the bundled file is the authority on those, so
   *  deleting one would last until the next boot and then reappear, which looks
   *  like the button not working.
   *
   *  Refused, too, while anybody is holding, waiting for or has booked it.
   *  `removeRespawn` deletes those rows along with the spawn, and somebody's
   *  evening disappearing because a moderator was tidying up is not a trade
   *  worth making silently. Free it first and the removal goes through.
   *
   *  `syncSeed` retires a dropped code without asking this, and the difference
   *  is what the two are for: the file dropping a code says that spawn is not a
   *  thing any more, where a moderator pressing Remove is tidying and can be
   *  told to come back in an hour.
   */
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

  /** Note that the board post now shows the catalogue as it currently stands.
   *
   *  For the paths that redraw unconditionally — `/repair`, whose whole job is
   *  to fix a board that may be missing or wrong whatever a fingerprint says.
   *  Without this the next boot would find no record of that redraw and do it
   *  again. */
  def recordBoardDrawn(guildId: String): Unit =
    repository.setBoardDigest(guildId, RespawnBoardImage.digestOf(repository.listRespawns(guildId)))

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

  /** The longest a moderator may drag a window out to.
   *
   *  Not the guild's maximum claim length, which deliberately does not apply
   *  here (see [[editSlot]]) — this is only a guard against a number that
   *  could not be a hunt. Half a day is already longer than anything the grid
   *  is used to draw, and beyond it a window stops being an evening and starts
   *  overwriting the rest of the week. */
  val MaxModeratorSlotMinutes: Int = 12 * 60

  /** The longest ceiling a single spawn may be given.
   *
   *  A day, matching `RespawnSchedule.Daily`. Not because anything breaks above
   *  it, but because a claim longer than a day cannot be booked as a repeating
   *  slot (`addSchedule` refuses it), so a ceiling above this would be usable
   *  from one door and not the other — and a moderator who typed 10000 into the
   *  box meant something other than a week-long hold on a respawn. */
  val MaxSpawnCeilingMinutes: Int = RespawnSchedule.Daily


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
  def claim(guild: Guild, userId: String, userName: String, nickname: String, characterName: String,
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
                  // Not being able to afford the whole hunt is no longer a
                  // refusal — beginClaim shortens it to whatever is left. Only
                  // a tank with nothing worth starting in it is refused, and it
                  // is refused here so that somebody with none is not put in a
                  // queue they could never take their turn in.
                  if (!tank.unlimited && tank.remainingMinutes < MinimumClaimMinutes)
                    ClaimOutcome.NoStamina(respawn, minutes, tank, ServerSaveSchedule.nextServerSave(now))
                  // An outstanding offer means the spawn is already spoken for,
                  // even though its previous holder may already have been closed
                  // out. Without this, claiming it outright would leave two live
                  // claims the moment the offer was accepted.
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

  /** Start a claim right now. The single place a claim becomes active — the
   *  command path, the Claim button, queue promotion on expiry, and any future
   *  scheduled claim all come through here, so stamina reservation and the
   *  thread update can't be forgotten by one of them. */
  def beginClaim(guild: Guild, respawn: Respawn, config: RespawnSettings, userId: String,
                 userName: String, nickname: String, characterName: String, minutes: Int, kind: String,
                 now: ZonedDateTime): ClaimOutcome = {
    val guildId = guild.getId
    val boundary = resetBoundary(now)

    // A booked slot cuts an ad-hoc claim short. Stamina is then charged for the
    // shorter hunt, not the one that was asked for — nobody should pay for time
    // a reservation takes back. A scheduled occurrence starting its own slot is
    // exempt, or it would truncate against itself.
    val reservation =
      if (kind == RespawnClaim.KindScheduled) None else nextReservationStart(guildId, respawn.id, now)
    val untilBooking = endsAtFor(now, minutes, reservation)
    val allowedByBooking = math.max(0, java.time.Duration.between(now, untilBooking).toMinutes).toInt
    if (allowedByBooking < MinimumClaimMinutes)
      return ClaimOutcome.Reserved(respawn, reservation.getOrElse(untilBooking))

    // A tank that cannot cover the whole hunt shortens it rather than refusing
    // it. Somebody with forty minutes left wants those forty minutes, and being
    // told to come back after server save because they cannot afford two hours
    // is a refusal that helps nobody — the spawn sits empty and they hunt
    // nothing. The same rule a booking already gets: take what is there, charge
    // for what was taken, and say it was shortened.
    val tank = repository.stamina(guildId, userId, config.staminaMinutes, boundary)
    val granted = RespawnService.grantedMinutes(allowedByBooking, tank)
    // Below the floor there is genuinely nothing worth starting, and that is a
    // stamina refusal rather than a booking one.
    if (granted < MinimumClaimMinutes)
      return ClaimOutcome.NoStamina(respawn, minutes, tank, ServerSaveSchedule.nextServerSave(now))
    val end = now.plusMinutes(granted.toLong)

    // Re-check under the reservation itself rather than trusting the read above:
    // a second claim from the same user may have taken the room in between, and
    // reserveStamina writing nothing is the authoritative answer.
    if (!repository.reserveStamina(guildId, userId, granted, config.staminaMinutes, boundary)) {
      val fresh = repository.stamina(guildId, userId, config.staminaMinutes, boundary)
      ClaimOutcome.NoStamina(respawn, granted, fresh, ServerSaveSchedule.nextServerSave(now))
    } else {
      repository.insertActiveClaim(guildId, respawn.id, userId, userName, nickname, characterName,
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
          // getting less than they asked for. The booking is named only when the
          // booking is what did it — a hunt cut short by an empty tank has no
          // booking to blame, and saying otherwise would send somebody looking
          // for a slot that isn't there.
          val shortenedByBooking = reservation.isDefined && granted >= allowedByBooking
          if (granted < minutes)
            ClaimOutcome.Shortened(respawn, claim, minutes, if (shortenedByBooking) reservation else None)
          else ClaimOutcome.Claimed(respawn, claim)
      }
    }
  }

  private def enqueue(guild: Guild, respawn: Respawn, config: RespawnSettings, userId: String,
                      userName: String, nickname: String, characterName: String, minutes: Int): ClaimOutcome = {
    // Queueing deliberately does NOT reserve stamina. A queue that may never
    // reach the front would otherwise let people park their whole tank in other
    // people's queues; the reservation happens at promotion instead, and
    // someone who can't afford it by then is skipped rather than blocking the
    // line (see sweepGuild).
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
            // Read before the bound is checked rather than after, because the
            // bound now depends on which spawn this is — see
            // `RespawnSettings.maxFor`. A spawn that has gone from the catalogue
            // under a live claim falls back to the guild's number, which is the
            // same answer this gave before there were per-spawn ceilings.
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
          s"A moderator has freed ${RespawnEmbeds.spawnLink(respawn)}, so it's no longer yours.",
          imageFor(respawn), RespawnEmbeds.RedColor))
      holder
    }
  }

  /** Give whoever holds a spawn more time on it, for a moderator putting right a
   *  hunt that lost some — a crash, a server issue, a dispute that ate half of it.
   *
   *  Deliberately unlike the member's own extend in two ways, both because this
   *  is an override rather than a request:
   *
   *  Nobody's stamina is charged. The holder asked for the window they paid for
   *  and is being handed the difference; billing them for a moderator's decision
   *  would be the opposite of the repair. Handing somebody time *in the tank* is
   *  a separate tool, and the one to reach for when that is what is meant.
   *
   *  The guild's maximum claim length does not apply. It is a rule about what
   *  members may ask for, and enforcing it here would make the button quietly
   *  refuse on exactly the long hunts most likely to need rescuing.
   *
   *  What happens to everybody else falls out of the claim simply running
   *  longer: the queue is served when it ends, so each person waits the extra
   *  time, and a booking that starts inside the new window is cut into — the
   *  same thing a member's own extend has always done.
   */
  def extendHolder(guild: Guild, respawn: Respawn, extraMinutes: Int,
                   now: ZonedDateTime = ZonedDateTime.now()): Either[String, (RespawnClaim, ZonedDateTime)] = {
    val guildId = guild.getId
    if (extraMinutes <= 0) Left("That would add no time.")
    else settings(guildId) match {
      case None => Left("The respawn claim system isn't set up on this server.")
      case Some(config) =>
        // A claim in limbo has already been offered on to the next person; adding
        // time to it would extend a hunt that is on its way out of somebody's
        // hands, and the offer would still stand.
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

  /** How far back the Log panel will page. Beyond this a paged embed is the
   *  wrong tool — the rows are all still there, but walking years of them ten
   *  at a time is not something to invite people to do. */
  val LogMaxPages: Int = 10

  /** One page of the claim log, newest first, for whichever [[LogScope]] is
   *  asked for.
   *
   *  Asks for one row more than a page so the caller can tell "there is more"
   *  from "this is the end" without a second count query; [[LogPage.hasOlder]]
   *  consumes that and hands back only the page itself. */
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
   *  asked about becomes a question for that slot's owner (see `askForClash`),
   *  and anything else is still refused outright. */
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
          // The nickname belongs on the candidate as much as on the saved rule:
          // a booking that clashes never becomes a row of its own, so this
          // stand-in is the only record of who is asking, and the request it
          // turns into is named from it.
          val candidate = RespawnSchedule(0L, respawn.id, userId, userName, characterName,
            firstStart, RespawnSchedule.Daily, durationMinutes, active = true, now, daysOfWeek,
            nickname = nickname)
          // Checking for a clash and then writing the booking is two decisions
          // on one picture, and without the lock two people booking the same
          // evening at the same moment each got the picture from before the
          // other wrote. Nothing downstream would have caught it — the unique
          // index on an occurrence sees two schedule ids, not one evening — so
          // the read and the write are serialised on the spawn.
          //
          // Only they are. Everything after — materialising, the card, and the
          // DM a clash sends — is outside, because holding a row lock across a
          // Discord round trip would stall every other claim on the spawn for
          // as long as Discord felt like taking. A rule is visible to the next
          // booker the moment it is written, so nothing is lost by letting go
          // before the slots behind it exist.
          repository.withRespawnLock(guildId, respawn.id) {
            val schedules = repository.schedulesForRespawn(guildId, respawn.id)
              .filter(overlaps(_, candidate))
              .filterNot(surrendered(guildId, _, candidate, now))
            val slots = clashingReservations(guildId, respawn.id, candidate, now)
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

  /** Whether `schedule` has given up every day it would contest with
   *  `candidate`, and so no longer stands in the way of it.
   *
   *  A rule is a sentence about every day; what became of one of those days
   *  lives in the row for it. Comparing rules alone, a booking handed to
   *  somebody else — or one a moderator took off the calendar — left the
   *  original rule still defending an evening nobody was going to hunt, so the
   *  next person to want it was refused on behalf of a booking that no longer
   *  existed.
   *
   *  Deliberately all-or-nothing. A repeating rule that has given up one
   *  Thursday still owns every other, so it takes every contested day being
   *  settled before it stops counting — and a day too far ahead to have a row
   *  yet has settled nothing, which is what keeps `TooFarAhead` meaning what it
   *  says rather than quietly becoming a yes. */
  private def surrendered(guildId: String, schedule: RespawnSchedule,
                          candidate: RespawnSchedule, now: ZonedDateTime): Boolean = {
    val horizon = now.plusMinutes(Config.Respawn.scheduleLookAheadMinutes.toLong)
    val settled = daysGivenUp(guildId, now, Some(horizon), Some(schedule.respawnId))
      .getOrElse(schedule.id, Set.empty)
    RespawnSchedule.surrendered(schedule, candidate, settled, now, horizon)
  }

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
   *  asked. Shared by the two ways of saying yes: **Keep** on the request DM, and
   *  **Confirm** on the reminder, which settles the slot outright and so has to
   *  answer any outstanding question with it.
   *
   *  `askedAt` deliberately survives (see `keepOccurrence`), so the slot still
   *  cannot be asked about a second time. No card rewrite either: the slot stays
   *  exactly where it was, with the same owner and the same time — only the
   *  *asked* note goes, which is not worth an edit of its own. */
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
              slot.requesterUserName.getOrElse(""), slot.requesterNickname.getOrElse(""),
              start, minutes)
            RespawnThreads.dm(guild, requester,
              RespawnEmbeds.dmEmbed("The hunt is yours",
                RespawnEmbeds.slotRequestGranted(respawn, start, minutes), imageFor(respawn)))
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
              // Blue rather than the warning yellow it used to be: this is a
              // booking, and booking is what blue means on the board too (see
              // RespawnEmbeds.BookedColor). Nothing is wrong here — it is a hunt
              // about to start, not a deadline running out.
              RespawnThreads.dm(guild, slot.userId,
                RespawnEmbeds.dmEmbed("Your hunt starts soon",
                  RespawnEmbeds.slotReminder(respawn, slot), imageFor(respawn),
                  RespawnEmbeds.BookedColor),
                // Optional, and worth pressing: it settles the slot early, so
                // nobody can ask for it and the start needs no answer.
                Some(RespawnThreads.confirmSlotButtons(guildId, slot.id, "Confirm")))
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
            // Usually there is an outgoing claim in limbo behind this offer and the
            // pass below closes it and moves the spawn on. But that claim only gets
            // one handover window: once it has been closed out, every later offer on
            // the same spawn stands alone, so a second lapse leaves nothing for the
            // pass below to find. Advance it here in that case, or the spawn sits
            // free with a lapsed offer still on its card, its post still open, and
            // whoever is behind in the queue never asked.
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

      // Bookings that started on their own and whose owner never said they were
      // there. Given up on their behalf, exactly as if they had pressed Leave:
      // the unused minutes go back in the tank and the spawn moves on through
      // the ordinary handover, to whoever is queued or to nobody.
      //
      // The point is the people behind them. A booking nobody turned up for used
      // to hold a spawn for its whole window while everyone who would have hunted
      // it waited on somebody absent.
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
            // Not finished here, same as an early release: the next person gets
            // their answer window and the spawn stays put until they take it, so
            // a third party cannot snipe it in between. beginHandover closes the
            // claim itself when there is nobody to hand it to.
            beginHandover(guild, respawn, config, now, outgoing = Some(claim),
              outgoingOutcome = RespawnClaim.Outcome.Unconfirmed)
          }
        }.failed.foreach { error =>
          logger.warn(s"Failed to give up unconfirmed respawn claim ${claim.id} in guild '$guildId'", error)
        }
      }

      // The claim-ending reminder, kept but not sent. It was one DM per hunt
      // telling somebody something their own claim card already says, and the
      // confirmation prompts above are now the DMs a booking produces — three
      // notifications for one evening's hunt was more than the feature is worth.
      //
      // Left here rather than deleted because turning it back on is a matter of
      // uncommenting it: `unwarnedActiveClaims`, `markWarned` and
      // RespawnEmbeds.expiryWarning are all still in place and still tested.
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
        repository.startReservation(guildId, slot.id, now, bookedEnd, confirmBy) match {
          case None =>
            // Something else already started it; hand the stamina straight back.
            repository.refundStamina(guildId, slot.userId, remaining, boundary)
          case Some(started) if started.confirmed =>
            // Settled from the reminder, so there is nothing left to ask.
            refreshThread(guild, respawn, config)
            RespawnThreads.dm(guild, slot.userId,
              RespawnEmbeds.dmEmbed("Your hunt has started",
                RespawnEmbeds.slotStarted(respawn, started), imageFor(respawn),
                RespawnEmbeds.FreeColor))
          case Some(started) =>
            // The spawn is genuinely theirs from this moment — the card shows
            // them on it and nobody else can take it — but only until the
            // deadline above, which the sweep enforces.
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
          // The card itself already flips to free with a Claim button on it, so
          // there is nothing to say — a "now free" post would just be noise in a
          // thread meant to stay readable. refreshThread also puts the post to
          // sleep, since by now nobody holds the spawn.
          refreshThread(guild, respawn, config)
          None
      }
    }
  }

  /** Someone pressed **Confirm** on a booking reminder, or **Take Claim** on the
   *  hunt it grew into. One method for both, because it is one answer to one
   *  question — "yes, I'm here" — and which of the two they pressed is decided by
   *  where the claim had got to, not by the button.
   *
   *  Confirming early is worth something on its own: it takes the slot out of
   *  reach of anyone trying to book over it (see `RespawnClaim.requestable`) and
   *  means the start needs no answer. Confirming a started hunt is what keeps it,
   *  and the sweep gives up on one nobody confirms.
   *
   *  No card rewrite either way. Confirming changes nothing a card shows — the
   *  same person holds (or has booked) the same spawn for the same window — and
   *  card edits are the system's scarcest resource. */
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
              // Either it was confirmed already — a second press, from the
              // reminder after Take Claim, say — or it is neither reserved nor
              // active any more, which is the deadline having gone by while the
              // DM sat open. Those are different answers to the presser.
              case None =>
                if (claim.confirmed) ConfirmOutcome.Already(respawn) else ConfirmOutcome.Gone
              case Some(confirmed) if confirmed.isActive => ConfirmOutcome.Taken(respawn, confirmed)
              case Some(confirmed) =>
                // Confirming answers an outstanding "are you hunting tonight?"
                // too — it is the same yes. Left alone, the request would sit
                // there and the sweep would hand this slot to whoever asked,
                // hours after its owner said they were coming; worse, a slot
                // with a request still on it never starts at all, since
                // `dueReservations` waits for one to be resolved.
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
   *  the post on first claim, revives it if the spawn was idle, and puts it back
   *  to sleep once nobody holds it.
   *
   *  Sleeping belongs here rather than at the call sites that end a hunt. It used
   *  to live in `beginHandover`, which is only one of the ways a spawn comes free
   *  — a lapsed offer, a missed or unaffordable booked slot, a cancelled diary all
   *  free a spawn too, and each of those went through this function, which wakes
   *  the post to redraw the card and never closed it again. So a spawn's post
   *  stayed open on every path but one. Tying it to `active` instead makes the
   *  post's state a function of the claim state, like the card and the tag are. */
  def refreshThread(guild: Guild, respawn: Respawn, config: RespawnSettings): Option[ThreadChannel] = {
    // A forum this bot cannot see used to end the whole refresh in silence: the
    // claim landed, the reply said so, and nothing in Discord moved — with
    // nothing anywhere to say why. Every other way this can fail already says
    // so, and it is the one that leaves a guild's posts permanently stale, so
    // it says so too.
    val forum = RespawnThreads.findForum(guild, config)
    if (forum.isEmpty) {
      logger.warn(s"No respawn forum to update for '${respawn.code}' in guild '${guild.getId}': " +
        s"configured channel '${config.forumChannel}' is not one this bot can see. " +
        "Claims and bookings will keep working; their posts will not change.")
    }
    forum.flatMap { forum =>
      val guildId = guild.getId
      val active = repository.activeClaim(guildId, respawn.id)
      // The person holding an unanswered offer is still shown at the head of the
      // queue, exactly where they were while queued. That's both truthful — they
      // are next — and what makes an offer going out change nothing on the card,
      // so it needs no edit at all.
      val queue = repository.offeredClaim(guildId, respawn.id).toList ++ repository.queueFor(guildId, respawn.id)
      val now = ZonedDateTime.now()
      val reservations = repository.reservationsFor(guildId, respawn.id, now)
      // Bookings that exist only as a rule so far. A slot is written when its
      // start comes within the look-ahead, so anything booked further out than
      // that has nothing to show for itself for days — and the card would have
      // read "nothing booked" straight after somebody booked it, which is what
      // the dashboard's week-wide grid made easy to hit and Discord's own
      // half-day picker never could.
      //
      // Only rules with no slot at all. A repeating booking whose next evening is
      // already written down is on the card once through that row; adding its
      // rule as well would list the same booking twice.
      val written = reservations.flatMap(_.scheduleId).toSet
      val upcoming = repository.schedulesForRespawn(guildId, respawn.id).filterNot(s => written.contains(s.id))
      // A rule whose next evening has been given away must name the one after
      // it. Without this the card listed tonight twice — once as the booking
      // that had taken it, and once as the rule that used to hold it.
      val givenUp = daysGivenUp(guildId, now, respawnId = Some(respawn.id))
      val card = RespawnEmbeds.claimCard(respawn, active, queue, reservations, config,
        imageFor(respawn), upcoming, now, givenUp)
      val buttons = RespawnThreads.claimButtons(respawn.id, active.isDefined)

      // Re-read after a possible create so the row carries the new thread id;
      // the create callback writes it, but the local `respawn` is a snapshot.
      val opened = RespawnThreads.openThread(guild, forum, respawn, card, buttons,
        threadId => repository.setThreadId(guildId, respawn.id, threadId))

      opened.foreach { post =>
        // Card, tag and sleep together, in that order — see RespawnThreads.settle
        // for why they cannot be asked for side by side.
        //
        // Archived, not locked: people can still leave notes on a spawn between
        // hunts, and reviving it doesn't need a moderator. Archiving is what keeps
        // the forum's front page to the spawns people are actually on — a free
        // spawn is claimed from the pinned board, since Discord disables this
        // post's own Claim button while it sleeps.
        //
        // Keyed on the holder alone: a spawn with bookings but nobody on it is
        // still nobody's hunt, and its diary is on the card for whoever opens it.
        // `active` covers a claim in limbo, so a post stays awake for the whole of
        // a handover rather than flickering shut between two hunts.
        RespawnThreads.settle(forum, post.thread,
          if (post.created) None else Some(card -> buttons),
          RespawnThreads.tagFor(claimed = active.isDefined),
          sleep = active.isEmpty)
      }
      opened.map(_.thread)
    }
  }

  // --- putting posts back to sleep ------------------------------------------

  /** Archive the posts somebody has been clicking on that have since gone quiet.
   *  Returns how many were closed.
   *
   *  The other half of [[RespawnSleep]]: presses are written down on JDA's event
   *  thread, and this — on the sweep, where blocking is fine — is what acts on
   *  them once they are due. A post is only closed if the spawn it belongs to
   *  still has nobody on it, which is re-read here rather than trusted from
   *  whenever the press happened: five minutes is long enough for somebody to
   *  have claimed it, and closing a held spawn's post would take the Leave
   *  button away from its holder.
   */
  def closeIdleThreads(guild: Guild, config: RespawnSettings,
                       now: java.time.Instant = java.time.Instant.now()): Int = {
    val guildId = guild.getId
    val ready = RespawnSleep.due(guildId, now)
    if (ready.isEmpty) 0
    else RespawnThreads.findForum(guild, config).fold(0) { _ =>
      // One catalogue read for the batch, keyed by thread so a due entry that
      // belongs to no spawn — the board post, or a thread in some other forum
      // that `RespawnSleep.touched` could not rule out without this lookup — is
      // simply dropped.
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

  /** Close any post that is awake with nobody on the spawn, whatever left it
   *  that way. Returns how many were closed.
   *
   *  The backstop under [[RespawnSleep]], which is in memory: a restart forgets
   *  every pending close, and a post left open by a press just before one would
   *  otherwise stay open until somebody claimed and left that spawn. It also
   *  catches the posts already stuck open from before any of this existed.
   *
   *  `getThreadChannels` is cache-only and by definition lists the forum's
   *  *un*-archived posts, which is exactly the candidate set — so the scan
   *  itself costs nothing and only the spawns it turns up cost a query. Posts
   *  the debounce is already about to handle are skipped, so this never closes
   *  one out from under somebody mid-visit.
   *
   *  `limit` caps the archives per pass. The lookups ahead of it are lazy, so a
   *  forum full of held spawns costs its queries and stops at no requests rather
   *  than spending the cap on posts that turn out to be fine.
   */
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

  /** Every row a guild's calendars are drawn from, over one window, in four
   *  reads rather than five per spawn per week.
   *
   *  The same rows the per-spawn calls return, asked for guild-wide and grouped
   *  here — see [[com.tibiabot.web.CalendarRows]], which is what holds them.
   *  Nothing is decided at this level: the deciding is still
   *  `JdaRespawnActions.assembleCalendar`, which now takes its rows from here
   *  instead of asking for its own.
   */
  def calendarRows(guildId: String, from: ZonedDateTime,
                   to: ZonedDateTime): com.tibiabot.web.CalendarRows =
    com.tibiabot.web.CalendarRows(
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

  /** Days each rule has given up, as the surfaces that draw rules need them:
   *  keyed by schedule, so asking "when is this one next" costs a lookup rather
   *  than a scan. See [[com.tibiabot.persistence.ScheduleOccurrence]].
   *
   *  Both bounds narrow the same question — one spawn's week for the calendar,
   *  every spawn from now on for somebody's list of bookings. */
  def daysGivenUp(guildId: String, from: ZonedDateTime,
                  to: Option[ZonedDateTime] = None,
                  respawnId: Option[Long] = None): Map[Long, Set[java.time.Instant]] =
    repository.settledOccurrences(guildId, from, to, respawnId)
      .groupBy(_.scheduleId)
      .map { case (id, days) => id -> days.map(_.startsAt.toInstant).toSet }

  // --- putting the calendar right ------------------------------------------

  /** Take one day off the calendar, leaving the rule behind it alone.
   *
   *  The moderator counterpart to a member cancelling a booking, and pointedly
   *  narrower: cancelling is about a standing arrangement, this is about one
   *  evening that has gone wrong. A repeating booking keeps repeating.
   *
   *  A day that has been materialised is cancelled; one that has not is written
   *  down as already cancelled, which is the only way to record "not this day"
   *  about a rule — and is what both the calendar and the next person to book
   *  that evening read. Either way the day ends up settled, so the two cases
   *  differ only in whether there was a row to begin with.
   */
  def dropSlot(guild: Guild, respawn: Respawn, startsAt: ZonedDateTime,
               now: ZonedDateTime = ZonedDateTime.now()): Either[String, String] = {
    val guildId = guild.getId
    settings(guildId) match {
      case None => Left("The respawn claim system isn't set up on this server yet.")
      case Some(config) =>
        val outcome = repository.withRespawnLock(guildId, respawn.id) {
          repository.slotAt(guildId, respawn.id, startsAt) match {
            // Ending a hunt somebody is on is a different act — it has a queue
            // to advance behind it — and Remove Claim on the board is what does
            // it. Quietly doing that instead would be a surprise.
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

  /** Put one day in somebody else's name.
   *
   *  What they get is a booking of their own rather than a rewritten occurrence
   *  of the old owner's rule — the same shape a slot takes when its owner gives
   *  it up to whoever asked, and for the same reason: it is no longer a day of
   *  anybody's standing arrangement, and the trail keeps both halves of what
   *  happened. So the old owner's day is settled and a fresh booking is written
   *  beside it.
   *
   *  Refused for a day that is already running. Moving a hunt somebody is on is
   *  a different act with different consequences — see [[reassignClaim]], which
   *  is what the board's Hand To does — and quietly doing that instead would be
   *  a surprise.
   */
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

  /** Change how long one window on the calendar runs, for a moderator.
   *
   *  The third of the calendar tools, and aimed the same way as the other two:
   *  at whatever is selected on the grid, named by the instant it starts on,
   *  whether or not it has a row behind it yet. What it does with that depends
   *  on which of the three things it turns out to be.
   *
   *  ==A hunt somebody is on now==
   *  Its deadline moves. Nobody's stamina is charged for a longer one, for the
   *  same reason [[extendHolder]] charges nobody: the holder asked for the
   *  window they paid for, and a moderator's decision is not theirs to fund. A
   *  shorter one hands back what they will now never hunt, which is the same
   *  reasoning read the other way round — leaving them billed for time a
   *  moderator took off them would be the punishment neither of us meant.
   *
   *  It cannot go below what has already elapsed; those minutes are spent
   *  whatever anybody now says. Setting it to exactly that ends the hunt at the
   *  next sweep, which is as close to "stop now" as this tool goes — Remove
   *  claim is the one that ends a hunt outright, with the queue behind it
   *  served properly.
   *
   *  ==An evening already booked==
   *  Its row is rewritten in place. A day of a repeating booking keeps its
   *  place in the rule and the rule keeps its own length, so one evening runs
   *  longer and next week does not — which is what a moderator putting one
   *  evening right is asking for, and the same narrowness [[dropSlot]] has.
   *
   *  ==An evening a rule has not written down yet==
   *  The day is settled and a booking in the same person's name is written
   *  beside it at the new length, exactly as [[reassignSlot]] does for a day
   *  that changes hands. There is nowhere else to record "this day, but longer"
   *  about a rule.
   *
   *  ==What it will not do==
   *  The guild's maximum claim length does not apply — it is a rule about what
   *  members may ask for, and enforcing it here would refuse exactly the
   *  repairs most worth making. [[MaxModeratorSlotMinutes]]
   *  still does, as a guard against a number that could not be a hunt.
   *
   *  A future window is refused if it would run into the next thing on the
   *  spawn, because booking one over somebody else is refused too and a
   *  moderator's ruler should not be the way around that. A live hunt is not:
   *  it overruns what follows, which is what a member's own extend and the
   *  +30m button have always done, and the answer says whose evening it now
   *  reaches into.
   */
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
   *
   *  Both a booking that has been written down and a day a rule has not got to
   *  yet, for the reason `clashingReservations` gives: the two see different
   *  things, and a window dragged out over an evening beyond the look-ahead
   *  would find nothing to collide with if only the rows were consulted.
   *
   *  Strictly after `after`, so the window being edited never finds itself.
   */
  private def nextUpOn(guildId: String, respawnId: Long, after: ZonedDateTime,
                       until: ZonedDateTime, now: ZonedDateTime): Option[String] = {
    val booked = repository.reservationsFor(guildId, respawnId, after)
      .filter(_.startsAt.exists(_.isBefore(until)))
      .map(slot => Names.plain(slot.nickname, slot.userName))
    if (booked.nonEmpty) booked.headOption
    else {
      // A day already settled is in nobody's way, which is what stops an evening
      // a moderator has just taken off the calendar from blocking the edit to
      // the one before it.
      val settled = daysGivenUp(guildId, after, Some(until), Some(respawnId))
      repository.schedulesForRespawn(guildId, respawnId).iterator.flatMap { schedule =>
        schedule.occurrencesBetween(after.plusMinutes(1), until)
          .filterNot(start => settled.getOrElse(schedule.id, Set.empty).contains(start.toInstant))
          .map(_ => Names.plain(schedule.nickname, schedule.userName))
      }.toList.headOption
    }
  }

  /** Telling somebody their hunt just got longer or shorter under them.
   *
   *  Worth a message for the same reason [[forceLeave]] is: a deadline that
   *  moves with no explanation is indistinguishable from the bot getting it
   *  wrong. Only a live hunt earns one — an evening next week is read off the
   *  calendar rather than remembered as a countdown. */
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

  /** The whole catalogue with its live state, for the web dashboard's board.
   *
   *  Five queries whatever the catalogue's size, rather than the three per
   *  spawn the single-spawn accessors would cost — a few hundred spawns on a
   *  polling page makes that difference the whole performance story.
   *
   *  `lastActivity` is absent for a spawn nobody has ever claimed, which the
   *  board renders as its most faded state rather than as an error. */
  def board(guildId: String, now: ZonedDateTime = ZonedDateTime.now()): List[RespawnBoardEntry] =
    RespawnService.assembleBoard(
      repository.listRespawns(guildId),
      repository.allActiveClaims(guildId),
      repository.allQueuedClaims(guildId),
      repository.allReservations(guildId, now),
      repository.lastActivityByRespawn(guildId).toMap
    )

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
   *  beyond `RespawnUserPrefs.MaxWarnMinutes` would fire the instant a claim
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

object RespawnService {

  /** Match a typed query against a guild's catalogue, when it wasn't a code.
   *
   *  A ladder from exact to loose, stopping at the first rung that lands on
   *  exactly one row. Every rung that can match several answers `None` instead
   *  of picking one, because a search that guesses is worse than one that says
   *  it doesn't know — the caller shows the query back and the reader tries
   *  again.
   *
   *  The last rung is the word one: every word of the query somewhere in the
   *  row, in any order, so "fire library" finds "Secret Library (Fire)" — which
   *  no substring of that name does, since the words are the wrong way round and
   *  have a bracket between them. It searches the name and the creature as one
   *  piece of text, so "burning library" gets there too. Only for multi-word
   *  queries: on a single word it is precisely the substring rungs above, and
   *  running it again would say nothing new.
   *
   *  Pure and separated from the repository so it can be read against a handful
   *  of rows in a test rather than a database.
   */
  private[respawn] def resolveIn(all: List[Respawn], query: String): Option[Respawn] = {
    val trimmed = query.trim
    if (trimmed.isEmpty) return None
    val lower = trimmed.toLowerCase

    val words = lower.split("\\s+").filter(_.nonEmpty).toList
    def allWordsIn(respawn: Respawn): Boolean = {
      val haystack = s"${respawn.name} ${respawn.creature}".toLowerCase
      words.forall(haystack.contains)
    }

    // "415 — Cult Orcs" comes back from autocomplete as the display name in
    // some clients, so match that shape too before falling back to a substring
    // search. `creature.nonEmpty` guards the substring rung because "" is a
    // substring of everything, and the many rows with no creature set must not
    // match whatever was typed.
    val rungs = List(
      all.filter(_.displayName.equalsIgnoreCase(trimmed)),
      all.filter(_.name.equalsIgnoreCase(trimmed)),
      all.filter(_.name.toLowerCase.contains(lower)),
      all.filter(_.creature.equalsIgnoreCase(trimmed)),
      all.filter(r => r.creature.nonEmpty && r.creature.toLowerCase.contains(lower)),
      if (words.sizeIs < 2) Nil else all.filter(allWordsIn)
    )

    // Nothing on a rung means try the next one. Several means stop — and stay
    // unresolved. That second part is the one worth stating: "cult" naming two
    // spawns is a real ambiguity, and answering it from some looser field
    // further down would be exactly the guess this is built to refuse. Only a
    // rung that finds silence hands on to the next.
    rungs.iterator
      .map {
        case Nil           => None
        case single :: Nil => Some(Some(single))
        case _             => Some(None)
      }
      .collectFirst { case Some(answer) => answer }
      .flatten
  }

  /** Limits on a spawn somebody adds by hand.
   *
   *  Well under what the columns hold. They are about what stays readable rather
   *  than what fits: a code is typed to claim by and shown on a chip, and every
   *  name is drawn in full on the board image, so one absurdly long entry makes
   *  the picture wider for every other guild member who opens it.
   */
  /** How many people the stamina picker is willing to offer. Generous for a
   *  guild and small enough that the payload stays a list rather than a dump. */
  val MaxKnownMembers: Int = 500

  val MaxCodeLength: Int = 16
  val MaxSpawnNameLength: Int = 60
  val MaxRegionLength: Int = 40

  /** What is wrong with a spawn somebody typed, or None if nothing is.
   *
   *  Everything about the fields themselves, which is everything that can be
   *  decided without asking the guild's catalogue anything — so the rules can be
   *  read back in a test without a database. Whether the code is already taken
   *  is the caller's question, since only it can answer that.
   *
   *  Expects the four fields already trimmed. Worded for whoever typed them,
   *  because a form is the only way in.
   */
  def spawnFault(code: String, region: String, name: String, creature: String): Option[String] =
    if (code.isEmpty) Some("A spawn needs a code — it is what people type to claim it.")
    // Letters, digits and hyphens: what the bundled codes look like, and a code
    // travels in a URL and in a thread title, so it stays to characters that
    // mean the same thing everywhere.
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
    // Checked here rather than left to fail quietly: an unusable creature name
    // costs nothing at all until somebody wonders why their spawn is the only
    // one on the board without a picture.
    else if (creature.nonEmpty && com.tibiabot.web.CreatureSprites.safeFileName(creature).isEmpty)
      Some(s"I can't fetch a picture for '$creature'. Use the creature's wiki name, " +
        "or leave it empty and the spawn goes without one.")
    else None

  /** When an owner has to answer by: a little way into the slot itself,
   *  whenever they were asked.
   *
   *  It used to be a fixed hour from the question, cut short if that ran past
   *  the slot. That measured the wrong thing. Somebody asked at lunchtime about
   *  an evening slot lost it by mid-afternoon without ever having been near a
   *  computer, and the question was never about the afternoon — it was "are you
   *  hunting this tonight", which only the evening can answer. So the clock runs
   *  to the hunt rather than from the asking, and the whole of the answer is
   *  whether they turned up.
   *
   *  Past the start rather than on it, because a deadline landing exactly on the
   *  start punishes somebody logging in on time: they arrive to find the slot
   *  already gone. It costs the asker very little — the hunt they take over runs
   *  its full length from whenever it starts — and turns "you were a minute
   *  late" into "you never turned up".
   *
   *  Always in the future when it is set. A slot stops being requestable once it
   *  starts, since by then it is a running claim rather than a booking, so there
   *  is no way to be asked about an evening that has already gone. */
  def answerDeadline(slotStart: ZonedDateTime, graceMinutes: Int): ZonedDateTime =
    slotStart.plusMinutes(graceMinutes.toLong)

  /** How long a claim actually runs for, given what a booking leaves and what
   *  is in the tank.
   *
   *  Both limits shorten rather than refuse. A booking has always worked that
   *  way; stamina now does too, because "you cannot afford two hours" was a
   *  refusal that left the spawn empty and the person hunting nothing, when
   *  what they wanted was the forty minutes they had.
   *
   *  Pure, so the table — which limit binds, and what happens when the tank is
   *  unlimited or empty — is checkable without a database or a Discord guild.
   *  The floor, and what the shortfall is blamed on, stay with the caller that
   *  has the spawn and the booking to name.
   */
  def grantedMinutes(allowedByBooking: Int, tank: Stamina): Int =
    if (tank.unlimited) allowedByBooking else math.min(allowedByBooking, tank.remainingMinutes)

  /** Stitch a board together from the bulk reads that feed it.
   *
   *  Pure, so the part that can actually be wrong — which claim wins when a
   *  spawn somehow has more than one active, what order a queue comes out in,
   *  what a never-claimed spawn looks like — is checkable without a database.
   *  [[RespawnService.board]] is then only the five reads.
   */
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
