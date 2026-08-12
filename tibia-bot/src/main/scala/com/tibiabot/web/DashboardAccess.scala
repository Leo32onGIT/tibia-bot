package com.tibiabot.web

/** What someone may do on the respawn dashboard within one guild.
 *
 *  The tiers nest rather than being independent flags, because
 *  [[com.tibiabot.commands.Permissions.isModerator]] already treats Manage
 *  Server as implying the moderator role — somebody who can administer the
 *  server can obviously also move a claim. Modelling them as a ladder keeps a
 *  route's guard a single comparison and makes it impossible to write a check
 *  that accidentally excludes admins.
 */
sealed abstract class AccessTier(val rank: Int, val name: String) {
  /** Whether this tier is sufficient where `required` is asked for. */
  def atLeast(required: AccessTier): Boolean = rank >= required.rank
}

object AccessTier {
  /** Can see the board and act on their own claims and bookings. Earned purely
   *  by being able to see a world's channels — no role needed. */
  case object Member extends AccessTier(0, "member")

  /** The guild's "Violent Bot Moderator" role. Adds the tools that act on other
   *  people: force-leave, reassign, grant stamina, cancel someone else's
   *  booking. */
  case object Moderator extends AccessTier(1, "moderator")

  /** Manage Server. Everything a moderator has, plus the guild-level settings
   *  the moderator role deliberately does not delegate. */
  case object Admin extends AccessTier(2, "admin")

  /** The tier a member's Discord permissions earn them.
   *
   *  Mirrors `Permissions.isModerator`'s rule exactly — Manage Server *or* the
   *  role grants moderator powers — rather than restating it, so the web and
   *  the slash commands can't drift into disagreeing about who is trusted. */
  def of(hasManageServer: Boolean, hasModeratorRole: Boolean): AccessTier =
    if (hasManageServer) Admin
    else if (hasModeratorRole) Moderator
    else Member
}

/** One guild a visitor may use the respawn dashboard in, and the worlds within
 *  it whose channels they can see.
 *
 *  `worlds` is what the visitor proved access *through*, not a filter on what
 *  they will then see: the respawn catalogue is guild-wide (`Respawn.world` is
 *  stored but nothing reads it), so a guild's whole catalogue is in scope once
 *  any one of its worlds is visible. Keeping the list is still worth it — it is
 *  what the guild picker shows to tell two entries apart, and it is the
 *  evidence behind the decision. */
final case class GuildAccess(
  guildId: String,
  guildName: String,
  tier: AccessTier,
  worlds: List[String],
  /** The guild's own icon on Discord's CDN, when it has set one. Absent for a
   *  guild that never did, which is common enough that every surface showing it
   *  needs a fallback rather than a broken image.
   *
   *  Defaulted so that the many places constructing this for a test — where the
   *  icon is beside the point — need say nothing about it. */
  iconUrl: Option[String] = None
)

/** Where a visitor lands after signing in. */
sealed trait DashboardEntry
object DashboardEntry {
  /** Signed in, but there is nothing here for them — not in a guild with the
   *  bot, or in one where they cannot see any tracked world. Deliberately its
   *  own case rather than an empty picker, because the two want completely
   *  different words on screen. */
  case object Nowhere extends DashboardEntry

  /** Exactly one guild, so there is nothing to ask. */
  final case class Straight(access: GuildAccess) extends DashboardEntry

  /** Several, so they pick. */
  final case class Choose(options: List[GuildAccess]) extends DashboardEntry
}

/** The access decisions, kept free of JDA so the whole table can be checked
 *  without a Discord connection. The lookups that feed it live in
 *  [[DashboardAccessService]].
 */
object DashboardAccess {

  /** Whether a guild is usable at all.
   *
   *  Two independent conditions, and both are load-bearing. The respawn system
   *  has to actually be set up, or there is no board to show. And the visitor
   *  has to be able to see at least one tracked world's category — that is what
   *  stands in for "belongs to this community", since being in a Discord server
   *  says nothing about whether its Tibia team let you into their channels. */
  def eligible(respawnConfigured: Boolean, visibleWorlds: List[String]): Boolean =
    respawnConfigured && visibleWorlds.nonEmpty

  /** Where to send someone, given everything they can reach.
   *
   *  Sorted by guild name so a picker is stable between visits rather than
   *  reflecting whatever order the guilds happened to be resolved in.
   *
   *  `demoGuildId` is the bot's own support Discord, which almost everybody who
   *  uses the bot has joined and almost nobody hunts in. Counting it would put a
   *  picker in front of every single member of one community — a question with
   *  one real answer — so it is set aside: somebody with one community of their
   *  own goes straight there, and the choice is only asked when there are two
   *  communities to choose between.
   *
   *  It is set aside rather than removed. Somebody whose *only* respawn forum is
   *  the support server has come to look at the thing, and taking them straight
   *  to it is the demo. Nothing here grants access — every guild in `accesses`
   *  was already resolved against the live guild — so this only decides where a
   *  visitor lands, and the support server stays reachable by its own URL either
   *  way. */
  def entryFor(accesses: List[GuildAccess], demoGuildId: String = ""): DashboardEntry = {
    val sorted = accesses.sortBy(_.guildName.toLowerCase)
    val theirs = if (demoGuildId.isEmpty) sorted else sorted.filterNot(_.guildId == demoGuildId)
    theirs match {
      case Nil =>
        // Only the demo left, if anything.
        sorted.headOption.fold[DashboardEntry](DashboardEntry.Nowhere)(DashboardEntry.Straight)
      case single :: Nil => DashboardEntry.Straight(single)
      case many          => DashboardEntry.Choose(many)
    }
  }

  /** Whether a request for `guildId` may proceed at `required` or better.
   *
   *  Every mutating route goes through this rather than trusting anything the
   *  browser sent. A cached guild list may decide what a *menu* offers, but the
   *  thing that grants access is this check against freshly resolved access —
   *  otherwise a stale cache becomes a permission, and someone who was removed
   *  from a guild keeps acting in it until their session expires. */
  def permits(accesses: List[GuildAccess], guildId: String, required: AccessTier): Boolean =
    accesses.exists(a => a.guildId == guildId && a.tier.atLeast(required))
}
