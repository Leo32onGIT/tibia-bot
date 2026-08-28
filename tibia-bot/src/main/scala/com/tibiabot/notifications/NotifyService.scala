package com.tibiabot.notifications

import com.tibiabot.discord.{DiscordGateway, RateLimitedSender}
import com.tibiabot.domain.{BountySub, MasslogSub, NotifyDecision}
import com.tibiabot.persistence.NotifyRepository
import com.tibiabot.presentation.NotifyEmbeds
import com.tibiabot.tracking.MasslogDetector
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.User

import java.time.Instant
import scala.collection.concurrent.TrieMap

/** The mass-log and bounty DM subscriptions: what is stored, who is due, and the
 *  DMs themselves.
 *
 *  ==Why the subscriptions are cached==
 *  The online-list sweep asks "does anyone here care?" every fifteen seconds for
 *  every guild on a world, and the honest answer is almost always no. Reading
 *  that from Postgres each time would be a query per guild per sweep to learn
 *  nothing, so the whole (small) set is loaded once at startup and written
 *  through from there. Writes go to the database first and to the cache after,
 *  so a failed write can't leave the cache claiming a subscription that isn't
 *  stored.
 *
 *  ==Concurrency==
 *  Reads come from each world's sweep thread; writes come from JDA's interaction
 *  pool. TrieMap rather than a plain mutable map for exactly that reason — see
 *  BotListener's pendingScreenshots for the same choice.
 */
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
   *  A failure is logged and dropped rather than counted towards giving up on
   *  the subscription: unlike the boosted list, these rows belong to a guild
   *  this bot is certainly in, so a failure means closed DMs, and closed DMs are
   *  the user's business to reopen. */
  private def send(userId: String, embed: net.dv8tion.jda.api.entities.MessageEmbed, controls: ActionRow): Unit =
    outboundSender.enqueue("notify-dm") { () =>
      val user: User = discordGateway.retrieveUser(userId)
      if (user != null) {
        user.openPrivateChannel().queue(
          channel => channel.sendMessageEmbeds(embed).setComponents(controls).queue(
            _ => (),
            (ex: Throwable) => logger.debug(s"Could not deliver notification DM to '$userId': ${ex.getMessage}")
          ),
          (ex: Throwable) => logger.debug(s"Could not open a DM channel with '$userId': ${ex.getMessage}")
        )
      }
    }
}
