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

  val All: List[AccessTier] = List(Member, Moderator, Admin)

  /** A tier back from the name it travels under between processes. None for
   *  anything unrecognised, so a build that learns a fourth tier is ignored by
   *  one that has not rather than being read as some arbitrary third. */
  def byName(name: String): Option[AccessTier] = All.find(_.name == name)
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

/** A guild that could not be resolved this time round.
 *
 *  Not the same as one the visitor may not use — that one is simply absent.
 *  This is a guild whose answer never arrived: a rate-limited Discord lookup,
 *  or another bot in the fleet that did not reply inside its second. The name
 *  is whatever was known without asking, which is the guild's own name locally
 *  and the roster's copy of it for a guild run elsewhere.
 */
final case class UnreachableGuild(guildId: String, guildName: String)

/** Everything one resolution pass found out, including what it failed to find
 *  out.
 *
 *  Resolution used to yield a bare list, which made "you belong to one server"
 *  and "we could only reach one of your servers" the same value. They are not
 *  remotely the same thing to act on: the first is a reason to skip the picker,
 *  the second is a reason to show it and say what went wrong. Everything that
 *  decides where a visitor lands now reads this rather than a list.
 */
final case class AccessReport(granted: List[GuildAccess], unreachable: List[UnreachableGuild],
                              fleetUnknown: Boolean = false) {
  def ++(other: AccessReport): AccessReport =
    AccessReport(granted ++ other.granted, unreachable ++ other.unreachable,
      fleetUnknown || other.fleetUnknown)

  /** Whether this pass got a straight answer about everything it asked about.
   *
   *  Two ways to fall short of that, and they have to be counted separately
   *  because only one of them can be put on screen. `unreachable` is a guild we
   *  knew to ask about and did not hear back from: the picker names it, and the
   *  visitor can see that something is missing. `fleetUnknown` is not having
   *  found out what there was to ask about — the rosters are the only record of
   *  which guilds the other bots run, and a pass that could not read them
   *  cannot tell a visitor with one server from one whose other three are
   *  behind a bot it never heard of.
   *
   *  That second kind has nothing to name and so is invisible on the page,
   *  which is precisely why it must not be stored as though it were the whole
   *  answer. Left claiming completeness it took [[AccessCache]]'s full ten
   *  minutes, and a Redis blip of a moment turned into a picker missing a
   *  server for the rest of the visitor's session.
   */
  def complete: Boolean = unreachable.isEmpty && !fleetUnknown
}

object AccessReport {
  val Empty: AccessReport = AccessReport(Nil, Nil)
  def of(granted: List[GuildAccess]): AccessReport = AccessReport(granted, Nil)

  /** Nothing resolved, and not because there was nothing to resolve — as
   *  distinct from [[Empty]], which is the honest answer for a visitor who is
   *  in no guild any other bot runs. */
  val FleetUnknown: AccessReport = AccessReport(Nil, Nil, fleetUnknown = true)
}

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

  /** Several, so they pick. `unreachable` names servers that belong on this
   *  list but did not answer — normally empty, and shown as a note when it is
   *  not, so a short list explains itself instead of looking authoritative. */
  final case class Choose(options: List[GuildAccess],
                          unreachable: List[UnreachableGuild] = Nil) extends DashboardEntry

  /** Nothing resolved, and not because there is nothing: every candidate failed
   *  to answer. Distinct from [[Nowhere]] because "you have no servers here"
   *  and "we could not reach your servers" are opposite advice — the first says
   *  set the bot up, the second says try again in a moment. */
  final case class Unreachable(guilds: List[UnreachableGuild]) extends DashboardEntry
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
  def entryFor(report: AccessReport, demoGuildId: String = ""): DashboardEntry = {
    val sorted = report.granted.sortBy(_.guildName.toLowerCase)
    val theirs = if (demoGuildId.isEmpty) sorted else sorted.filterNot(_.guildId == demoGuildId)
    val missing = report.unreachable.sortBy(_.guildName.toLowerCase)

    // Split on whether the answer is complete before anything else, because
    // every shortcut below is only safe when it is. The two halves used to be
    // one, on the reasoning that a guild we could not resolve and a guild the
    // visitor may not use amount to the same absence — which is true of the
    // list and false of what to do about it.
    if (missing.isEmpty) theirs match {
      case Nil =>
        // Only the demo left, if anything.
        sorted.headOption.fold[DashboardEntry](DashboardEntry.Nowhere)(DashboardEntry.Straight)
      case single :: Nil => DashboardEntry.Straight(single)
      case many          => DashboardEntry.Choose(many, Nil)
    }
    // Incomplete, so never straight through. A single guild is only "nothing to
    // ask" when it is the whole truth; when something failed to answer, that
    // same single guild is a list we already know to be short, and skipping the
    // picker would drop somebody into a board they never chose without ever
    // mentioning the one they came for. The demo is not a consolation prize
    // here either — being sent into the support server because your own did not
    // answer is the same silent misdirection wearing a friendlier face.
    else theirs match {
      case Nil if sorted.isEmpty => DashboardEntry.Unreachable(missing)
      case Nil                   => DashboardEntry.Choose(sorted, missing)
      case some                  => DashboardEntry.Choose(some, missing)
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
