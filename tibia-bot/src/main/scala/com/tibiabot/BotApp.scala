package com.tibiabot

import akka.actor.ActorSystem
import com.tibiabot.tibiadata.TibiaDataClient
import com.tibiabot.tibiadata.response.{BoostedResponse, CreatureResponse}
import com.tibiabot.scheduler.ServerSaveSchedule
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.{Guild, MessageEmbed}
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons._
import net.dv8tion.jda.api.{EmbedBuilder, Permission}
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.utils.TimeFormat
import net.dv8tion.jda.api.exceptions.{ErrorHandler, ErrorResponseException}
import net.dv8tion.jda.api.requests.ErrorResponse

import java.time.{Instant, ZonedDateTime}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters._
import java.time.ZoneId
import scala.util.Random
import scala.concurrent.Await
import com.tibiabot.presentation.Embeds.BrandColor
import com.tibiabot.presentation.Names

object BotApp extends App with StrictLogging {

  // Domain model extracted to com.tibiabot.domain. Aliased here (type + companion
  // val) so every existing reference — bare within BotApp and BotApp.X elsewhere —
  // resolves unchanged. Compile-only: no behaviour change.
  type Worlds = domain.Worlds; val Worlds = domain.Worlds
  type Discords = domain.Discords; val Discords = domain.Discords
  type Players = domain.Players; val Players = domain.Players
  type Guilds = domain.Guilds; val Guilds = domain.Guilds
  type BoostedCache = domain.BoostedCache; val BoostedCache = domain.BoostedCache
  type PlayerCache = domain.PlayerCache; val PlayerCache = domain.PlayerCache
  type WorldTransfer = domain.WorldTransfer; val WorldTransfer = domain.WorldTransfer
  type DeathsCache = domain.DeathsCache; val DeathsCache = domain.DeathsCache
  type LevelsCache = domain.LevelsCache; val LevelsCache = domain.LevelsCache
  type ListCache = domain.ListCache; val ListCache = domain.ListCache
  type SatchelStamp = domain.SatchelStamp; val SatchelStamp = domain.SatchelStamp
  type BoostedStamp = domain.BoostedStamp; val BoostedStamp = domain.BoostedStamp
  type DeathScreenshot = domain.DeathScreenshot; val DeathScreenshot = domain.DeathScreenshot
  type CustomSort = domain.CustomSort; val CustomSort = domain.CustomSort
  type BossEntry = domain.BossEntry; val BossEntry = domain.BossEntry

  // Core hunted/allied/world state, read every cycle by the per-world streams and
  // written by command threads. Declared early (before tibiaDataClient below,
  // which needs it) since object fields initialise top-to-bottom.
  val streamState = new state.StreamState

  implicit private val actorSystem: ActorSystem = ActorSystem()
  implicit private val ex: ExecutionContextExecutor = actorSystem.dispatcher

  /** Build a RateLimitedSender ticking every `delayMs`.
   *
   *  The five-minute tick no longer logs — the `[rate-limit:name]` line was a
   *  steady drip of two entries every five minutes forever, and the same figures
   *  are on the dashboard whenever somebody actually wants them.
   *
   *  It still runs, because [[discord.RateLimitedSender.snapshotAndReset]] is the
   *  only thing that rolls the per-label window, and the dashboard reads that
   *  window (see web.StatusRoute). Dropping the tick with the log would quietly
   *  turn "avg wait" from the last five minutes into an average since boot, which
   *  stops moving when a lane starts struggling — the opposite of what it is for.
   */
  private def makeMonitoredSender(name: String, delayMs: Int, perGroupMinGapMs: Long = 0): discord.RateLimitedSender = {
    val sender = new discord.RateLimitedSender(drain => {
      val cancellable = actorSystem.scheduler.scheduleWithFixedDelay(
        0.seconds,
        delayMs.milliseconds
      )(new Runnable { def run(): Unit = drain() })(actorSystem.dispatcher)
      () => cancellable.cancel()
    }, perGroupMinGapMs = perGroupMinGapMs)
    actorSystem.scheduler.scheduleWithFixedDelay(5.minutes, 5.minutes)(() => {
      sender.snapshotAndReset()
      ()
    })(ex)
    sender
  }

  // Shared, bot-wide background lane for low-priority outbound sends (renames,
  // activity/admin embeds, batched level-ups, boosted-DM notifications) — one
  // instance across every world stream so the aggregate send rate is bounded
  // bot-wide, not per world. Deaths and the boosted-channel server-save post
  // are top priority and bypass this entirely.
  val outboundSender = makeMonitoredSender("background", Config.globalMessageDelayMs)

  // Separate, much slower lane just for online-list message edits/sends —
  // Discord rate-limits this traffic far harder than the general REST budget
  // (confirmed via live 429s with Retry-After 4-6s), so it needs its own pace
  // instead of sharing the lane above with everything else. Those limits are
  // per-channel, so the lane is additionally told to space out sends targeting
  // the same channel (see the group argument in dispatchOnlineListUpdate); the
  // bot-wide pace alone can't prevent one channel's several embeds draining
  // back-to-back.
  val onlineListSender = makeMonitoredSender(
    "online-list", Config.onlineListMessageDelayMs, Config.onlineListPerChannelMinGapMs
  )

  // Per-world population/poll-timing/throughput counters and per-world recent-
  // activity feeds, both read by the monitoring dashboard's /status endpoint.
  // Per-world (not one shared bot-wide buffer) so a busy world's events can't
  // push a quiet world's events out of the window.
  val worldMetricsRegistry = new tracking.WorldMetricsRegistry
  val recentEventsRegistry = new tracking.RecentEventsRegistry

  // WorldMetrics' deaths/levels/edits counters are a fixed 15-minute window,
  // reset here rather than on read since the dashboard may poll far more often
  // than the window itself resets.
  actorSystem.scheduler.scheduleWithFixedDelay(15.minutes, 15.minutes)(() => worldMetricsRegistry.resetAllCounters())(ex)

  private val tibiaDataClient: tibiadata.TibiaApi =
    new tibiadata.CachingTibiaApi(new TibiaDataClient(streamState), persistence.RedisCacheProvider.cache,
      Config.Cache.boostedTtl)(scala.concurrent.ExecutionContext.global)
  private val connectionProvider: persistence.ConnectionProvider =
    new persistence.JdbcConnectionProvider(Config.postgresHost, Config.postgresPassword)
  private val schemaInitializer = new persistence.SchemaInitializer(connectionProvider)
  private val boostedRepository: persistence.BoostedRepository =
    new persistence.jdbc.JdbcBoostedRepository(connectionProvider)
  private lazy val wikiClient: wiki.WikiClient = new wiki.FandomWikiClient()
  private val galthenRepository: persistence.GalthenRepository =
    new persistence.jdbc.JdbcGalthenRepository(connectionProvider)
  private val deathScreenshotRepository: persistence.DeathScreenshotRepository =
    new persistence.jdbc.JdbcDeathScreenshotRepository(connectionProvider)
  private val cacheRepository: persistence.CacheRepository =
    new persistence.jdbc.JdbcCacheRepository(connectionProvider)
  private val activityRepository: persistence.ActivityRepository =
    new persistence.jdbc.JdbcActivityRepository(connectionProvider)
  private val worldTransferRepository: persistence.WorldTransferRepository =
    new persistence.jdbc.JdbcWorldTransferRepository(connectionProvider)

  /** How long an announced world transfer is remembered. Must outlast the ~180
   *  days Tibia shows a former world for: prune inside that window and the field
   *  is still there to be detected all over again, so the transfer gets announced
   *  a second time. Past 180 days the field has cleared and the record has nothing
   *  left to suppress, making everything beyond that pure margin. */
  private val TransferRecordRetentionDays = 365L
  private val huntedAlliedRepository: persistence.HuntedAlliedRepository =
    new persistence.jdbc.JdbcHuntedAlliedRepository(connectionProvider)
  private val customSortRepository: persistence.CustomSortRepository =
    new persistence.jdbc.JdbcCustomSortRepository(connectionProvider)
  private val respawnRepository: persistence.RespawnRepository =
    new persistence.jdbc.JdbcRespawnRepository(connectionProvider)
  private val worldConfigRepository: persistence.WorldConfigRepository =
    new persistence.jdbc.JdbcWorldConfigRepository(connectionProvider, Config.mergedWorlds)
  private val discordConfigRepository: persistence.DiscordConfigRepository =
    new persistence.jdbc.JdbcDiscordConfigRepository(connectionProvider)
  private val patreonSeatRepository: persistence.PatreonSeatRepository =
    new persistence.jdbc.JdbcPatreonSeatRepository(connectionProvider)
  private val patreonSeatOverrideRepository: persistence.PatreonSeatOverrideRepository =
    new persistence.jdbc.JdbcPatreonSeatOverrideRepository(connectionProvider)
  private val patreonGraceRepository: persistence.PatreonGraceRepository =
    new persistence.jdbc.JdbcPatreonGraceRepository(connectionProvider)
  val guildActivityRepository: persistence.GuildActivityRepository =
    new persistence.jdbc.JdbcGuildActivityRepository(connectionProvider)
  private val renameCooldownRepository: persistence.RenameCooldownRepository =
    new persistence.jdbc.JdbcRenameCooldownRepository(connectionProvider)
  private val patreonMemberRepository: persistence.PatreonMemberRepository =
    new persistence.jdbc.JdbcPatreonMemberRepository(connectionProvider)

  // Let the games begin
  logger.info("Starting up")

  val jda = app.Bootstrap.buildReadyJda(Config.token, new BotListener())
  logger.info("JDA ready")

  // single read-side seam over JDA (guild/user lookups, identity, presence)
  val discordGateway: discord.DiscordGateway = new discord.JdaDiscordGateway(jda)

  private val guilds: List[Guild] = discordGateway.guilds

  // per-world stream lifecycle
  private val streamSupervisor = new app.StreamSupervisor

  // Galthen's Satchel cooldown tracking
  val galthenService = new galthen.GalthenService(galthenRepository, connectionProvider, discordGateway)

  /** The guild's "Violent Bot Moderator" role id, or "0" if it has none.
   *
   *  Read per invocation rather than cached: it changes on /setup and /repair,
   *  and a stale id would silently lock out everyone holding the role. */
  def moderatorRoleId(guildId: String): String =
    try discordConfigRepository.getConfig(guildId).getOrElse("moderator_role", "0")
    catch {
      // A guild with no config yet has no role either; falling back to "0" means
      // the check simply reduces to Manage Server.
      case _: Throwable => "0"
    }

  // Respawn claim system — see respawn.RespawnService. Constructed regardless of
  // Config.Respawn.enabled (it does no I/O until called, and the repository
  // creates its tables lazily on first use); the flag gates command
  // registration, channel creation and the sweep below instead.
  val respawnService = new respawn.RespawnService(respawnRepository)

  // Which guilds' respawn systems are this bot identity's to run. Several bots
  // can share a guild and all sweep the same per-guild database; this is what
  // stops them answering each other's claims (see respawn.RespawnOwnership).
  private val respawnOwnership = new respawn.RespawnOwnership(discordGateway.selfUserId)

