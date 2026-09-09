package com.tibiabot.notifications

import com.tibiabot.discord.{DiscordGateway, RateLimitedSender}
import com.tibiabot.domain.{BountySub, MasslogSub, NotifyDecision}
import com.tibiabot.persistence.NotifyRepository
import com.tibiabot.presentation.NotifyEmbeds
import com.tibiabot.tracking.MasslogDetector
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.exceptions.{ErrorHandler, ErrorResponseException}
import net.dv8tion.jda.api.requests.ErrorResponse

import java.time.Instant
import scala.collection.concurrent.TrieMap

/** The mass-log and bounty DM subscriptions: what is stored, who is due, and the
 *  DMs themselves.
 *
 *  ==Why the subscriptions are cached==
 *  The online-list sweep asks "does anyone here care?" every fifteen seconds per
 *  guild per world, and the answer is almost always no — a query per guild per
 *  sweep to learn nothing. The whole (small) set is loaded at startup and written
 *  through: database first, cache after, so a failed write cannot leave the cache
 *  claiming a subscription that is not stored.
 *
 *  ==Concurrency==
 *  Reads come from each world's sweep thread, writes from JDA's interaction pool —
 *  hence TrieMap rather than a plain mutable map. */
final class NotifyService(
  repository: NotifyRepository,
  discordGateway: DiscordGateway,
  outboundSender: RateLimitedSender
) extends StrictLogging {

  private val masslogSubs = TrieMap.empty[Long, MasslogSub]
  private val bountySubs = TrieMap.empty[Long, BountySub]

  /** Load what's stored. Called once during startup, before the world streams
   *  begin — a sweep that runs first simply finds nothing and does nothing. */
  def load(): Unit = {
    try {
      repository.allMasslog().foreach(sub => masslogSubs.put(sub.id, sub))
      repository.allBounty().foreach(sub => bountySubs.put(sub.id, sub))
      logger.info(s"Loaded ${masslogSubs.size} mass-log and ${bountySubs.size} bounty DM subscriptions")
    } catch {
      // A bot that can't read these should still track worlds; the feature is
      // off until the next restart rather than the process failing to come up.
      case ex: Throwable => logger.error("Failed to load notification subscriptions", ex)
    }
  }

  // --- reads -------------------------------------------------------------

  private def sameWorld(a: String, b: String): Boolean = a.equalsIgnoreCase(b)

  def masslogFor(guildId: String, world: String): List[MasslogSub] =
    masslogSubs.values.filter(s => s.guildId == guildId && sameWorld(s.world, world)).toList

  def bountiesFor(guildId: String, world: String): List[BountySub] =
    bountySubs.values.filter(s => s.guildId == guildId && sameWorld(s.world, world)).toList

  def bountiesFor(guildId: String, world: String, userId: String): List[BountySub] =
    bountiesFor(guildId, world).filter(_.userId == userId).sortBy(_.character.toLowerCase)

  def masslogFor(guildId: String, world: String, userId: String): Option[MasslogSub] =
    masslogFor(guildId, world).find(_.userId == userId)

  /** Every character anyone is watching on this world, lowercased — the set the
   *  sweep intersects the roster against. Spans guilds, because the roster does:
   *  two servers watching the same character is one presence question. */
  def bountyTargets(world: String): Set[String] =
    bountySubs.values.collect { case s if sameWorld(s.world, world) => s.character.toLowerCase }.toSet

  def masslogById(id: Long): Option[MasslogSub] = masslogSubs.get(id)
  def bountyById(id: Long): Option[BountySub] = bountySubs.get(id)

  // --- writes ------------------------------------------------------------

  def subscribeMasslog(guildId: String, world: String, userId: String, threshold: Int): MasslogSub = {
    val stored = repository.upsertMasslog(guildId, world, userId, threshold)
    masslogSubs.put(stored.id, stored)
    stored
  }

  /** Adding a bounty on a character somebody already watches adjusts that one
   *  rather than holding two: the unique index ignores case, so the upsert
   *  returns the same row id and this replaces the cached copy in place. */
  def addBounty(guildId: String, world: String, userId: String, character: String, cooldownMinutes: Int): BountySub = {
    val stored = repository.upsertBounty(guildId, world, userId, character, cooldownMinutes)
    bountySubs.put(stored.id, stored)
    stored
  }

  def setMasslogEnabled(id: Long, enabled: Boolean): Option[MasslogSub] =
    masslogSubs.get(id).map { sub =>
      repository.setMasslogEnabled(id, enabled)
      val updated = sub.copy(enabled = enabled, mutedUntil = None)
      masslogSubs.put(id, updated)
      updated
    }

  def setBountyEnabled(id: Long, enabled: Boolean): Option[BountySub] =
    bountySubs.get(id).map { sub =>
      repository.setBountyEnabled(id, enabled)
      val updated = sub.copy(enabled = enabled, mutedUntil = None)
      bountySubs.put(id, updated)
      updated
    }

  def muteMasslog(id: Long, until: Instant): Option[MasslogSub] =
    masslogSubs.get(id).map { sub =>
      repository.muteMasslog(id, until)
      val updated = sub.copy(mutedUntil = Some(until))
      masslogSubs.put(id, updated)
      updated
    }

  def muteBounty(id: Long, until: Instant): Option[BountySub] =
    bountySubs.get(id).map { sub =>
      repository.muteBounty(id, until)
      val updated = sub.copy(mutedUntil = Some(until))
      bountySubs.put(id, updated)
      updated
    }

  def setMasslogThreshold(id: Long, threshold: Int): Option[MasslogSub] =
    masslogSubs.get(id).map { sub =>
      repository.setMasslogThreshold(id, threshold)
      val updated = sub.copy(threshold = threshold)
      masslogSubs.put(id, updated)
      updated
    }

  /** Stop watching one character. Returns the row that went, so the caller can
   *  say whose alerts have just stopped — after this it is nowhere to be read.
   *
   *  Database first, cache after, like every other write here: a delete that
   *  fails must leave the subscription still firing rather than leave the sweep
   *  and the stored rows disagreeing until the next restart. */
  def removeBounty(id: Long): Option[BountySub] =
    bountySubs.get(id).flatMap { _ =>
      try {
        repository.deleteBounty(id)
        bountySubs.remove(id)
      } catch {
        case ex: Throwable =>
          logger.warn(s"Failed to delete bounty subscription $id", ex)
          None
      }
    }

  /** Same contract as [[removeBounty]], for the one mass-log subscription a user
   *  holds on a world. Deleted rather than switched off, so that "I do not want
   *  this" leaves nothing behind claiming otherwise — the role comes off with it
   *  and the DM offers the way back. */
  def removeMasslog(id: Long): Option[MasslogSub] =
    masslogSubs.get(id).flatMap { _ =>
      try {
        repository.deleteMasslog(id)
        masslogSubs.remove(id)
      } catch {
        case ex: Throwable =>
          logger.warn(s"Failed to delete mass-log subscription $id", ex)
          None
      }
    }

  /** Drop everything one user holds in one guild, for a user who has left it.
   *
   *  Everything rather than the one subscription whose DM failed: leaving takes
   *  all of them out of reach at once, and dropping them one failed alert at a
   *  time would keep trying the rest for as long as the guild kept generating
   *  alerts they can no longer be told about. */
  def forgetUser(guildId: String, userId: String): Unit = {
    try repository.deleteUser(guildId, userId)
    catch {
      case ex: Throwable =>
        logger.warn(s"Failed to delete notification subscriptions for user '$userId' in guild '$guildId'", ex)
        return
    }
    val dropped = masslogSubs.count { case (_, s) => s.guildId == guildId && s.userId == userId } +
      bountySubs.count { case (_, s) => s.guildId == guildId && s.userId == userId }
    masslogSubs.filterInPlace((_, sub) => !(sub.guildId == guildId && sub.userId == userId))
    bountySubs.filterInPlace((_, sub) => !(sub.guildId == guildId && sub.userId == userId))
    if (dropped > 0) logger.info(s"Dropped $dropped notification subscription(s) for '$userId', who has left guild '$guildId'")
  }

  def forgetGuild(guildId: String): Unit = {
    try repository.deleteGuild(guildId)
    catch { case ex: Throwable => logger.warn(s"Failed to delete notification subscriptions for guild '$guildId'", ex) }
    masslogSubs.filterInPlace((_, sub) => sub.guildId != guildId)
    bountySubs.filterInPlace((_, sub) => sub.guildId != guildId)
  }

  def forgetWorld(guildId: String, world: String): Unit = {
    try repository.deleteWorld(guildId, world)
    catch { case ex: Throwable => logger.warn(s"Failed to delete notification subscriptions for '$world' in guild '$guildId'", ex) }
    masslogSubs.filterInPlace((_, sub) => !(sub.guildId == guildId && sameWorld(sub.world, world)))
    bountySubs.filterInPlace((_, sub) => !(sub.guildId == guildId && sameWorld(sub.world, world)))
  }

  // --- delivery ----------------------------------------------------------

  /** A mass log just happened on this world for this guild. Sends to whoever's
   *  threshold it clears and isn't off, muted or inside their cooldown.
   *
   *  `zapCount` is the online list's own count of enemies inside
   *  [[com.tibiabot.tracking.MasslogDetector.RecentLoginSeconds]] — the people
   *  wearing the `:zap:`. */
  def onMasslog(guildId: String, world: String, guildName: String, zapCount: Int, enemiesOnline: Int): Unit = {
    val now = Instant.now()
    masslogFor(guildId, world).foreach { sub =>
      val clears = zapCount >= sub.threshold
      if (clears && NotifyDecision.due(sub.enabled, sub.mutedUntil, sub.lastNotified, MasslogDetector.NotifyCooldownMinutes, now)) {
        markMasslogNotified(sub, now)
        send(
          sub.userId,
          sub.guildId,
          NotifyEmbeds.masslogDm(world, guildName, zapCount, enemiesOnline, sub.threshold),
          NotifyEmbeds.masslogControls(sub.copy(lastNotified = Some(now)))
        )
      }
    }
  }

  /** A watched character just came online. Only the subscriptions naming that
   *  character in this guild are touched. */
  def onBountyLogin(guildId: String, world: String, guildName: String, character: String, level: Int, vocation: String): Unit = {
    val now = Instant.now()
    bountiesFor(guildId, world)
      .filter(_.character.equalsIgnoreCase(character))
      .foreach { sub =>
        if (NotifyDecision.due(sub.enabled, sub.mutedUntil, sub.lastNotified, sub.cooldownMinutes, now)) {
          markBountyNotified(sub, now)
          send(
            sub.userId,
            sub.guildId,
            NotifyEmbeds.bountyDm(world, guildName, character, level, vocation),
            NotifyEmbeds.bountyControls(sub.copy(lastNotified = Some(now)))
          )
        }
      }
  }

  private def markMasslogNotified(sub: MasslogSub, at: Instant): Unit = {
    // Stamped *before* the DM is queued, not in its callback: the sweep that
    // follows must already see the cooldown, or a slow send becomes a second
    // message about the same mass log.
    masslogSubs.put(sub.id, sub.copy(lastNotified = Some(at)))
    try repository.markMasslogNotified(sub.id, at)
    catch { case ex: Throwable => logger.warn(s"Failed to stamp mass-log notification ${sub.id}", ex) }
  }

  private def markBountyNotified(sub: BountySub, at: Instant): Unit = {
    bountySubs.put(sub.id, sub.copy(lastNotified = Some(at)))
    try repository.markBountyNotified(sub.id, at)
    catch { case ex: Throwable => logger.warn(s"Failed to stamp bounty notification ${sub.id}", ex) }
  }

  /** Queue a DM on the shared background lane, the same one the boosted
   *  server-save DMs use — these are per-user messages that must never compete
   *  with deaths or online-list edits for REST budget.
   *
   *  A failure is not counted towards giving up, the way the boosted list counts
   *  its own: a run of failures cannot tell "has closed their DMs" from "is no
   *  longer here", and those want opposite answers. Closed DMs are the user's
   *  business to reopen and the subscription should wait for them; a user who has
   *  left the guild can never be told anything by it again.
   *
   *  So the failure asks instead of counting — see [[dropIfGone]]. The question
   *  has a definitive answer on the first failure, which is why no strike count
   *  is stored anywhere. */
  private def send(userId: String, guildId: String, embed: net.dv8tion.jda.api.entities.MessageEmbed, controls: ActionRow): Unit =
    outboundSender.enqueue("notify-dm") { () =>
      val user: User = discordGateway.retrieveUser(userId)
      if (user != null) {
        user.openPrivateChannel().queue(
          channel => channel.sendMessageEmbeds(embed).setComponents(controls).queue(
            _ => (),
            (ex: Throwable) => {
              logger.debug(s"Could not deliver notification DM to '$userId': ${ex.getMessage}")
              dropIfGone(guildId, userId)
            }
          ),
          (ex: Throwable) => {
            logger.debug(s"Could not open a DM channel with '$userId': ${ex.getMessage}")
            dropIfGone(guildId, userId)
          }
        )
      }
    }

  /** After a DM fails: is this someone who cannot be written to, or someone who
   *  is no longer here?
   *
   *  Discord refuses a DM to a user sharing no guild with the bot, and it refuses
   *  one to a user who has closed them — the same 50007 either way, which is why
   *  the answer has to be asked for rather than read off the failure.
   *
   *  `retrieveMemberById` is a REST lookup, so it works without the privileged
   *  members intent this bot deliberately does not enable — `syncRole` already
   *  depends on that. Only UNKNOWN_MEMBER drops anything: every other failure,
   *  including a plain outage, leaves the subscription alone, because a wrongly
   *  dropped one is silent and the user would have no idea to set it up again. */
  private def dropIfGone(guildId: String, userId: String): Unit =
    try {
      Option(discordGateway.guildById(guildId)).foreach { guild =>
        guild.retrieveMemberById(userId).queue(
          _ => (),
          new ErrorHandler().handle(
            ErrorResponse.UNKNOWN_MEMBER,
            new java.util.function.Consumer[ErrorResponseException] {
              def accept(ex: ErrorResponseException): Unit = forgetUser(guildId, userId)
            })
        )
      }
    } catch {
      case ex: Throwable => logger.debug(s"Could not check membership of '$userId' in '$guildId': ${ex.getMessage}")
    }
}
