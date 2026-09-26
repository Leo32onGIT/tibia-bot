package com.tibiabot.observer

import com.tibiabot.domain.{MiniWorldChange, ObserverStatus, ObserverToken, RaidAnnouncement}
import com.tibiabot.domain.time.Clock
import com.tibiabot.persistence.{ObserverCoverageRepository, ObserverRepository}
import com.tibiabot.scheduler.ServerSaveSchedule
import com.typesafe.scalalogging.StrictLogging

import java.time.Instant
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future, blocking}

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

/** The members' Tibia Observer links: what is stored, the add/remove operations
 *  behind the `/observer` panel, and the feeds pooled across them.
 *
 *  Mirrors [[com.tibiabot.notifications.NotifyService]]: the (small) set is loaded
 *  once at startup and written through — database first, cache after — so a failed
 *  write can never leave the cache claiming a link that is not stored. Keyed by
 *  `(guildId, userId)`, so any number of members of a guild can link.
 *
 *  With `enabled` off (the `observer-api` mode gate) a token is stored
 *  `Pending` without contacting the API. With it on, linking
 *  exchanges it for a durable credential and sets the account's rules (see
 *  `applyRules`). A link whose credential the API refuses for good is marked
 *  `NeedsRelink` and stops being polled (see `pollEach`). */