  // Per-user boosted boss/creature notification subscriptions. Takes this bot's
  // own id for the same reason respawnOwnership above does: bots sharing a
  // bot_cache database must not consume each other's server-save state.
  val boostedService = new boosted.BoostedService(connectionProvider, boostedRepository, cacheRepository, tibiaDataClient, () => boostedBossesList, discordGateway.selfUserId)

  // Ties bot activity to a Patreon subscription via a seat system (see
  // paywall.PaywallService): /setup checks the caller, then assigns one of
  // their seats to that (guild, world) pair; that pair's activity keeps
  // posting only while Patreon still reports that seat's owner an active
  // patron (read from patreonMemberRepository's synced snapshot below — the
  // support Discord's role plays no part in this any more), and a world that
  // stops checking out (including one that was never seated at all) gets
  // Config.Patreon.graceDays to sort it out before anything is actually
  // paused. Seat count is Config.Patreon.seatsPerUser plus any per-user
  // adjustment granted through the dashboard (see
  // PaywallService.effectiveSeatLimit).
  val paywallService = new paywall.PaywallService(discordGateway, patreonSeatRepository, patreonSeatOverrideRepository, patreonGraceRepository, patreonMemberRepository, Config.Patreon.supportGuildId, Config.Patreon.seatsPerUser, Config.Patreon.graceDays, discordGateway.applicationOwnerId)

  // Direct Patreon API access — see Config.PatreonApi and syncPatreonMembers
  // below. What this syncs is the source of truth for who's subscribed, so
  // the paywall above depends on it running; it also feeds the dashboard's
  // supporters panel.
  private val patreonApiClient = new patreonapi.PatreonApiClient()(actorSystem, ex)

  // Per-guild hunted/allied player and guild list CRUD
  val huntedAlliedService = new hunted.HuntedAlliedService(
    huntedAlliedRepository, activityRepository, cacheRepository, streamState, tibiaDataClient,
    discordRetrieveConfig _, worldConfig _, checkConfigDatabase _
  )

  // Per-guild custom online-list tag categories (/neutral tag ...)
  val customSortService = new customsort.CustomSortService(
    customSortRepository, streamState, tibiaDataClient, huntedAlliedService.fetchPlayerSummary _,
    discordRetrieveConfig _, checkConfigDatabase _
  )

  // get bot userID (used to stamp automated enemy detection messages)
  val botUser = discordGateway.selfUserId
  // ...and what it is called, which is what those messages actually show. A
  // mention would ping the bot's own role in every guild it logs to.
  val botUserName: String = discordGateway.selfUserName
  // the application owner = the bot creator (used to gate /admin)
  val botOwner: String = discordGateway.applicationOwnerId

  // Bot-creator-only /admin operations (needs botUser, defined above)
  val adminService = new admin.AdminService(
    discordGateway,
    botUser,
    discordRetrieveConfig _,
    () => refreshDreamScarBosses(shiftOnFailure = false),
    () => refreshBoostedMessages()
  )

  // Two gated areas on the bot's public domain, which also serves an unrelated,
  // unauthenticated landing page at its root via Caddy passing through to
  // GitHub Pages (see Caddyfile).
  //
  //   /dashboard — the member-facing respawn dashboard, and where the OAuth
  //                routes live. Primary, so a bare login lands here and the
  //                redirect URI already registered with Discord is unchanged.
  //   /status    — the owner-only monitoring dashboard. Named for what its own
  //                masthead has always said (`Violent Bot /status`) rather than
  //                something new.
  //
  // One login covers both: the callback sets the session cookie under each
  // path. Deliberately not one cookie at `/`, which would hand the session to
  // GitHub Pages on every visit to the landing page.
  //
  // The OAuth client id is the bot's own application id, which for a standard
  // bot is the same snowflake as its user id.
  private val dashboardMountPath = "/dashboard"
  private val adminMountPath = "/status"
  private val discordAuth = new web.DiscordAuth(
    clientId = botUser,
    clientSecret = Config.Web.discordClientSecret,
    sessionSecret = Config.Web.sessionSecret,
    redirectUri = s"${Config.Web.baseUrl}$dashboardMountPath/auth/callback",
    mountPath = dashboardMountPath,
    extraCookiePaths = List(adminMountPath),
    secureCookies = Config.Web.secureCookies,
    // So a dashboard link pasted into Discord unfurls as this bot rather than as
    // Discord itself, which is what the crawler found at the end of the sign-in
    // redirect it was following. Built from the configured origin, so it names
    // whichever domain this deployment actually answers on, and picked per area
    // so the two mounts above describe themselves rather than the site.
    linkPreview = Some(web.LinkPreview.forPath(Config.Web.baseUrl))
  )(actorSystem, ex)
  private val statusRoute = new web.StatusRoute(
    discordAuth, botOwner, streamSupervisor, worldMetricsRegistry, recentEventsRegistry,
    outboundSender, onlineListSender, discordGateway, web.LogCapture.instance, paywallService, patreonMemberRepository
  )
  private val patreonAdminRoute = new web.PatreonAdminRoute(discordAuth, botOwner, paywallService)
  // Guilds another bot in the fleet runs. This one cannot resolve a visitor
  // there — roles and channel visibility can only be read by a bot actually in
  // the guild — so it asks, over the same Redis the write relay uses. Absent
  // without Redis, where there is no fleet to ask.
  private val remoteGuildAccess =
    if (Config.redisEnabled)
      Some(new web.RemoteGuildAccess(
        persistence.RedisCacheProvider.cache, actorSystem.scheduler,
        isLocal = guildId => discordGateway.guildById(guildId) != null)(ex))
    else None
  private val dashboardAccessService = new web.DashboardAccessService(
    discordGateway,
    respawnConfigured = guildId => respawnService.settings(guildId).isDefined,
    worldsOf = guildId => worldConfigRepository.listWorlds(guildId)
      .map(w => web.WorldChannel(w.name, w.category)),
    moderatorRoleOf = moderatorRoleId,
    // Nearly everybody who uses the bot is in the support Discord, so counting
    // it would ask almost every visitor to choose between their own community
    // and ours. It is still reachable, and it is where somebody with no
    // community of their own lands.
    demoGuildId = Config.Patreon.supportGuildId,
    remote = remoteGuildAccess
  )
  // Fetched on this host, which can reach the wiki even where the people
  // looking at the dashboard cannot, and served back from our own domain.
  private val creatureSpriteCache = new web.CreatureSpriteCache(
    java.nio.file.Paths.get(Config.Respawn.spriteCacheDir),
    new web.WikiSpriteFetcher()(actorSystem, ex).fetch
  )(ex)
  // Writes go through their own seam, which is where the "is this bot the one
  // that runs this guild's respawns" check lives — see JdaRespawnActions.
  // Its own small pool: every dashboard write is blocking work (JDA REST calls
  // and database round trips), and running it on the HTTP dispatcher would let a
  // handful of slow claims starve the server that is meant to be answering
  // everybody else.
  private val respawnActionPool = scala.concurrent.ExecutionContext.fromExecutorService(
    java.util.concurrent.Executors.newFixedThreadPool(4, (r: Runnable) => {
      val thread = new Thread(r, "respawn-action")
      thread.setDaemon(true)
      thread
    }))
  private val localRespawnActions =
    new web.JdaRespawnActions(discordGateway, respawnService, respawnOwnership)(respawnActionPool)
  // A guild whose respawns another bot runs has its writes handed to that
  // process through Redis; reads never relay, since every bot shares the
  // guild's database.
  private val relayedRespawnActions = new web.RelayedRespawnActions(
    persistence.RedisCacheProvider.cache, actorSystem.scheduler)(respawnActionPool)
  private val respawnActions = new web.RoutingRespawnActions(
    localRespawnActions, relayedRespawnActions, localRespawnActions.ownsGuild)
  // The other half: perform what somebody else's dashboard handed to us. Every
  // bot runs one, and each only touches guilds it runs, so exactly one process
  // executes any given command.
  private val respawnCommandConsumer = new web.RespawnCommandConsumer(
    persistence.RedisCacheProvider.cache, localRespawnActions,
    localRespawnActions.ownsGuild, discordGateway.selfUserId,
    // The permission check for every relayed write, made here because this is
    // the process that is actually in the guild. The same resolution the access
    // relay answers questions with, and deliberately the local one: a bot going
    // back out over Redis to ask about its own guild would be asking itself.
    resolve = (guildId, userId) => dashboardAccessService.localAccessIn(userId, guildId))(respawnActionPool)
  if (Config.Respawn.enabled && Config.redisEnabled) {
    actorSystem.scheduler.scheduleWithFixedDelay(
      web.RespawnCommandConsumer.SweepEvery, web.RespawnCommandConsumer.SweepEvery
    )(() => { respawnCommandConsumer.sweep(); () })(ex)
    logger.info("Respawn command relay listening for writes from other bots' dashboards")
  }
  // The other side of the access relay: answer what somebody else's dashboard
  // asks about a guild this bot is in. Resolved locally on purpose — going back
  // out over Redis here would have two bots asking each other the same question
  // until both timed out.
  private val accessQueryConsumer = new web.AccessQueryConsumer(
    persistence.RedisCacheProvider.cache,
    resolve = (guildId, userId) => dashboardAccessService.localAccessIn(userId, guildId),
    canSee = guildId => discordGateway.guildById(guildId) != null)(ex)

  /** Republished well inside its own TTL, so a missed beat or two never drops
   *  this bot's guilds out of anybody's picker. */
  private val guildRosterPublishEvery = 30.seconds
  private val guildRosterTtl = 2.minutes

  /** The guilds this bot could answer about, so the others know to ask.
   *
   *  Only guilds with the respawn system set up: the dashboard has nothing to
   *  show for the rest, and naming them would have other bots asking questions
   *  whose answer is always no. Republished rather than kept, so a bot that dies
   *  falls out of everyone's picker within the TTL instead of being asked about
   *  forever.
   */
  private def publishGuildRoster(): Unit =
    try {
      val guilds = discordGateway.guilds
        .filter(g => respawnService.settings(g.getId).isDefined)
        .map(g => web.RosterGuild(g.getId, g.getName, Option(g.getIconUrl)))
      persistence.RedisCacheProvider.cache.setEx(
        web.GuildRoster.key(discordGateway.selfUserId),
        web.GuildRoster(discordGateway.selfUserId, guilds).toJson,
        guildRosterTtl
      ).recover { case e: Throwable => logger.warn(s"Failed to publish guild roster: ${e.getMessage}") }(ex)
    } catch {
      case e: Throwable => logger.warn("Failed to build guild roster", e)
    }

