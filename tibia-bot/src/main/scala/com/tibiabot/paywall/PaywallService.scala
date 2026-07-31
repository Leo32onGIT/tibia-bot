package com.tibiabot.paywall

import com.tibiabot.discord.DiscordGateway
import com.tibiabot.persistence.{PatreonGraceRepository, PatreonMemberRepository, PatreonSeatOverrideRepository, PatreonSeatRepository}
import net.dv8tion.jda.api.entities.Guild

import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

/** Ties ongoing bot activity to a Patreon subscription via a seat system:
 *  each supporter gets `seatLimit` seats (adjustable per-user via
 *  [[effectiveSeatLimit]] — a dashboard-granted override on top of the flat
 *  default), and running `/setup` for a (guild, world) pair assigns one —
 *  see [[com.tibiabot.setup.ChannelService]]. A positive seat-count
 *  adjustment also bypasses the underlying subscription check entirely (see
 *  [[callerIsSubscribed]]) — the dashboard's "grant extra seats" admin action
 *  doubles as a full paywall override for that one person, not just a
 *  seat-count bump.
 *
 *  Whether someone is subscribed is Patreon's answer, read from the synced
 *  campaign snapshot in `PatreonMemberRepository` — not, as it once was, a
 *  Patreon-granted role in the support Discord. See [[callerIsSubscribed]].
 *  The support guild is still consulted for one unrelated thing: resolving a
 *  username to a Discord id for the dashboard (see [[findUserIdByUsername]]).
 *
 *  Nothing is cut off the moment it stops checking out. A configured world
 *  whose seat owner has lapsed — *and* one that was never tied to a seat at
 *  all (a legacy setup, grandfathered in from before the seat system
 *  existed) — instead starts a `graceDays` timer, kept in
 *  `PatreonGraceRepository` so it survives restarts, and keeps running
 *  untouched until that runs out. Resolving the subscription at any point
 *  before the deadline stops the clock and nothing ever happens. See
 *  [[applyRefresh]] — that one rule covers both cases, so an orphaned setup
 *  and a cancelled one are on identical footing. */
