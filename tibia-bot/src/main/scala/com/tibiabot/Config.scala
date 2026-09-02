package com.tibiabot

import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.jdk.DurationConverters._

object Config {
  // prod or dev environment
  val prod = true

  private val discord = ConfigFactory.load().getConfig("discord-config")
  private val mappings = ConfigFactory.load().getConfig("mapping-config")

  val token: String = discord.getString("token")
  val postgresHost: String = discord.getString("postgres-host")
  val postgresPassword: String = discord.getString("postgres-password")
  val tibiadataApi: String = discord.getString("localapi-host")
  val redisHost: String = discord.getString("redis-host")
  val redisPort: Int = discord.getInt("redis-port")
  val redisPassword: String = discord.getString("redis-password")
  val redisEnabled: Boolean = redisHost.nonEmpty

  /** TTLs for how long cached TibiaData API responses are reused before
   *  re-fetching. Backed by the `cache { }` block in discord.conf
   *  (overridable per-key via CACHE_* env vars).
   *
   *  API-response caching only — behavioural dedup/notification windows
   *  (death/level/online retention, DB cache cleanup) live elsewhere, since
   *  those affect what gets posted rather than just freshness. */
  object Cache {
    private def dur(key: String): FiniteDuration = discord.getDuration(s"cache.$key").toScala
    val boostedTtl: FiniteDuration = dur("boosted-ttl")
    val worldListTtl: FiniteDuration = dur("world-list-ttl")
    val onlineDurationTtl: FiniteDuration = dur("online-duration-ttl")
    val killerLevelTtl: FiniteDuration = dur("killer-level-ttl")
  }

  val tibiaDataMaxInFlight: Int = discord.getInt("tibiadata-max-in-flight")

  /** CipSoft's official fansite API, the bot's second source for character sheets
   *  — see [[com.tibiabot.fansiteapi.FansiteApiClient]].
   *
   *  `mode` is both rollout gate and rollback: `off` stays on the TibiaData path,
   *  `shadow` fetches both and compares without changing what is posted, `race`
   *  runs them out of phase and takes the fresher sheet. A mode past `off` with no
   *  token degrades to `off` rather than failing every fetch. */
  object FansiteApi {
    sealed trait Mode
    case object Off extends Mode
    case object Shadow extends Mode
    case object Race extends Mode

    private val fansite = discord.getConfig("fansite-api")
    val token: String = fansite.getString("token").trim
    val baseUrl: String = fansite.getString("base-url").stripSuffix("/")
    val userAgent: String = fansite.getString("user-agent")
    val maxInFlight: Int = fansite.getInt("max-in-flight")
    val phaseOffsetTicks: Int = fansite.getInt("phase-offset-ticks")
    val circuitOpenFor: java.time.Duration = fansite.getDuration("circuit-open-for")
    val secondaryGrace: FiniteDuration = fansite.getDuration("secondary-grace").toScala

    private val requested: Mode = fansite.getString("mode").trim.toLowerCase match {
      case "shadow" => Shadow
      case "race"   => Race
      case _        => Off
    }

    val mode: Mode = if (token.isEmpty) Off else requested
    val enabled: Boolean = mode != Off
    /** True when a missing token is the only reason this is disabled — worth a
     *  startup warning, since it means a deploy asked for the feature and
     *  quietly did not get it. */
    val disabledForMissingToken: Boolean = requested != Off && token.isEmpty
  }

  /** Settings for the character age cache — see
   *  [[com.tibiabot.tibiadata.AgeCachedTibiaApi]]. Separate from `Cache` above
   *  because it is not only durations, and because `enabled` is meant to be a
   *  one-env-var way back to always-fetch behaviour without a rollback. */
  object CharacterCache {
    private def sub(key: String): String = s"character-cache.$key"
    val enabled: Boolean = discord.getBoolean(sub("enabled"))
    val ttl: FiniteDuration = discord.getDuration(sub("ttl")).toScala
    val maxStale: FiniteDuration = discord.getDuration(sub("max-stale")).toScala
    val canaryFraction: Double = discord.getDouble(sub("canary-fraction"))
    val maxEntries: Int = discord.getInt(sub("max-entries"))

