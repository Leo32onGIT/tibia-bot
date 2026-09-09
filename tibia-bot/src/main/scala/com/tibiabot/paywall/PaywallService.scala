package com.tibiabot.paywall

import com.tibiabot.discord.DiscordGateway
import com.tibiabot.persistence.{PatreonGraceRepository, PatreonMemberRepository, PatreonSeatOverrideRepository, PatreonSeatRepository}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.Guild

import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

/** Ties ongoing bot activity to a Patreon subscription via seats: each supporter
 *  gets `seatLimit` of them (adjustable per user via [[effectiveSeatLimit]]), and
 *  `/setup` assigns one to a (guild, world) pair — see
 *  [[com.tibiabot.setup.ChannelService]]. A positive seat adjustment also bypasses
 *  the subscription check entirely (see [[callerIsSubscribed]]), so the
 *  dashboard's "grant extra seats" doubles as a full paywall override.
 *
 *  Subscription status is Patreon's answer, read from the synced snapshot in
 *  `PatreonMemberRepository`. The support guild is consulted only to resolve a
 *  *username* to a Discord id (see [[resolveUserId]]), as a convenience — a raw id
 *  or mention bypasses it.
 *
 *  Nothing is cut off the moment it stops checking out. A world whose seat owner
 *  has lapsed — or that was never seated at all — starts a `graceDays` timer kept
 *  in `PatreonGraceRepository`, and runs untouched until it expires. Resolving the
 *  subscription first stops the clock. See `applyRefresh`, whose one rule puts an
 *  orphaned setup and a cancelled one on identical footing.
 *
 *  All of it steps aside where Patreon was never set up (see
 *  `patreonNotConfigured`), which is what makes the bot self-hostable. */