final class PaywallService(
  discordGateway: DiscordGateway,
  patreonSeatRepository: PatreonSeatRepository,
  patreonSeatOverrideRepository: PatreonSeatOverrideRepository,
  patreonGraceRepository: PatreonGraceRepository,
  patreonMemberRepository: PatreonMemberRepository,
  supportGuildId: String,
  seatLimit: Int,
  graceDays: Int,
  ownerId: String
) {
  private val activeStatus = new ConcurrentHashMap[(String, String), Boolean]()

  /** Cheap, synchronous — consulted on every send-loop iteration in
   *  TibiaBot. Defaults true (fail-open): a (guild, world) pair not yet
   *  checked, or one whose check errored transiently, is never silently cut
   *  off. That default also covers the window between startup and the first
   *  [[refreshAll]] sweep; an expired grace timer is durable, so that sweep
   *  puts an already-paused world straight back to inactive (quietly — see
   *  [[applyRefresh]]'s `notified` handling). */
  def isActive(guildId: String, world: String): Boolean = activeStatus.getOrDefault((guildId, world), true)

  /** The subscription check — the `/setup` command gate calls this directly;
   *  `refreshAll` calls it once per distinct seat owner.
   *
   *  Answered from Patreon itself: the synced campaign snapshot (see
   *  patreonapi.PatreonApiClient) is searched for this Discord account, and
   *  they pass if Patreon reports them an active patron. This used to be a
   *  REST lookup for a Patreon-granted role in the support guild, which
   *  meant a real supporter still failed if they'd left that Discord, or if
   *  Patreon's own role sync hadn't fired. Reading Patreon directly drops
   *  both of those failure modes: subscribe, connect Discord on Patreon's
   *  side, and the next `/setup` works.
   *
   *  Two bypasses come first and skip Patreon entirely. The bot owner always
   *  passes — so they can always `/setup`, and any seat assigned to them
   *  never lapses via the periodic recheck either, since that reuses this
   *  same check. So does a user with a *positive* dashboard-granted seat
   *  adjustment: an admin using "grant extra seats" is treated as an explicit
   *  override of the whole paywall for that person, not just their seat count
   *  (a zero or negative adjustment does not grant this bypass).
   *
   *  Unknown account, no linked Discord on Patreon's side, a lapsed or
   *  declined pledge, or a failed database read all read as "not
   *  subscribed", never as an error to propagate. That answer no longer cuts
   *  anyone off on its own — it starts the grace period (see
   *  [[applyRefresh]]), so a bad sync or a database blip costs days of
   *  headroom rather than anyone's tracking. */
  def callerIsSubscribed(userId: String): Boolean =
    if (userId == ownerId || patreonSeatOverrideRepository.extraSeatsFor(userId) > 0) true
    else try patreonMemberRepository.isActivePatron(userId)
    catch { case _: Throwable => false }

  /** Resolves a Discord username to that member's user id, searched within
   *  the support guild — the dashboard's "grant extra seats" admin action
   *  takes a username rather than asking the admin to go find a raw id.
   *  Case-insensitive exact match; None if nobody matches, there are
   *  multiple guild nicknames sharing a prefix with no exact match, or the
   *  support guild isn't reachable. Uses retrieveMembersByPrefix — a scoped,
   *  query-based gateway search, not the full member cache — so this needs no
   *  privileged GUILD_MEMBERS intent (Discord's bot-verification process past
   *  100 guilds) for what's a rare, admin-initiated lookup. The only thing
   *  left that touches the support guild: it plays no part in deciding who's
   *  subscribed. */
  def findUserIdByUsername(username: String): Option[String] = {
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
    userId == ownerId || canAssignSeatPure(
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
   *  [[applyRefresh]]). */
  def hasSeat(guildId: String, world: String): Boolean =
    patreonSeatRepository.seatFor(guildId, world).isDefined

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

  /** Called after a Patreon member sync — Patreon becomes the source of
   *  truth for anyone it now has a confirmed Discord link to, so a
   *  dashboard-granted seat adjustment an admin gave that person as a
   *  temporary bridge (before Patreon "picked them up" — see
   *  [[callerIsSubscribed]]'s positive-override bypass) is reclaimed back to
   *  the flat default. Only clears a *positive* adjustment — the actual
   *  bypass/bonus this is undoing — a zero or negative one is left alone,
   *  same "positive only" rule `callerIsSubscribed` itself uses. Returns the
   *  ids actually cleared, for the caller to log.
   *
   *  This method itself is a dumb, unconditional "clear these ids' positive
   *  overrides" — it's the caller's job to pass only ids *newly* linked this
   *  sync (see BotApp.syncPatreonMembers), not every currently-linked id.
   *  Passing an already-linked id here every cycle would silently wipe out
   *  a legitimate bonus later granted to an existing supporter — this is
   *  meant to fire once, at the hand-off moment, not repeatedly. */
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

  /** Given every configured (guildId, world) and its seat owner if it has one
   *  — `None` covers both a legacy setup that was never seated and one whose
   *  seat has since been released — updates the active-status map and returns
   *  the pairs whose grace period has just run out and which have not been
   *  announced yet.
   *
   *  A setup is compliant when it has a seat *and* that seat's owner still
   *  checks out. Anything else starts (or keeps) a grace timer, and stays
   *  fully active until `graceDays` have passed since the first sweep that
   *  saw it that way — so a lapsed subscription and a never-seated legacy
   *  setup follow the same path, and a transient blip in `checkUser` (a
   *  Discord outage, say) can no longer pause anyone as long as it clears
   *  within the window. Becoming compliant again at any point deletes the
   *  timer, deadline and `notified` flag together, so a later lapse gets a
   *  fresh window and its own notice.
   *
   *  Announcing is gated on the persisted `notified` flag rather than on an
   *  in-memory active -> inactive transition: the active-status map is empty
   *  after a restart, so a transition test would re-announce every already
   *  paused world on the first sweep after every deploy.
   *
   *  Pure aside from the map mutation and the grace repository, so the
   *  timing logic (the part worth getting right) is testable with a fake
   *  `checkUser` and an in-memory grace repository, no JDA involved. */
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

  /** Periodic background re-check across every configured (guild, world) —
   *  `setups`, supplied by the caller, since the seat table only knows about
   *  the ones that have a seat and the whole point is to also catch the ones
   *  that don't. Fires `onLapsed` once per pair whose grace period has just
   *  run out, never twice for the same lapse (see [[applyRefresh]]) — with
   *  the seat owner's id and username snapshot for the notice, both empty
   *  when the world never had a seat at all and there's nobody to address. */
  def refreshAll(setups: List[(String, String)])(onLapsed: (Guild, String, String, String) => Unit): Unit = {
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
  }
}