    /** `pollInterval` is the caller's own poll cadence rather than a setting:
     *  the cache rounds to the nearest poll, so a value that drifted from the
     *  real tick would quietly cost a whole interval of latency. The stream
     *  that owns the tick passes it in. */
    def settings(pollInterval: FiniteDuration): tibiadata.AgeCacheSettings =
      tibiadata.AgeCacheSettings(ttl, pollInterval, maxStale, canaryFraction, maxEntries)
  }
  val creatureUrlMappings: Map[String, String] = mappings.getObject("creature-url-mappings").asScala.map {
    case (k, v) => k -> v.unwrapped().toString
  }.toMap

  // this is the message sent when the bot joins a discord or a user uses /help
  val helpText = s"**How to use the bot:**\n" +
    "Simply use `/setup <World Name>` to setup the bot.\n\n" +
    "**Commands & Features:**\n" +
    "All interactions with the bot are done through **[slash commands](https://support-apps.discord.com/hc/en-us/articles/26501837786775-Slash-Commands-FAQ)**.\n" +
    "If you type `/` and click on **Violent Bot** - you will see all the commands available to you.\n\n" +
    "[Website](https://violentbot.xyz) | [Discord](https://discord.gg/SWMq9Pz8ud) | [Patreon](https://patreon.com/violentbot)"

  // discord config
  val webHookAvatar: String = discord.getString("avatar-url")
  val nameChangeThumbnail: String = discord.getString("namechange-thumbnail")
  val guildLeaveThumbnail: String = discord.getString("guild-leave-thumbnail")
  val guildSwapGrey: String = discord.getString("guild-swap-thumbnail-grey")
  val guildSwapRed: String = discord.getString("guild-swap-thumbnail-red")
  val guildSwapGreen: String = discord.getString("guild-swap-thumbnail-green")
  val guildJoinGrey: String = discord.getString("guild-join-thumbnail-grey")
  val guildJoinRed: String = discord.getString("guild-join-thumbnail-red")
  val guildJoinGreen: String = discord.getString("guild-join-thumbnail-green")
  val worldTransferGrey: String = discord.getString("world-transfer-thumbnail-grey")
  val worldTransferRed: String = discord.getString("world-transfer-thumbnail-red")
  val worldTransferGreen: String = discord.getString("world-transfer-thumbnail-green")

  // Emojis
 val nemesisEmoji: String = discord.getString("nemesis-emoji")
 val archfoeEmoji: String = discord.getString("archfoe-emoji")
 val baneEmoji: String = discord.getString("bane-emoji")
 val summonEmoji: String = discord.getString("summon-emoji")
 val allyGuild: String = discord.getString("allyguild-emoji")
 val otherGuild: String = discord.getString("otherguild-emoji")
 val enemyGuild: String = discord.getString("enemyguild-emoji")
 val ally: String = discord.getString("ally-emoji")
 val enemy: String = discord.getString("enemy-emoji")
 val mkEmoji: String = discord.getString("mk-emoji")
 val cubeEmoji: String = discord.getString("cube-emoji")
 val svarGreenEmoji: String = discord.getString("svar-green-emoji")
 val svarScrapperEmoji: String = discord.getString("svar-scrapper-emoji")
 val svarWarlordEmoji: String = discord.getString("svar-warlord-emoji")
 val zelosEmoji: String = discord.getString("zelos-emoji")
 val libEmoji: String = discord.getString("library-emoji")
 val hodEmoji: String = discord.getString("hod-emoji")
 val feruEmoji: String = discord.getString("feru-emoji")
 val inqEmoji: String = discord.getString("inq-emoji")
 val kilmareshEmoji: String = discord.getString("kilmaresh-emoji")
 val exivaEmoji: String = discord.getString("exiva-emoji")
 val indentEmoji: String = discord.getString("indent-emoji")
 val dailyEmoji: String = discord.getString("daily-emoji")
 val levelUpEmoji: String = discord.getString("levelup-emoji")
 val primalEmoji: String = discord.getString("primal-emoji")
 val hazardEmoji: String = discord.getString("hazard-emoji")
 val yesEmoji: String = discord.getString("yes-emoji")
 val noEmoji: String = discord.getString("no-emoji")
 val letterEmoji: String = discord.getString("letter-emoji")
 val goldEmoji: String = discord.getString("gold-emoji")
 val bossEmoji: String = discord.getString("boss-emoji")
 val creatureEmoji: String = discord.getString("creature-emoji")
 val torchOnEmoji: String = discord.getString("torch-on-emoji")
 val torchOffEmoji: String = discord.getString("torch-off-emoji")
 val satchelEmoji: String = discord.getString("satchel-emoji")
 val dreamScarEmoji: String = discord.getString("dreamscar-emoji")
 val masslogEmoji: String = discord.getString("masslog-emoji")
 val bountyEmoji: String = discord.getString("bounty-emoji")
 val dromeEmoji: String = discord.getString("drome-emoji")
  // Rate limiting configuration
  val globalMessageDelayMs: Int = discord.getInt("global-message-delay-ms")
  val onlineListMessageDelayMs: Int = discord.getInt("online-list-message-delay-ms")
  val onlineListPerChannelMinGapMs: Long = discord.getLong("online-list-per-channel-min-gap-ms")
  val onlineListRepostEnabled: Boolean = discord.getBoolean("online-list-repost-enabled")
  val onlineListRepostQueueDepth: Int = discord.getInt("online-list-repost-queue-depth")
  val onlineListRepostCooldownMs: Long = discord.getLong("online-list-repost-cooldown-ms")
  val onlineListRepostUrgentQueueDepth: Int = discord.getInt("online-list-repost-urgent-queue-depth")
  val onlineListRepostUrgentCooldownMs: Long = discord.getLong("online-list-repost-urgent-cooldown-ms")

