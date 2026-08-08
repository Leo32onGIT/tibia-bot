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
  moderatorRoleOf: String => String
) extends com.typesafe.scalalogging.StrictLogging {

  /** Every guild this visitor can use, resolved live.
   *
   *  `userGuildIds` comes from their login (see [[UserGuildCache]]) and is used
   *  only to narrow the bot's several hundred guilds to the few worth a REST
   *  call — it grants nothing. Everything that decides access is read here,
   *  now, so a stale or tampered-with guild list can at worst make this do less
   *  work, never more.
   *
   *  An empty list therefore means "no hint", not "no guilds". It happens
   *  whenever the cache has aged out or the process restarted, and treating it
   *  as an answer would show somebody with a perfectly valid session an empty
   *  dashboard until they signed in again. So every guild is considered
   *  instead, and the checks below decide as they always do — a visitor who is
   *  not in a guild, or cannot see any of its worlds, still resolves to no
   *  access.
   *
   *  The cost of that is bounded by how many guilds have the respawn system set
   *  up rather than by how many the bot is in, since `respawnConfigured` is a
   *  cheap local read and dismisses the rest before any REST call.
   */
  def accessFor(userId: String, userGuildIds: Set[String]): List[GuildAccess] = {
    val candidates =
      if (userGuildIds.isEmpty) discordGateway.guilds
      else discordGateway.guilds.filter(g => userGuildIds.contains(g.getId))
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
        else resolveGuild(userId, guild.getId, guild.getName, worlds)
      }
    }
  }

  private def resolveGuild(userId: String, guildId: String, guildName: String,
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
          visibleWorlds
        ))
      }
    }

  /** Where to send this visitor when they arrive. */
  def entryFor(userId: String, userGuildIds: Set[String]): DashboardEntry =
    DashboardAccess.entryFor(accessFor(userId, userGuildIds))

  /** Whether a request may act on `guildId` at `required` or better.
   *
   *  Resolved fresh every time rather than read from anything cached: this is
   *  the check that actually grants a mutation, and a moderator who lost the
   *  role a minute ago must not still be able to move somebody else's claim.
   */
  def permits(userId: String, userGuildIds: Set[String], guildId: String, required: AccessTier): Boolean =
    DashboardAccess.permits(accessFor(userId, userGuildIds), guildId, required)
}