  if (Config.Respawn.enabled && Config.redisEnabled) {
    actorSystem.scheduler.scheduleWithFixedDelay(
      web.AccessQueryConsumer.SweepEvery, web.AccessQueryConsumer.SweepEvery
    )(() => { accessQueryConsumer.sweep(); () })(ex)
    // Published straight away as well as on the beat, so a bot restarting does
    // not drop out of everybody else's server picker for half a minute.
    actorSystem.scheduler.scheduleWithFixedDelay(
      scala.concurrent.duration.Duration.Zero, guildRosterPublishEvery
    )(() => publishGuildRoster())(ex)
    logger.info("Dashboard access relay listening for questions from other bots' dashboards")
  }
  // One read of a guild's board answers every tab watching it — see
  // BoardSnapshotCache. Cleared by a dashboard write so whoever made it sees it
  // straight away; everything else shows up within the few seconds it holds.
  private val boardSnapshots = new web.BoardSnapshotCache(guildId => respawnService.board(guildId))
  private val respawnDashboardRoute =
    new web.RespawnDashboardRoute(discordAuth, dashboardAccessService, creatureSpriteCache,
      boardOf = guildId => boardSnapshots.board(guildId),
      // None when the guild never set the respawn system up, which is the same
      // condition that leaves it out of the access funnel in the first place.
      limitsOf = (guildId, userId) => respawnService.settings(guildId).map { settings =>
        web.BoardLimits.from(
          respawnService.stamina(guildId, userId, settings),
          settings.maxDurationMinutes,
          settings.defaultDurationMinutes,
          respawnService.nextStaminaReset())
      },
      peopleOf = guildId => respawnService.knownMembers(guildId),
      actions = respawnActions,
      boardChanged = boardSnapshots.invalidate)(respawnActionPool)
  // A shared-world-cycle secondary doesn't run its own dashboard at all —
  // its worlds/guilds are instead published (below) for the primary's
  // dashboard to merge in, so no HTTP server, no Caddy, no second domain needed.
  if (Config.BotRole.current != Config.BotRole.Secondary) {
    import akka.http.scaladsl.server.Directives._
    // The auth routes stay under /dashboard, where their redirect URI already
    // points; /status reaches them via the session cookie set for both paths.
    val routes = concat(
      pathPrefix("dashboard") { concat(discordAuth.routes, respawnDashboardRoute.routes) },
      pathPrefix("status") { concat(statusRoute.routes, patreonAdminRoute.routes) }
    )
    akka.http.scaladsl.Http()(actorSystem).newServerAt("0.0.0.0", Config.Web.statusPort).bind(routes)
    logger.info(s"Dashboards listening internally on port ${Config.Web.statusPort}: " +
      s"members at $dashboardMountPath, owner at $adminMountPath")
  } else {
    // Matches the dashboard's own ~10s poll cadence (StatusRoute's comment on
    // buildPatreonJson) — frequent enough that a primary's merged view feels
    // live, cheap enough that it's a non-issue running forever in the
    // background. TTL on the published key is longer than this interval
    // (see secondaryStatusPublishTtl) so a couple of missed cycles don't
    // make this secondary's panel flicker off the primary's dashboard.
    actorSystem.scheduler.scheduleWithFixedDelay(5.seconds, 10.seconds)(() => publishSecondaryStatus())(ex)
    logger.info(s"Running as a shared-world-cycle secondary ('${discordGateway.selfUserName}') — publishing status to the primary's dashboard instead of running its own")
  }

  private val secondaryStatusPublishTtl = 30.seconds

  /** Publishes this secondary's full status snapshot (worlds/guilds, rate-limit
   *  lanes, bot identity — the exact same data its own dashboard would show,
   *  were it running one) for a primary to merge into its dashboard's
   *  worlds view and fleet panel — see StatusRoute.remoteSecondaryStatuses. */
  private def publishSecondaryStatus(): Unit = {
    try {
      val json = statusRoute.buildBotStatusJson()
      persistence.RedisCacheProvider.cache.setEx(
        s"${web.StatusRoute.secondaryStatusKeyPrefix}${discordGateway.selfUserId}",
        json.compactPrint,
        secondaryStatusPublishTtl
      ).recover { case e: Throwable => logger.warn(s"Failed to publish secondary status: ${e.getMessage}") }(ex)
    } catch {
      case e: Throwable => logger.warn("Failed to build secondary status snapshot", e)
    }
  }

  // streamState is declared above (before tibiaDataClient). BotApp delegates so
  // existing call sites (BotApp.activityData / modifyActivityData / ...) are unchanged.
  def activityData: Map[String, List[PlayerCache]] = streamState.activityData
  def worldTransfersData: Map[String, List[WorldTransfer]] = streamState.worldTransfersData
  def huntedPlayersData: Map[String, List[Players]] = streamState.huntedPlayersData
  def alliedPlayersData: Map[String, List[Players]] = streamState.alliedPlayersData
  def huntedGuildsData: Map[String, List[Guilds]] = streamState.huntedGuildsData
  def alliedGuildsData: Map[String, List[Guilds]] = streamState.alliedGuildsData
  def customSortData: Map[String, List[CustomSort]] = streamState.customSortData
  def discordsData: Map[String, List[Discords]] = streamState.discordsData
  def worldsData: Map[String, List[Worlds]] = streamState.worldsData
  def activityCommandBlocker: Map[String, Boolean] = streamState.activityCommandBlocker
  def characterCache: Map[String, ZonedDateTime] = streamState.characterCache
  def warmCharacterCache(loaded: Map[String, ZonedDateTime]): Unit = streamState.warmCharacterCache(loaded)
  def modifyActivityData(f: Map[String, List[PlayerCache]] => Map[String, List[PlayerCache]]): Unit =
    streamState.modifyActivityData(f)
  def modifyWorldTransfersData(f: Map[String, List[WorldTransfer]] => Map[String, List[WorldTransfer]]): Unit =
    streamState.modifyWorldTransfersData(f)

  /** Record an incoming world transfer as announced for `guildId`, in cache and db. */
  def recordWorldTransfer(guildId: String, name: String, formerWorlds: List[String], detectedAt: ZonedDateTime): Unit = {
    val transfer = WorldTransfer(name.toLowerCase, formerWorlds, detectedAt)
    streamState.modifyWorldTransfersData { m =>
      m + (guildId -> (transfer :: m.getOrElse(guildId, List()).filterNot(_.name.equalsIgnoreCase(name))))
    }
    worldTransferRepository.record(guildId, name, formerWorlds, detectedAt)
  }
  def modifyHuntedPlayersData(f: Map[String, List[Players]] => Map[String, List[Players]]): Unit =
    streamState.modifyHuntedPlayersData(f)
  def modifyAlliedPlayersData(f: Map[String, List[Players]] => Map[String, List[Players]]): Unit =
    streamState.modifyAlliedPlayersData(f)
  def modifyHuntedGuildsData(f: Map[String, List[Guilds]] => Map[String, List[Guilds]]): Unit =
    streamState.modifyHuntedGuildsData(f)
  def modifyAlliedGuildsData(f: Map[String, List[Guilds]] => Map[String, List[Guilds]]): Unit =
    streamState.modifyAlliedGuildsData(f)
  def modifyCustomSortData(f: Map[String, List[CustomSort]] => Map[String, List[CustomSort]]): Unit =
    streamState.modifyCustomSortData(f)
  def modifyDiscordsData(f: Map[String, List[Discords]] => Map[String, List[Discords]]): Unit =
    streamState.modifyDiscordsData(f)
  def modifyWorldsData(f: Map[String, List[Worlds]] => Map[String, List[Worlds]]): Unit =
    streamState.modifyWorldsData(f)
  def modifyActivityCommandBlocker(f: Map[String, Boolean] => Map[String, Boolean]): Unit =
    streamState.modifyActivityCommandBlocker(f)

  // Warm the Date-header character cache from the last Redis snapshot so a
  // restart doesn't re-baseline every character against the rate-limited API,
  // then snapshot it every 60s (configurable). Whole-map snapshot keeps the
  // per-character hot path off Redis entirely; no-op + empty load when Redis
  // is disabled.
  private val charCachePersistence =
    new persistence.CharacterCachePersistence(persistence.RedisCacheProvider.cache, Config.Cache.characterSnapshotTtl)(ex)
  charCachePersistence.load().foreach { loaded =>
    if (loaded.nonEmpty) {
      warmCharacterCache(loaded) // existing (fresher) entries win
      logger.info(s"Warmed character cache from Redis snapshot: ${loaded.size} entries")
    }
  }
  private val snapshotInterval = Config.Cache.characterSnapshotInterval
  actorSystem.scheduler.scheduleWithFixedDelay(snapshotInterval, snapshotInterval)(() => { charCachePersistence.save(characterCache); () })(ex)

  // Per-guild channel/role setup lifecycle (create/repair/remove, join/leave).
  // State mutation for join/leave stays in BotApp via the forgetGuild callback;
  // ChannelService reads/writes streamState directly for everything else.
  val channelService = new setup.ChannelService(
    streamSupervisor,
    schemaInitializer,
    worldConfigRepository,
    discordConfigRepository,
    streamState,
    boostedService,
    paywallService,
    respawnService,
    botUser,
    startBot = (guild, world) => startBot(guild, world),
    serverSaveExtraEmbeds = world => serverSaveExtraEmbeds(world),
    syncPatreonBeforeCheck = () => syncPatreonMembersForSetup(),
    forgetGuild = guildId => {
      if (worldsData.contains(guildId)) modifyWorldsData(_ - guildId)
      val updatedDiscordsData = discordsData.map { case (world, discordsList) =>
        if (discordsList.exists(_.id == guildId)) world -> discordsList.filterNot(_.id == guildId)
        else world -> discordsList
      }
      if (updatedDiscordsData != discordsData) modifyDiscordsData(_ => updatedDiscordsData)
    },
    sharedConfigGuilds = Set("912739993015947324", "1176279097001918516", "1224670957466161234")
  )

  // Per-world setting commands (auto-hunt detection, deaths/levels visibility,
  // exiva-on-death, minimum level, fullbless level, leaderboards)
  val worldSettingsService = new worldsettings.WorldSettingsService(
    worldConfigRepository, discordConfigRepository, streamState, channelService, botUser
  )

  // Dream Courts boss rotation extracted to domain.time.DreamScarCycle.
  // dreamScar/dromeTime are written by the scheduler thread (and dreamScar also by
  // the /admin resync thread) but read every cycle by the per-world streams — so
  // they need @volatile for the same cross-thread visibility reason as the state
  // below; without it a stream can keep reading a stale boss/cycle after a shift.
  @volatile var dreamScar: Map[String, String] = fetchDreamScarBosses().getOrElse(Map.empty)
  // Which server save the map above is aligned to. The refresh below fires when
  // a newer one has happened, so the boot read counts as already covering the
  // save it was taken after.
  @volatile private var dreamScarSave: Long =
    ServerSaveSchedule.lastServerSave(ZonedDateTime.now(domain.time.Clock.Berlin)).toEpochSecond
  @volatile var dromeTime = domain.time.DromeCycle.initial // 27 May 2026 server save - increment 2 weeks from here

  val boostedBosses: Future[Either[String, BoostedResponse]] = tibiaDataClient.getBoostedBoss()
  val bossFuture: Future[List[String]] = boostedBosses.map {
    case Right(boostedResponse) =>
      val boostedBoss = boostedResponse.boostable_bosses.boostable_boss_list
      val boostedBossList = boostedBoss.map(_.name.toLowerCase).toList
      boostedBossList
    case Left(_) =>
      List.empty[String]
  }

  private var updateOnOdd = 0
  private var paywallCheckCounter = 0