  /** Monitoring dashboard: Discord OAuth2 + session signing + reverse-proxy domain. */
  object Web {
    private val web = discord.getConfig("web")
    val discordClientSecret: String = web.getString("discord-client-secret")
    val sessionSecret: String = web.getString("session-secret")
    val statusDomain: String = web.getString("status-domain")
    val statusPort: Int = web.getInt("status-port")

    /** Where a browser actually reaches this bot — scheme and authority, no
     *  trailing slash — the origin the OAuth redirect URI is built on. Derived
     *  from `status-domain` unless overridden, so production needs no new setting.
     *  The override is for local runs behind a plain-HTTP port forward, where an
     *  `https://` redirect URI is one the browser cannot load; Discord allows
     *  `http://` for localhost specifically. */
    val baseUrl: String = {
      if (configuredBaseUrl.nonEmpty) configuredBaseUrl else s"https://$statusDomain"
    }

    private def configuredBaseUrl: String = web.getString("base-url").trim.stripSuffix("/")

    /** Where members are sent to use the dashboard, which is not always somewhere
     *  this bot serves. [[baseUrl]] answers "where is *this* bot reached", which
     *  on a bot running no dashboard is a bare `https://`. This answers what the
     *  respawn board asks instead: where the reader goes to use the dashboard.
     *  One dashboard serves every guild, so its address defaults here rather than
     *  being told to each bot; a bot serving its own still links to itself. */
    val dashboardOrigin: String =
      com.tibiabot.web.Origin.of(configuredBaseUrl, statusDomain, web.getString("dashboard-domain"))

    /** Whether session cookies may be marked `Secure`.
     *
     *  Tied to the origin rather than configured separately, so it cannot drift
     *  from it: a `Secure` cookie is dropped outright over plain HTTP, which
     *  would leave a local login succeeding at Discord and then landing on a
     *  dashboard that sees no session and bounces straight back. Anything
     *  served over HTTPS — which is every deployment — is unaffected. */
    val secureCookies: Boolean = baseUrl.startsWith("https://")
  }

  /** Patreon paywall: how many (guild, world) seats each subscriber gets, and
   *  the support Discord — which no longer gates anything, and is kept only
   *  for the dashboard's username -> Discord id lookup (see
   *  paywall.PaywallService.findUserIdByUsername). Who counts as subscribed
   *  comes from the Patreon API instead; see [[PatreonApi]]. */
  object Patreon {
    private val patreon = discord.getConfig("patreon")
    val supportGuildId: String = patreon.getString("support-guild-id")
    val seatsPerUser: Int = patreon.getInt("seats-per-user")
    /** How long a configured world keeps running after the subscription
     *  behind it stops checking out, before activity is actually paused —
     *  see paywall.PaywallService's grace period. 0 pauses on the first
     *  sweep that notices, i.e. the behaviour from before grace existed. */
    val graceDays: Int = patreon.getInt("grace-days")
  }

