package com.tibiabot.web

import com.tibiabot.discord.DiscordGateway

/** The parts of a tracked world this needs: its name, and the category whose
 *  visibility stands in for belonging to that world's team.
 *
 *  Narrower than `domain.Worlds` on purpose. That carries twenty-six fields
 *  about channels, roles and display options, none of which bear on who may
 *  open the dashboard — depending on it would drag all of them into every test
 *  here for the sake of two.
 */
final case class WorldChannel(name: String, categoryId: String)

/** Resolves which guilds a signed-in visitor may use the respawn dashboard in,
 *  and at what tier.
 *
 *  The lookups are injected as plain functions rather than repositories so this
 *  can be exercised without a database or a Discord connection; the decisions
 *  themselves live in [[DashboardAccess]], which has no dependencies at all.
 *
 *  `respawnConfigured` and `worldsOf` are per-guild reads against that guild's
 *  own database; `moderatorRoleOf` is the guild's configured "Violent Bot
 *  Moderator" role id, or "0" when it has none.
 */
final class DashboardAccessService(
  discordGateway: DiscordGateway,
  respawnConfigured: String => Boolean,
  worldsOf: String => List[WorldChannel],
  moderatorRoleOf: String => String,
  /** The bot's own support Discord, which is kept out of the guild picker —
   *  see [[DashboardAccess.entryFor]]. Empty means there is no such guild, and
   *  every guild counts. */
  demoGuildId: String = "",
  cache: AccessCache = new AccessCache(AccessCache.DefaultTtl),
  /** Guilds another bot in this fleet runs, which this process cannot resolve
   *  for itself — see [[RemoteGuildAccess]]. Absent on a deployment with no
   *  Redis or no other bots, where it costs nothing and contributes nothing. */
  remote: Option[RemoteGuildAccess] = None,
  /** How long to let the other bots answer before rendering without them. Kept
   *  a little above [[RemoteGuildAccess.DefaultTimeout]] so the wait is decided
   *  there, by the part that can say which guild gave up, rather than here.
   *
   *  This is a backstop and should not be what fires. When it did, it meant a
   *  page load held one of four blocking threads for its whole duration — so
   *  the ceiling matters more than the guild it was waiting on. */
  remoteWait: java.time.Duration = java.time.Duration.ofSeconds(2)
) extends com.typesafe.scalalogging.StrictLogging {

  /** As [[accessFor]], but willing to answer from the last few seconds.
   *
   *  For reads, and for a member acting on their own claim. Working this out
   *  costs a Discord REST call per candidate guild, and a board left open polls
   *  every ten seconds — so without this, watching a page cost six round trips
   *  a minute and put that latency on every one of them.
   *
   *  Anything that acts on somebody else's claim calls [[accessFor]] instead,
   *  because the cost of a stale answer there is a moderator who lost the role
   *  still being able to move people off spawns. The worst this can do is let
   *  somebody read a board they were removed from moments ago.
   *
   *  `mustInclude` names the guild the caller is about to check, where there is
   *  one. A remembered answer that does not contain it is thrown away and the
   *  question asked again, because the only thing that answer can produce is a
   *  refusal — and a refusal is exactly what a half-resolved list looks like.
   *  One page load that lost its race with another bot would otherwise be
   *  remembered as "no such server for you" and refuse every reload for the
   *  next three quarters of a minute.
   *
   *  It costs nothing in the ordinary case: a visitor reading a board they can
   *  see is answered from the memory as before, and it is only the load that
   *  was about to fail anyway that pays for a fresh lookup.
   */
  def rememberedAccessFor(userId: String, userGuildIds: Set[String],
                          mustInclude: Option[String] = None): List[GuildAccess] = {
    val key = s"$userId:${userGuildIds.toList.sorted.mkString(",")}"
    def usable(granted: List[GuildAccess]) =
      mustInclude.forall(guildId => granted.exists(_.guildId == guildId))
    cache.get(key).filter(usable).getOrElse {
      val fresh = accessFor(userId, userGuildIds)
      cache.put(key, fresh)
      fresh
    }
  }

  /** Every guild this visitor can use, resolved live.
   *
   *  `userGuildIds` comes from their login (see [[UserGuildCache]]) and is used
   *  only to narrow the bot's several hundred guilds to the few worth a REST
   *  call — it grants nothing. Everything that decides access is read here,
   *  now, so a stale or tampered-with guild list can at worst make this do less
   *  work, never more.
   *
   *  An empty list resolves to no access, and deliberately so.
   *
   *  It briefly did the opposite: an empty list was treated as "no hint" and
   *  every guild was considered, so that somebody whose cache had aged out was
   *  not shown an empty dashboard. That is a real problem, but this was the
   *  wrong answer to it. Each candidate costs a blocking member lookup, and
   *  after a restart *every* visitor's list is empty at once — so the fallback
   *  turned one page load into one REST call per respawn-configured guild and
   *  pushed `GET /dashboard` past akka's request timeout in production.
   *
   *  The narrowing is what keeps this affordable, so it stays. The cache going
   *  empty wants fixing where it happens — by outliving a restart — rather than
   *  by scanning everything each time it does.
   */
  def accessFor(userId: String, userGuildIds: Set[String]): List[GuildAccess] =
    localAccessFor(userId, userGuildIds) ++ remoteAccessFor(userId, userGuildIds)

  /** Guilds another bot runs, resolved by asking it.
   *
   *  A visitor's tier is their roles and what channels they can see, and only a
   *  bot in the guild can be told either — so a guild run elsewhere could never
   *  be resolved here and was left out of the picker entirely, however plainly
   *  the visitor belonged to it.
   *
   *  Blocking, unlike everything it calls: this whole service is already run on
   *  the blocking pool by the routes above (see `RespawnDashboardRoute.read`),
   *  and every caller is shaped around getting a list rather than a promise of
   *  one. The wait is bounded twice over — once per guild inside, once here —
   *  and a timeout yields no guilds rather than an error, so the worst it can
   *  do is show a picker one server short.
   */
  private def remoteAccessFor(userId: String, userGuildIds: Set[String],
                             remembering: Boolean = true): List[GuildAccess] =
    remote.fold(List.empty[GuildAccess]) { resolver =>
      try scala.concurrent.Await.result(
        resolver.accessFor(userId, userGuildIds, remembering),
        scala.concurrent.duration.Duration.fromNanos(remoteWait.toNanos))
      catch {
        case scala.util.control.NonFatal(e) =>
          logger.warn(s"Gave up waiting on other bots for dashboard access: ${e.getMessage}")
          Nil
      }
    }

  /** Whether this process is in the guild at all, and so whether it can decide
   *  anything about who somebody is there.
   *
   *  The question behind who checks permission for a write. A bot that cannot
   *  see the guild has no way to read a member of it, and asking the bot that
   *  can — then deciding here on its answer — is a round trip that can fail for
   *  somebody perfectly entitled. So a write into a guild this bot cannot see is
   *  carried to the bot that can, and decided there; see [[RespawnCommand]]. */
  def canSee(guildId: String): Boolean = discordGateway.guildById(guildId) != null

  /** One guild, resolved here and never by asking anyone else.
   *
   *  What [[AccessQueryConsumer]] answers with. It must not be the full
   *  [[accessFor]]: that asks the other bots in turn, so answering a question
   *  with it would have two processes asking each other the same one until both
   *  timed out.
   */
  def localAccessIn(userId: String, guildId: String): Option[GuildAccess] =
    localAccessFor(userId, Set(guildId)).headOption

  private def localAccessFor(userId: String, userGuildIds: Set[String]): List[GuildAccess] = {
    val candidates = discordGateway.guilds.filter(g => userGuildIds.contains(g.getId))
    candidates.flatMap { guild =>
      val guildId = guild.getId
      // Checked before the REST call, because it is a cheap local read and a
      // guild that never set the respawn system up can be dismissed without
      // asking Discord anything.
      if (!respawnConfigured(guildId)) None
      else {
        // A world with no category recorded can't be used to prove anything, so
        // it is dropped rather than treated as visible to everyone.
        val worlds = worldsOf(guildId).filter(_.categoryId.nonEmpty)
        if (worlds.isEmpty) None
        // The icon is read here because this is the only place holding the JDA
        // guild; it is null for a guild that never set one, which Option turns
        // into an absence the page can fall back from.
        else resolveGuild(userId, guild.getId, guild.getName, Option(guild.getIconUrl), worlds)
      }
    }
  }

  private def resolveGuild(userId: String, guildId: String, guildName: String,
                           iconUrl: Option[String],
                           worlds: List[WorldChannel]): Option[GuildAccess] =
    discordGateway.memberAccess(guildId, userId, worlds.map(_.categoryId)).flatMap { member =>
      val visibleWorlds = worlds.filter(w => member.visibleChannelIds.contains(w.categoryId)).map(_.name)
      if (!DashboardAccess.eligible(respawnConfigured = true, visibleWorlds)) None
      else {
        val moderatorRole = moderatorRoleOf(guildId)
        // An unset role id must not match anything — a guild with no moderator
        // role would otherwise promote everyone who happens to hold no roles.
        val hasRole = moderatorRole.nonEmpty && moderatorRole != "0" &&
          member.roleIds.contains(moderatorRole)
        Some(GuildAccess(
          guildId, guildName,
          AccessTier.of(member.hasManageServer, hasRole),
          visibleWorlds,
          iconUrl
        ))
      }
    }

  /** Where to send this visitor when they arrive. */
  def entryFor(userId: String, userGuildIds: Set[String]): DashboardEntry =
    entryOf(accessFor(userId, userGuildIds))

  /** The same decision over guilds already resolved.
   *
   *  Split out because the landing page needs both halves — where to send them,
   *  and the list itself, which is what tells the board's header whether there
   *  is anywhere to switch to — and resolving access twice costs a Discord REST
   *  call per candidate guild. */
  def entryOf(accesses: List[GuildAccess]): DashboardEntry =
    DashboardAccess.entryFor(accesses, demoGuildId)

  /** Whether a request may act on `guildId` at `required` or better.
   *
   *  Resolved fresh every time rather than read from anything cached: this is
   *  the check that actually grants a mutation, and a moderator who lost the
   *  role a minute ago must not still be able to move somebody else's claim.
   */
  def permits(userId: String, userGuildIds: Set[String], guildId: String, required: AccessTier): Boolean =
    DashboardAccess.permits(accessIn(userId, userGuildIds, guildId), guildId, required)

  /** Access in one named guild, resolved fresh.
   *
   *  Whoever asks this already knows which guild they mean, so the other bots
   *  are only troubled when the answer can actually come from one of them.
   *  Going through the full [[accessFor]] made every moderator action — a
   *  force-leave, a reassign — wait on Redis and on however many bots had
   *  published a roster, for guilds that had nothing to do with the action.
   *
   *  A guild run elsewhere is asked about without the standing memory that
   *  [[RemoteGuildAccess]] keeps for reads. This is the check that grants a
   *  mutation, so a bot that cannot say *now* whether somebody is still a
   *  moderator has to be taken as a no.
   */
  def accessIn(userId: String, userGuildIds: Set[String], guildId: String): List[GuildAccess] =
    if (!userGuildIds.contains(guildId)) Nil
    else if (discordGateway.guildById(guildId) != null) localAccessFor(userId, Set(guildId))
    else remoteAccessFor(userId, Set(guildId), remembering = false)
}