  /** (guildId, world) pairs whose paused online-list presentation has already
   *  been applied in this process — the paywall sweep hands us every paused
   *  world on every tick (see PaywallService.refreshAll's `onStillLapsed`),
   *  and this is what keeps that from purging and reposting the same notice
   *  every 30 minutes forever. Deliberately not persisted and never pruned:
   *  one restore per paused world per process is the whole budget, and a
   *  restart — the only thing that can knock the presentation out of sync in
   *  the first place — clears it. */
  private val pausedNoticeApplied = java.util.concurrent.ConcurrentHashMap.newKeySet[(String, String)]()

  val bossesFutures: Future[List[String]] = for {
    bosses <- bossFuture
  } yield bosses

  val boostedBossesList: List[String] = Await.result(bossesFutures, 10.seconds)

  createCacheDatabase()

  // Register slash commands per guild: support servers get the admin set,
  // everyone else gets the full config set once they have a world tracked,
  // or just the minimal set (setup/remove/repair/galthen/boosted) until then.
  guilds.foreach{g =>
    // A guild that's never run /setup has no per-guild database yet at all
    // (only created lazily by /setup itself) — checkConfigDatabase must gate
    // worldConfig, not just the world-list query inside it, or this throws
    // instead of returning empty.
    val hasWorldConfigured = checkConfigDatabase(g) && worldConfig(g).nonEmpty
    val excludeAll = com.tibiabot.commands.CommandSchemas.excludedFromCommands(g.getIdLong, g.getJDA.getSelfUser.getId)
    g.updateCommands().addCommands(com.tibiabot.commands.CommandSchemas.commandsFor(g.getIdLong, hasWorldConfigured, excludeAll, Config.Respawn.enabled).asJava).complete()
  }

  // Start all world streams
  // Written once on the startup thread (after all world streams are launched) and
  // read on JDA event threads in BotListener — @volatile so a command thread can't
  // cache the initial false and reject every slash command as "still starting up".
  @volatile var startUpComplete = false
  val startTime = Instant.now()
  // update Drome Timer to the latest cycle
  if (dromeTime.isBefore(startTime)) {
    advanceDromeTime(startTime)
  }
  startBot(None, None) // guild: Option[Guild], world: Option[String]

  // Respawn claim system: close expired claims, promote whoever is queued
  // behind them, and warn claimants whose time is nearly up.
  //
  // On its own single-threaded scheduler rather than the shared actorSystem
  // one, because the body blocks — it creates, edits and archives forum threads
  // through JDA — and the Akka scheduler's dispatcher also runs every world's
  // poll stream. scheduleWithFixedDelay on a single thread additionally means a
  // slow sweep can never overlap itself.
  //
  // Nothing here is scheduled per claim, so this is restart-safe by
  // construction: a claim that lapsed while the bot was down is resolved by the
  // first sweep after it comes back, not lost with an in-memory timer.
  if (Config.Respawn.enabled) {
    val respawnSweeper = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
      (r: Runnable) => {
        val thread = new Thread(r, "respawn-sweep")
        thread.setDaemon(true)
        thread
      })
    // Bring each configured guild's spawn images in line with the bundled list.
    // Deciding which monster represents a spawn is an ongoing curation job, and
    // the seed import deliberately never revisits a code a guild already has —
    // so without this an improved list would only reach brand-new guilds.
    // Cheap (one batched UPDATE per guild) and a no-op once in step, so it runs
    // on every boot rather than needing to be remembered as a command.
    discordGateway.guilds.filter(g => worldsData.contains(g.getId)).foreach { guild =>
      try {
        respawnService.settings(guild.getId)
          .filter(respawnOwnership.ownsRespawns(guild, _))
          .foreach { config =>
            val changed = respawnService.syncSeedCreatures(guild.getId)
            if (changed > 0) logger.info(s"Updated $changed respawn creature images in guild '${guild.getId}'")
            // The codes themselves, for the same reason: this is the moment an
            // edited respawns.json reaches a guild that already exists. It used
            // to take somebody remembering to run /repair, which meant a code
            // added to the bundled file simply never appeared on servers that
            // had been set up before it.
            val sync = respawnService.syncSeed(guild.getId)
            if (sync.changedAnything)
              logger.info(s"Respawn catalogue in guild '${guild.getId}': ${sync.added} added, " +
                s"${sync.updated} corrected, ${sync.retired} retired" +
                (if (sync.inUse > 0) s", ${sync.inUse} kept because somebody is on them" else ""))
            // And the picture of them, which is what anybody actually reads a
            // code off. A no-op unless the catalogue really differs from what
            // the pinned post was last drawn from, so a plain restart costs
            // nothing.
            if (respawnService.redrawBoardIfChanged(guild, config))
              logger.info(s"Redrew the respawn board in guild '${guild.getId}' — its codes had changed")
          }
      } catch {
        case ex: Throwable =>
          logger.warn(s"Could not bring the respawn catalogue up to date for guild '${guild.getId}'", ex)
      }
    }

    val sweepMillis = Config.Respawn.sweepInterval.toMillis
    respawnSweeper.scheduleWithFixedDelay(new Runnable {
      // Counts sweeps so the board-post refresh (a REST call per guild) runs
      // roughly daily instead of every 30 seconds.
      private var ticks = 0L
      private val ticksPerDay = math.max(1L, (24 * 60 * 60 * 1000L) / math.max(1L, sweepMillis))

      def run(): Unit = {
        if (!startUpComplete) return
        ticks += 1
        val refreshBoards = ticks % ticksPerDay == 0
        // Only guilds with a configured world are worth sweeping. The bot sits
        // in plenty of guilds that never ran /setup and so have no
        // `_<guildId>` database at all — asking those for their settings opens
        // a connection that can only fail, every cycle, forever. A guild can't
        // have a respawn forum without /setup having created its database
        // first, so this filter loses nothing.
        discordGateway.guilds.filter(g => worldsData.contains(g.getId)).foreach { guild =>
          try {
            // Only the identity that built this guild's forum sweeps it. Several
            // bots can share a guild and each runs this same loop against the one
            // shared per-guild database, so without this they race over every
            // claim — whichever got there first would send the hunt reminder,
            // start the due slot and DM the handover offer, regardless of which
            // bot the spawn was actually claimed through. See RespawnOwnership.
            respawnService.settings(guild.getId)
              .filter(respawnOwnership.ownsRespawns(guild, _))
              .foreach { config =>
                respawnService.sweep(guild)
                if (refreshBoards) respawn.RespawnThreads.refreshBoard(guild, config)
              }
          } catch {
            // One guild's bad state (deleted channel, revoked permission) must
            // not stop every other guild's claims from being resolved.
            case ex: Throwable =>
              logger.warn(s"Respawn sweep failed for guild '${guild.getId}'", ex)
          }
        }
      }
    }, sweepMillis, sweepMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
    logger.info(s"Respawn claim system enabled — sweeping every ${Config.Respawn.sweepInterval}")
  }