  /** Direct Patreon API access (patreonapi.PatreonApiClient) — periodically
   *  syncs the campaign's member list for the dashboard's supporters panel.
   *  Load-bearing: this snapshot is what the paywall's subscription check
   *  reads (see paywall.PaywallService.callerIsSubscribed), so leaving it
   *  unconfigured means nobody passes `/setup`.
   *  `enabled` mirrors `redisEnabled`'s shape — everything downstream no-ops
   *  cleanly while this is unconfigured. */
  object PatreonApi {
    private val patreonApi = discord.getConfig("patreon-api")
    val clientId: String = patreonApi.getString("client-id")
    val clientSecret: String = patreonApi.getString("client-secret")
    val accessToken: String = patreonApi.getString("access-token")
    val refreshToken: String = patreonApi.getString("refresh-token")
    val campaignId: String = patreonApi.getString("campaign-id")
    val syncInterval: FiniteDuration = patreonApi.getDuration("sync-interval").toScala
    /** `/setup` syncs before its subscription check so a new supporter need not
     *  wait out `sync-interval`. This is the shortest gap between two syncs, so a
     *  run of `/setup`s cannot become a run of Patreon fetches. Shared with the
     *  periodic sync — one that just ran counts. */
    val setupSyncCooldown: FiniteDuration = patreonApi.getDuration("setup-sync-cooldown").toScala
    /** How long `/setup` will wait on that sync before giving up on it and
     *  answering from the previous snapshot. The sync itself is left running;
     *  this only bounds how long the command blocks. */
    val setupSyncTimeout: FiniteDuration = patreonApi.getDuration("setup-sync-timeout").toScala
    val enabled: Boolean = accessToken.nonEmpty
  }

  /** Shared world-cycle role — see discord.conf's bot-role comment.
   *  `Disabled` (the default) behaves exactly as before this feature
   *  existed: no extra Redis traffic, no cross-Postgres world scanning. */
  object BotRole {
    sealed trait Role
    case object Disabled extends Role
    case object Primary extends Role
    case object Secondary extends Role

    val current: Role = discord.getString("bot-role").trim.toLowerCase match {
      case "primary" => Primary
      case "secondary" => Secondary
      case _ => Disabled
    }
    val sharingEnabled: Boolean = current != Disabled

    /** A secondary waits for the primary's sheets rather than fetching its
     *  own — see [[com.tibiabot.tibiadata.PrimaryPresence]] for why this is
     *  safe to default on. */
    val secondaryConsumeOnly: Boolean = discord.getBoolean("secondary-consume-only")

    /** A primary polls worlds only a secondary serves, purely to publish them
     *  — see [[com.tibiabot.app.UnionFetchReconciler]]. */
    val primaryFetchesFleetWorlds: Boolean = discord.getBoolean("primary-fetches-fleet-worlds")

    val heartbeatInterval: FiniteDuration = discord.getDuration("primary-heartbeat-interval").toScala

    /** Longer than the interval, so one missed beat is not read as a death. */
    val heartbeatTtl: FiniteDuration = heartbeatInterval * 3

    /** Consume-only applies only where there is a primary to consume from. */
    val consumeOnlyActive: Boolean = current == Secondary && secondaryConsumeOnly
    val fleetFetchActive: Boolean = current == Primary && primaryFetchesFleetWorlds

    /** How wide a fleet-fetch poll fans out. Narrower than a real world
     *  stream's 32: these worlds are somebody else's, nothing here is waiting
     *  on the result, and the point is to fill a cache rather than to detect a
     *  death a moment sooner. Being gentler with the upstreams is worth more
     *  than the speed. */
    val fleetFetchFanOut: Int = discord.getInt("fleet-fetch-fan-out")
  }