final class ObserverService(
  repository: ObserverRepository,
  crypto: TokenCrypto,
  apiClient: ObserverApiClient,
  enabled: Boolean,
  /** Set on a secondary, which never calls the Observer API itself: linking and
   *  clearing rules are handed to the primary through it (see [[ObserverRelay]]),
   *  and the feeds come from the primary's published copy (see [[ObserverFeed]]). */
  relay: Option[ObserverRelay] = None,
  /** The worlds a guild has set up, its main one first. An account's rules cover
   *  its links' guilds' worlds before any others (see `applyRules`). */
  guildWorlds: String => List[String] = _ => Nil,
  /** Every world a guild tracks, on any bot. The feeds are pooled across every
   *  Discord, so an account's rules cover each of these it has characters on — not
   *  only those of the guild it was linked in. */
  trackedWorlds: () => List[String] = () => Nil,
  now: () => Instant = () => Instant.now(),
  /** The latest server save at or before an instant: mini world changes hold until
   *  the next one, which is how long an account's last good share of them lasts. */
  lastServerSave: Instant => Instant = at =>
    ServerSaveSchedule.lastServerSave(at.atZone(Clock.Berlin)).toInstant,
  /** What each link's raid rules cover, kept when the rules are set, for the
   *  coverage `/observer` shows (see [[panel]]). */
  coverage: ObserverCoverageRepository = ObserverCoverageRepository.None
) extends StrictLogging {
  import ObserverService.RuleScope

  private val tokens = TrieMap.empty[(String, String), ObserverToken]

  private def keyOf(guildId: String, userId: String): (String, String) = (guildId, userId)
  private def keyOf(t: ObserverToken): (String, String) = keyOf(t.guildId, t.userId)

  /** Load what's stored. Called once at startup; a lookup that runs first simply
   *  finds nothing. */
  def load(): Unit =
    try {
      repository.all().foreach(t => tokens.put(keyOf(t), t))
      logger.info(s"Loaded ${tokens.size} Observer token(s)")
    } catch {
      case ex: Throwable => logger.error("Failed to load Observer tokens", ex)
    }

  def configured(guildId: String, userId: String): Boolean =
    tokens.contains(keyOf(guildId, userId))

  /** A member's link as it is stored — read from the shared table, since the
   *  primary is what marks a link for relinking and a secondary's cache would never
   *  hear of it. Falls back to the cache if the read fails. */
  def statusFor(guildId: String, userId: String): Option[ObserverToken] = {
    val key = keyOf(guildId, userId)
    try {
      val stored = repository.forUser(guildId, userId)
      stored match {
        case Some(t) => tokens.put(key, t)
        case None    => tokens.remove(key)
      }
      stored
    } catch {
      case ex: Throwable =>
        logger.warn(s"Could not read the Observer link for '$userId' in guild '$guildId'; using the one this bot knows", ex)
        tokens.get(key)
    }
  }

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
   *  its own member asked or a secondary relayed the request. The account's rules
   *  are set after answering: they read every guild's worlds, and the member is
   *  waiting on the link, not on them. */
  def linkDirect(guildId: String, userId: String, token: String): LinkOutcome =
    if (!enabled) LinkOutcome.Failed("Observer is off on this bot")
    else {
      apiClient.link(token.trim) match {
        case LinkResult.Linked(credential, accountLabel, worlds) =>
          val worldLabel = if (worlds.nonEmpty) Some(worlds.mkString(", ").take(250)) else None
          val stored = repository.upsert(guildId, userId, crypto.encrypt(credential), ObserverStatus.Linked, accountLabel, worldLabel)
          tokens.put(keyOf(guildId, userId), stored)
          inBackground(ruleScope().foreach(scope => applyRules(stored, credential, scope, worldsOfGuild())))
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
        reapplyRulesDirect(request.guildId, request.world)
        ObserverRelay.Done
      case other => ObserverRelay.Failed(s"unknown request '$other'")
    }

  /** Every stored link, whatever its status, read from the shared table rather than
   *  this bot's cache: the cache only knows the links made through this bot, and
   *  the primary fetches for the links made through every bot. Falls back to the
   *  cache if the read fails. */
  private def storedTokens(): List[ObserverToken] =
    try repository.all()
    catch {
      case ex: Throwable =>
        logger.warn("Could not read the Observer links; using the ones this bot knows", ex)
        tokens.values.toList
    }

  /** The links that are live — the ones polled and given rules. */
  private def linkedTokens(): List[ObserverToken] = storedTokens().filter(_.status == ObserverStatus.Linked)

  private def credentialOf(t: ObserverToken): Option[String] =
    repository.tokenEncFor(t.guildId, t.userId).map(crypto.decrypt)

  /** Store a fresh credential for a link, which is live again if it was not. */
  private def storeCredential(t: ObserverToken, credential: String): Unit = {
    val stored = repository.upsert(t.guildId, t.userId, crypto.encrypt(credential),
      ObserverStatus.Linked, t.accountLabel, t.world)
    tokens.put(keyOf(t), stored)
  }

  /** Whether the API refusing `refused` of `asked` links is trouble on its side
   *  rather than their links dying: every one of two or more at once. */
  private def refusedAllAtOnce(refused: Int, asked: Int): Boolean = asked > 1 && refused == asked

  /** Mark the links the API refused for good as needing a fresh token — unless it
   *  refused them all at once (see [[refusedAllAtOnce]]): members don't revoke
   *  their links together. A marked link stops being polled; its member sees it on
   *  `/observer`, and the renewal sweep links it again if its credential turns out
   *  to work after all. */
  private def markRefused(refused: List[ObserverToken], asked: Int, during: String): Unit =
    if (refused.nonEmpty) {
      if (refusedAllAtOnce(refused.size, asked))
        logger.warn(s"The Observer API refused all $asked linked accounts at once during $during; " +
          "taking that for trouble on its side and marking none of them for relinking")
      else refused.foreach { t =>
        try {
          repository.setStatus(t.id, ObserverStatus.NeedsRelink, t.world)
          tokens.put(keyOf(t), t.copy(status = ObserverStatus.NeedsRelink))
          logger.warn(s"The Observer API refused the link for '${t.userId}' in guild '${t.guildId}' during $during, " +
            "and would not renew it; it needs a fresh token")
        } catch {
          case ex: Throwable => logger.warn(s"Could not mark the Observer link for '${t.userId}' in guild '${t.guildId}' for relinking", ex)
        }
      }
    }

  /** Ask each link's feed with `ask`. A credential the API refuses is renewed and
   *  asked again with the fresh one, since a refusal can be the API's own hiccup;
   *  when the renewal is refused too, the link is dead — revoked, or expired — and
   *  is marked for relinking (see [[markRefused]]). A refused link answers
   *  `Unauthorised` either way, so a caller can leave it out without taking it for
   *  a failure worth waiting on — except when every link was refused at once,
   *  which is the API's trouble, and they all answer `Failed`. */
  private def pollEach[A](links: List[ObserverToken], what: String)(ask: String => FeedResult[A]): List[(ObserverToken, FeedResult[A])] = {
    val refused = ListBuffer.empty[ObserverToken]
    val results = links.map { t =>
      val result =
        try credentialOf(t) match {
          // Unlinked since the list was read: nothing to ask with, and nothing lost.
          case None => FeedResult.Unauthorised
          case Some(credential) =>
            ask(credential) match {
              case FeedResult.Unauthorised =>
                apiClient.renew(credential) match {
                  case RenewResult.Renewed(fresh) =>
                    storeCredential(t, fresh)
                    ask(fresh)
                  case RenewResult.Rejected =>
                    refused += t
                    FeedResult.Unauthorised
                  case RenewResult.Failed => FeedResult.Unauthorised
                }
              case other => other
            }
        } catch {
          case ex: Throwable =>
            logger.warn(s"Observer $what poll failed for '${t.userId}' in guild '${t.guildId}'", ex)
            FeedResult.Failed
        }
      t -> result
    }
    markRefused(refused.toList, links.size, s"the $what poll")
    if (!refusedAllAtOnce(refused.size, links.size)) results
    else results.map {
      case (t, FeedResult.Unauthorised) => t -> FeedResult.Failed
      case other                        => other
    }
  }

  /** Whether any account linked in this guild has characters on `world` — what
   *  earns the world a raids channel when it is set up after they linked. */
  def coversWorld(guildId: String, world: String): Boolean =
    enabled && linkedTokens().exists(t => t.guildId == guildId && t.worlds.exists(_.equalsIgnoreCase(world)))

  /** Each link's last good mini world changes, and when they were fetched: its
   *  share of the pool while it cannot be fetched. */
  private val mwcShares = TrieMap.empty[(String, String), (Instant, List[MiniWorldChange])]

  /** The mini world changes pooled across every linked account, keyed by lower-cased
   *  world, de-duplicated by title and sorted by it — a guild's world is covered if
   *  any linked member, in any Discord, has a rule for it. The API side of
   *  [[ObserverFeed]].
   *
   *  An account that cannot be fetched keeps its share from its last good fetch
   *  while that was since the latest server save, since the changes hold until the
   *  next one: its worlds' changes neither vanish mid-day nor hold up everyone
   *  else's. That goes for a link marked for relinking too, until the save; after
   *  it, nobody can see that account's worlds any more. Only a failure with no share
   *  since the save to fall back on makes the whole pool `None` (and not a refused
   *  credential, which will not come back by asking again): a partial pool then
   *  would read as that account's changes ending, or, just after the save, would
   *  make yesterday's look new when they come back. `None` too when Observer is
   *  off. */
  def fetchPooledMwc(): Option[Map[String, List[MiniWorldChange]]] =
    if (!enabled) None
    else {
      val at = now()
      val save = lastServerSave(at)
      def share(t: ObserverToken): Option[List[MiniWorldChange]] =
        mwcShares.get(keyOf(t)).collect { case (fetchedAt, changes) if !fetchedAt.isBefore(save) => changes }
      val stored = storedTokens()
      // An unlinked account's changes go with it.
      val kept = stored.map(keyOf).toSet
      mwcShares.keys.toList.filterNot(kept).foreach(mwcShares.remove)
      val (live, lapsed) = stored.partition(_.status == ObserverStatus.Linked)
      var incomplete = false
      val polled = pollEach(live, "MWC")(apiClient.mwc).map {
        case (t, FeedResult.Fetched(changes)) =>
          mwcShares.put(keyOf(t), at -> changes)
          changes
        case (t, FeedResult.Unauthorised) => share(t).getOrElse(Nil)
        case (t, FeedResult.Failed) =>
          share(t).getOrElse {
            incomplete = true
            Nil
          }
      }
      if (incomplete) None
      else Some((polled ++ lapsed.flatMap(share)).flatten
        .filter(c => c.world.nonEmpty && c.title.nonEmpty)
        .groupBy(_.world.toLowerCase)
        .view.mapValues(_.distinctBy(_.title.toLowerCase).sortBy(_.title.toLowerCase)).toMap)
    }

  /** All currently-announced raids pooled across every linked account and grouped
   *  by world — any guild tracking a world benefits from every member's
   *  exploration, in any Discord. Every distinct entry is kept: one per stage, and
   *  one per account whose rules cover the raid's area. The raids poller combines
   *  them into one view of each raid (see ObserverRaidPoller.merge). An account
   *  that cannot be fetched just adds nothing this time: its raids are picked up by
   *  the next poll, and what has been posted stays posted. The API side of
   *  [[ObserverFeed]]. */
  def fetchPooledRaids(): Map[String, List[RaidAnnouncement]] =
    if (!enabled) Map.empty
    else pollEach(linkedTokens(), "raids")(apiClient.raids)
      .flatMap {
        case (_, FeedResult.Fetched(raids)) => raids
        case _                              => Nil
      }
      .distinct.groupBy(_.world)

  /** Renew every link's credential, whichever bot it was linked through. The JWT
   *  lasts ~90 days and `/renew` mints a fresh one from it, so a daily sweep keeps
   *  links from ever lapsing while in use. Best-effort per link; a renewal that
   *  fails leaves the old credential in place (still valid until its own expiry)
   *  to try again next sweep.
   *
   *  A link marked for relinking is tried too, and is live again — its rules set
   *  again — when its credential works after all: it was refused while the API was
   *  having trouble. A live one the API refuses here is marked (see
   *  `markRefused`). Run only by a bot that talks to the API. */
  def renewAll(): Unit = if (enabled) {
    val candidates = storedTokens().filter(t => t.status == ObserverStatus.Linked || t.status == ObserverStatus.NeedsRelink)
    val live = candidates.count(_.status == ObserverStatus.Linked)
    val refused = ListBuffer.empty[ObserverToken]
    val revived = ListBuffer.empty[ObserverToken]
    var renewed = 0
    candidates.foreach { t =>
      try credentialOf(t).foreach { credential =>
        apiClient.renew(credential) match {
          case RenewResult.Renewed(fresh) =>
            storeCredential(t, fresh)
            renewed += 1
            if (t.status == ObserverStatus.NeedsRelink) {
              logger.info(s"The Observer link for '${t.userId}' in guild '${t.guildId}' works again; linked")
              revived += t.copy(status = ObserverStatus.Linked)
            }
          case RenewResult.Rejected => if (t.status == ObserverStatus.Linked) refused += t
          case RenewResult.Failed   => ()
        }
      } catch {
        case ex: Throwable => logger.warn(s"Observer renew failed for '${t.userId}' in guild '${t.guildId}'", ex)
      }
    }
    markRefused(refused.toList, live, "the renewal sweep")
    if (revived.nonEmpty) ruleScope().foreach(scope => applyEach(revived.toList, scope))
    if (candidates.nonEmpty) logger.info(s"Observer credential renewal: $renewed/${candidates.size} renewed")
  }

  /** `None` when the worlds tracked can't be read: the rules are then left as they
   *  are rather than cleared on a hiccup. */
  private def ruleScope(): Option[RuleScope] =
    try Some(RuleScope(linkedTokens(), trackedWorlds().distinctBy(_.toLowerCase).sortBy(_.toLowerCase)))
    catch {
      case ex: Throwable =>
        logger.warn("Could not read which worlds are tracked; leaving the Observer rules as they are", ex)
        None
    }

  /** [[guildWorlds]], read once per guild for a batch of links. */
  private def worldsOfGuild(): String => List[String] = {
    val read = TrieMap.empty[String, List[String]]
    guildId => read.getOrElseUpdate(guildId, guildWorlds(guildId))
  }

  private def inBackground(work: => Unit): Unit =
    Future(blocking(work))(ExecutionContext.global).failed.foreach { ex =>
      logger.warn("Setting Observer rules failed", ex)
    }(ExecutionContext.global)

  /** Set the rules that make an account's feeds populate: MWC rules, and raid
   *  rules over the areas it has explored (see [[ObserverApiClient.ensureRaidRules]]).
   *  Best effort, and logged either way — nothing else would show that a link is
   *  quietly getting nothing.
   *
   *  The worlds are those the account has characters on that some guild tracks,
   *  on any bot: the feeds are pooled, so a raid it sees reaches every guild with a
   *  raids channel for that world, and its mini world changes every guild tracking
   *  it. A world no guild tracks has nowhere to post, and one the account has no
   *  character on was never what linking offered. When none is left, the bot's
   *  rules come off the account altogether. If the worlds can't be read, the rules
   *  are left as they are rather than cleared on a hiccup.
   *
   *  The API caps how many rules an account holds (5 MWC and 15 raid rules as of
   *  24 Sep 2026) and refuses a store over the cap outright, so the worlds go most
   *  wanted first and the sidecar sets as many as the account has room for: the
   *  worlds of the guilds its links are in, each guild's main world first, then the
   *  rest (see [[wanted]]). */
  private def applyRules(t: ObserverToken, credential: String, scope: RuleScope,
                         worldsOf: String => List[String]): Unit = {
    val who = s"'${t.userId}' in guild '${t.guildId}'"
    def report(kind: String, attempt: => RulesResult): Option[RulesResult] =
      try {
        val result = attempt
        if (!result.ok) {
          logger.warn(s"Observer $kind rules were not set for $who: ${result.detail}")
          None
        } else {
          val left = if (result.skipped.isEmpty) ""
            else s"; no room (limit ${result.limit.getOrElse("?")}) for ${result.skipped.mkString(", ")}"
          logger.info(s"Observer $kind rules set for $who on ${result.applied.size} world(s): " +
            s"${result.applied.mkString(", ")}$left")
          Some(result)
        }
      } catch {
        case ex: Throwable =>
          logger.warn(s"Observer $kind rules failed for $who", ex)
          None
      }
    val worlds =
      try Some(wanted(t, scope, worldsOf))
      catch {
        case ex: Throwable =>
          logger.warn(s"Could not read which worlds the guilds $who is linked in have set up; leaving the Observer rules as they are", ex)
          None
      }
    worlds.foreach { ws =>
      if (ws.nonEmpty) {
        report("MWC", apiClient.ensureRules(credential, ws))
        report("raid", apiClient.ensureRaidRules(credential, ws)).foreach(recordCoverage(t, _))
      } else {
        val cleared = try apiClient.clearRules(credential) catch {
          case ex: Throwable =>
            logger.warn(s"Observer rules could not be cleared for $who", ex)
            false
        }
        if (cleared) {
          logger.info(s"Observer rules cleared for $who: no guild tracks any of the account's worlds " +
            s"(${if (t.worlds.isEmpty) "none known" else t.worlds.mkString(", ")})")
          forgetCoverage(t.guildId, t.userId)
        }
      }
    }
  }

  /** Keep what a link's raid rules now cover, and any area names that came with
   *  them. An area id that still has no name is logged once: `/observer` can't show
   *  it until it has one, and the fields Observer sent say where a name could be. */
  private def recordCoverage(t: ObserverToken, result: RulesResult): Unit =
    try {
      coverage.setAreas(t.guildId, t.userId, result.regions)
      coverage.setNames(result.areaNames)
      val named = ObserverAreas.KnownNames ++ coverage.names()
      val unnamed = result.regions.values.flatten.toSet.filterNot(named.contains).filterNot(reportedUnnamed.contains)
      if (unnamed.nonEmpty) {
        unnamed.foreach(reportedUnnamed.put(_, ()))
        logger.warn(s"Observer area id(s) ${unnamed.toList.sorted.mkString(", ")} have no name, so /observer can't " +
          s"show them; its explored areas carry the fields ${result.areaFields.mkString(", ")}")
      }
      val unlisted = result.areaNames.values.toSet.filterNot(n => ObserverAreas.raidAreas.exists(_.equalsIgnoreCase(n)))
        .filterNot(reportedUnlisted.contains)
      if (unlisted.nonEmpty) {
        unlisted.foreach(reportedUnlisted.put(_, ()))
        logger.info(s"Observer named area(s) the raid catalogue has no raids in, so /observer doesn't list them: " +
          unlisted.toList.sorted.mkString(", "))
      }
    } catch {
      case ex: Throwable => logger.warn(s"Could not keep what the Observer rules cover for '${t.userId}' in guild '${t.guildId}'", ex)
    }

  private val reportedUnnamed = TrieMap.empty[Int, Unit]
  private val reportedUnlisted = TrieMap.empty[String, Unit]

  private def forgetCoverage(guildId: String, userId: String): Unit =
    try coverage.clearLink(guildId, userId)
    catch { case ex: Throwable => logger.warn(s"Could not forget what the Observer link for '$userId' in guild '$guildId' covered", ex) }

  /** The worlds an account gets rules for, most wanted first. The bot's rules on
   *  an account are one set, so every link that is probably the same account (see
   *  [[ObserverService.sameAccount]]) must ask for the same: their guilds' worlds
   *  come first, oldest link first, then every other tracked world. An account
   *  linked in two Discords then gets the same rules whichever of them set them
   *  last. */
  private def wanted(t: ObserverToken, scope: RuleScope, worldsOf: String => List[String]): List[String] = {
    val links = (t :: scope.links.filter(ObserverService.sameAccount(_, t)))
      .distinctBy(keyOf).sortBy(l => (l.createdAt, l.id))
    ObserverService.ruleWorlds(t.worlds, links.flatMap(l => worldsOf(l.guildId)) ++ scope.everywhere)
  }

  private def applyEach(links: List[ObserverToken], scope: RuleScope): Unit = {
    val worldsOf = worldsOfGuild()
    links.foreach { t =>
      try credentialOf(t).foreach(applyRules(t, _, scope, worldsOf))
      catch {
        case ex: Throwable => logger.warn(s"Observer rules could not be re-applied for '${t.userId}' in guild '${t.guildId}'", ex)
      }
    }
  }

  /** Set every linked account's rules again, against what the guilds track now.
   *  Run daily and shortly after boot, so the rules mend themselves whatever was
   *  missed. Run only by a bot that talks to the API. */
  def reapplyRules(): Unit = if (enabled) ruleScope().foreach(scope => applyEach(scope.links, scope))

  /** Set the rules again for the links a guild's `/setup` or `/remove` of `world`
   *  touches: those linked in that guild, whose order of worlds starts with the
   *  guild's, and every account with a character on `world`, whose rule for it
   *  comes or goes with whether any guild still tracks it. On a secondary the
   *  primary is asked to. Not waited on: it is a few sidecar calls per link, and
   *  `/setup` and `/remove` shouldn't sit behind them. */
  def reapplyRulesFor(guildId: String, world: String): Unit = if (enabled) relay match {
    case Some(primary) => primary.reapplyRules(guildId, Some(world))
    case None => inBackground(reapplyRulesDirect(guildId, Some(world)))
  }

  /** [[reapplyRulesFor]] against the API here — the primary's side. Without a
   *  world (asked by a bot from before the world was sent), the guild's links. */
  def reapplyRulesDirect(guildId: String, world: Option[String]): Unit = ruleScope().foreach { scope =>
    val touched = scope.links.filter(t =>
      t.guildId == guildId || world.exists(w => t.worlds.exists(_.equalsIgnoreCase(w))))
    applyEach(touched, scope)
  }

  /** Drop the bot's rules from a member's account against the API — the primary's
   *  side of an unlink. True when they were cleared.
   *
   *  The bot's rules on an account are one set, so any other link to the same
   *  account — the member's own in another Discord, or someone sharing it — just
   *  lost its rules too. Those links get theirs set again, without this one, once
   *  it is cleared (not waited on). */
  def clearRulesDirect(guildId: String, userId: String): Boolean = {
    val leaving = storedTokens().find(t => t.guildId == guildId && t.userId == userId)
    val cleared =
      try leaving.flatMap(credentialOf).exists(apiClient.clearRules)
      catch {
        case ex: Throwable =>
          logger.warn(s"Observer clearRules failed for '$userId' in guild '$guildId'", ex)
          false
      }
    leaving.foreach { gone =>
      inBackground(ruleScope().foreach { scope =>
        val rest = scope.links.filterNot(l => keyOf(l) == keyOf(gone))
        applyEach(rest.filter(ObserverService.sameAccount(_, gone)), scope.copy(links = rest))
      })
    }
    cleared
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
    if (removed) {
      tokens.remove(keyOf(guildId, userId))
      forgetCoverage(guildId, userId)
    }
    removed
  }

  /** Drop every link in a guild the bot has left. */
  def forgetGuild(guildId: String): Unit = {
    try repository.deleteGuild(guildId)
    catch { case ex: Throwable => logger.warn(s"Failed to delete Observer tokens for guild '$guildId'", ex) }
    tokens.filterInPlace { case ((g, _), _) => g != guildId }
    try coverage.clearGuild(guildId)
    catch { case ex: Throwable => logger.warn(s"Failed to forget the Observer coverage for guild '$guildId'", ex) }
  }

  /** Drop a link for a member who has left the guild. */
  def forgetUser(guildId: String, userId: String): Unit = {
    try repository.deleteUser(guildId, userId)
    catch { case ex: Throwable => logger.warn(s"Failed to delete Observer token for '$userId' in guild '$guildId'", ex) }
    tokens.remove(keyOf(guildId, userId))
    forgetCoverage(guildId, userId)
  }

  /** What `/observer` shows a member: their link, and the raid-area coverage of
   *  the worlds this guild has set up — every one of them with no working link to
   *  go by, or only those the linked account has characters on. An area counts as
   *  covered when any working link, in any guild, has a raid rule over it on that
   *  world; the member's own are marked. Read from the shared tables, so any bot
   *  can answer. A read that fails shows no coverage rather than failing the
   *  reply. */
  def panel(guildId: String, userId: String): ObserverPanel = {
    val token = statusFor(guildId, userId)
    val setUp =
      try guildWorlds(guildId).distinctBy(_.toLowerCase)
      catch {
        case ex: Throwable =>
          logger.warn(s"Could not read which worlds guild '$guildId' has set up for /observer", ex)
          Nil
      }
    val shown = token match {
      case Some(t) if t.status != ObserverStatus.Pending => setUp.filter(w => t.worlds.exists(_.equalsIgnoreCase(w)))
      case _                                             => setUp
    }
    val (areas, names) =
      try (coverage.liveAreas(shown), ObserverAreas.KnownNames ++ coverage.names())
      catch {
        case ex: Throwable =>
          logger.warn(s"Could not read the Observer coverage for guild '$guildId'", ex)
          (Nil, ObserverAreas.KnownNames)
      }
    ObserverPanel(token, shown.map { world =>
      val here = areas.filter(_.world.equalsIgnoreCase(world))
      val covered = here.flatMap(a => names.get(a.areaId).map(_.toLowerCase -> (a.guildId == guildId && a.userId == userId)))
        .groupMapReduce(_._1)(_._2)(_ || _)
      WorldCoverage(world, ObserverAreas.raidAreas.flatMap(area => covered.get(area.toLowerCase).map(area -> _)).toMap)
    })
  }
}

/** A world's raid areas that some working link covers, by the raid catalogue's name
 *  for each, and whether the member looking is one of those links. */
final case class WorldCoverage(world: String, covered: Map[String, Boolean])

/** What `/observer` shows a member (see ObserverService.panel). */
final case class ObserverPanel(token: Option[ObserverToken], worlds: List[WorldCoverage])

object ObserverService {

  /** What deciding an account's rules reads, loaded once for a batch of links:
   *  every live link, and every world a guild on any bot tracks. */
  private[observer] final case class RuleScope(links: List[ObserverToken], everywhere: List[String])

  /** The worlds an account gets rules for: those in `wanted` — most wanted first —
   *  that the account also has characters on (`accountWorlds`), in that order,
   *  each once. Compared ignoring case; each keeps the spelling the account gave
   *  it. */
  def ruleWorlds(accountWorlds: List[String], wanted: List[String]): List[String] = {
    val account = accountWorlds.map(w => w.toLowerCase -> w).toMap
    wanted.map(_.toLowerCase).distinct.flatMap(account.get)
  }

  /** Whether two links are probably the same Observer account: the same character
   *  worlds. The API names no account to go by. Two different accounts taken for
   *  one only share an order of worlds, which matters only past the rule cap; links
   *  with no worlds known are never taken for the same. */
  def sameAccount(a: ObserverToken, b: ObserverToken): Boolean = {
    def worlds(t: ObserverToken) = t.worlds.map(_.toLowerCase).toSet
    worlds(a).nonEmpty && worlds(a) == worlds(b)
  }
}