  // run the scheduler to clean cache and update dashboard every hour.
  // scheduleWithFixedDelay (not the deprecated schedule) so a slow cycle — this
  // body makes blocking API calls at server save — can't pile up behind itself.
  actorSystem.scheduler.scheduleWithFixedDelay(60.seconds, 30.seconds)(() => {
    // Rotate the watching-status text and run the periodic cache cleanups
    // (deaths/levels/hunted-list/galthen/online-list) once every 10 ticks
    // (~5 minutes at the 30s tick interval), not every tick.
    if (updateOnOdd >= 10) {
      try {
        val randomActivity = List(
          "number go up",
          "Tibia players die",
          "some kid red skull",
          "UE combos slap",
          "another 50k spent on twist"
        )
        val randomActivityFromList = Random.shuffle(randomActivity).headOption.getOrElse("people press buttons")
        discordGateway.setWatchingActivity(randomActivityFromList)
      } catch {
        case ex: Throwable => logger.warn("Failed to update the bot's status counts", ex)
      }
      removeDeathsCache(ZonedDateTime.now())
      removeLevelsCache(ZonedDateTime.now())
      cleanHuntedList()
      galthenService.cleanExpired()
      cleanOnlineListCache(30)
      updateOnOdd = 0
    } else {
      updateOnOdd += 1
    }
    // Patreon paywall: recheck every configured world against the support
    // guild every ~30 minutes (60 ticks at the 30s tick interval) — see
    // paywall.PaywallService. A much lower-urgency check than updateOnOdd's
    // ~5-minute cache cleanup, so it gets its own, longer cadence.
    //
    // worldsData rather than the seat table is the list of worlds to sweep:
    // the seat table only knows the worlds that have a seat, and the grace
    // period has to apply to the ones that don't (legacy setups) just as
    // much. It's also the same per-guild world config the streams run off,
    // maintained by /setup, /remove and guild-leave alike, so a world that
    // isn't there is one nothing is being posted for anyway.
    if (paywallCheckCounter >= 60) {
      paywallCheckCounter = 0
      try {
        val configuredSetups = worldsData.toList.flatMap { case (guildId, worlds) => worlds.map(w => (guildId, w.name)) }
        paywallService.refreshAll(configuredSetups)(onLapsed = { (guild, world, userId, userName) =>
          // This branch *is* the paused presentation being applied, so claim it
          // before doing any of it — that leaves onStillLapsed below to repair
          // only worlds paused in an earlier process, and keeps a guild with no
          // admin channel (which falls through everything below) behaving as it
          // always has rather than newly acquiring a notice.
          pausedNoticeApplied.add((guild.getId, world))
          val adminChannel = guild.getTextChannelById(discordRetrieveConfig(guild).getOrElse("admin_channel", "0"))
          // Not routed through AdminLog.post — that helper's title ("a command
          // was run") is shared/fixed across every other caller, and this
          // isn't a command audit, it's a system-triggered notice.
          if (adminChannel != null && (adminChannel.canTalk() || !Config.prod)) {
            // userName is a snapshot taken at /setup time — empty for seats
            // assigned before that field existed, which Names.user renders as
            // "someone" rather than leaving an empty `` `` `` pair to break the
            // rest of this message's markdown (Discord would treat the next
            // stray backtick, in "`/setup`" below, as its closing one).
            val subscriber = Names.user(userName)
            // An empty userId means this world never had a seat at all (a
            // legacy setup whose grace period ran out) — there's no
            // subscription to describe as lapsed and nobody to name, so say
            // what's actually true instead of pointing at a `<@>` nobody.
            val cause =
              if (userId.isEmpty) s"This world isn't tied to an active **Patreon** subscription."
              else s"The Patreon subscription tied to $subscriber is no longer active or *cannot be verified*."
            val pausedEmbed = new EmbedBuilder()
            pausedEmbed.setTitle(s":warning: Violent Bot paused for $world")
            pausedEmbed.setDescription(s"Activity tracking has been **paused** for **`$world`**.\n\n$cause [Resubscribe](https://www.patreon.com/violentbot) and run `/setup` for **$world** again to resume tracking.\n\n[Website](https://violentbot.xyz) | [Discord](https://discord.gg/SWMq9Pz8ud) | [Patreon](https://patreon.com/violentbot)")
            pausedEmbed.setColor(presentation.Embeds.NemesisPurple)
            adminChannel.sendMessageEmbeds(pausedEmbed.build()).queue { adminMessage =>
              postPausedOnlineListNotice(guild, world, Some(adminMessage.getJumpUrl()))
            }
          }

          // Low priority (rare, one DM per lapse) — goes through the shared
          // background lane so it can't compete with deaths/boosted-channel
          // posts for REST slots. Mirrors the boosted-DM notification pattern.
          // Skipped entirely for a never-seated world: no seat, no owner, so
          // there is no personal inbox this belongs in — the admin-channel
          // notice above is the whole story for those.
          if (userId.nonEmpty) outboundSender.enqueue("paywall-lapse-dm") { () =>
            val user = discordGateway.retrieveUser(userId)
            if (user != null) {
              try {
                user.openPrivateChannel().queue { pc =>
                  val dmEmbed = new EmbedBuilder()
                  dmEmbed.setTitle(s":warning: Violent Bot has been paused")
                  dmEmbed.setDescription(s"Violent Bot is paused for **$world** on **${guild.getName}** because your Patreon subscription is no longer active — or your Discord account is no longer connected to Patreon, so the subscription check can't match you. If this was unintentional, [resubscribe](https://www.patreon.com/violentbot) or reconnect Discord on Patreon and run `/setup` for **$world** again to resume tracking.\n\n[Website](https://violentbot.xyz) | [Discord](https://discord.gg/SWMq9Pz8ud) | [Patreon](https://patreon.com/violentbot)")
                  dmEmbed.setColor(presentation.Embeds.NemesisPurple)
                  pc.sendMessageEmbeds(dmEmbed.build()).queue(null, new ErrorHandler().handle(
                    List(ErrorResponse.NO_MUTUAL_GUILDS, ErrorResponse.CANNOT_SEND_TO_USER).asJava,
                    new java.util.function.Consumer[ErrorResponseException] {
                      def accept(ex: ErrorResponseException): Unit =
                        logger.info(s"Could not DM paywall-lapse notice to user '$userId': no shared guild / DMs closed")
                    }
                  ))
                }
              } catch {
                case ex: Exception => logger.warn(s"Failed to DM paywall-lapse notice to user: '$userId'", ex)
              }
            }
          }
        }, onStillLapsed = { (guild, world) =>
          // Paused before this process started, and announced back then — so no
          // notice and no DM, only the online-list presentation, which a restart
          // may have left showing a live player count and real content (see
          // PaywallService.hydrateFromGrace for how that happened). At most once
          // per world per process; postPausedOnlineListNotice skips a channel
          // already carrying the paused name, so the usual case costs nothing.
          if (pausedNoticeApplied.add((guild.getId, world))) postPausedOnlineListNotice(guild, world, None)
        })
      } catch {
        case ex: Throwable => logger.warn("Failed to refresh Patreon paywall status", ex)
      }
    } else {
      paywallCheckCounter += 1
    }
    // Updating boosted creature/boss at server save
    val currentTime = ZonedDateTime.now(ZoneId.of("Europe/Berlin")).toLocalTime()
    if (ServerSaveSchedule.isServerSaveWindow(currentTime)) {
      try{
        // Re-read the wiki once per server save. Keyed off which save the map is
        // aligned to rather than "has an hour passed since the last check": the
        // old throttle started counting at boot, so a process that came up in
        // the quarter hour before 10:00 stayed inside its own hour for the whole
        // 45-minute window and skipped that day's rotation — permanently, since
        // nothing re-read the wiki after boot. Re-reading also means a map that
        // is wrong for any reason fixes itself the next morning instead of
        // carrying the error forward a step at a time.
        val currentSave = ServerSaveSchedule.lastServerSave(ZonedDateTime.now(domain.time.Clock.Berlin)).toEpochSecond
        if (currentSave != dreamScarSave) {
          dreamScarSave = currentSave
          refreshDreamScarBosses(shiftOnFailure = true)
        }
        if (dromeTime.isBefore(Instant.now())) {
          advanceDromeTime(Instant.now())
        }
      }
      catch {
        case ex: Throwable => logger.warn("Failed to get Dream Boss info from wiki", ex)
      }
      try {
        boostedService.boostedMessages().map { boostedBossAndCreature =>
          val currentBoss = boostedBossAndCreature.boss
          val currentCreature = boostedBossAndCreature.creature
          val bossChanged = boostedBossAndCreature.bossChanged
          val creatureChanged = boostedBossAndCreature.creatureChanged

          val boostedBoss: Future[Either[String, BoostedResponse]] = tibiaDataClient.getBoostedBoss()
          val bossEmbedFuture: Future[(MessageEmbed, Boolean, String)] = boostedBoss.map {
            case Right(boostedResponse) =>
              val boostedBoss = boostedResponse.boostable_bosses.boosted.name
              if (boostedBoss.toLowerCase != currentBoss.toLowerCase) {
                boostedService.boostedMonsterUpdate(boostedBoss, "", "1", "")
              }
              (
                presentation.BoostedEmbeds.create(creatureImageUrl(boostedBoss),s"The boosted boss today is:\n### ${Config.indentEmoji}${Config.archfoeEmoji} **[$boostedBoss](${creatureWikiUrl(boostedBoss)})**"),
                boostedBoss.toLowerCase != currentBoss.toLowerCase && currentBoss.toLowerCase != "none",
                boostedBoss
              )

            case Left(_) =>
              throw new Exception(s"Failed to load boosted boss.")
          }

          val boostedCreature: Future[Either[String, CreatureResponse]] = tibiaDataClient.getBoostedCreature()
          val creatureEmbedFuture: Future[(MessageEmbed, Boolean, String)] = boostedCreature.map {
            case Right(creatureResponse) =>
              val boostedCreature = creatureResponse.creatures.boosted.name
              if (boostedCreature.toLowerCase != currentCreature.toLowerCase) {
                boostedService.boostedMonsterUpdate("", boostedCreature, "", "1")
              }
              (
                presentation.BoostedEmbeds.create(creatureImageUrl(boostedCreature),s"The boosted creature today is:\n### ${Config.indentEmoji}${Config.levelUpEmoji} **[$boostedCreature](${creatureWikiUrl(boostedCreature)})**"),
                boostedCreature.toLowerCase != currentCreature.toLowerCase && currentCreature.toLowerCase != "none",
                boostedCreature
              )

            case Left(_) =>
              throw new Exception(s"Failed to load boosted boss.")
          }

          // Combine both futures and send the message
          val combinedFutures: Future[List[(MessageEmbed, Boolean, String)]] = for {
            bossEmbed <- bossEmbedFuture
            creatureEmbed <- creatureEmbedFuture
          } yield List(bossEmbed, creatureEmbed)

          combinedFutures.map { boostedInfoList =>
            if (bossChanged == "1" && creatureChanged == "1") {
              boostedService.boostedMonsterUpdate("", "", "0", "0")
              val embeds: List[MessageEmbed] = boostedInfoList.map { case (embed, _, _) => embed }.toList
              val notificationsList: List[BoostedStamp] = boostedService.boostedDmTargets()
              // One DM per user per save, not per matching subscription: someone who
              // subscribed to today's boss and today's creature by name has two rows
              // but only wants one message (it carries both embeds either way), and
              // the failure count below is meant to measure saves, not rows.
              val recipients: List[String] = notificationsList.collect {
                case entry if boostedInfoList.exists { case (_, _, boostedName) =>
                  boostedName.toLowerCase == entry.boostedName.toLowerCase || entry.boostedName.toLowerCase == "all"
                } => entry.user
              }.distinct

              recipients.foreach { recipientId =>
                // Low priority (per-user DM burst) — goes through the shared background
                // lane so it can't compete with deaths/boosted-channel posts for REST slots.
                outboundSender.enqueue("send") { () =>
                  val user: User = discordGateway.retrieveUser(recipientId)
                  if (user != null) {
                    try {
                      user.openPrivateChannel().queue { privateChannel =>
                        val messageText = s"🔔 ${boostedInfoList.head._3} • ${boostedInfoList.last._3}"
                        privateChannel.sendMessage(messageText).setEmbeds(embeds.asJava).setComponents(ActionRow.of(
                          Button.primary("boosted list", " ").withEmoji(Emoji.fromFormatted(Config.letterEmoji))
                        )).queue(
                          (_: Message) => {
                            // Delivered — this is the bot that shares a guild with them, so it
                            // takes ownership of their DMs (claiming the row if it was still
                            // unclaimed) and their failure count goes back to zero.
                            try boostedService.dmDelivered(recipientId)
                            catch { case ex: Throwable => logger.warn(s"Failed to record boosted-DM delivery for user: '$recipientId'", ex) }
                          },
                          new ErrorHandler().handle(
                            List(ErrorResponse.NO_MUTUAL_GUILDS, ErrorResponse.CANNOT_SEND_TO_USER).asJava,
                            new java.util.function.Consumer[ErrorResponseException] {
                              // Can't tell "DMs closed" from "wrong bot" by error code, so this
                              // never drops a subscription on one failure the way it used to —
                              // with several bots on one notifications table that quietly
                              // deleted the lists of everyone the other bot served. Only a run
                              // of failed saves against a row this bot actually owns gives up.
                              def accept(ex: ErrorResponseException): Unit = {
                                try {
                                  if (boostedService.dmFailed(recipientId))
                                    logger.info(s"Removed boosted-DM subscription for user '$recipientId': undeliverable for several server saves running")
                                } catch {
                                  case ex: Throwable => logger.warn(s"Failed to record boosted-DM failure for user: '$recipientId'", ex)
                                }
                              }
                            }
                          )
                        )
                      }
                    } catch {
                      case ex: Exception => logger.warn(s"Failed to send Boosted notification to user: '$recipientId'", ex)
                    }
                  }
                }
              }

              repostBoostedMessages(embeds)
            }
          }
        }
      }
      catch {
        case ex : Throwable => logger.warn("Failed to update the boosted messages", ex)
      }
    }
  })