  /** The respawn claim system (the `📅・sᴘᴀᴡɴs` forum, plus `/stamina` and
   *  `/bookings`).
   *
   *  `enabled` was the rollout gate and now defaults to **true**, matching what
   *  prod runs. False is still a clean withdrawal rather than a broken state:
   *  neither command is registered and `/setup`/`/repair` skip the forum — worth
   *  keeping for a local run that shouldn't touch a guild's forums.
   *
   *  The duration/queue/stamina values are *defaults for a guild's first setup*,
   *  copied into its `respawn_settings` row at creation and read from there
   *  afterwards, so retuning the bot's defaults never changes the rules under a
   *  guild already using it. */
  object Respawn {
    private val respawn = discord.getConfig("respawn")
    val enabled: Boolean = respawn.getBoolean("enabled")
    /** How long a claim runs when the user doesn't say. */
    val defaultDurationMinutes: Int = respawn.getInt("default-duration-minutes")
    /** Ceiling on a single claim, including extensions. */
    val maxDurationMinutes: Int = respawn.getInt("max-duration-minutes")
    /** How many people may wait behind the active claim. */
    val queueLimit: Int = respawn.getInt("queue-limit")
    /** Each user's daily claim budget, refilled at server save. 0 disables
     *  stamina entirely (unlimited claiming). */
    val staminaMinutes: Int = respawn.getInt("stamina-minutes")
    /** DM the claimer this many minutes before their claim ends. */
    val warnMinutes: Int = respawn.getInt("warn-minutes")
    /** How long the next person in line has to accept a handover offer before
     *  it's assumed they're away and the spawn moves on. */
    val handoverMinutes: Int = respawn.getInt("handover-minutes")
    /** How often the expiry/promotion sweep runs. Also the worst-case lateness
     *  of a claim ending. */
    val sweepInterval: FiniteDuration = respawn.getDuration("sweep-interval").toScala
    /** How long a free spawn's post has to go untouched before it is archived
     *  again. Interacting with an archived post re-opens it, so this is what
     *  stops a burst of clicks costing an archive apiece — see
     *  [[com.tibiabot.respawn.RespawnSleep]]. The worst-case lateness on top is
     *  one [[sweepInterval]], since the sweep is what drains it. */
    val closeDelay: FiniteDuration = respawn.getDuration("close-delay").toScala
    /** How far ahead recurring slots are booked. Long enough that people can see
     *  and plan around tonight's slot; short enough that a cancelled schedule
     *  leaves few bookings to clear. */
    val scheduleLookAheadMinutes: Int = respawn.getInt("schedule-look-ahead-minutes")
    /** How far into a booked slot its owner may still say they are hunting it,
     *  before it passes to whoever asked. The deadline is this much past the
     *  slot's own start whenever the question was put, so somebody asked hours
     *  ahead is judged on whether they turned up rather than on whether they
     *  read a DM that afternoon. */
    val bookingRequestGraceMinutes: Int = respawn.getInt("booking-request-grace-minutes")
    /** Whether a booked slot claims itself when it comes round instead of asking
     *  its owner to press a button. A default for a guild's first setup only —
     *  read the live value off [[com.tibiabot.domain.RespawnSettings.autoClaim]],
     *  which each guild sets for itself. */
    val autoClaim: Boolean = respawn.getBoolean("auto-claim")
    /** How long before a booked slot starts its owner is reminded. 0 turns the
     *  reminder off, and so does a guild's autoclaim: with nothing to confirm
     *  there is nothing for the nudge to be early for. Separate from the
     *  claim-end reminder members set for themselves: this one is about a hunt
     *  that hasn't begun. */
    val slotReminderMinutes: Int = respawn.getInt("slot-reminder-minutes")
    /** How long after a booking starts on its own its owner has to confirm they
     *  are there, before it is given up for them and the spawn moves on as it
     *  would after any other claim ending. Capped at the slot's own end, so a
     *  booking shorter than this is never outlived by its own deadline. */
    val slotConfirmMinutes: Int = respawn.getInt("slot-confirm-minutes")
    /** Most standing bookings one member may hold in a guild. */
    val maxSchedulesPerUser: Int = respawn.getInt("max-schedules-per-user")
    /** Shown as the claim embed's image when a spawn has no `creature` set —
     *  most of the seed catalogue starts out that way. */
    val fallbackImage: String = respawn.getString("fallback-image")
    /** Directory the member dashboard caches creature sprites into, so they are
     *  served from our own domain rather than hotlinked from a wiki that
     *  geoblocks some of the people looking at them. */
    val spriteCacheDir: String = respawn.getString("sprite-cache-dir")
  }

