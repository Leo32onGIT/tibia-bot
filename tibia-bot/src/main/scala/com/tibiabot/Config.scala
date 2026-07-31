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
    val characterSnapshotTtl: FiniteDuration = dur("character-snapshot-ttl")
    val characterSnapshotInterval: FiniteDuration = dur("character-snapshot-interval")
    val onlineDurationTtl: FiniteDuration = dur("online-duration-ttl")
    val killerLevelTtl: FiniteDuration = dur("killer-level-ttl")
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
 val dromeEmoji: String = discord.getString("drome-emoji")
  // Rate limiting configuration
  val globalMessageDelayMs: Int = discord.getInt("global-message-delay-ms")
  val onlineListMessageDelayMs: Int = discord.getInt("online-list-message-delay-ms")
  val onlineListPerChannelMinGapMs: Long = discord.getLong("online-list-per-channel-min-gap-ms")

  /** Monitoring dashboard: Discord OAuth2 + session signing + reverse-proxy domain. */
  object Web {
    private val web = discord.getConfig("web")
    val discordClientSecret: String = web.getString("discord-client-secret")
    val sessionSecret: String = web.getString("session-secret")
    val statusDomain: String = web.getString("status-domain")
    val statusPort: Int = web.getInt("status-port")
  }

  /** Patreon paywall: the support Discord + role Patreon assigns to active
   *  subscribers, and how many (guild, world) seats each subscriber gets. */
  object Patreon {
    private val patreon = discord.getConfig("patreon")
    val supportGuildId: String = patreon.getString("support-guild-id")
    val roleId: String = patreon.getString("role-id")
    val seatsPerUser: Int = patreon.getInt("seats-per-user")
  }

  /** Direct Patreon API access (patreonapi.PatreonApiClient) — periodically
   *  syncs the campaign's member list for the dashboard's supporters panel.
   *  Purely additive: does not affect the paywall's own Discord-role check.
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
  }

  /** The respawn claim system (`/respawn` + the `📅・sᴘᴀᴡɴs` forum).
   *
   *  `enabled` defaults to **false** and is the feature's rollout gate. Prod and
   *  DEV run the same image, so without it the first deploy of this branch would
   *  start creating forum channels in every guild that has run `/setup`. While
   *  it is off, `/respawn` isn't registered with Discord and `/setup`/`/repair`
   *  skip the forum entirely; flip `RESPAWN_ENABLED=true` only where the feature
   *  is actually being tested.
   *
   *  The duration/queue/stamina values here are *defaults for a guild's first
   *  setup*. They're copied into that guild's `respawn_settings` row at creation
   *  and read from there afterwards, so retuning the bot's defaults later never
   *  silently changes the rules under a guild that is already using it.
   */
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
    /** How far ahead recurring slots are booked. Long enough that people can see
     *  and plan around tonight's slot; short enough that a cancelled schedule
     *  leaves few bookings to clear. */
    val scheduleLookAheadMinutes: Int = respawn.getInt("schedule-look-ahead-minutes")
    /** How long the owner of a booked slot has to say whether they are hunting it
     *  before it passes to whoever asked. Clamped to shortly after the slot's own
     *  start, so it never waits past the hunt it is about. */
    val bookingRequestResponseMinutes: Int = respawn.getInt("booking-request-response-minutes")
    /** How long before a booked slot starts its owner is reminded. 0 turns the
     *  reminder off. Separate from the claim-end reminder members set for
     *  themselves: this one is about a hunt that hasn't begun. */
    val slotReminderMinutes: Int = respawn.getInt("slot-reminder-minutes")
    /** Most standing bookings one member may hold in a guild. */
    val maxSchedulesPerUser: Int = respawn.getInt("max-schedules-per-user")
    /** Shown as the claim embed's image when a spawn has no `creature` set —
     *  most of the seed catalogue starts out that way. */
    val fallbackImage: String = respawn.getString("fallback-image")
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