  /** Replace every guild's boosted message: delete the one currently posted in
   *  its boosted channel and send a fresh one carrying `boostedEmbeds` (the
   *  boosted boss and creature) plus Rashid, that guild's own Dream Courts
   *  boss, and the Drome cycle when it's due. Returns how many guilds a send
   *  was dispatched for — the send itself is queued, so a guild counted here
   *  can still fail asynchronously (logged per guild).
   *
   *  Shared by the server-save refresh above and `/admin boosted`. */
  private def repostBoostedMessages(boostedEmbeds: List[MessageEmbed]): Int = {
    var posted = 0
    discordGateway.guilds.foreach { guild =>
      if (checkConfigDatabase(guild)) {
        val discordInfo = discordRetrieveConfig(guild)
        val channelId = if (discordInfo.nonEmpty) discordInfo("boosted_channel") else "0"
        val lastWorld = if (discordInfo.nonEmpty) discordInfo("last_world") else "Antica"
        if (channelId != "0") {
          val boostedChannel = guild.getTextChannelById(channelId)
          if (boostedChannel != null) {
            if (boostedChannel.canTalk()) {
              val boostedMessage = if (discordInfo.nonEmpty) discordInfo("boosted_messageid") else "0"
              if (boostedMessage != "0") {
                try {
                  boostedChannel.deleteMessageById(boostedMessage).queue()
                } catch {
                  case ex: Throwable => logger.warn(s"Failed to get the boosted boss creature message for deletion in Guild ID: '${guild.getId}' Guild Name: '${guild.getName}':", ex)
                }
              }

              val dreamScarDaily =
                dreamScar
                  .get(lastWorld)
                  .orElse(dreamScar.get("Unknown"))
                  .getOrElse("Unknown")

              val rashidLocation = ServerSaveSchedule.rashidLocation(ServerSaveSchedule.gameDayOfWeek(ZonedDateTime.now(domain.time.Clock.Berlin)))
              val rashidEmbed = new EmbedBuilder()
              rashidEmbed.setDescription(s"Today Rashid can be found in:\n### ${Config.indentEmoji}${Config.goldEmoji} **[${rashidLocation}](https://tibia.fandom.com/wiki/Rashid)**")
              rashidEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Rashid.gif")
              rashidEmbed.setColor(BrandColor)

              val now = Instant.now()
              val dromeShow = ServerSaveSchedule.shouldShowDrome(now, dromeTime)
              val dromeEmbed = new EmbedBuilder()
                .setDescription(s"The current Drome cycle will end:\n### ${Config.indentEmoji}${Config.dromeEmoji} ${TimeFormat.RELATIVE.format(dromeTime)}")
                .setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Phant.gif")
                .setColor(BrandColor)

              val dreamScarEmbed = new EmbedBuilder()
              dreamScarEmbed.setDescription(s"The Dream Courts boss for **$lastWorld** is:\n### ${Config.indentEmoji}${Config.dreamScarEmoji} **[${dreamScarDaily}](https://tibia.fandom.com/wiki/Dream_Scar/Boss_of_the_Day)**")
              dreamScarEmbed.setThumbnail(creatureImageUrl(dreamScarDaily))
              dreamScarEmbed.setColor(BrandColor)

              val embedsList = if (dromeShow) List(rashidEmbed.build(), dreamScarEmbed.build(), dromeEmbed.build()) else List(rashidEmbed.build(), dreamScarEmbed.build())
              val addRashidDreamScarEmbeds: List[MessageEmbed] = boostedEmbeds ++ embedsList

              posted += 1
              boostedChannel.sendMessageEmbeds(addRashidDreamScarEmbeds.asJava)
                .setComponents(ActionRow.of(
                  Button.primary("boosted list", "Server Save Notifications").withEmoji(Emoji.fromFormatted(Config.letterEmoji))
                ))
                .queue((message: Message) => {
                  //updateBoostedMessage(guild.getId, message.getId)
                  discordUpdateConfig(guild, "", "", "", message.getId, lastWorld)
                }, (e: Throwable) => {
                  logger.warn(s"Failed to send boosted boss/creature message for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}':", e)
                })
            } else {
              logger.warn(s"Failed to send & delete boosted message for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}': no VIEW/SEND permissions")
            }
          }
        }
      }
    }
    posted
  }

  /** `/admin boosted`: rebuild and repost every guild's boosted message right
   *  now, off the server-save cycle — for when a save was missed, or a batch
   *  of messages went out wrong and needs redoing without waiting a day.
   *
   *  Reads the boosted boss/creature straight from TibiaData (BoostedService's
   *  embeds carry their own fallback if that call fails) and deliberately
   *  leaves the boosted cache and its changed-flags untouched: this is a
   *  repost of what's true now, not a second server save, so it must neither
   *  suppress the real one nor fire the subscriber DMs again. */
  def refreshBoostedMessages(): Future[Int] =
    for {
      bossEmbed <- boostedService.boostedBossEmbed()
      creatureEmbed <- boostedService.boostedCreatureEmbed()
    } yield repostBoostedMessages(List(bossEmbed, creatureEmbed))

  // Once a day: leave any guild that's tracked no worlds for a while and
  // hasn't run any command recently either — see pruneInactiveGuilds. Its
  // own independent schedule (24h is too long to piggyback on the shared
  // 30s-tick counter above the way the paywall check does).
  actorSystem.scheduler.scheduleWithFixedDelay(1.hour, 24.hours)(() => {
    try pruneInactiveGuilds()
    catch { case ex: Throwable => logger.warn("Failed to run the inactive-guild prune sweep", ex) }
  })(ex)

  /** A guild with no worlds tracked (its own per-guild database may not even
   *  exist yet, if /setup has never run there — see checkConfigDatabase)
   *  leaves once worldless for Config.InactiveGuild.worldlessDays, unless a
   *  command's been run there within Config.InactiveGuild.activityDays
   *  (personal commands like /galthen or /boosted count — someone's still
   *  genuinely using the bot, just not for world tracking). The two support
   *  guilds are never auto-left. */
  private def pruneInactiveGuilds(): Unit = {
    val now = ZonedDateTime.now()
    discordGateway.guilds.foreach { guild =>
      if (!com.tibiabot.commands.CommandSchemas.supportGuildIds.contains(guild.getIdLong)) {
        val hasWorlds = checkConfigDatabase(guild) && worldConfig(guild).nonEmpty
        if (hasWorlds) {
          guildActivityRepository.clearWorldless(guild.getId)
        } else {
          val worldlessSince = guildActivityRepository.markWorldlessIfUnset(guild.getId, now)
          val lastCommandAt = guildActivityRepository.lastCommandAt(guild.getId)
          if (scheduler.GuildPruneRule.shouldLeave(worldlessSince, lastCommandAt, now, Config.InactiveGuild.worldlessDays, Config.InactiveGuild.activityDays)) {
            try {
              adminService.leave(guild.getId, "This server hasn't tracked any worlds and hasn't used any commands in a while, so I'm leaving to keep things tidy. Feel free to invite me back anytime you'd like to use Violent Bot again.")
            } catch {
              case ex: Throwable => logger.warn(s"Failed to auto-leave inactive Guild ID: '${guild.getId}'", ex)
            }
          }
        }
      }
    }
  }

  // Declared ahead of the schedule below and of syncPatreonMembers, both of
  // which stamp it — object fields initialise top-to-bottom.
  private val patreonSyncThrottle = new patreonapi.SyncThrottle(Config.PatreonApi.setupSyncCooldown)

  // Own independent schedule (same reasoning as the prune sweep above) —
  // guarded by Config.PatreonApi.enabled so this is a no-op until real
  // Patreon API credentials are configured. That "no-op" is louder than it
  // used to be: this sync is now the only thing that answers who's
  // subscribed, so running without credentials means an empty snapshot and
  // nobody able to /setup. Said plainly in the log rather than left to be
  // deduced from a paywall that quietly refuses everyone.
  if (Config.PatreonApi.enabled) {
    logger.info(s"Patreon API sync enabled, campaign '${Config.PatreonApi.campaignId}', every ${Config.PatreonApi.syncInterval}")
    actorSystem.scheduler.scheduleWithFixedDelay(1.minute, Config.PatreonApi.syncInterval)(() => {
      try syncPatreonMembers().failed.foreach(ex => logger.warn("Failed to sync Patreon members", ex))
      catch { case ex: Throwable => logger.warn("Failed to sync Patreon members", ex) }
    })(ex)
  } else {
    logger.warn("Patreon API sync disabled (no access token configured) — the paywall reads this sync, so nobody will pass the /setup subscription check")
  }

  /** Runs a Patreon sync ahead of `/setup`'s subscription check, so someone who
   *  subscribed and linked their Discord minutes ago passes on their first try
   *  instead of being told to subscribe until the next half-hourly sweep lands.
   *  Called from setup.ChannelService.createChannels.
   *
   *  Blocking, but bounded three ways, because `/setup` answering from slightly
   *  stale data is a nuisance and `/setup` hanging is not:
   *   - Skipped entirely if any sync started within
   *     `Config.PatreonApi.setupSyncCooldown` — so a burst of `/setup`s, or one
   *     right after the periodic sweep, is one fetch rather than one each (see
   *     patreonapi.SyncThrottle).
   *   - Waits at most `Config.PatreonApi.setupSyncTimeout`. On timeout the sync
   *     is left running — it'll land for the next caller — and this one answers
   *     from the previous snapshot.
   *   - Every failure is swallowed and logged. A Patreon outage must never be
   *     the reason `/setup` errors, and it can't cost anyone access either: a
   *     failed fetch leaves the last good snapshot in place (see
   *     [[syncPatreonMembers]]).
   *
   *  Names are deliberately not resolved on this path (`resolveNames = false`)
   *  — that's a blocking Discord lookup per linked patron, easily the slowest
   *  part of a sync, and it feeds only the dashboard's supporters panel. The
   *  paywall reads patron status and the linked Discord id, both of which come
   *  straight from Patreon. Known names are carried forward regardless, so
   *  nothing on the dashboard blanks out; the next periodic sync refreshes
   *  them. */
  def syncPatreonMembersForSetup(): Unit =
    if (Config.PatreonApi.enabled && patreonSyncThrottle.tryAcquire(System.nanoTime())) {
      try Await.result(syncPatreonMembers(resolveNames = false), Config.PatreonApi.setupSyncTimeout)
      catch {
        case _: java.util.concurrent.TimeoutException =>
          logger.warn(s"Patreon sync for /setup didn't finish within ${Config.PatreonApi.setupSyncTimeout}; leaving it running and answering from the previous snapshot")
        case ex: Throwable =>
          logger.warn("Patreon sync for /setup failed; answering from the previous snapshot", ex)
      }
    }

  /** Patreon's own API only gives us a linked patron's Discord *id*, not a
   *  display name — resolved here, once per sync (infrequent, unlike the
   *  dashboard's 10s poll), via the same blocking JDA lookup PatreonAdminRoute
   *  uses for a single user. A failed lookup just leaves that member's
   *  username unresolved for this pass; it's retried on the next sync. */
  private def resolveDiscordUsername(member: domain.PatreonMember,
                                     known: Map[String, String]): domain.PatreonMember =
    member.discordUserId match {
      case None => member
      case Some(discordId) =>
        // Whatever was resolved last time, for when this lookup can't answer.
        // The snapshot is replaced wholesale, so without this a Discord timeout
        // blanks a supporter's name on the dashboard until some later sync
        // happens to succeed — losing a name we already knew, over a hiccup that
        // had nothing to do with them.
        def lastKnown = member.copy(discordUsername = known.get(discordId))
        try {
          val user = discordGateway.retrieveUser(discordId)
          if (user != null) member.copy(discordUsername = Some(user.getName)) else lastKnown
        } catch {
          case ex: Throwable =>
            logger.warn(s"Failed to resolve Discord username for linked Patreon member '${member.patreonMemberId}' (Discord id '$discordId')", ex)
            lastKnown
        }
    }

