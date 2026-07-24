package com.tibiabot.paywall

import com.tibiabot.discord.DiscordGateway
import com.tibiabot.persistence.PatreonSeatRepository
import net.dv8tion.jda.api.entities.Guild

import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

/** Ties ongoing bot activity to a Patreon subscription via a seat system:
 *  each supporter gets `seatLimit` seats, and running `/setup` for a (guild,
 *  world) pair assigns one — see [[com.tibiabot.setup.ChannelService]]. A
 *  (guild, world) pair with no assigned seat (everything that existed before
 *  this feature, or a world nobody's set up under this model yet) is always
 *  treated as active — the grandfather case, so pre-existing tracking is
 *  unaffected. */
final class PaywallService(
  discordGateway: DiscordGateway,
  patreonSeatRepository: PatreonSeatRepository,
  supportGuildId: String,
  patreonRoleId: String,
  seatLimit: Int,
  ownerId: String
) {
  private val activeStatus = new ConcurrentHashMap[(String, String), Boolean]()

  /** Cheap, synchronous — consulted on every send-loop iteration in
   *  TibiaBot. Defaults true (fail-open): a (guild, world) pair not yet
   *  checked, or one whose check errored transiently, is never silently cut
   *  off. */
  def isActive(guildId: String, world: String): Boolean = activeStatus.getOrDefault((guildId, world), true)

  /** Does this member's role list include the Patreon role? Pure, so it's
   *  testable without a live JDA Member. */
  private[paywall] def hasPatreonRole(memberRoleIds: List[String]): Boolean =
    memberRoleIds.contains(patreonRoleId)

  /** Blocking REST lookup against the support guild — the `/setup` command
   *  gate calls this directly; `refreshAll` calls it once per distinct seat
   *  owner. The bot owner always passes, regardless of role — so they can
   *  always `/setup`, and any seat assigned to them never lapses via the
   *  periodic recheck either, since that reuses this same check. Otherwise:
   *  not a member of the support guild (or a lookup failure) reads as
   *  "not subscribed", never as an error to propagate. A REST lookup rather
   *  than JDA's member cache deliberately — caching every member of the
   *  support guild would need the privileged GUILD_MEMBERS intent (Discord's
   *  bot-verification process past 100 guilds) for what's an infrequent,
   *  low-volume check. */
  def callerIsSubscribed(userId: String): Boolean = {
    if (userId == ownerId) true
    else {
      val supportGuild = discordGateway.guildById(supportGuildId)
      if (supportGuild == null) false
      else try {
        val member = supportGuild.retrieveMemberById(userId).complete()
        member != null && hasPatreonRole(member.getRoles.asScala.map(_.getId).toList)
      } catch {
        case _: Throwable => false
      }
    }
  }

  /** Pure — can this user claim a seat for (guildId, world)? If someone
   *  already owns that pair, only that same person may (re-)claim it
   *  (idempotent re-`/setup`, always allowed even at the limit) — a
   *  different user is blocked outright, regardless of their own seat
   *  count. If nobody owns it yet, allowed only if they're under their seat
   *  limit. Split from [[canAssignSeat]] so this logic is testable without a
   *  database. */
  private[paywall] def canAssignSeatPure(existingOwner: Option[String], currentSeatCount: Int, userId: String): Boolean =
    existingOwner match {
      case Some(owner) => owner == userId
      case None => currentSeatCount < seatLimit
    }

  /** The `/setup` seat-availability check — reads live seat state. The bot
   *  owner always passes: unlimited seats, same reasoning as
   *  [[callerIsSubscribed]]'s bypass. */
  def canAssignSeat(userId: String, guildId: String, world: String): Boolean =
    userId == ownerId || canAssignSeatPure(
      patreonSeatRepository.seatFor(guildId, world).map(_.userId),
      patreonSeatRepository.seatsForUser(userId).size,
      userId
    )

  /** Assigns (or idempotently reassigns) a seat. Call only after
   *  [[canAssignSeat]] confirmed true. */
  def assignSeat(userId: String, userName: String, guildId: String, world: String): Unit =
    patreonSeatRepository.assignSeat(userId, userName, guildId, world, ZonedDateTime.now())

  /** Frees the seat assigned to (guildId, world), if any. */
  def releaseSeat(guildId: String, world: String): Unit =
    patreonSeatRepository.releaseSeat(guildId, world)

  /** True once (guildId, world) has ever been tied to a seat. False means
   *  either it's brand new, or it's a legacy setup from before the seat
   *  system existed — see [[isActive]]'s grandfather rule, which treats both
   *  the same (always active) but which `/setup` needs to tell apart to
   *  offer claiming a legacy world onto a seat. */
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

  /** Only reachable when (guildId, world) is currently paused. The new
   *  claimant needs no relation to the lapsed owner — just room under their
   *  own seat limit. Reclaiming a seat you already (still) own is always
   *  allowed, even at the limit — same "no net change" reasoning as
   *  [[canAssignSeatPure]]'s idempotent-reclaim case. Deliberately not a
   *  relaxed [[canAssignSeatPure]]: that method's "someone else owns it ->
   *  blocked" rule is what stops a plain `/setup` from stealing an *active*
   *  seat, but reassignment only ever runs against a *paused* one — the
   *  lapsed owner having it isn't a block condition here, it's the whole
   *  point. */
  private[paywall] def canReassignSeatPure(newUserAlreadyOwnsIt: Boolean, newUserSeatCount: Int): Boolean =
    newUserAlreadyOwnsIt || newUserSeatCount < seatLimit

  /** The `/setup`-on-a-paused-world reassignment-availability check. The
   *  paused gate still applies to the bot owner (reassignment is only ever
   *  meaningful for a paused seat regardless of who's claiming it) — only
   *  the seat-limit portion is bypassed for them, same as [[canAssignSeat]]. */
  def canReassignSeat(newUserId: String, guildId: String, world: String): Boolean =
    !isActive(guildId, world) && (newUserId == ownerId || canReassignSeatPure(
      patreonSeatRepository.seatFor(guildId, world).exists(_.userId == newUserId),
      patreonSeatRepository.seatsForUser(newUserId).size
    ))

  /** Reassigns and reactivates immediately, rather than waiting for the next
   *  periodic [[refreshAll]] sweep — [[canReassignSeat]] already confirmed
   *  the new owner's live subscription status moments before this runs. */
  def reassignSeat(newUserId: String, newUserName: String, guildId: String, world: String): Unit = {
    patreonSeatRepository.assignSeat(newUserId, newUserName, guildId, world, ZonedDateTime.now())
    activeStatus.put((guildId, world), true)
  }

  /** Given each seat's (guildId, world, userId) and a way to check a user's
   *  live status, updates the active-status map and returns the (guildId,
   *  world) pairs that just flipped active -> inactive on *this* call — not
   *  pairs that were already inactive. Pure aside from the map mutation, so
   *  the transition-detection logic (the part worth getting right) is
   *  testable with a fake `checkUser`, no JDA or database involved. */
  private[paywall] def applyRefresh(seats: List[(String, String, String)], checkUser: String => Boolean): List[(String, String)] =
    seats.flatMap { case (guildId, world, userId) =>
      val key = (guildId, world)
      val wasActive = isActive(guildId, world)
      val nowActive = checkUser(userId)
      activeStatus.put(key, nowActive)
      if (wasActive && !nowActive) Some(key) else None
    }

  /** Periodic background re-check across every assigned seat. Fires
   *  `onLapsed` once per (guild, world) that just transitioned to inactive,
   *  not on every recheck of an already-lapsed pair — with the seat owner's
   *  id and username snapshot, for the lapse notice. */
  def refreshAll(onLapsed: (Guild, String, String, String) => Unit): Unit = {
    val seats = patreonSeatRepository.allSeats()
    val lapsed = applyRefresh(seats.map(s => (s.guildId, s.world, s.userId)), callerIsSubscribed)
    lapsed.foreach { case (guildId, world) =>
      val guild = discordGateway.guildById(guildId)
      val seat = seats.find(s => s.guildId == guildId && s.world == world)
      val userId = seat.map(_.userId).getOrElse("")
      val userName = seat.map(_.userName).getOrElse("")
      if (guild != null) onLapsed(guild, world, userId, userName)
    }
  }
}