  /** Auto-leave a guild with no worlds tracked for this many days, unless a
   *  command's been run there within activityDays — see BotApp.pruneInactiveGuilds. */
  object InactiveGuild {
    private val inactiveGuild = discord.getConfig("inactive-guild")
    val worldlessDays: Int = inactiveGuild.getInt("worldless-days")
    val activityDays: Int = inactiveGuild.getInt("activity-days")
  }

  // creature mappings
  val notableCreatures: List[String] = mappings.getStringList("notable-creatures").asScala.toList
  val primalCreatures: List[String] = mappings.getStringList("primal-creatures").asScala.toList
  val hazardCreatures: List[String] = mappings.getStringList("hazard-creatures").asScala.toList
  val bossSummons: List[String] = mappings.getStringList("boss-summons").asScala.toList
  val nemesisCreatures: List[String] = mappings.getStringList("nemesis-creatures").asScala.toList
  val archfoeCreatures: List[String] = mappings.getStringList("archfoe-creatures").asScala.toList
  val baneCreatures: List[String] = mappings.getStringList("bane-creatures").asScala.toList
  val mkBosses: List[String] = mappings.getStringList("mk-bosses").asScala.toList
  val cubeBosses: List[String] = mappings.getStringList("cube-bosses").asScala.toList
  val svarGreenBosses: List[String] = mappings.getStringList("svar-green-bosses").asScala.toList
  val svarScrapperBosses: List[String] = mappings.getStringList("svar-scrapper-bosses").asScala.toList
  val svarWarlordBosses: List[String] = mappings.getStringList("svar-warlord-bosses").asScala.toList
  val zelosBosses: List[String] = mappings.getStringList("zelos-bosses").asScala.toList
  val libBosses: List[String] = mappings.getStringList("library-bosses").asScala.toList
  val hodBosses: List[String] = mappings.getStringList("hod-bosses").asScala.toList
  val feruBosses: List[String] = mappings.getStringList("feru-bosses").asScala.toList
  val inqBosses: List[String] = mappings.getStringList("inq-bosses").asScala.toList
  val kilmareshBosses: List[String] = mappings.getStringList("kilmaresh-bosses").asScala.toList

  // worlds - dynamically fetched from TibiaData API
  def worldList: List[String] = WorldManager.getWorldList()
  val mergedWorlds = List(
    // Pulsera
    "Illusera",
    "Wizera",
    "Seanera",
    // Yovera
    "Optera",
    "Marbera",
    // Wildera
    "Fera",
    "Ardera",
    // Kendria
    "Trona",
    "Marcia",
    "Adra",
    "Suna",
    // Nevia
    "Famosa",
    "Karna",
    "Olima",
    // Retalia
    "Versa",
    "Bastia",
    // Jadebra
    "Ocebra",
    "Alumbra",
    "Dibra",
    // Rasteibra
    "Zenobra",
    "Xandebra",
    // Ustebra
    "Tembra",
    "Reinobra",
    // Obscubra
    "Cadebra",
    "Visabra",
    "Libertabra",
    // Guerribra
    "Mudabra",
    "Nossobra",
    "Batabra",
    // Quidera
    "Pulsera",
    "Axera",
    // Fibera
    "Kardera",
    "Mykera",
    // Ourobra
    "Bombra",
    "Utobra",
    // Gladibra
    "Guerribra",
    "Ousabra",
    // Xyla
    "Kendria",
    "Castela",
    // Karmeya
    "Damora",
    "Nadora",
    // Malivora
    "Impulsa",
    "Syrena",
    //Xymera
    "Vandera",
    "Runera",
    //Blumera
    "Ulera",
    "Vitera",
    "Esmera",
    //Monstera
    "Wildera",
    "Gravitera",
    //Tempestera
    "Flamera",
    "Temera",
    "Fibera",
    //Terribra
    "Jacabra",
    "Obscubra",
    //Sombra
    "Quebra",
    "Ambra",
    //Eclipta
    "Divina",
    "Malivora",
    //Kalanta
    "Zephyra",
    "Wadira",
    //Citra
    "Yara",
    "Jaguna"

  )
  // creatures - dynamically fetched from the Tibia Fandom wiki (not TibiaData)
  val creaturesListFromApi: List[String] = BotApp.fetchCreatureNames()
  val creaturesList: List[String] = creaturesListFromApi.map(_.toLowerCase.trim)
}