  /** Best-effort snapshot of the Patreon campaign's member list (see
   *  patreonapi.PatreonApiClient) — run on the periodic schedule above and,
   *  with `resolveNames = false`, on demand ahead of `/setup`'s subscription
   *  check (see [[syncPatreonMembersForSetup]], which is also where the
   *  throttling and the reasoning for skipping name resolution live). Backs
   *  both the dashboard's
   *  supporters panel and the paywall gate itself — this snapshot is what
   *  PaywallService.callerIsSubscribed reads, so the guards below on what
   *  gets written are load-bearing, not tidiness. It also reclaims a
   *  dashboard-granted seat override for
   *  someone Patreon has *just now* confirmed a Discord link for (see
   *  PaywallService.reclaimOverridesFromPatreon): a one-time hand-off from
   *  the admin's manual bridge to Patreon as the ongoing source of truth,
   *  the moment that account first appears linked — never on a later sync,
   *  so a legitimate bonus later granted to an already-linked supporter
   *  isn't silently wiped out every cycle. Determined by diffing against the
   *  previous snapshot (captured here before it's overwritten), not by
   *  anything in the fetched member data itself. Fire-and-forget: a failed
   *  fetch just leaves the last snapshot in place until the next tick, same
   *  degrade-gracefully shape as everything else here. */
  private def syncPatreonMembers(resolveNames: Boolean = true): Future[Unit] = {
    patreonSyncThrottle.record(System.nanoTime())
    patreonApiClient.fetchAllMembers().map {
      // A failed fetch must never reach replaceSnapshot: that call prunes
      // every row the sync didn't carry, so an empty list wouldn't leave the
      // snapshot stale, it would erase it — and with it every supporter's
      // access, since callerIsSubscribed reads this table.
      case None =>
        logger.warn("Patreon member sync failed; keeping the previous snapshot rather than replacing it")
      // A *successful* empty response is refused for the same reason. A live
      // campaign never legitimately drops to zero members, so this reads as a
      // misconfigured campaign id or a Patreon-side oddity answering 200 with
      // no data — and a campaign that really had no members would have
      // nobody to gate anyway, so declining to wipe costs nothing.
      case Some(members) if members.isEmpty =>
        logger.warn("Patreon member sync returned no members at all; keeping the previous snapshot rather than emptying it")
      case Some(members) =>
        // Read once, before anything is overwritten, and used for two things: the
        // names already resolved, and which accounts were already linked.
        val previous = patreonMemberRepository.snapshot()
        val knownNames = previous.flatMap(m => m.discordUserId.zip(m.discordUsername)).toMap
        val enriched =
          if (resolveNames) members.map(resolveDiscordUsername(_, knownNames))
          // The /setup path — no Discord lookups, just whatever was resolved
          // last time, so the fetch is the only slow part of the sync.
          else members.map(m => m.copy(discordUsername = m.discordUserId.flatMap(knownNames.get)))
        logger.info(s"Synced ${enriched.size} Patreon members (${enriched.count(_.discordUserId.isDefined)} with a linked Discord account, ${enriched.count(m => m.patronStatus.contains("active_patron") && m.discordUserId.isDefined)} of those active)")
        val previouslyLinkedIds = previous.flatMap(_.discordUserId).toSet
        try patreonMemberRepository.replaceSnapshot(enriched, ZonedDateTime.now())
        catch { case ex: Throwable => logger.warn("Failed to persist the Patreon member sync", ex) }

        val newlyLinkedIds = enriched.flatMap(_.discordUserId).toSet -- previouslyLinkedIds
        val reclaimed = paywallService.reclaimOverridesFromPatreon(newlyLinkedIds)
        if (reclaimed.nonEmpty) logger.info(s"Patreon sync picked up ${reclaimed.size} newly-linked Discord account(s) with a dashboard-granted seat override — reset to the default")
    }
  }

  def cleanOnlineListCache(maxAgeMinutes: Long): Unit = {
    val currentTime = ZonedDateTime.now()
    streamState.pruneCharacterCache { timestamp =>
      timestamp.until(currentTime, java.time.temporal.ChronoUnit.MINUTES) <= maxAgeMinutes
    }
  }

  /** Load a guild's hunted/allied/worlds/activity/customSort config into
   *  streamState. Shared by both startBot paths (single-guild join vs. full
   *  startup) — previously copy-pasted identically in each. */
  private def loadGuildState(g: Guild): List[Worlds] = {
    val guildId = g.getId

    val huntedPlayers = playerConfig(g, "hunted_players")
    modifyHuntedPlayersData(_ + (guildId -> huntedPlayers))

    val alliedPlayers = playerConfig(g, "allied_players")
    modifyAlliedPlayersData(_ + (guildId -> alliedPlayers))

    val huntedGuilds = guildConfig(g, "hunted_guilds")
    modifyHuntedGuildsData(_ + (guildId -> huntedGuilds))

    val alliedGuilds = guildConfig(g, "allied_guilds")
    modifyAlliedGuildsData(_ + (guildId -> alliedGuilds))

    val worldsInfo = worldConfig(g)
    modifyWorldsData(_ + (guildId -> worldsInfo))

    val activityInfo = activityConfig(g)
    modifyActivityData(_ + (guildId -> activityInfo))

    // Announced world transfers, minus anything old enough that the former-world
    // field it suppresses has long since cleared. Read before the prune: the read
    // is what creates the table on a guild that has never had one.
    val transferCutoff = ZonedDateTime.now().minusDays(TransferRecordRetentionDays)
    val transferInfo = worldTransferConfig(g).filter(_.detectedAt.isAfter(transferCutoff))
    modifyWorldTransfersData(_ + (guildId -> transferInfo))
    worldTransferRepository.removeExpired(guildId, transferCutoff)

    val customSortInfo = customSortConfig(g)
    modifyCustomSortData(_ + (guildId -> customSortInfo))

    modifyActivityCommandBlocker(_ + (guildId -> false))

    worldsInfo
  }

  /** Build a guild's Discords admin/boosted-channel record from its stored
   *  config. Shared by both startBot paths. */
  private def buildDiscordsEntry(g: Guild, guildId: String): Discords = {
    val adminChannels = discordRetrieveConfig(g)
    Discords(
      id = guildId,
      adminChannel = if (adminChannels.nonEmpty) adminChannels("admin_channel") else "0",
      boostedChannel = if (adminChannels.nonEmpty) adminChannels("boosted_channel") else "0",
      boostedMessage = if (adminChannels.nonEmpty) adminChannels("boosted_messageid") else "0"
    )
  }

  private def startBot(guild: Option[Guild], world: Option[String]): Unit = {
    // A guild's stored world config can reference a world TibiaData has since
    // merged/retired (e.g. a legacy `/setup` entry) — polling it would only
    // ever fail to parse. Filtering against the live world list here means
    // that failure happens once, as a clear skip log, instead of every poll
    // cycle forever as a parse-warning.
    val validWorlds = WorldManager.getWorldList().toSet
    def skipStale(worldName: String): Unit =
      logger.warn(s"Skipping stream for '$worldName' — not a currently valid Tibia world (stale/merged guild config?)")

    if (guild.isDefined && world.isDefined) {
      val g = guild.get
      val guildId = g.getId
      val worldsInfo = loadGuildState(g)
      val discords = buildDiscordsEntry(g, guildId)

      worldsInfo.foreach{ w =>
        if (w.name == world.get) {
          modifyDiscordsData(d => d.updated(w.name, discords :: d.getOrElse(w.name, Nil)))
          // Preserves prior behaviour: when the world stream already exists it was
          // left unchanged (the usedBy append was overwritten and never took effect);
          // only an absent world starts a new stream.
          if (!streamSupervisor.contains(world.get)) {
            if (validWorlds.contains(world.get)) {
              streamSupervisor.put(world.get, new TibiaBot(world.get, outboundSender, onlineListSender, worldMetricsRegistry.forWorld(world.get), recentEventsRegistry.forWorld(world.get), paywallService).run(), List(discords))
            } else {
              skipStale(world.get)
            }
          }
        }
      }
    } else {
      guilds.foreach{g =>
        val guildId = g.getId

        if (checkConfigDatabase(g)) {
          val worldsInfo = loadGuildState(g)
          val discords = buildDiscordsEntry(g, guildId)

          // populate a new Discords list so i can only run 1 stream per world
          worldsInfo.foreach{ w =>
            modifyDiscordsData(d => d.updated(w.name, discords :: d.getOrElse(w.name, Nil)))
          }
        }
      }
      discordsData.foreach { case (worldName, discordsList) =>
        if (validWorlds.contains(worldName)) {
          streamSupervisor.put(worldName, new TibiaBot(worldName, outboundSender, onlineListSender, worldMetricsRegistry.forWorld(worldName), recentEventsRegistry.forWorld(worldName), paywallService).run(), discordsList)
          Thread.sleep(5500) // space each stream out 5.5 seconds
        } else {
          skipStale(worldName)
        }
      }

      // Shared world-cycle: as primary, also poll (and publish, via
      // SharedWorldTibiaApi inside TibiaBot) any world a secondary's guilds
      // need that none of THIS process's own guilds do — discordsData above is
      // built purely from our own JDA guild membership, so it can't see
      // those. Empty discords list: nothing of ours to fan out to for these,
      // the poll+publish is the whole point of the stream.
      if (Config.BotRole.current == Config.BotRole.Primary) {
        val extraWorlds = worldConfigRepository.allTrackedWorldNames().toSet -- discordsData.keySet
        extraWorlds.foreach { worldName =>
          if (validWorlds.contains(worldName)) {
            streamSupervisor.put(worldName, new TibiaBot(worldName, outboundSender, onlineListSender, worldMetricsRegistry.forWorld(worldName), recentEventsRegistry.forWorld(worldName), paywallService).run(), Nil)
            Thread.sleep(5500)
          } else {
            skipStale(worldName)
          }
        }
      }

      startUpComplete = true
    }
  }

  private def cleanHuntedList(): Unit =
    cacheRepository.removeExpiredList(ZonedDateTime.now())


  /** The Rashid / Dream Courts / (Drome, when active) server-save embeds for a
   *  world, appended after the boosted embeds in the notifications message.
   *  Reads the live dreamScar map and dromeTime. */
  private def serverSaveExtraEmbeds(world: String): List[MessageEmbed] = {
    val dreamScarDaily =
      dreamScar
        .get(world)
        .orElse(dreamScar.get("Unknown"))
        .getOrElse("Unknown")
    val rashidLocation = ServerSaveSchedule.rashidLocation(ServerSaveSchedule.gameDayOfWeek(ZonedDateTime.now(domain.time.Clock.Berlin)))
    val rashidEmbed = new EmbedBuilder()
      .setDescription(s"Today Rashid can be found in:\n### ${Config.indentEmoji}${Config.goldEmoji} **[${rashidLocation}](https://tibia.fandom.com/wiki/Rashid)**")
      .setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Rashid.gif")
      .setColor(BrandColor)
      .build()
    val dreamScarEmbed = new EmbedBuilder()
      .setDescription(s"The Dream Courts boss for **$world** is:\n### ${Config.indentEmoji}${Config.dreamScarEmoji} **[${dreamScarDaily}](https://tibia.fandom.com/wiki/Dream_Scar/Boss_of_the_Day)**")
      .setThumbnail(creatureImageUrl(dreamScarDaily))
      .setColor(BrandColor)
      .build()
    val dromeShow = ServerSaveSchedule.shouldShowDrome(Instant.now(), dromeTime)
    val dromeEmbed = new EmbedBuilder()
      .setDescription(s"The current Drome cycle will end:\n### ${Config.indentEmoji}${Config.dromeEmoji} ${TimeFormat.RELATIVE.format(dromeTime)}")
      .setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Phant.gif")
      .setColor(BrandColor)
      .build()
    if (dromeShow) List(rashidEmbed, dreamScarEmbed, dromeEmbed) else List(rashidEmbed, dreamScarEmbed)
  }

  def charUrl(char: String): String = presentation.Urls.charUrl(char)

