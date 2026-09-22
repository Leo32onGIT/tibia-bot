package com.tibiabot.observer

import com.tibiabot.domain.{MiniWorldChange, ObserverStatus, ObserverToken}
import com.tibiabot.persistence.ObserverRepository
import com.typesafe.scalalogging.StrictLogging

import scala.collection.concurrent.TrieMap

/** The members' Tibia Observer links: what is stored, and the add/remove
 *  operations behind the `/observer` panel.
 *
 *  Mirrors [[com.tibiabot.notifications.NotifyService]]: the (small) set is loaded
 *  once at startup and written through — database first, cache after — so a failed
 *  write can never leave the cache claiming a link that is not stored. Keyed by
 *  `(guildId, userId)`.
 *
 *  Phase 1 stores a token as [[ObserverStatus.Pending]] without contacting the
 *  Observer API (`enabled` is the `observer-api` mode gate, off for now). Live
 *  verification — turning a `Pending` link into `Linked` and resolving its world —
 *  is a later phase and slots in at [[link]] behind the same flag. */
/** What happened when a member added a token. */
sealed trait LinkOutcome
object LinkOutcome {
  /** Stored. `verified` = live-linked against the Observer API (mode on) versus
   *  stored pending without contacting it (mode off). */
  final case class Ok(token: ObserverToken, verified: Boolean) extends LinkOutcome
  /** The token was rejected — wrong/expired/spent; the user must add a fresh one. */
  case object InvalidToken extends LinkOutcome
  /** The sidecar or upstream failed; not the user's fault. */
  final case class Failed(reason: String) extends LinkOutcome
}

final class ObserverService(
  repository: ObserverRepository,
  crypto: TokenCrypto,
  apiClient: ObserverApiClient,
  enabled: Boolean
) extends StrictLogging {

  private val tokens = TrieMap.empty[(String, String), ObserverToken]

  private def keyOf(guildId: String, userId: String): (String, String) = (guildId, userId)

  /** Load what's stored. Called once at startup; a lookup that runs first simply
   *  finds nothing. */
  def load(): Unit =
    try {
      repository.all().foreach(t => tokens.put(keyOf(t.guildId, t.userId), t))
      logger.info(s"Loaded ${tokens.size} Observer token(s)")
    } catch {
      case ex: Throwable => logger.error("Failed to load Observer tokens", ex)
    }

  def configured(guildId: String, userId: String): Boolean =
    tokens.contains(keyOf(guildId, userId))

  def statusFor(guildId: String, userId: String): Option[ObserverToken] =
    tokens.get(keyOf(guildId, userId))

  /** Add a member's token.
   *
   *  With `enabled` off (mode off) the 5-char code is stored `Pending`, unverified.
   *  With it on, the code is exchanged via the sidecar for a durable link: the
   *  single-use code is spent and the **refresh token** it returns is what gets
   *  stored (encrypted) — never the code, which is worthless afterwards. */
  def link(guildId: String, userId: String, token: String): LinkOutcome =
    if (!enabled) {
      val stored = repository.upsert(guildId, userId, crypto.encrypt(token.trim), ObserverStatus.Pending, None, None)
      tokens.put(keyOf(guildId, userId), stored)
      LinkOutcome.Ok(stored, verified = false)
    } else {
      apiClient.link(token.trim) match {
        case LinkResult.Linked(credential, accountLabel, worlds) =>
          val worldLabel = if (worlds.nonEmpty) Some(worlds.mkString(", ").take(250)) else None
          val stored = repository.upsert(guildId, userId, crypto.encrypt(credential), ObserverStatus.Linked, accountLabel, worldLabel)
          tokens.put(keyOf(guildId, userId), stored)
          // Best-effort: set MWC rules for the account's worlds so the feed populates.
          if (worlds.nonEmpty) try apiClient.ensureRules(credential, worlds)
            catch { case ex: Throwable => logger.warn(s"Observer ensureRules failed for '$userId'", ex) }
          LinkOutcome.Ok(stored, verified = true)
        case LinkResult.InvalidToken =>
          LinkOutcome.InvalidToken
        case LinkResult.Failed(reason) =>
          logger.warn(s"Observer link failed for '$userId' in guild '$guildId': $reason")
          LinkOutcome.Failed(reason)
      }
    }

  /** MWC for a user by Discord id alone, regardless of guild — the boosted DM is
   *  per-user, and a token's MWC is account-scoped, so any of the user's linked
   *  tokens answers the same. Empty when they have none linked. */
  def activeMwcForUser(userId: String): List[MiniWorldChange] =
    if (!enabled) Nil
    else tokens.collectFirst {
      case ((g, u), t) if u == userId && t.status == ObserverStatus.Linked => g
    } match {
      case Some(guildId) => activeMwc(guildId, userId)
      case None          => Nil
    }

  /** The currently-active mini world changes for a linked member, via the sidecar.
   *  Empty for an unlinked member, mode off, or any failure — never throws. */
  def activeMwc(guildId: String, userId: String): List[MiniWorldChange] =
    if (!enabled) Nil
    else statusFor(guildId, userId) match {
      case Some(t) if t.status == ObserverStatus.Linked =>
        try repository.tokenEncFor(guildId, userId).map(crypto.decrypt).map(apiClient.mwc).getOrElse(Nil)
        catch {
          case ex: Throwable =>
            logger.warn(s"Observer MWC fetch failed for '$userId' in guild '$guildId'", ex)
            Nil
        }
      case _ => Nil
    }

  /** Remove a member's link. Database first, cache after — a delete that fails
   *  leaves the link in place rather than desynchronising the two. Returns whether
   *  anything was actually removed. */
  def unlink(guildId: String, userId: String): Boolean = {
    // Best-effort: drop the bot's MWC rules from the account before forgetting it.
    if (enabled) statusFor(guildId, userId).filter(_.status == ObserverStatus.Linked).foreach { _ =>
      try repository.tokenEncFor(guildId, userId).map(crypto.decrypt).foreach(apiClient.clearRules)
      catch { case ex: Throwable => logger.warn(s"Observer clearRules failed for '$userId' in guild '$guildId'", ex) }
    }
    val removed =
      try repository.delete(guildId, userId)
      catch {
        case ex: Throwable =>
          logger.warn(s"Failed to delete Observer token for '$userId' in guild '$guildId'", ex)
          false
      }
    if (removed) tokens.remove(keyOf(guildId, userId))
    removed
  }

  /** Drop every link in a guild the bot has left. */
  def forgetGuild(guildId: String): Unit = {
    try repository.deleteGuild(guildId)
    catch { case ex: Throwable => logger.warn(s"Failed to delete Observer tokens for guild '$guildId'", ex) }
    tokens.filterInPlace { case ((g, _), _) => g != guildId }
  }

  /** Drop a link for a member who has left the guild. */
  def forgetUser(guildId: String, userId: String): Unit = {
    try repository.deleteUser(guildId, userId)
    catch { case ex: Throwable => logger.warn(s"Failed to delete Observer token for '$userId' in guild '$guildId'", ex) }
    tokens.remove(keyOf(guildId, userId))
  }
}
