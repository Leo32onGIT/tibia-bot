package com.tibiabot.observer

import com.tibiabot.domain.{MiniWorldChange, ObserverStatus, ObserverToken, RaidAnnouncement}
import com.tibiabot.persistence.ObserverRepository
import com.typesafe.scalalogging.StrictLogging

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future, blocking}

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
  enabled: Boolean,
  /** Set on a secondary, which never calls the Observer API itself: linking and
   *  clearing rules are handed to the primary through it (see [[ObserverRelay]]),
   *  and the feeds come from the primary's published copy (see [[ObserverFeed]]). */
  relay: Option[ObserverRelay] = None,
  /** The worlds a guild has set up, its main one first. An account linked there
   *  gets rules only for these, and only those it has characters on (see
   *  [[applyRules]]). */
  guildWorlds: String => List[String] = _ => Nil
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
    } else relay match {
      case None => linkDirect(guildId, userId, token)
      case Some(primary) =>
        // The primary links it and writes the row to the shared table; read it back
        // for this bot's cache, which is what the panel shows.
        primary.link(guildId, userId, token.trim) match {
          case ObserverRelay.Linked =>
            repository.forUser(guildId, userId) match {
              case Some(stored) =>
                tokens.put(keyOf(guildId, userId), stored)
                LinkOutcome.Ok(stored, verified = true)
              case None => LinkOutcome.Failed("linked, but the stored link could not be read back")
            }
          case ObserverRelay.InvalidToken => LinkOutcome.InvalidToken
          case ObserverRelay.Failed(reason) =>
            logger.warn(s"Relayed Observer link failed for '$userId' in guild '$guildId': $reason")
            LinkOutcome.Failed(reason)
          case ObserverRelay.Done => LinkOutcome.Failed("unexpected answer from the primary")
        }
    }

  /** Link against the Observer API here — the primary's side of a link, whether
   *  its own member asked or a secondary relayed the request. */
  def linkDirect(guildId: String, userId: String, token: String): LinkOutcome =
    if (!enabled) LinkOutcome.Failed("Observer is off on this bot")
    else {
      apiClient.link(token.trim) match {
        case LinkResult.Linked(credential, accountLabel, worlds) =>
          val worldLabel = if (worlds.nonEmpty) Some(worlds.mkString(", ").take(250)) else None
          val stored = repository.upsert(guildId, userId, crypto.encrypt(credential), ObserverStatus.Linked, accountLabel, worldLabel)
          tokens.put(keyOf(guildId, userId), stored)
          applyRules(guildId, userId, credential, worlds)
          LinkOutcome.Ok(stored, verified = true)
        case LinkResult.InvalidToken =>
          LinkOutcome.InvalidToken
        case LinkResult.Failed(reason) =>
          logger.warn(s"Observer link failed for '$userId' in guild '$guildId': $reason")
          LinkOutcome.Failed(reason)
      }
    }

  /** The primary's side of a relayed request (see [[ObserverRelay]]). */
  def handleRelayed(request: ObserverRelay.Request): ObserverRelay.Reply =
    request.op match {
      case ObserverRelay.OpLink =>
        request.token match {
          case None => ObserverRelay.Failed("no token in the request")
          case Some(token) =>
            linkDirect(request.guildId, request.userId, token) match {
              case LinkOutcome.Ok(_, _)      => ObserverRelay.Linked
              case LinkOutcome.InvalidToken  => ObserverRelay.InvalidToken
              case LinkOutcome.Failed(reason) => ObserverRelay.Failed(reason)
            }
        }
      case ObserverRelay.OpClearRules =>
        if (clearRulesDirect(request.guildId, request.userId)) ObserverRelay.Done
        else ObserverRelay.Failed("the rules could not be cleared")
      case ObserverRelay.OpReapplyRules =>
        reapplyRulesDirect(request.guildId)
        ObserverRelay.Done
      case other => ObserverRelay.Failed(s"unknown request '$other'")
    }

  /** Every linked account, read from the shared table rather than this bot's
   *  cache: the cache only knows the links made through this bot, and the primary
   *  fetches for the links made through every bot. Falls back to the cache if the
   *  read fails. */
  private def linkedTokens(): List[ObserverToken] = {
    val all =
      try repository.all()
      catch {
        case ex: Throwable =>
          logger.warn("Could not read the Observer links; using the ones this bot knows", ex)
          tokens.values.toList
      }
    all.filter(_.status == ObserverStatus.Linked)
  }

  /** Whether any account linked in this guild has characters on `world` — what
   *  earns the world a raids channel when it is set up after they linked. */
  def coversWorld(guildId: String, world: String): Boolean =
    enabled && linkedTokens().exists(t => t.guildId == guildId && t.worlds.exists(_.equalsIgnoreCase(world)))

  /** The mini world changes pooled across every linked account, keyed by
   *  lower-cased world, de-duplicated by title and sorted by it — a guild's world is
   *  covered if any linked member, in any Discord, has a rule for it. `None` when
   *  Observer is off or any account's fetch failed: a partial pool would read as
   *  changes ending. The API side of [[ObserverFeed]]. */
  def fetchPooledMwc(): Option[Map[String, List[MiniWorldChange]]] =
    if (!enabled) None
    else {
      val results = linkedTokens().map { t =>
        try repository.tokenEncFor(t.guildId, t.userId).map(crypto.decrypt).flatMap(apiClient.mwcResult)
        catch {
          case ex: Throwable =>
            logger.warn(s"Observer MWC poll failed for '${t.userId}' in guild '${t.guildId}'", ex)
            None
        }
      }
      if (!results.forall(_.isDefined)) None
      else Some(results.flatten.flatten
        .filter(c => c.world.nonEmpty && c.title.nonEmpty)
        .groupBy(_.world.toLowerCase)
        .view.mapValues(_.distinctBy(_.title.toLowerCase).sortBy(_.title.toLowerCase)).toMap)
    }

  /** All currently-announced raids pooled across every linked account and grouped
   *  by world — any guild tracking a world benefits from every member's
   *  exploration, in any Discord. Every distinct entry is kept, one per stage and
   *  per account that can see it: they do not all say the same, since an account
   *  with limited discoveries is not told which raid it is until it starts, and the
   *  raids poller combines them (see ObserverRaidPoller.merge). The API side of
   *  [[ObserverFeed]]. */
  def fetchPooledRaids(): Map[String, List[RaidAnnouncement]] =
    if (!enabled) Map.empty
    else {
      val all = linkedTokens().flatMap { t =>
        try repository.tokenEncFor(t.guildId, t.userId).map(crypto.decrypt).map(apiClient.raids).getOrElse(Nil)
        catch {
          case ex: Throwable =>
            logger.warn(s"Observer raids poll failed for '${t.userId}' in guild '${t.guildId}'", ex)
            Nil
        }
      }
      all.distinct.groupBy(_.world)
    }

  /** Renew every linked credential, whichever bot it was linked through. The JWT
   *  lasts ~90 days and `/renew` mints a fresh one from it, so a periodic sweep keeps
   *  links from ever lapsing while in use. Best-effort per token; a renew that fails
   *  leaves the old credential in place (still valid until its own expiry) to try
   *  again next sweep. Run only by a bot that talks to the API. */
  def renewAll(): Unit = if (enabled) {
    val linked = linkedTokens()
    var renewed = 0
    linked.foreach { t =>
      try repository.tokenEncFor(t.guildId, t.userId).map(crypto.decrypt).foreach { credential =>
        apiClient.renew(credential).foreach { fresh =>
          val stored = repository.upsert(t.guildId, t.userId, crypto.encrypt(fresh),
            ObserverStatus.Linked, t.accountLabel, t.world)
          tokens.put(keyOf(t.guildId, t.userId), stored)
          renewed += 1
        }
      } catch {
        case ex: Throwable => logger.warn(s"Observer renew failed for '${t.userId}' in guild '${t.guildId}'", ex)
      }
    }
    if (linked.nonEmpty) logger.info(s"Observer credential renewal: $renewed/${linked.size} renewed")
  }

  /** Set the rules that make an account's feeds populate: MWC rules, and raid
   *  rules covering every region — every raid, not only those in areas it has
   *  explored, which the API allows (confirmed 24 Sep 2026). Best effort, and
   *  logged either way — nothing else would show that a link is quietly getting
   *  nothing.
   *
   *  Only for worlds that are both the account's (it has characters there) and
   *  set up in the guild it was linked in — see [[ObserverService.ruleWorlds]].
   *  A world the guild doesn't track has nowhere to post, and one the account has
   *  no character on was never what linking offered. When no world is both, the
   *  bot's rules come off the account altogether, so a removed world doesn't keep
   *  a rule. If the guild's worlds can't be read, the rules are left as they are
   *  rather than cleared on a hiccup.
   *
   *  The API caps how many rules an account holds (5 MWC and 15 raid rules as of
   *  24 Sep 2026) and refuses a store over the cap outright, so the worlds go in
   *  the guild's order, its main world first, and the sidecar sets as many as the
   *  account has room for. */
  private def applyRules(guildId: String, userId: String, credential: String, worlds: List[String]): Unit = {
    val who = s"'$userId' in guild '$guildId'"
    def report(kind: String, attempt: => RulesResult): Unit =
      try {
        val result = attempt
        if (!result.ok) logger.warn(s"Observer $kind rules were not set for $who: ${result.detail}")
        else {
          val left = if (result.skipped.isEmpty) ""
            else s"; no room (limit ${result.limit.getOrElse("?")}) for ${result.skipped.mkString(", ")}"
          logger.info(s"Observer $kind rules set for $who on ${result.applied.size} world(s): " +
            s"${result.applied.mkString(", ")}$left")
        }
      } catch {
        case ex: Throwable => logger.warn(s"Observer $kind rules failed for $who", ex)
      }
    val setUp =
      try Some(guildWorlds(guildId))
      catch {
        case ex: Throwable =>
          logger.warn(s"Could not read which worlds guild '$guildId' has set up; leaving the Observer rules for $who as they are", ex)
          None
      }
    setUp.foreach { tracked =>
      val wanted = ObserverService.ruleWorlds(worlds, tracked)
      if (wanted.nonEmpty) {
        report("MWC", apiClient.ensureRules(credential, wanted))
        report("raid", apiClient.ensureRaidRules(credential, wanted))
      } else {
        val cleared = try apiClient.clearRules(credential) catch {
          case ex: Throwable =>
            logger.warn(s"Observer rules could not be cleared for $who", ex)
            false
        }
        if (cleared) logger.info(s"Observer rules cleared for $who: none of the account's worlds " +
          s"(${if (worlds.isEmpty) "none known" else worlds.mkString(", ")}) is set up in the guild")
      }
    }
  }

  /** Set every linked account's rules again, against what the guilds track now.
   *  Run daily and shortly after boot, so the rules mend themselves whatever was
   *  missed. Run only by a bot that talks to the API. */
  def reapplyRules(): Unit = if (enabled) linkedTokens().foreach(reapply)

  /** Set the rules again for every account linked in one guild, after it set up or
   *  removed a world — which changes which worlds they should cover. On a
   *  secondary the primary is asked to. Not waited on: it is a few sidecar calls
   *  per link, and `/setup` and `/remove` shouldn't sit behind them. */
  def reapplyRulesFor(guildId: String): Unit = if (enabled) relay match {
    case Some(primary) => primary.reapplyRules(guildId)
    case None => Future(blocking(reapplyRulesDirect(guildId)))(ExecutionContext.global)
  }

  /** [[reapplyRulesFor]] against the API here — the primary's side. */
  def reapplyRulesDirect(guildId: String): Unit = linkedTokens().filter(_.guildId == guildId).foreach(reapply)

  private def reapply(t: ObserverToken): Unit =
    try repository.tokenEncFor(t.guildId, t.userId).map(crypto.decrypt).foreach { credential =>
      applyRules(t.guildId, t.userId, credential, t.worlds)
    } catch {
      case ex: Throwable => logger.warn(s"Observer rules could not be re-applied for '${t.userId}' in guild '${t.guildId}'", ex)
    }

  /** Drop the bot's rules from a member's account against the API — the primary's
   *  side of an unlink. True when they were cleared. */
  def clearRulesDirect(guildId: String, userId: String): Boolean =
    try repository.tokenEncFor(guildId, userId).map(crypto.decrypt).exists(apiClient.clearRules)
    catch {
      case ex: Throwable =>
        logger.warn(s"Observer clearRules failed for '$userId' in guild '$guildId'", ex)
        false
    }

  /** Remove a member's link. Database first, cache after — a delete that fails
   *  leaves the link in place rather than desynchronising the two. Returns whether
   *  anything was actually removed. */
  def unlink(guildId: String, userId: String): Boolean = {
    // Best-effort: drop the bot's rules from the account before forgetting it —
    // through the primary on a secondary, which needs the stored credential, so
    // this has to happen before the delete below. A failure costs nothing but some
    // in-app notification rules left on the account.
    if (enabled) statusFor(guildId, userId).filter(_.status == ObserverStatus.Linked).foreach { _ =>
      relay match {
        case None => clearRulesDirect(guildId, userId)
        case Some(primary) =>
          primary.clearRules(guildId, userId) match {
            case ObserverRelay.Failed(reason) =>
              logger.warn(s"Relayed Observer clearRules failed for '$userId' in guild '$guildId': $reason")
            case _ => ()
          }
      }
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

object ObserverService {

  /** The worlds an account linked in a guild gets rules for: those the guild has
   *  set up (`guildWorlds`, its main world first) that the account also has
   *  characters on (`accountWorlds`), in the guild's order. Compared ignoring
   *  case; each keeps the spelling the account gave it. */
  def ruleWorlds(accountWorlds: List[String], guildWorlds: List[String]): List[String] = {
    val account = accountWorlds.map(w => w.toLowerCase -> w).toMap
    guildWorlds.map(_.toLowerCase).distinct.flatMap(account.get)
  }
}