  def guildUrl(guild: String): String = presentation.Urls.guildUrl(guild)

  private def checkConfigDatabase(guild: Guild): Boolean = schemaInitializer.guildDatabaseExists(guild.getId)

  private def createCacheDatabase(): Unit = schemaInitializer.initCache()

  def getDeathsCache(world: String): List[DeathsCache] = cacheRepository.getDeaths(world)

  def addDeathsCache(world: String, name: String, time: String): Unit =
    cacheRepository.addDeath(world, name, time)

  private def removeDeathsCache(time: ZonedDateTime): Unit =
    cacheRepository.removeExpiredDeaths(time)

  def getLevelsCache(world: String): List[LevelsCache] = cacheRepository.getLevels(world)

  def addLevelsCache(world: String, name: String, level: String, vocation: String, lastLogin: String, time: String): Unit =
    cacheRepository.addLevel(world, name, level, vocation, lastLogin, time)

  private def removeLevelsCache(time: ZonedDateTime): Unit =
    cacheRepository.removeExpiredLevels(time)

  def getRenameCooldowns(world: String): Map[String, ZonedDateTime] = renameCooldownRepository.loadForWorld(world)

  def recordRenameCooldown(world: String, channelId: String, at: ZonedDateTime): Unit =
    renameCooldownRepository.recordRename(world, channelId, at)

  private def playerConfig(guild: Guild, query: String): List[Players] =
    huntedAlliedRepository.getPlayers(guild.getId, query)

  private def guildConfig(guild: Guild, query: String): List[Guilds] =
    huntedAlliedRepository.getGuilds(guild.getId, query)

  private def activityConfig(guild: Guild): List[PlayerCache] =
    activityRepository.getActivity(guild.getId)

  private def worldTransferConfig(guild: Guild): List[WorldTransfer] =
    worldTransferRepository.getTransfers(guild.getId)

  def discordRetrieveConfig(guild: Guild): Map[String, String] =
    discordConfigRepository.getConfig(guild.getId)

  private def worldConfig(guild: Guild): List[Worlds] =
    worldConfigRepository.listWorlds(guild.getId)

  private def discordUpdateConfig(guild: Guild, adminCategory: String, adminChannel: String, boostedChannel: String, boostedMessage: String, lastWorld: String): Unit =
    discordConfigRepository.update(guild.getId, adminCategory, adminChannel, boostedChannel, boostedMessage, lastWorld)

  def worldRetrieveConfig(guild: Guild, world: String): Map[String, String] =
    worldConfigRepository.retrieveWorld(guild.getId, world)

  /** Clears whatever online-list content is currently posted for this world
   *  and replaces it with a paused notice. Targets just the combined channel
   *  in combined mode, or all three allies/neutrals/enemies channels that
   *  still exist in separate mode — same channels/columns TibiaBot's own
   *  recurring online-list update reads (see TibiaBot.onlineList). No
   *  explicit "resumed" cleanup is needed: once the world's active again,
   *  that recurring update's normal fetch-existing-bot-messages-and-edit-in-
   *  place logic (updateMultiFields) naturally overwrites this embed with
   *  real content on its next tick.
   *
   *  Two callers, distinguished by `adminMessageUrl`. `Some` is the lapse
   *  handler announcing a fresh pause, and the notice links back to the
   *  admin-channel explanation it just posted. `None` is the repair path for
   *  a world paused in an earlier process (see the sweep's `onStillLapsed`):
   *  there is no fresh admin message to point at, and a channel already
   *  carrying the paused name is already showing this notice, so it's left
   *  untouched rather than purged and reposted. That check is deliberately
   *  not applied to the announcing path, where the notice must land even if
   *  a previous cycle's name is still on the channel — its link would
   *  otherwise be stale.
   *
   *  The announcing path calls this from inside the admin-message send's own
   *  .queue() callback, so every JDA call here must stay non-blocking
   *  (.queue(), never .complete()) — JDA refuses nested .complete() calls
   *  from a callback thread as a deadlock guard. */
  private def postPausedOnlineListNotice(guild: Guild, world: String, adminMessageUrl: Option[String]): Unit = {
    val worldConfig = worldRetrieveConfig(guild, world)
    val combined = worldConfig.getOrElse("combined_online", "false") == "true"
    val channelIds =
      if (combined) List(worldConfig.getOrElse("allies_channel", "0"))
      else List("allies_channel", "neutrals_channel", "enemies_channel").map(worldConfig.getOrElse(_, "0"))
    channelIds.filterNot(_ == "0").distinct.foreach { channelId =>
      val channel = guild.getTextChannelById(channelId)
      if (channel != null && (channel.canTalk() || !Config.prod)) {
        // Swap the "-<online count>" suffix for a warning icon, same base-name
        // recovery TibiaBot's own renameOnlineChannelIfDue uses — one-off
        // rename (only runs on the pause transition, not a recurring tick),
        // so no throttling needed: nothing else renames this channel while
        // it's paywall-paused (TibiaBot's own online-list update is already
        // gated on paywallService.isActive and skips it entirely).
        val pausedName = s"${presentation.OnlineListEmbeds.baseName(channel.getName, "online")}-${presentation.OnlineListEmbeds.pausedSuffix}"
        val alreadyPaused = channel.getName == pausedName
        if (!alreadyPaused) {
          channel.getManager.setName(pausedName).queue(null, (ex: Throwable) =>
            logger.warn(s"Failed to rename paused online-list channel for Guild ID: '${guild.getId}' World: '$world'", ex))
        }
        // Nothing to repair on a channel that never lost the paused name — see
        // the scaladoc; only the repair path (no admin message) gets to skip.
        if (!(alreadyPaused && adminMessageUrl.isEmpty)) channel.getHistory.retrievePast(100).queue { history =>
          try {
            val existing = history.asScala.filter(_.getAuthor.getId == botUser).toList.asJava
            if (!existing.isEmpty) channel.purgeMessages(existing)
            val pausedEmbed = new EmbedBuilder()
            // Linkless on the repair path: the admin-channel message announcing
            // this pause went out in an earlier process and its jump URL wasn't
            // kept, so the word is left plain rather than pointed somewhere wrong.
            val pausedText = adminMessageUrl.fold("**paused**")(url => s"**[paused]($url)**")
            pausedEmbed.setDescription(s":warning: Tracking for **`$world`** is currently $pausedText.")
            pausedEmbed.setThumbnail(Config.webHookAvatar)
            pausedEmbed.setColor(13773097)
            channel.sendMessageEmbeds(pausedEmbed.build()).queue()
          } catch {
            case ex: Throwable => logger.warn(s"Failed to post paused notice to online-list channel for Guild ID: '${guild.getId}' World: '$world'", ex)
          }
        }
      }
    }
  }

  private def customSortConfig(guild: Guild): List[CustomSort] =
    customSortRepository.getAll(guild.getId)

  private def creatureImageUrl(creature: String): String =
    presentation.Urls.creatureImageUrl(creature, Config.creatureUrlMappings)

  def creatureWikiUrl(creature: String): String =
    presentation.Urls.creatureWikiUrl(creature, Config.creatureUrlMappings)

  // Death screenshot database methods
  def storeDeathScreenshot(guildId: String, world: String, characterName: String, deathTime: Long, screenshotUrl: String, addedBy: String, addedName: String, messageId: String): Unit =
    deathScreenshotRepository.store(guildId, world, characterName, deathTime, screenshotUrl, addedBy, addedName, messageId)

  def getDeathScreenshots(guildId: String, world: String, characterName: String, deathTime: Long): List[DeathScreenshot] =
    deathScreenshotRepository.get(guildId, world, characterName, deathTime)

  def deleteDeathScreenshot(guildId: String, characterName: String, deathTime: Long, screenshotUrl: String, userId: String): Boolean = {
    val guild = discordGateway.guildById(guildId)
    val member = guild.retrieveMemberById(userId).complete()
    val admin = member != null && (member.hasPermission(Permission.MANAGE_SERVER) || member.hasPermission(Permission.MESSAGE_MANAGE))
    deathScreenshotRepository.deleteIfPermitted(guildId, characterName, deathTime, screenshotUrl) { addedBy =>
      addedBy == userId || admin
    }
  }

  /** Read the Dream Courts table off the wiki and align it to the current game
   *  day. None when the page gave us nothing usable, leaving it to the caller
   *  to decide what to keep.
   *
   *  The alignment is the point. That page is served from Fandom's parser cache
   *  and routinely lags a day behind the rollover — the page itself carries a
   *  "click here to purge the cache" link for exactly this — so a read taken at
   *  the wrong moment is a day stale. It does state the day it was rendered
   *  for, and the rotation advances one step per day, so a stale render is both
   *  detectable and correctable rather than something we have to discard. */
  def fetchDreamScarBosses(): Option[Map[String, String]] = {
    val snapshot =
      try wikiClient.dreamScarSnapshot()
      catch {
        case ex: Throwable =>
          logger.warn("Failed to fetch the Dream Courts bosses from the wiki", ex)
          domain.DreamScarSnapshot(None, Nil)
      }
    if (snapshot.bosses.isEmpty) {
      logger.warn("The Dream Courts wiki page returned no boss table")
      None
    } else {
      val fetched = snapshot.bosses.map(e => e.world -> e.boss).toMap
      val gameDay = ServerSaveSchedule.gameDayOfWeek(ZonedDateTime.now(domain.time.Clock.Berlin))
      snapshot.renderedDay match {
        case None =>
          logger.warn("The Dream Courts wiki page didn't say which day it was rendered for — taking it at face value")
          Some(fetched)
        case Some(renderedDay) =>
          val behind = domain.time.DreamScarCycle.daysBehind(renderedDay, gameDay)
          if (behind == 0) Some(fetched)
          else {
            logger.warn(s"The Dream Courts wiki page was rendered for $renderedDay but the game day is $gameDay — advancing it by $behind day(s)")
            Some(domain.time.DreamScarCycle.shiftAllBossesUp(fetched, behind))
          }
      }
    }
  }

  /** Bring `dreamScar` up to date, preferring a fresh (and day-aligned) wiki
   *  read so the map can correct itself rather than inheriting every past
   *  mistake. `shiftOnFailure` says what to do when the wiki can't be read at
   *  all: the server-save refresh advances the map we already hold, so an
   *  outage still rotates instead of freezing a day behind, while a manual
   *  `/admin dreamscar` leaves it alone — that one exists to *undo* drift, and
   *  shifting on a failed fetch could just as easily add some. */
  private def refreshDreamScarBosses(shiftOnFailure: Boolean): Unit =
    fetchDreamScarBosses() match {
      case Some(bosses) => dreamScar = bosses
      case None if shiftOnFailure =>
        logger.warn("Advancing the Dream Courts bosses locally instead of re-reading them")
        dreamScar = shiftAllBossesUp(dreamScar)
      case None => ()
    }

  def fetchCreatureNames(): List[String] = wikiClient.creatureNames()

  def advanceDromeTime(inputTime: Instant): Unit =
    dromeTime = domain.time.DromeCycle.advanceFrom(dromeTime, inputTime)

  def shiftAllBossesUp(current: Map[String, String]): Map[String, String] =
    domain.time.DreamScarCycle.shiftAllBossesUp(current)

}