final class PaywallService(
  discordGateway: DiscordGateway,
  patreonSeatRepository: PatreonSeatRepository,
  patreonSeatOverrideRepository: PatreonSeatOverrideRepository,
  patreonGraceRepository: PatreonGraceRepository,
  patreonMemberRepository: PatreonMemberRepository,
  supportGuildId: String,
  seatLimit: Int,
  graceDays: Int,
  ownerId: String,
  /** Whether this install has Patreon API credentials at all (see
   *  Config.PatreonApi.enabled) — half of `patreonNotConfigured`. Defaults
   *  to "configured", so anything constructing this without an opinion gets
   *  the paywall enforced rather than bypassed. */
  patreonApiConfigured: Boolean = true
) extends StrictLogging {
  private val activeStatus = new ConcurrentHashMap[(String, String), Boolean]()

  /** Cheap and synchronous — consulted on every send-loop iteration in TibiaBot.
   *  Fail-open: a pair not yet checked, or whose check errored transiently, is
   *  never silently cut off. Pairs past their grace deadline are seeded false at
   *  construction (see `hydrateFromGrace`). */
  def isActive(guildId: String, world: String): Boolean = activeStatus.getOrDefault((guildId, world), true)

  /** Being paused is durable — the grace row is — but the map above is not, and
   *  [[isActive]] fails open. Unseeded, every world past its grace period resumed
   *  full tracking from boot until the first [[refreshAll]] sweep ~31 minutes
   *  later. The harm outlived that window: the online-list channel was renamed
   *  back to a player count and the paused notice overwritten, while the sweep
   *  that re-paused it stayed silent (`applyRefresh` won't re-announce an
   *  already-`notified` pause), so nothing ever put either back.
   *
   *  Seeding closes it at the source rather than by making the sweep eager: the
   *  same expiry test against the same durable rows, so a restart resumes the
   *  state it left. Only *expired* timers are seeded. A failed read leaves
   *  everything fail-open — a database that isn't up must not pause anyone. */
  private def hydrateFromGrace(now: ZonedDateTime): Unit =
    try {
      val paused = patreonGraceRepository.allGrace().filterNot(_.started.plusDays(graceDays.toLong).isAfter(now))
      paused.foreach(g => activeStatus.put((g.guildId, g.world), false))
      if (paused.nonEmpty) logger.info(s"Paywall: restored ${paused.size} paused world(s) from the grace table at startup")
    } catch {
      case ex: Throwable => logger.warn("Paywall: could not read the grace table at startup — every world stays active until the first sweep", ex)
    }

  /** How many rows the synced Patreon snapshot holds, or `None` if it could not be
   *  read. Three-valued deliberately: "Patreon says nobody" and "Patreon didn't
   *  answer" must be told apart before either is acted on. `Some(0)` is a fact,
   *  and never a legitimate answer about who is subscribed
   *  (BotApp.syncPatreonMembers refuses to write one), so it means the integration
   *  is not running. `None` is the absence of a fact and concludes nothing. */
  private def patreonSnapshotSize(): Option[Int] =
    try Some(patreonMemberRepository.snapshot().size)
    catch {
      case ex: Throwable =>
        logger.warn("Paywall: could not read the Patreon snapshot", ex)
        None
    }

  /** Is Patreon provably not set up here? If so the whole paywall steps aside,
   *  which is what makes the bot self-hostable without gutting the hosted gate.
   *
   *  Both halves are required, since either alone is too weak. Credentials alone:
   *  a deploy losing its token to an env mistake would throw `/setup` open while
   *  its member table still held a good snapshot. An empty table alone: a database
   *  still coming up looks exactly like a self-host. Requiring both means the
   *  paywall is bypassed only where there is no evidence Patreon was involved.
   *
   *  Note the asymmetry with [[refreshAll]], which stands down on an empty
   *  snapshot regardless of credentials: pausing wrongly costs somebody their
   *  tracking, so it fails open; letting somebody in wrongly costs a subscription,
   *  so this fails closed. */
  private def patreonNotConfigured: Boolean =
    !patreonApiConfigured && patreonSnapshotSize().contains(0)

  /** Seeding a pause is only meaningful against a snapshot that could have
   *  produced it. With an empty one, the grace rows on disk were written by
   *  sweeps that had nothing to check against (the pre-fix behaviour, or a
   *  window where the integration was down), and honouring them here would
   *  re-pause every world for the ~31 minutes until the first sweep clears
   *  them — see [[refreshAll]]. An unreadable snapshot still hydrates: the
   *  rows are the durable record of a real decision, and refusing to read a
   *  table is no reason to discard it. */
  patreonSnapshotSize() match {
    case Some(0) => logger.info("Paywall: the Patreon snapshot is empty at startup — leaving every world active rather than restoring pauses from the grace table")
    case _       => hydrateFromGrace(ZonedDateTime.now())
  }

  /** The subscription check — `/setup` calls it directly, `refreshAll` once per
   *  seat owner. Answered from the synced campaign snapshot (see
   *  patreonapi.PatreonApiClient): they pass if Patreon reports them an active
   *  patron. This was once a role lookup in the support guild, which failed real
   *  supporters who had left that Discord or whose role sync had not fired.
   *
   *  Two bypasses come first and skip Patreon entirely. The bot owner always
   *  passes, so their seats never lapse on the periodic recheck either. So does a
   *  *positive* dashboard-granted seat adjustment, which is treated as an explicit
   *  override of the whole paywall (zero or negative does not).
   *
   *  Unknown account, no linked Discord, a lapsed pledge or a failed read all
   *  answer "not subscribed" rather than propagating an error. That answer cuts
   *  nobody off on its own — it starts the grace period (see `applyRefresh`), so a
   *  bad sync costs days of headroom rather than anyone's tracking. */
  def callerIsSubscribed(userId: String): Boolean =
    if (patreonNotConfigured || userId == ownerId || patreonSeatOverrideRepository.extraSeatsFor(userId) > 0) true
    else try patreonMemberRepository.isActivePatron(userId)
    catch { case _: Throwable => false }

  /** Resolve whatever the dashboard's "grant extra seats" box was given into a
   *  Discord user id: a raw id, a pasted `<@id>` mention, or a username.
   *
   *  The id paths exist because the username path only sees the support guild, and
   *  the people most likely to be granted a free seat were never in it. An id
   *  resolves against Discord directly (`GET /users/{id}` needs no shared guild),
   *  so it works for anyone — and is verified rather than trusted, since a
   *  mistyped snowflake would write a durable override for a nonexistent account.
   *
   *  The username path uses retrieveMembersByPrefix, a scoped gateway search
   *  rather than the full member cache, so it needs no privileged GUILD_MEMBERS
   *  intent. Case-insensitive exact match; None if nobody matches. */
  def resolveUserId(input: String): Option[String] = {
    val trimmed = input.trim
    // A mention pasted straight out of Discord — `<@123>` or the legacy
    // nickname form `<@!123>`.
    val mention = """^<@!?(\d{17,20})>$""".r
    val rawId = """^\d{17,20}$""".r
    trimmed match {
      case mention(id) => verifiedUserId(id)
      case rawId()     => verifiedUserId(trimmed)
      case username    => findUserIdByUsername(username)
    }
  }

  /** The id back, but only if Discord actually knows that account. The input is
   *  already a well-formed snowflake by this point, so the lookup is purely an
   *  existence check — hence returning `id` rather than re-reading it off the
   *  response. */
  private def verifiedUserId(id: String): Option[String] =
    try Option(discordGateway.retrieveUser(id)).map(_ => id)
    catch { case _: Throwable => None }

  private def findUserIdByUsername(username: String): Option[String] = {
    val supportGuild = discordGateway.guildById(supportGuildId)
    if (supportGuild == null) None
    else try {
      supportGuild.retrieveMembersByPrefix(username, 25).get().asScala
        .find(_.getUser.getName.equalsIgnoreCase(username))
        .map(_.getUser.getId)
    } catch {
      case _: Throwable => None
    }
  }

  /** This user's seat limit: the flat global default plus their own
   *  dashboard-granted adjustment (see [[setExtraSeats]]), which may be
   *  negative — floored so a limit can never go below 0. */
  def effectiveSeatLimit(userId: String): Int =
    math.max(0, seatLimit + patreonSeatOverrideRepository.extraSeatsFor(userId))

  /** Pure — can this user claim a seat for (guildId, world)? If someone
   *  already owns that pair, only that same person may (re-)claim it
   *  (idempotent re-`/setup`, always allowed even at the limit) — a
   *  different user is blocked outright, regardless of their own seat
   *  count. If nobody owns it yet, allowed only if they're under their
   *  (effective) seat limit. Split from [[canAssignSeat]] so this logic is
   *  testable without a database. */
  private[paywall] def canAssignSeatPure(existingOwner: Option[String], currentSeatCount: Int, userId: String, effectiveLimit: Int): Boolean =
    existingOwner match {
      case Some(owner) => owner == userId
      case None => currentSeatCount < effectiveLimit
    }

  /** The `/setup` seat-availability check — reads live seat state. The bot
   *  owner always passes: unlimited seats, same reasoning as
   *  [[callerIsSubscribed]]'s bypass. */
  def canAssignSeat(userId: String, guildId: String, world: String): Boolean =
    patreonNotConfigured || userId == ownerId || canAssignSeatPure(
      patreonSeatRepository.seatFor(guildId, world).map(_.userId),
      patreonSeatRepository.seatsForUser(userId).size,
      userId,
      effectiveSeatLimit(userId)
    )

  /** Assigns (or idempotently reassigns) a seat. Call only after
   *  [[canAssignSeat]] confirmed true. Stops any grace timer that was
   *  running against this world — claiming it onto a live seat is exactly
   *  the "sorted it out" outcome the timer was waiting for, and leaving the
   *  row behind would hand the new owner a deadline they never earned. */
  def assignSeat(userId: String, userName: String, guildId: String, world: String): Unit = {
    patreonSeatRepository.assignSeat(userId, userName, guildId, world, ZonedDateTime.now())
    patreonGraceRepository.clearGrace(guildId, world)
  }

  /** Frees the seat assigned to (guildId, world), if any, and drops any grace
   *  timer with it — this is `/remove`, so there's no longer a setup for a
   *  timer to be counting down against. */
  def releaseSeat(guildId: String, world: String): Unit = {
    patreonSeatRepository.releaseSeat(guildId, world)
    patreonGraceRepository.clearGrace(guildId, world)
  }

  /** True once (guildId, world) has ever been tied to a seat. False means
   *  either it's brand new, or it's a legacy setup from before the seat
   *  system existed — `/setup` needs to tell those apart from a seated world
   *  to offer claiming a legacy one onto a seat, while it still can (a legacy
   *  world keeps running until its grace period runs out — see
   *  `applyRefresh`).
   *
   *  Answers true unconditionally where Patreon isn't configured: there are
   *  no seats on such an install by design, and the honest "no seat" would
   *  otherwise make `/setup` offer to claim one against every world that
   *  already exists — a prompt about a system that isn't running. */
  def hasSeat(guildId: String, world: String): Boolean =
    patreonNotConfigured || patreonSeatRepository.seatFor(guildId, world).isDefined

  /** Frees every seat owned by this user. */
  def releaseAllSeats(userId: String): Unit =
    patreonSeatRepository.releaseAllSeatsForUser(userId)

  /** Every seat owned by this user — the `/patreon` self-service view. */
  def seatsForUser(userId: String): List[com.tibiabot.domain.PatreonSeat] =
    patreonSeatRepository.seatsForUser(userId)

  /** Every seat, for the dashboard's supporters panel — same source
   *  [[refreshAll]] sweeps, just exposed for reading. */
  def allSeats(): List[com.tibiabot.domain.PatreonSeat] = patreonSeatRepository.allSeats()

  /** Every (guildId, world) with a grace timer running, as one bulk read for
   *  the dashboard. [[isActive]] deliberately stays true through the grace
   *  period — that's the whole point — so it alone can't tell the dashboard
   *  a supporter has lapsed until the pause finally lands, a week late. This
   *  is what makes that visible on the sweep that detects it. */
  def worldsInGrace(): Set[(String, String)] =
    patreonGraceRepository.allGrace().map(g => (g.guildId, g.world)).toSet

  /** Sets (or replaces) a user's seat-count adjustment — the dashboard's
   *  "grant extra seats" admin action. Arbitrary, admin's discretion; may be
   *  negative — see [[effectiveSeatLimit]] for the floor. */
  def setExtraSeats(userId: String, extraSeats: Int): Unit =
    patreonSeatOverrideRepository.setExtraSeats(userId, extraSeats, ZonedDateTime.now())

  /** Every user with a non-default seat adjustment, for the dashboard's
   *  supporters panel — same source [[effectiveSeatLimit]] reads, just
   *  exposed in bulk to avoid a per-supporter lookup. */
  def allExtraSeats(): Map[String, Int] = patreonSeatOverrideRepository.allExtraSeats()

  /** Called after a Patreon member sync: Patreon becomes the source of truth for
   *  anyone it now has a confirmed Discord link to, so a dashboard-granted
   *  adjustment given as a temporary bridge is reclaimed to the flat default.
   *  Clears only *positive* adjustments — the bypass this undoes — matching
   *  `callerIsSubscribed`'s rule. Returns the ids cleared, for the caller to log.
   *
   *  Unconditional by design: it is the caller's job to pass only ids *newly*
   *  linked this sync (see BotApp.syncPatreonMembers). Passing every linked id
   *  each cycle would wipe out a legitimate bonus granted later. */
  def reclaimOverridesFromPatreon(linkedDiscordUserIds: Iterable[String]): Set[String] = {
    val current = patreonSeatOverrideRepository.allExtraSeats()
    val toClear = linkedDiscordUserIds.filter(id => current.getOrElse(id, 0) > 0).toSet
    toClear.foreach(id => patreonSeatOverrideRepository.setExtraSeats(id, 0, ZonedDateTime.now()))
    toClear
  }

  /** Only reachable when (guildId, world) is currently paused. The new
   *  claimant needs no relation to the lapsed owner — just room under their
   *  own (effective) seat limit. Reclaiming a seat you already (still) own
   *  is always allowed, even at the limit — same "no net change" reasoning
   *  as [[canAssignSeatPure]]'s idempotent-reclaim case. Deliberately not a
   *  relaxed [[canAssignSeatPure]]: that method's "someone else owns it ->
   *  blocked" rule is what stops a plain `/setup` from stealing an *active*
   *  seat, but reassignment only ever runs against a *paused* one — the
   *  lapsed owner having it isn't a block condition here, it's the whole
   *  point. */
  private[paywall] def canReassignSeatPure(newUserAlreadyOwnsIt: Boolean, newUserSeatCount: Int, effectiveLimit: Int): Boolean =
    newUserAlreadyOwnsIt || newUserSeatCount < effectiveLimit

  /** The `/setup`-on-a-paused-world reassignment-availability check. The
   *  paused gate still applies to the bot owner (reassignment is only ever
   *  meaningful for a paused seat regardless of who's claiming it) — only
   *  the seat-limit portion is bypassed for them, same as [[canAssignSeat]]. */
  def canReassignSeat(newUserId: String, guildId: String, world: String): Boolean =
    !isActive(guildId, world) && (newUserId == ownerId || canReassignSeatPure(
      patreonSeatRepository.seatFor(guildId, world).exists(_.userId == newUserId),
      patreonSeatRepository.seatsForUser(newUserId).size,
      effectiveSeatLimit(newUserId)
    ))

  /** Reassigns and reactivates immediately, rather than waiting for the next
   *  periodic [[refreshAll]] sweep — [[canReassignSeat]] already confirmed
   *  the new owner's live subscription status moments before this runs.
   *  [[assignSeat]] clears the expired grace timer that paused it. */
  def reassignSeat(newUserId: String, newUserName: String, guildId: String, world: String): Unit = {
    assignSeat(newUserId, newUserName, guildId, world)
    activeStatus.put((guildId, world), true)
  }

  /** Given every configured (guildId, world) and its seat owner if it has one —
   *  `None` covers both a never-seated legacy setup and a released seat — updates
   *  the active-status map and returns the pairs whose grace has just run out and
   *  which have not been announced.
   *
   *  A setup is compliant when it has a seat *and* that seat's owner checks out.
   *  Anything else starts or keeps a grace timer and stays fully active until
   *  `graceDays` have passed, so a lapsed subscription and a never-seated setup
   *  follow one path and a transient `checkUser` blip pauses nobody if it clears
   *  in the window. Becoming compliant deletes timer, deadline and `notified`
   *  together, so a later lapse gets a fresh window and its own notice.
   *
   *  Announcing is gated on the persisted `notified` flag rather than an in-memory
   *  active -> inactive transition: the map is rebuilt every restart (see
   *  `hydrateFromGrace`), so a transition test would re-announce after a deploy.
   *
   *  Pure aside from the map and the grace repository, so the timing logic is
   *  testable with a fake `checkUser` and no JDA. */
  private[paywall] def applyRefresh(setups: List[(String, String, Option[String])], checkUser: String => Boolean, now: ZonedDateTime): List[(String, String)] = {
    val timers = patreonGraceRepository.allGrace().map(g => (g.guildId, g.world) -> g).toMap
    setups.flatMap { case (guildId, world, ownerId) =>
      val key = (guildId, world)
      if (ownerId.exists(checkUser)) {
        if (timers.contains(key)) patreonGraceRepository.clearGrace(guildId, world)
        activeStatus.put(key, true)
        None
      } else {
        val timer = timers.get(key)
        // No row yet means this sweep is the first to see it non-compliant, so
        // the clock starts now — and the deadline below is measured from that
        // same `now`, matching the row beginGrace just wrote.
        if (timer.isEmpty) patreonGraceRepository.beginGrace(guildId, world, now)
        val startedAt = timer.map(_.started).getOrElse(now)
        val expired = !startedAt.plusDays(graceDays.toLong).isAfter(now)
        activeStatus.put(key, !expired)
        if (expired && !timer.exists(_.notified)) {
          patreonGraceRepository.markNotified(guildId, world)
          Some(key)
        } else None
      }
    }
  }

  /** Periodic re-check across every configured (guild, world). `setups` comes from
   *  the caller, since the seat table knows only the seated ones and the point is
   *  to catch the rest. Fires `onLapsed` once per pair whose grace has just run
   *  out, never twice for the same lapse (see `applyRefresh`), with the seat
   *  owner's id and username — both empty for a never-seated world.
   *
   *  `onStillLapsed` fires for every pair left paused *without* announcing. Not a
   *  second notification: it is the caller's chance to restore the paused
   *  presentation (channel name, online-list notice) for a world that lost it. It
   *  fires every sweep while a world stays paused, so it must be idempotent.
   *
   *  Sweeps only when there is a Patreon snapshot worth judging against — see
   *  `patreonSnapshotSize`. */
  def refreshAll(setups: List[(String, String)])(
    onLapsed: (Guild, String, String, String) => Unit,
    onStillLapsed: (Guild, String) => Unit
  ): Unit = patreonSnapshotSize() match {
    // Nothing to check against. Every owner would read as lapsed at once and
    // the sweep would start a grace timer for the whole install, pausing all
    // of it `graceDays` later — the delay being what makes it so hard to
    // trace back to a Patreon integration that was never running. So don't
    // sweep, and undo any timer a sweep already started on this basis.
    case Some(0) => resumeOnEmptySnapshot()
    // Couldn't tell. Same fail-open rule the rest of this class runs on: a
    // database blip costs a sweep, never anyone's tracking. Already-paused
    // worlds stay paused (activeStatus is untouched), so this defers the
    // decision rather than making one.
    case None => logger.warn("Paywall: skipping the sweep — the Patreon snapshot could not be read, so nothing can be judged lapsed")
    case Some(_) => sweep(setups)(onLapsed, onStillLapsed)
  }

  /** Grace timers written while there was no snapshot to check against are
   *  not records of anyone lapsing, so they're dropped rather than left to
   *  expire — otherwise an install that already paused stays paused for good,
   *  since [[refreshAll]] no longer sweeps and nothing else clears a timer.
   *
   *  Also makes the recovery automatic in the other direction: an integration
   *  that breaks long enough to pause worlds resumes them on the first sweep
   *  after it's noticed, without waiting on a good snapshot first. Worlds are
   *  marked active here as well as cleared, since [[isActive]] is read from
   *  the map on every send-loop tick and only `hydrateFromGrace` and
   *  [[applyRefresh]] otherwise write it. */
  private def resumeOnEmptySnapshot(): Unit =
    try {
      val timers = patreonGraceRepository.allGrace()
      timers.foreach { g =>
        patreonGraceRepository.clearGrace(g.guildId, g.world)
        activeStatus.put((g.guildId, g.world), true)
      }
      if (timers.nonEmpty)
        logger.warn(s"Paywall: the Patreon snapshot is empty, so no subscription can be verified — dropped ${timers.size} grace timer(s) and resumed those worlds rather than pausing the whole install")
    } catch {
      case ex: Throwable => logger.warn("Paywall: could not clear the grace table against an empty Patreon snapshot", ex)
    }

  private def sweep(setups: List[(String, String)])(
    onLapsed: (Guild, String, String, String) => Unit,
    onStillLapsed: (Guild, String) => Unit
  ): Unit = {
    val seats = patreonSeatRepository.allSeats().map(s => (s.guildId, s.world) -> s).toMap
    // One REST lookup per distinct seat owner, not per seat: a supporter with
    // five worlds set up is one subscription, and the sweep now walks every
    // configured world rather than only the seated ones.
    val checked = scala.collection.mutable.Map.empty[String, Boolean]
    def checkOnce(userId: String): Boolean = checked.getOrElseUpdate(userId, callerIsSubscribed(userId))

    val lapsed = applyRefresh(
      setups.map { case (guildId, world) => (guildId, world, seats.get((guildId, world)).map(_.userId)) },
      checkOnce,
      ZonedDateTime.now()
    )
    lapsed.foreach { case (guildId, world) =>
      val guild = discordGateway.guildById(guildId)
      val seat = seats.get((guildId, world))
      val userId = seat.map(_.userId).getOrElse("")
      val userName = seat.map(_.userName).getOrElse("")
      if (guild != null) onLapsed(guild, world, userId, userName)
    }
    // Read back off the map applyRefresh has just written rather than having it
    // return a second list: after the sweep, `isActive` is the authoritative
    // answer for every pair in `setups`, and "paused but not announced this
    // sweep" is exactly the difference between that and `lapsed`.
    val lapsedNow = lapsed.toSet
    setups.foreach { case (guildId, world) =>
      if (!lapsedNow.contains((guildId, world)) && !isActive(guildId, world)) {
        val guild = discordGateway.guildById(guildId)
        if (guild != null) onStillLapsed(guild, world)
      }
    }
  }
}
