package com.tibiabot

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.tibiabot.tibiadata.TibiaDataClient
import com.tibiabot.tibiadata.response.{CharacterResponse, GuildResponse, BoostedResponse, CreatureResponse, RaceResponse, Members, HighscoresResponse}
import com.tibiabot.scheduler.ServerSaveSchedule
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.{Guild, MessageEmbed}
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.interactions.commands.build.{Commands, OptionData, SlashCommandData, SubcommandData, SubcommandGroupData}
import net.dv8tion.jda.api.interactions.commands.{DefaultMemberPermissions, OptionType}
import net.dv8tion.jda.api.interactions.components.buttons._
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.{EmbedBuilder, Permission}
import org.postgresql.util.PSQLException
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.utils.TimeFormat

import java.awt.Color
import java.sql.{Connection, Timestamp}
import java.time.{Instant, ZoneOffset, ZonedDateTime, DayOfWeek}
import scala.collection.immutable.ListMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters._
import java.time.format._
import scala.util.{Failure, Success}
import java.time.{LocalTime, ZoneId, LocalDateTime, LocalDate}
import java.time.temporal.ChronoUnit
import scala.util.{Try, Success, Failure}
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.util.Random
import scala.concurrent.Await
import scala.concurrent.duration._

import sttp.client3._
import org.jsoup.Jsoup
import scala.jdk.CollectionConverters._
import io.circe.parser._
import io.circe.HCursor

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
  type DeathsCache = domain.DeathsCache; val DeathsCache = domain.DeathsCache
  type LevelsCache = domain.LevelsCache; val LevelsCache = domain.LevelsCache
  type ListCache = domain.ListCache; val ListCache = domain.ListCache
  type SatchelStamp = domain.SatchelStamp; val SatchelStamp = domain.SatchelStamp
  type BoostedStamp = domain.BoostedStamp; val BoostedStamp = domain.BoostedStamp
  type DeathScreenshot = domain.DeathScreenshot; val DeathScreenshot = domain.DeathScreenshot
  type CustomSort = domain.CustomSort; val CustomSort = domain.CustomSort
  type BossEntry = domain.BossEntry; val BossEntry = domain.BossEntry

  implicit private val actorSystem: ActorSystem = ActorSystem()
  implicit private val ex: ExecutionContextExecutor = actorSystem.dispatcher
  private val tibiaDataClient: tibiadata.TibiaApi = new TibiaDataClient()
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
  private val huntedAlliedRepository: persistence.HuntedAlliedRepository =
    new persistence.jdbc.JdbcHuntedAlliedRepository(connectionProvider)
  private val customSortRepository: persistence.CustomSortRepository =
    new persistence.jdbc.JdbcCustomSortRepository(connectionProvider)
  private val worldConfigRepository: persistence.WorldConfigRepository =
    new persistence.jdbc.JdbcWorldConfigRepository(connectionProvider, Config.mergedWorlds)
  private val discordConfigRepository: persistence.DiscordConfigRepository =
    new persistence.jdbc.JdbcDiscordConfigRepository(connectionProvider)

  // Let the games begin
  logger.info("Starting up")

  val jda = app.Bootstrap.buildReadyJda(Config.token, new BotListener())
  logger.info("JDA ready")

  // single read-side seam over JDA (guild/user lookups, identity, presence)
  val discordGateway: discord.DiscordGateway = new discord.JdaDiscordGateway(jda)

  // get the discord servers the bot is in
  private val guilds: List[Guild] = discordGateway.guilds

  // per-world stream lifecycle
  private val streamSupervisor = new app.StreamSupervisor

  // Galthen's Satchel cooldown tracking
  val galthenService = new galthen.GalthenService(galthenRepository, connectionProvider, discordGateway)

  // Per-user boosted boss/creature notification subscriptions
  val boostedService = new boosted.BoostedService(connectionProvider, boostedRepository, () => boostedBossesList)

  // Bot-creator-only /admin operations
  val adminService = new admin.AdminService(
    discordGateway,
    botUser,
    discordRetrieveConfig _,
    () => { dreamScar = fetchDreamScarBosses().map(e => e.world -> e.boss).toMap }
  )

  // get bot userID (used to stamp automated enemy detection messages)
  val botUser = discordGateway.selfUserId
  private val botName = discordGateway.selfUserName
  // the application owner = the bot creator (used to gate /admin)
  val botOwner: String = discordGateway.applicationOwnerId

  // Core hunted/allied/world state, read every cycle by the per-world streams and
  // written by command threads — @volatile gives cross-thread visibility.
  @volatile var customSortData: Map[String, List[CustomSort]] = Map.empty
  @volatile var huntedGuildsData: Map[String, List[Guilds]] = Map.empty
  @volatile var alliedGuildsData: Map[String, List[Guilds]] = Map.empty
  @volatile var activityCommandBlocker: Map[String, Boolean] = Map.empty
  @volatile var characterCache: Map[String, ZonedDateTime] = Map.empty

  // The maps written by both streams and command threads live in StreamState, which
  // serialises every read-modify-write. BotApp delegates so existing call sites
  // (BotApp.activityData / modifyActivityData / ...) are unchanged.
  val streamState = new state.StreamState
  def activityData: Map[String, List[PlayerCache]] = streamState.activityData
  def huntedPlayersData: Map[String, List[Players]] = streamState.huntedPlayersData
  def alliedPlayersData: Map[String, List[Players]] = streamState.alliedPlayersData
  def modifyActivityData(f: Map[String, List[PlayerCache]] => Map[String, List[PlayerCache]]): Unit =
    streamState.modifyActivityData(f)
  def modifyHuntedPlayersData(f: Map[String, List[Players]] => Map[String, List[Players]]): Unit =
    streamState.modifyHuntedPlayersData(f)
  def modifyAlliedPlayersData(f: Map[String, List[Players]] => Map[String, List[Players]]): Unit =
    streamState.modifyAlliedPlayersData(f)

  @volatile var worldsData: Map[String, List[Worlds]] = Map.empty
  @volatile var discordsData: Map[String, List[Discords]] = Map.empty
  var worlds: List[String] = Config.worldList

  // Dream Courts boss rotation extracted to domain.time.DreamScarCycle
  val bossCycle = domain.time.DreamScarCycle.bossCycle
  val indexOfBoss: Map[String, Int] = domain.time.DreamScarCycle.indexOfBoss
  var dreamScar: Map[String, String] = fetchDreamScarBosses().map(e => e.world -> e.boss).toMap
  var dreamScarLastCheck: String = System.currentTimeMillis().toString
  var dromeTime = domain.time.DromeCycle.initial // 27 May 2026 server save - increment 2 weeks from here

  // Boosted Boss
  val boostedBosses: Future[Either[String, BoostedResponse]] = tibiaDataClient.getBoostedBoss()
  val bossFuture: Future[List[String]] = boostedBosses.map {
    case Right(boostedResponse) =>
      val boostedBoss = boostedResponse.boostable_bosses.boostable_boss_list
      val boostedBossList = boostedBoss.map(_.name.toLowerCase).toList
      boostedBossList
    case Left(errorMessage) =>
      List.empty[String]
  }

  // Combine both futures and send the message
  private var updateOnOdd = 0

  val bossesFutures: Future[List[String]] = for {
    bosses <- bossFuture
  } yield bosses

  val boostedBossesList: List[String] = Await.result(bossesFutures, 10.seconds)

  // Slash command schemas live in commands.CommandSchemas
  lazy val commands = com.tibiabot.commands.CommandSchemas.commands

  // create the deaths/levels cache db
  createCacheDatabase()

  // initialize the database
  guilds.foreach{g =>
    if (g.getIdLong == 867319250708463628L || g.getIdLong == 1082484147492237515L) { // Violent Bot Discords
      val adminCommands = com.tibiabot.commands.CommandSchemas.adminCommands
      g.updateCommands().addCommands(adminCommands.asJava).complete()
    } else {
      // update the commands
      g.updateCommands().addCommands(commands.asJava).complete()
    }
  }

  // Start all world streams
  var startUpComplete = false
  val startTime = Instant.now()
  // update Drome Timer to the latest cycle
  if (dromeTime.isBefore(startTime)) {
    advanceDromeTime(startTime)
  }
  startBot(None, None) // guild: Option[Guild], world: Option[String]

  // run the scheduler to clean cache and update dashboard every hour
  actorSystem.scheduler.schedule(60.seconds, 30.seconds) {
    // set activity status
    // only do this every second cycle
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
        case _: Throwable => logger.info("Failed to update the bot's status counts")
      }
      removeDeathsCache(ZonedDateTime.now())
      removeLevelsCache(ZonedDateTime.now())
      cleanHuntedList()
      galthenService.cleanExpired()
      cleanOnlineListCache(30)
      updateOnOdd = 0 // Toggle the flag
    } else {
      updateOnOdd += 1
    }
    // Updating boosted creature/boss at server save
    val currentTime = ZonedDateTime.now(ZoneId.of("Europe/Berlin")).toLocalTime()
    if (ServerSaveSchedule.isServerSaveWindow(currentTime)) {
      try{
        val now = System.currentTimeMillis()
        if (now - dreamScarLastCheck.toLong > 60L * 60 * 1000) {
          dreamScarLastCheck = now.toString
          dreamScar = shiftAllBossesUp(dreamScar)
        }
        if (dromeTime.isBefore(Instant.now())) {
          advanceDromeTime(Instant.now())
        }
      }
      catch {
        case _ : Throwable => logger.info("Failed to get Dream Boss info from wiki")
      }
      try {
        boostedMessages().map { boostedBossAndCreature =>
          val currentBoss = boostedBossAndCreature.boss
          val currentCreature = boostedBossAndCreature.creature
          val bossChanged = boostedBossAndCreature.bossChanged
          val creatureChanged = boostedBossAndCreature.creatureChanged

          // Boosted Boss
          val boostedBoss: Future[Either[String, BoostedResponse]] = tibiaDataClient.getBoostedBoss()
          val bossEmbedFuture: Future[(MessageEmbed, Boolean, String)] = boostedBoss.map {
            case Right(boostedResponse) =>
              val boostedBoss = boostedResponse.boostable_bosses.boosted.name
              if (boostedBoss.toLowerCase != currentBoss.toLowerCase) {
                boostedMonsterUpdate(boostedBoss, "", "1", "")
              }
              (
                createBoostedEmbed("Boosted Boss", Config.bossEmoji, "https://www.tibia.com/library/?subtopic=boostablebosses", creatureImageUrl(boostedBoss), s"The boosted boss today is:\n### ${Config.indentEmoji}${Config.archfoeEmoji} **[$boostedBoss](${creatureWikiUrl(boostedBoss)})**"),
                boostedBoss.toLowerCase != currentBoss.toLowerCase && currentBoss.toLowerCase != "none",
                boostedBoss
              )

            case Left(errorMessage) =>
              throw new Exception(s"Failed to load boosted boss.")
          }

          // Boosted Creature
          val boostedCreature: Future[Either[String, CreatureResponse]] = tibiaDataClient.getBoostedCreature()
          val creatureEmbedFuture: Future[(MessageEmbed, Boolean, String)] = boostedCreature.map {
            case Right(creatureResponse) =>
              val boostedCreature = creatureResponse.creatures.boosted.name
              if (boostedCreature.toLowerCase != currentCreature.toLowerCase) {
                boostedMonsterUpdate("", boostedCreature, "", "1")
              }
              (
                createBoostedEmbed("Boosted Creature", Config.creatureEmoji, "https://www.tibia.com/library/?subtopic=creatures", creatureImageUrl(boostedCreature), s"The boosted creature today is:\n### ${Config.indentEmoji}${Config.levelUpEmoji} **[$boostedCreature](${creatureWikiUrl(boostedCreature)})**"),
                boostedCreature.toLowerCase != currentCreature.toLowerCase && currentCreature.toLowerCase != "none",
                boostedCreature
              )

            case Left(errorMessage) =>
              throw new Exception(s"Failed to load boosted boss.")
          }

          // Combine both futures and send the message
          val combinedFutures: Future[List[(MessageEmbed, Boolean, String)]] = for {
            bossEmbed <- bossEmbedFuture
            creatureEmbed <- creatureEmbedFuture
          } yield List(bossEmbed, creatureEmbed)

          combinedFutures.map { boostedInfoList =>
            if (bossChanged == "1" && creatureChanged == "1") {
              boostedMonsterUpdate("", "", "0", "0")
              // Do something if at least one of the embeds changed
              val embeds: List[MessageEmbed] = boostedInfoList.map { case (embed, _, _) => embed }.toList
              val notificationsList: List[BoostedStamp] = boostedService.boostedAll()
              notificationsList.foreach { entry =>
                var matchedNotification = false
                boostedInfoList.foreach { case (_, _, boostedName) =>
                  if (boostedName.toLowerCase == entry.boostedName.toLowerCase || entry.boostedName.toLowerCase == "all") {
                    matchedNotification = true
                  }
                }
                if (matchedNotification) {
                  val user: User = discordGateway.retrieveUser(entry.user)
                  if (user != null) {
                    try {
                      user.openPrivateChannel().queue { privateChannel =>
                        val messageText = s"🔔 ${boostedInfoList.head._3} • ${boostedInfoList.last._3}"
                        privateChannel.sendMessage(messageText).setEmbeds(embeds.asJava).setActionRow(
                          Button.primary("boosted list", " ").withEmoji(Emoji.fromFormatted(Config.letterEmoji))
                        ).queue()
                      }
                    } catch {
                      case ex: Exception => logger.info(s"Failed to send Boosted notification to user: '${entry.user}'")
                    }
                  }
                }
              }

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
                            case _: Throwable => logger.warn(s"Failed to get the boosted boss creature message for deletion in Guild ID: '${guild.getId}' Guild Name: '${guild.getName}':")
                          }
                        }

                        val dreamScarDaily = dreamScar.getOrElse(lastWorld, "World not found")

                        val rashidLocation = ServerSaveSchedule.rashidLocation(ZonedDateTime.now(ZoneId.of("Europe/Berlin")).minusHours(10).getDayOfWeek)
                        val rashidEmbed = new EmbedBuilder()
                        rashidEmbed.setDescription(s"Today Rashid can be found in:\n### ${Config.indentEmoji}${Config.goldEmoji} **[${rashidLocation}](https://tibia.fandom.com/wiki/Rashid)**")
                        rashidEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Rashid.gif")
                        rashidEmbed.setColor(3092790)

                        // Drome Timer
                        val now = Instant.now()
                        val dromeShow = ServerSaveSchedule.shouldShowDrome(now, dromeTime)
                        val dromeEmbed = new EmbedBuilder()
                          .setDescription(s"The current Drome cycle will end:\n### ${Config.indentEmoji}${Config.dromeEmoji} ${TimeFormat.RELATIVE.format(dromeTime)}")
                          .setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Phant.gif")
                          .setColor(3092790)

                        val dreamScarEmbed = new EmbedBuilder()
                        dreamScarEmbed.setDescription(s"The Dream Courts boss for **$lastWorld** is:\n### ${Config.indentEmoji}${Config.dreamScarEmoji} **[${dreamScarDaily}](https://tibia.fandom.com/wiki/Dream_Scar/Boss_of_the_Day)**")
                        dreamScarEmbed.setThumbnail(creatureImageUrl(dreamScarDaily))
                        dreamScarEmbed.setColor(3092790)

                        val embedsList = if (dromeShow) List(rashidEmbed.build(), dreamScarEmbed.build(), dromeEmbed.build()) else List(rashidEmbed.build(), dreamScarEmbed.build())
                        val addRashidDreamScarEmbeds: List[MessageEmbed] = embeds ++ embedsList

                        boostedChannel.sendMessageEmbeds(addRashidDreamScarEmbeds.asJava)
                          .setActionRow(
                            Button.primary("boosted list", "Server Save Notifications").withEmoji(Emoji.fromFormatted(Config.letterEmoji))
                          )
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
            }
          }
        }
      }
      catch {
        case _ : Throwable => logger.info("Failed to update the boosted messages")
      }
    }
  }

  // run hunted list cleanup every day at 10:30AM CET
  private val currentTime = Instant.now
  private val targetTime = LocalDateTime.of(LocalDate.now, LocalTime.of(10, 30, 0)).atZone(ZoneId.of("Europe/Berlin")).toInstant
  private val initialDelay = Duration.fromNanos(targetTime.toEpochMilli - currentTime.toEpochMilli).toSeconds.seconds
  private val interval = 24.hours

  def cleanOnlineListCache(maxAgeMinutes: Long): Unit = {
    val currentTime = ZonedDateTime.now()

    characterCache = characterCache.filter {
      case (_, timestamp) =>
        val ageMinutes = timestamp.until(currentTime, java.time.temporal.ChronoUnit.MINUTES)
        ageMinutes <= maxAgeMinutes
    }
  }

  //WIP
  private def boostedMonsterUpdate(boss: String, creature: String, bossChanged: String, creatureChanged: String): Unit =
    cacheRepository.updateBoosted(boss, creature, bossChanged, creatureChanged)

  private def boostedMessages(): List[BoostedCache] =
    cacheRepository.getBoosted()

  private def startBot(guild: Option[Guild], world: Option[String]): Unit = {

    if (guild.isDefined && world.isDefined) {

      val guildId = guild.get.getId

      //if (Config.verifiedDiscords.contains(guildId)) {
        // get hunted Players
        val huntedPlayers = playerConfig(guild.get, "hunted_players")
        modifyHuntedPlayersData(_ + (guildId -> huntedPlayers))

        // get allied Players
        val alliedPlayers = playerConfig(guild.get, "allied_players")
        modifyAlliedPlayersData(_ + (guildId -> alliedPlayers))

        // get hunted guilds
        val huntedGuilds = guildConfig(guild.get, "hunted_guilds")
        huntedGuildsData += (guildId -> huntedGuilds)

        // get allied guilds
        val alliedGuilds = guildConfig(guild.get, "allied_guilds")
        alliedGuildsData += (guildId -> alliedGuilds)

        // get worlds
        val worldsInfo = worldConfig(guild.get)
        worldsData += (guildId -> worldsInfo)

        // get tracked activity characters
        val activityInfo = activityConfig(guild.get, "tracked_activity")
        modifyActivityData(_ + (guildId -> activityInfo))

        // get customSort Data
        val customSortInfo = customSortConfig(guild.get, "online_list_categories")
        customSortData += (guildId -> customSortInfo)

        // set default activityCommandBlocker state
        activityCommandBlocker += (guildId -> false)

        val adminChannels = discordRetrieveConfig(guild.get)
        val adminChannelId = if (adminChannels.nonEmpty) adminChannels("admin_channel") else "0"
        val boostedChannelId = if (adminChannels.nonEmpty) adminChannels("boosted_channel") else "0"
        val boostedMessageId = if (adminChannels.nonEmpty) adminChannels("boosted_messageid") else "0"

        worldsInfo.foreach{ w =>
          if (w.name == world.get) {
            val discords = Discords(
              id = guildId,
              adminChannel = adminChannelId,
              boostedChannel = boostedChannelId,
              boostedMessage = boostedMessageId
            )
            discordsData = discordsData.updated(w.name, discords :: discordsData.getOrElse(w.name, Nil))
            // Preserves prior behaviour: when the world stream already exists it was
            // left unchanged (the usedBy append was overwritten and never took effect);
            // only an absent world starts a new stream.
            if (!streamSupervisor.contains(world.get)) {
              streamSupervisor.put(world.get, new TibiaBot(world.get).stream.run(), List(discords))
            }
          }
        }
      //}
    } else {
      // build guild specific data map
      guilds.foreach{g =>

        val guildId = g.getId
        //if (Config.verifiedDiscords.contains(guildId)) {

          if (checkConfigDatabase(g)) {
            // get hunted Players
            val huntedPlayers = playerConfig(g, "hunted_players")
            modifyHuntedPlayersData(_ + (guildId -> huntedPlayers))

            // get allied Players
            val alliedPlayers = playerConfig(g, "allied_players")
            modifyAlliedPlayersData(_ + (guildId -> alliedPlayers))

            // get hunted guilds
            val huntedGuilds = guildConfig(g, "hunted_guilds")
            huntedGuildsData += (guildId -> huntedGuilds)

            // get allied guilds
            val alliedGuilds = guildConfig(g, "allied_guilds")
            alliedGuildsData += (guildId -> alliedGuilds)

            // get worlds
            val worldsInfo = worldConfig(g)
            worldsData += (guildId -> worldsInfo)

            // get tracked activity characters
            val activityInfo = activityConfig(g, "tracked_activity")
            modifyActivityData(_ + (guildId -> activityInfo))

            // get customSort Data
            val customSortInfo = customSortConfig(g, "online_list_categories")
            customSortData += (guildId -> customSortInfo)

            // set default activityCommandBlocker state
            activityCommandBlocker += (guildId -> false)

            val adminChannels = discordRetrieveConfig(g)
            val adminChannelId = if (adminChannels.nonEmpty) adminChannels("admin_channel") else "0"
            val boostedChannelId = if (adminChannels.nonEmpty) adminChannels("boosted_channel") else "0"
            val boostedMessageId = if (adminChannels.nonEmpty) adminChannels("boosted_messageid") else "0"

            // populate a new Discords list so i can only run 1 stream per world
            worldsInfo.foreach{ w =>
              val discords = Discords(
                id = guildId,
                adminChannel = adminChannelId,
                boostedChannel = boostedChannelId,
                boostedMessage = boostedMessageId
              )
              discordsData = discordsData.updated(w.name, discords :: discordsData.getOrElse(w.name, Nil))
            }
          }
        //}
      }
      discordsData.foreach { case (worldName, discordsList) =>
        streamSupervisor.put(worldName, new TibiaBot(worldName).stream.run(), discordsList)
        Thread.sleep(5500) // space each stream out 3 seconds
      }
      startUpComplete = true
    }

    /***
    // check if world parameter has been passed, and convert to a list
    val guildWorlds = world match {
      case Some(worldName) => worldsData.getOrElse(guild.getId, List()).filter(w => w.name == worldName)
      case None => worldsData.getOrElse(guild.getId, List())
    }
    ***/
  }

  def infoHunted(event: SlashCommandInteractionEvent, subCommand: String, subOptionValue: String): MessageEmbed = {
    // get command option
    val subOptionValueLower = subOptionValue.toLowerCase()
    val guild = event.getGuild
    // default embed content
    var embedText = s"${Config.noEmoji} An error occurred while running the `info` command"
    if (checkConfigDatabase(guild)) {
      val guildId = guild.getId
      if (subCommand == "guild") { // command run with 'guild'
        val huntedGuilds = huntedGuildsData.getOrElse(guildId, List.empty[Guilds])
        huntedGuilds.find(_.name == subOptionValueLower).map {
          case gData =>
            // add guild to hunted list and database
            val gText = gData.reasonText
            val gUser = gData.addedBy
            val gNameFormal = subOptionValueLower.split(" ").map(_.capitalize).mkString(" ")
            val gLink = guildUrl(gNameFormal)
            embedText = s"**Guild:** [$gNameFormal]($gLink)\n **added by:** <@$gUser>\n **reason:** $gText"

            val embed = new EmbedBuilder()
            embed.setTitle(s":gear: hunted guild details:")
            embed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Tibiapedia.gif")
            embed.setColor(3092790)
            embed.setDescription(embedText)
            val returnEmbed = embed.build()
            return returnEmbed

        }.getOrElse {
          embedText = s":gear: The guild **$subOptionValueLower** is not on the hunted list."
        }
      } else if (subCommand == "player") { // command run with 'player'
        val huntedPlayers = huntedPlayersData.getOrElse(guildId, List.empty[Players])
        huntedPlayers.find(_.name == subOptionValueLower).map {
          case pData =>
            // add guild to hunted list and database
            val pText = pData.reasonText
            val pUser = pData.addedBy
            val pNameFormal = subOptionValueLower.split(" ").map(_.capitalize).mkString(" ")
            val pLink = charUrl(pNameFormal)
            embedText = s"**Player:** [$pNameFormal]($pLink)\n **added by:** <@$pUser>\n **reason:** $pText"

            val embed = new EmbedBuilder()
            embed.setTitle(s":gear: hunted player details:")
            embed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Tibiapedia.gif")
            embed.setColor(3092790)
            embed.setDescription(embedText)
            val returnEmbed = embed.build()
            return returnEmbed

        }.getOrElse {
          embedText = s":gear: The player **$subOptionValueLower** is not tagged with any notes."
        }
      }
    } else {
      embedText = s"${Config.noEmoji} You need to run `/setup` and add a world first."
    }
    new EmbedBuilder()
      .setColor(3092790)
      .setDescription(embedText)
      .build()
  }

  def infoAllies(event: SlashCommandInteractionEvent, subCommand: String, subOptionValue: String): MessageEmbed = {
    // get command option
    val subOptionValueLower = subOptionValue.toLowerCase()
    val guild = event.getGuild
    // default embed content
    var embedText = s"${Config.noEmoji} An error occurred while running the `info` command"
    if (checkConfigDatabase(guild)) {
      val guildId = guild.getId
      if (subCommand == "guild") { // command run with 'guild'
        val alliedGuilds = alliedGuildsData.getOrElse(guildId, List.empty[Guilds])
        alliedGuilds.find(_.name == subOptionValueLower).map {
          case gData =>
            // add guild to hunted list and database
            val gText = gData.reasonText
            val gUser = gData.addedBy
            val gNameFormal = subOptionValueLower.split(" ").map(_.capitalize).mkString(" ")
            val gLink = guildUrl(gNameFormal)
            embedText = s"**Guild:** [$gNameFormal]($gLink)\n **added by:** <@$gUser>\n **reason:** $gText"

            val embed = new EmbedBuilder()
            embed.setTitle(s":gear: allied guild details:")
            embed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Tibiapedia.gif")
            embed.setColor(3092790)
            embed.setDescription(embedText)
            val returnEmbed = embed.build()
            return returnEmbed

        }.getOrElse {
          embedText = s":gear: The guild **$subOptionValueLower** is not on the allied list."
        }
      } else if (subCommand == "player") { // command run with 'player'
        val alliedPlayers = alliedPlayersData.getOrElse(guildId, List.empty[Players])
        alliedPlayers.find(_.name == subOptionValueLower).map {
          case pData =>
            // add guild to hunted list and database
            val pText = pData.reasonText
            val pUser = pData.addedBy
            val pNameFormal = subOptionValueLower.split(" ").map(_.capitalize).mkString(" ")
            val pLink = charUrl(pNameFormal)
            embedText = s"**Player: [$pNameFormal]($pLink)**\n **added by:** <@$pUser>\n **reason:** $pText"

            val embed = new EmbedBuilder()
            embed.setTitle(s":gear: allied player details:")
            embed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Tibiapedia.gif")
            embed.setColor(3092790)
            embed.setDescription(embedText)
            val returnEmbed = embed.build()
            return returnEmbed

        }.getOrElse {
          embedText = s":gear: The player **$subOptionValueLower** is not tagged with any notes."
        }
      }
    } else {
      embedText = s"${Config.noEmoji} You need to run `/setup` and add a world first."
    }
    new EmbedBuilder()
      .setColor(3092790)
      .setDescription(embedText)
      .build()
  }

  def listAlliesAndHuntedGuilds(event: SlashCommandInteractionEvent, arg: String, callback: List[MessageEmbed] => Unit): Unit = {
    val guild = event.getGuild
    val embedColor = 3092790

    val guildHeader = s"__**Guilds:**__"
    val listGuilds: List[Guilds] = if (arg == "allies") alliedGuildsData.getOrElse(guild.getId, List.empty[Guilds]).map(g => g)
      else if (arg == "hunted") huntedGuildsData.getOrElse(guild.getId, List.empty[Guilds]).map(g => g)
      else List.empty
    val guildThumbnail = if (arg == "allies") "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Angel_Statue.gif" else if (arg == "hunted") "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Stone_Coffin.gif" else ""
    val guildBuffer = ListBuffer[MessageEmbed]()
    if (listGuilds.nonEmpty) {
      // run api against guild
      val guildListFlow = Source(listGuilds.map(p => (p.name, p.reason)).toSet).mapAsyncUnordered(4)(tibiaDataClient.getGuildWithInput).toMat(Sink.seq)(Keep.right)
      val futureResults: Future[Seq[(Either[String, GuildResponse], String, String)]] = guildListFlow.run()
      futureResults.onComplete {
        case Success(output) =>
          val guildApiBuffer = ListBuffer[String]()
          output.foreach {
            case (Right(guildResponse), name, reason) =>
              val guildName = guildResponse.guild.name
              val reasonEmoji = if (reason == "true") ":pencil:" else ""
              if (guildName != "") {
                val guildMembers = guildResponse.guild.members_total.toInt
                val guildLine = s":busts_in_silhouette: **$guildMembers** — **[$guildName](${guildUrl(guildName)})** $reasonEmoji"
                guildApiBuffer += guildLine
              }
              else {
                guildApiBuffer += s"**$name** *(This guild doesn't exist)* $reasonEmoji"
              }
            case (Left(errorMessage), name, reason) =>
              guildApiBuffer += s"**$name** *(This guild doesn't exist)*"
          }
          val guildsAsList: List[String] = List(guildHeader) ++ guildApiBuffer
          var field = ""
          var isFirstEmbed = true
          guildsAsList.foreach { v =>
            val currentField = field + "\n" + v
            if (currentField.length <= 4096) { // don't add field yet, there is still room
              field = currentField
            } else { // it's full, add the field
              val interimEmbed = new EmbedBuilder()
              interimEmbed.setDescription(field)
              interimEmbed.setColor(embedColor)
              if (isFirstEmbed) {
                interimEmbed.setThumbnail(guildThumbnail)
                isFirstEmbed = false
              }
              guildBuffer += interimEmbed.build()
              field = v
            }
          }
          val finalEmbed = new EmbedBuilder()
          finalEmbed.setDescription(field)
          finalEmbed.setColor(embedColor)
          if (isFirstEmbed) {
            finalEmbed.setThumbnail(guildThumbnail)
            isFirstEmbed = false
          }
          guildBuffer += finalEmbed.build()
          callback(guildBuffer.toList)
        case Failure(_) => // e.printStackTrace
      }
    } else { // guild list is empty
      val listIsEmpty = new EmbedBuilder()
      val listisEmptyMessage = guildHeader ++ s"\n*The guilds list is empty.*"
      listIsEmpty.setDescription(listisEmptyMessage)
      listIsEmpty.setColor(embedColor)
      listIsEmpty.setThumbnail(guildThumbnail)
      guildBuffer += listIsEmpty.build()
      callback(guildBuffer.toList)
    }
  }

  def clearAllies(event: SlashCommandInteractionEvent): MessageEmbed = {
    val guild = event.getGuild
    val guildId = guild.getId

    val listGuilds: List[Guilds] = alliedGuildsData.getOrElse(guildId, List.empty[Guilds])
    val listPlayers: List[Players] = alliedPlayersData.getOrElse(guildId, List.empty[Players])

    // Create Sets for faster lookups
    val guildNamesToRemove = listGuilds.map(_.name.toLowerCase).toSet
    val playerNamesToRemove = listPlayers.map(_.name.toLowerCase).toSet

    if (listGuilds.nonEmpty) {
      modifyActivityData { m =>
        m.mapValues {
          _.filterNot(pc => guildNamesToRemove.contains(pc.guild.toLowerCase))
        }.toMap
      }

      listGuilds.foreach { guildEntry =>
        removeAllyFromDatabase(guild, "guild", guildEntry.name.toLowerCase)
        removeGuildActivityfromDatabase(guild, guildEntry.name.toLowerCase)
      }
    }

    if (listPlayers.nonEmpty) {
      modifyActivityData { m =>
        val updatedList = m.getOrElse(guildId, List.empty)
          .filterNot(player => playerNamesToRemove.contains(player.name.toLowerCase))

        m.updated(guildId, updatedList)
      }

      listPlayers.foreach { filterPlayer =>
        removeAllyFromDatabase(guild, "player", filterPlayer.name.toLowerCase)
        removePlayerActivityfromDatabase(guild, filterPlayer.name.toLowerCase)
      }
    }

    val embedText = s"${Config.yesEmoji} The allies list has been reset."
    new EmbedBuilder()
      .setColor(3092790)
      .setDescription(embedText)
      .build()
  }

  def clearHunted(event: SlashCommandInteractionEvent): MessageEmbed = {
    val guild = event.getGuild
    val guildId = guild.getId
    val listGuilds: List[Guilds] = huntedGuildsData.getOrElse(guild.getId, List.empty[Guilds])
    val listPlayers: List[Players] = huntedPlayersData.getOrElse(guild.getId, List.empty[Players])
    // Create Sets for faster lookups
    val guildNamesToRemove = listGuilds.map(_.name.toLowerCase).toSet
    val playerNamesToRemove = listPlayers.map(_.name.toLowerCase).toSet
    if (listGuilds.nonEmpty) {
      // Filter out activityData in one pass by using a Set for efficient lookup
      modifyActivityData { m =>
        m.mapValues {
          _.filterNot(pc => guildNamesToRemove.contains(pc.guild.toLowerCase))
        }.toMap
      }
      // Perform database removal in a batch operation
      listGuilds.foreach { guildEntry =>
        removeHuntedFromDatabase(guild, "guild", guildEntry.name.toLowerCase)
        removeGuildActivityfromDatabase(guild, guildEntry.name.toLowerCase)
      }
    }
    if (listPlayers.nonEmpty) {
      // Efficiently update activityData by using Set lookups for player names
      modifyActivityData { m =>
        val updatedList = m.getOrElse(guildId, List.empty)
          .filterNot(player => playerNamesToRemove.contains(player.name.toLowerCase))

        m.updated(guildId, updatedList)
      }
      // Perform database removal in a batch operation
      listPlayers.foreach { filterPlayer =>
        removeHuntedFromDatabase(guild, "player", filterPlayer.name.toLowerCase)
        removePlayerActivityfromDatabase(guild, filterPlayer.name.toLowerCase)
      }
    }
    var embedText = s"${Config.yesEmoji} The hunted list has been reset."
    new EmbedBuilder()
      .setColor(3092790)
      .setDescription(embedText)
      .build()
    //
  }

  private def getListTable(world: String): List[ListCache] =
    cacheRepository.getList(world)

  def addListToCache(name: String, formerNames: List[String], world: String, formerWorlds: List[String], guild: String, level: String, vocation: String, lastLogin: String, updatedTime: ZonedDateTime): Unit =
    cacheRepository.addToList(name, formerNames, world, formerWorlds, guild, level, vocation, lastLogin, updatedTime)

  private def cleanHuntedList(): Unit =
    cacheRepository.removeExpiredList(ZonedDateTime.now())

  def dateStringToEpochSeconds(dateString: String): String = {
    if (dateString != "") {
      val formatter = DateTimeFormatter.ISO_INSTANT
      val instant = Instant.from(formatter.parse(dateString))
      val now = Instant.now()
      if (Math.abs(instant.until(now, ChronoUnit.HOURS)) <= 24) {
        s"<:daily:1133349016814485584><t:${instant.getEpochSecond().toString}:R>"
      } else {
        ""
      }
    } else ""
  }

  def listAlliesAndHuntedPlayers(event: SlashCommandInteractionEvent, arg: String, callback: List[MessageEmbed] => Unit): Unit = {
    // get command option
    val guild = event.getGuild
    val guildId = guild.getId
    val embedColor = 3092790

    //val playerHeader = if (arg == "allies") s"${Config.allyGuild} **Players** ${Config.allyGuild}" else if (arg == "hunted") s"${Config.enemy} **Players** ${Config.enemy}" else ""
    val playerHeader = s"__**Players:**__"
    val listPlayers: List[Players] = if (arg == "allies") alliedPlayersData.getOrElse(guild.getId, List.empty[Players]).map(g => g)
      else if (arg == "hunted") huntedPlayersData.getOrElse(guild.getId, List.empty[Players]).map(g => g)
      else List.empty
    val embedThumbnail = if (arg == "allies") "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Angel_Statue.gif" else if (arg == "hunted") "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Stone_Coffin.gif" else ""
    val playerBuffer = ListBuffer[MessageEmbed]()
    if (listPlayers.nonEmpty) {

      /// Get the list of all worlds
      val allWorlds: List[Worlds] = worldConfig(guild)
      var concatenatedListCache: List[ListCache] = List.empty[ListCache]
      for (world <- allWorlds) {
        val listCacheForWorld: List[ListCache] = getListTable(world.name)
        concatenatedListCache = concatenatedListCache ++ listCacheForWorld
      }

      // Filter the listPlayers to get only those players that are not in the concatenatedListCache or whose updateTime is older than 24 hours
      val playersToUpdate: List[Players] = listPlayers.filterNot { player =>
        concatenatedListCache.find(_.name.toLowerCase == player.name.toLowerCase).exists { cache =>
          cache.updatedTime.isAfter(ZonedDateTime.now().minus(24, ChronoUnit.HOURS))
        }
      }
      // Get the names of players in listPlayers
      val playerNamesSet: Set[String] = listPlayers.map(_.name.toLowerCase).toSet
      // Filter the concatenatedListCache to only include players that exist in listPlayers and meet the condition for update time
      val filteredConcatenatedListCache: List[ListCache] = concatenatedListCache.filter { player =>
        playerNamesSet.contains(player.name.toLowerCase) && player.updatedTime.isAfter(ZonedDateTime.now().minus(24, ChronoUnit.HOURS))
      }
      // run api against players
      val listPlayersFlow = Source(playersToUpdate.map(p => (p.name, p.reason, p.reasonText)).toSet).mapAsyncUnordered(4)(tibiaDataClient.getCharacterWithInput).toMat(Sink.seq)(Keep.right)
      val futureResults: Future[Seq[(Either[String, CharacterResponse], String, String, String)]] = listPlayersFlow.run()
      futureResults.onComplete {
        case Success(output) =>
          val vocationBuffers = ListMap(
            "druid" -> ListBuffer[(Int, String, String)](),
            "knight" -> ListBuffer[(Int, String, String)](),
            "paladin" -> ListBuffer[(Int, String, String)](),
            "sorcerer" -> ListBuffer[(Int, String, String)](),
            "monk" -> ListBuffer[(Int, String, String)](),
            "none" -> ListBuffer[(Int, String, String)]()
          )
          // Add concatenatedCacheNames to the respective vocationBuffers based on their vocations
          for (player <- filteredConcatenatedListCache) {
            val pName = player.name
            val pWorld = player.world
            val pLvl = player.level // You might want to set an appropriate level here for characters in the cache
            val pVoc = player.vocation.toLowerCase.split(' ').last
            val pEmoji = pVoc match {
              case "knight" => ":shield:"
              case "druid" => ":snowflake:"
              case "sorcerer" => ":fire:"
              case "paladin" => ":bow_and_arrow:"
              case "monk" => ":fist::skin-tone-3:"
              case "none" => ":hatching_chick:"
              case _ => ""
            }
            val pGuild = player.guild
            val allyGuildCheck = if (pGuild != "") alliedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == pGuild.toLowerCase()) else false
            val huntedGuildCheck = if (pGuild != "") huntedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == pGuild.toLowerCase()) else false
            val pIcon = (pGuild, allyGuildCheck, huntedGuildCheck, arg) match {
              case (_, true, _, "allies") => Config.allyGuild // allied guilds
              case (_, _, true, "allies") => s"${Config.enemyGuild}${Config.ally}"  // allied players but in enemy guild(?)
              case (_, _, true, "hunted") => s"${Config.enemyGuild}" // enemy player in hunted guild
              case (_, true, _, "hunted") => s"${Config.allyGuild}${Config.enemy}" // hunted players but in ally guild(?)
              case ("", _, _, "hunted") => Config.enemy // hunted players no guild
              case ("", _, _, "allies") => Config.ally // allied player in no guild
              case (_, _, _, "hunted") => s"${Config.otherGuild}${Config.enemy}" // hunted in neutral guild
              case (_, _, _, "allies") => s"${Config.otherGuild}${Config.ally}" // ally in neutral guild
              case _ => ""
            }
            val pLoginRelative = dateStringToEpochSeconds(player.last_login) // "2022-01-01T01:00:00Z"
            if (pVoc != "") {
              // only show players on worlds that you have setup
              if (allWorlds.exists(_.name.toLowerCase == pWorld.toLowerCase)) {
                vocationBuffers(pVoc) += ((pLvl.toInt, pWorld, s"$pEmoji **$pLvl** — **[${pName}](${charUrl(pName)})** $pIcon $pLoginRelative"))
              }
            }
          }
          output.foreach {
            case (Right(charResponse), name, _, _) =>
              if (charResponse.character.character.name != "") {
                val charName = charResponse.character.character.name
                val charLevel = charResponse.character.character.level.toInt
                val charGuild = charResponse.character.character.guild
                val charGuildName = if(charGuild.isDefined) charGuild.head.name else ""
                val allyGuildCheck = if (charGuildName != "") alliedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == charGuildName.toLowerCase()) else false
                val huntedGuildCheck = if (charGuildName != "") huntedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == charGuildName.toLowerCase()) else false
                val guildIcon = (charGuildName, allyGuildCheck, huntedGuildCheck, arg) match {
                  case (_, true, _, "allies") => Config.allyGuild // allied guilds
                  case (_, _, true, "allies") => s"${Config.enemyGuild}${Config.ally}"  // allied players but in enemy guild(?)
                  case (_, _, true, "hunted") => s"${Config.enemyGuild}" // enemy player in hunted guild
                  case (_, true, _, "hunted") => s"${Config.allyGuild}${Config.enemy}" // hunted players but in ally guild(?)
                  case ("", _, _, "hunted") => Config.enemy // hunted players no guild
                  case ("", _, _, "allies") => Config.ally // allied player in no guild
                  case (_, _, _, "hunted") => s"${Config.otherGuild}${Config.enemy}" // hunted in neutral guild
                  case (_, _, _, "allies") => s"${Config.otherGuild}${Config.ally}" // ally in neutral guild
                  case _ => ""
                }
                val charVocation = charResponse.character.character.vocation
                val charWorld = charResponse.character.character.world
                val charLink = charUrl(charName)
                val charEmoji = vocEmoji(charResponse)
                val pNameFormal = name.split(" ").map(_.capitalize).mkString(" ")
                val voc = charVocation.toLowerCase.split(' ').last
                val lastLoginTime = charResponse.character.character.last_login.getOrElse("")
                // only show players on worlds that you have setup
                if (allWorlds.exists(_.name.toLowerCase == charWorld.toLowerCase)) {
                  vocationBuffers(voc) += ((charLevel, charWorld, s"$charEmoji **${charLevel.toString}** — **[$pNameFormal]($charLink)** $guildIcon ${dateStringToEpochSeconds(lastLoginTime)}"))
                }
                //def addListToCache(name: String, formerNames: List[String], world: String, formerWorlds: List[String], guild: String, level: String, vocation: String, lastLogin: String, updatedTime: ZonedDateTime): Unit = {
                val formerNamesList = charResponse.character.character.former_names.map(_.toList).getOrElse(Nil)
                val formerWorldsList = charResponse.character.character.former_worlds.map(_.toList).getOrElse(Nil)
                val charLastLogin = charResponse.character.character.last_login.getOrElse("")
                addListToCache(charName, formerNamesList, charWorld, formerWorldsList, charGuildName, charLevel.toString, charVocation, charLastLogin, ZonedDateTime.now())
              } else {
                vocationBuffers("none") += ((0, "Character does not exist", s"${Config.noEmoji} **N/A** — **$name**"))
              }
            case (Left(errorMessage), name, _, _) =>
              vocationBuffers("none") += ((0, "Character does not exist", s"${Config.noEmoji} **N/A** — **$name**"))
          }
          // group by world
          val vocationWorldBuffers = vocationBuffers.map {
            case (voc, buffer) =>
              voc -> buffer.groupBy(_._2)
          }

          // druids grouped by world sorted by level
          val druidsWorldLists = vocationWorldBuffers("druid").map {
            case (world, worldBuffer) =>
              world -> worldBuffer.toList.sortBy(-_._1).map(_._3)
          }
          // knights
          val knightsWorldLists = vocationWorldBuffers("knight").map {
            case (world, worldBuffer) =>
              world -> worldBuffer.toList.sortBy(-_._1).map(_._3)
          }
          // paladins
          val paladinsWorldLists = vocationWorldBuffers("paladin").map {
            case (world, worldBuffer) =>
              world -> worldBuffer.toList.sortBy(-_._1).map(_._3)
          }
          // sorcerers
          val sorcerersWorldLists = vocationWorldBuffers("sorcerer").map {
            case (world, worldBuffer) =>
              world -> worldBuffer.toList.sortBy(-_._1).map(_._3)
          }
          // monks
          val monksWorldLists = vocationWorldBuffers("monk").map {
            case (world, worldBuffer) =>
              world -> worldBuffer.toList.sortBy(-_._1).map(_._3)
          }
          // none
          val noneWorldLists = vocationWorldBuffers("none").map {
            case (world, worldBuffer) =>
              world -> worldBuffer.toList.sortBy(-_._1).map(_._3)
          }

          // combine these into one list now that its ordered by level and grouped by world
          val allPlayers = List(noneWorldLists, monksWorldLists, sorcerersWorldLists, paladinsWorldLists, knightsWorldLists, druidsWorldLists).foldLeft(Map.empty[String, List[String]]) {
            (acc, m) => m.foldLeft(acc) {
              case (map, (k, v)) => map + (k -> (v ++ map.getOrElse(k, List())))
            }
          }


          // output a List[String] for the embed
          val playersList = List(playerHeader) ++ createWorldList(allPlayers)

          // build the embed
          var field = ""
          var isFirstEmbed = true
          playersList.foreach { v =>
            val currentField = field + "\n" + v
            if (currentField.length <= 4096) { // don't add field yet, there is still room
              field = currentField
            } else { // it's full, add the field
              val interimEmbed = new EmbedBuilder()
              interimEmbed.setDescription(field)
              interimEmbed.setColor(embedColor)
              if (isFirstEmbed) {
                interimEmbed.setThumbnail(embedThumbnail)
                isFirstEmbed = false
              }
              playerBuffer += interimEmbed.build()
              field = v
            }
          }
          val finalEmbed = new EmbedBuilder()
          finalEmbed.setDescription(field)
          finalEmbed.setColor(embedColor)
          if (isFirstEmbed) {
            finalEmbed.setThumbnail(embedThumbnail)
            isFirstEmbed = false
          }
          playerBuffer += finalEmbed.build()
          callback(playerBuffer.toList)
        case Failure(_) => // e.printStackTrace
      }
    } else { // player list is empty
      val listIsEmpty = new EmbedBuilder()
      val listisEmptyMessage = playerHeader ++ s"\n*The players list is empty.*"
      listIsEmpty.setDescription(listisEmptyMessage)
      listIsEmpty.setThumbnail(embedThumbnail)
      listIsEmpty.setColor(embedColor)
      playerBuffer += listIsEmpty.build()
      callback(playerBuffer.toList)

    }
  }

  def vocEmoji(char: CharacterResponse): String =
    presentation.Emojis.vocEmojiWithoutMonk(char.character.character.vocation)

  private def createWorldList(worlds: Map[String, List[String]]): List[String] = {
    val sortedWorlds = worlds.toList.sortBy(_._1)
      .sortWith((a, b) => {
        if (a._1 == "Character does not exist") false
        else if (b._1 == "Character does not exist") true
        else a._1 < b._1
      })
    sortedWorlds.flatMap {
      case (world, players) =>
        s":globe_with_meridians: **$world** :globe_with_meridians:" :: players
    }
  }

  def charUrl(char: String): String = presentation.Urls.charUrl(char)

  def guildUrl(guild: String): String = presentation.Urls.guildUrl(guild)

  def updateAdminChannel(inputId: String, channelId: String): Unit = {
    discordsData = discordsData.view.mapValues(_.map {
      case discord @ Discords(id, _, _, _) if id == inputId =>
        discord.copy(adminChannel = channelId)
      case other => other
    }).toMap
  }

  def updateBoostedChannel(inputId: String, channelId: String): Unit = {
    discordsData = discordsData.view.mapValues(_.map {
      case discord @ Discords(id, _, _, _) if id == inputId =>
        discord.copy(boostedChannel = channelId)
      case other => other
    }).toMap
  }

  def updateBoostedMessage(inputId: String, messageId: String): Unit = {
    discordsData = discordsData.view.mapValues(_.map {
      case discord @ Discords(id, _, _, _) if id == inputId =>
        discord.copy(boostedMessage = messageId)
      case other => other
    }).toMap
  }

  def addHunted(event: SlashCommandInteractionEvent, subCommand: String, subOptionValue: String, subOptionReason: String, callback: MessageEmbed => Unit): Unit = {
    // get command option
    val subOptionValueLower = subOptionValue.toLowerCase()
    val reason = if (subOptionReason == "none") "false" else "true"
    val commandUser = event.getUser.getId
    val guild = event.getGuild
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    // default embed content
    var embedText = s"${Config.noEmoji} An error occurred while running the /hunted command"
    if (checkConfigDatabase(guild)) {
      val guildId = guild.getId
      // get admin channel info from database
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (subCommand == "guild") { // command run with 'guild'
        // run api against guild
        val guildCheck: Future[Either[String, GuildResponse]] = tibiaDataClient.getGuild(subOptionValueLower)
        guildCheck.map {
          case Right(guildResponse) =>
            val guildName = guildResponse.guild.name
            val guildMembers = guildResponse.guild.members.getOrElse(List.empty[Members])
            (guildName, guildMembers)
          case Left(errorMessage) =>
            ("", List.empty)
        }.map { case (guildName, guildMembers) =>
          if (guildName != "") {
            if (!huntedGuildsData.getOrElse(guildId, List()).exists(g => g.name == subOptionValueLower)) {
              // add guild to hunted list and database
              huntedGuildsData = huntedGuildsData + (guildId -> (Guilds(subOptionValueLower, reason, subOptionReason, commandUser) :: huntedGuildsData.getOrElse(guildId, List())))
              addHuntedToDatabase(guild, "guild", subOptionValueLower, reason, subOptionReason, commandUser)
              embedText = s":gear: The guild **[$guildName](${guildUrl(guildName)})** has been added to the hunted list."

              // send embed to admin channel
              if (adminChannel != null) {
                if (adminChannel.canTalk() || !(Config.prod)) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> added the guild **[$guildName](${guildUrl(guildName)})** to the hunted list.")
                  adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Stone_Coffin.gif")
                  adminEmbed.setColor(3092790)
                  adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                }
              }

              // add each player in the guild to the activity list
              guildMembers.foreach { member =>
                val guildPlayers = activityData.getOrElse(guildId, List())
                if (!guildPlayers.exists(_.name == member.name)) {
                  val updatedTime = ZonedDateTime.now()
                  modifyActivityData(m => m + (guildId -> (PlayerCache(member.name, List(""), guildName, updatedTime) :: guildPlayers)))
                  addActivityToDatabase(guild, member.name, List(""), guildName, updatedTime)
                }
              }

              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            } else {
              embedText = s"${Config.noEmoji} The guild **[$guildName](${guildUrl(guildName)})** already exists in the hunted list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            }
          } else {
            embedText = s"${Config.noEmoji} The guild **$subOptionValueLower** does not exist."
            embedBuild.setDescription(embedText)
            callback(embedBuild.build())

          }
        }
      } else if (subCommand == "player") { // command run with 'player'
        // run api against player
        val playerCheck: Future[Either[String, CharacterResponse]] = tibiaDataClient.getCharacter(subOptionValueLower)
        playerCheck.map {
          case Right(charResponse) =>
            val character = charResponse.character.character
            (character.name, character.world, vocEmoji(charResponse), character.level.toInt)
          case Left(errorMessage) =>
            ("", "" , s"${Config.noEmoji}", 0)
        }.map { case (playerName, world, vocation, level) =>
          if (playerName != "") {
            if (!huntedPlayersData.getOrElse(guildId, List()).exists(g => g.name == subOptionValueLower)) {
              // add player to hunted list and database
              modifyHuntedPlayersData(m => m + (guildId -> (Players(subOptionValueLower, reason, subOptionReason, commandUser) :: m.getOrElse(guildId, List()))))
              addHuntedToDatabase(guild, "player", subOptionValueLower, reason, subOptionReason, commandUser)
              embedText = s":gear: The player **[$playerName](${charUrl(playerName)})** has been added to the hunted list."

              // send embed to admin channel
              if (adminChannel != null) {
                if (adminChannel.canTalk() || !(Config.prod)) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> added the player\n$vocation **$level** — **[$playerName](${charUrl(playerName)})**\nto the hunted list for **$world**.")
                  adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Stone_Coffin.gif")
                  adminEmbed.setColor(3092790)
                  adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                }
              }

              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            } else {
              embedText = s"${Config.noEmoji} The player **[$playerName](${charUrl(playerName)})** already exists in the hunted list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            }
          } else {
            embedText = s"${Config.noEmoji} The player **$subOptionValueLower** does not exist."
            embedBuild.setDescription(embedText)
            callback(embedBuild.build())

          }
        }
      }
    } else {
      embedText = s"${Config.noEmoji} You need to run `/setup` and add a world first."
      embedBuild.setDescription(embedText)
      callback(embedBuild.build())

    }
  }

  def addAlly(event: SlashCommandInteractionEvent, subCommand: String, subOptionValue: String, subOptionReason: String, callback: MessageEmbed => Unit): Unit = {
    // same scrucutre as addHunted, use comments there for understanding
    val subOptionValueLower = subOptionValue.toLowerCase()
    val reason = if (subOptionReason == "none") "false" else "true"
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    // default embed content
    var embedText = s"${Config.noEmoji} An error occurred while running the /allies command"
    if (checkConfigDatabase(guild)) {
      val guildId = guild.getId
      // get admin channel info from database
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (subCommand == "guild") {
        // run api against guild
        val guildCheck: Future[Either[String, GuildResponse]] = tibiaDataClient.getGuild(subOptionValueLower)
        guildCheck.map {
          case Right(guildResponse) =>
            val guildName = guildResponse.guild.name
            val guildMembers = guildResponse.guild.members.getOrElse(List.empty[Members])
            (guildName, guildMembers)
          case Left(errorMessage) =>
            ("", List.empty)
        }.map { case (guildName, guildMembers) =>
          if (guildName != "") {
            if (!alliedGuildsData.getOrElse(guildId, List()).exists(g => g.name == subOptionValueLower)) {
              alliedGuildsData = alliedGuildsData + (guildId -> (Guilds(subOptionValueLower, reason, subOptionReason, commandUser) :: alliedGuildsData.getOrElse(guildId, List())))
              addAllyToDatabase(guild, "guild", subOptionValueLower, reason, subOptionReason, commandUser)
              embedText = s":gear: The guild **[$guildName](${guildUrl(guildName)})** has been added to the allies list."

              if (adminChannel != null) {
                if (adminChannel.canTalk() || !(Config.prod)) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> added the guild **[$guildName](${guildUrl(guildName)})** to the allies list.")
                  adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Angel_Statue.gif")
                  adminEmbed.setColor(3092790)
                  adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                }
              }

              // add each player in the guild to the hunted list
              /***
              guildMembers.foreach { member =>
                val guildPlayers = alliedPlayersData.getOrElse(guildId, List())
                if (!guildPlayers.exists(_.name == member.name)) {
                  modifyAlliedPlayersData(m => m + (guildId -> (Players(member.name, "false", "this players guild was added to the hunted list", commandUser) :: guildPlayers)))
                  addAllyToDatabase(guild, "player", member.name, "false", "this players guild was added to the allies list", commandUser)
                }
              }
              ***/

              // add each player in the guild to the activity list
              guildMembers.foreach { member =>
                val guildPlayers = activityData.getOrElse(guildId, List())
                if (!guildPlayers.exists(_.name == member.name)) {
                  val updatedTime = ZonedDateTime.now()
                  modifyActivityData(m => m + (guildId -> (PlayerCache(member.name, List(""), guildName, updatedTime) :: guildPlayers)))
                  addActivityToDatabase(guild, member.name, List(""), guildName, updatedTime)
                }
              }

              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            } else {
              embedText = s"${Config.noEmoji} The guild **[$guildName](${guildUrl(guildName)})** already exists in the allies list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            }
          } else {
            embedText = s"${Config.noEmoji} The guild **$subOptionValueLower** does not exist."
            embedBuild.setDescription(embedText)
            callback(embedBuild.build())

          }
        }
      } else if (subCommand == "player") {
        // run api against player
        val playerCheck: Future[Either[String, CharacterResponse]] = tibiaDataClient.getCharacter(subOptionValueLower)
        playerCheck.map {
          case Right(charResponse) =>
            val character = charResponse.character.character
            (character.name, character.world, vocEmoji(charResponse), character.level.toInt)
          case Left(errorMessage) =>
            ("", "", s"${Config.noEmoji}", 0)
        }.map { case (playerName, world, vocation, level) =>
          if (playerName != "") {
            if (!alliedPlayersData.getOrElse(guildId, List()).exists(g => g.name == subOptionValueLower)) {
              modifyAlliedPlayersData(m => m + (guildId -> (Players(subOptionValueLower, reason, subOptionReason, commandUser) :: m.getOrElse(guildId, List()))))
              addAllyToDatabase(guild, "player", subOptionValueLower, reason, subOptionReason, commandUser)
              embedText = s":gear: The player **[$playerName](${charUrl(playerName)})** has been added to the allies list."

              if (adminChannel != null) {
                if (adminChannel.canTalk() || !(Config.prod)) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> added the player\n$vocation **$level** — **[$playerName](${charUrl(playerName)})**\nto the allies list for **$world**.")
                  adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Angel_Statue.gif")
                  adminEmbed.setColor(3092790)
                  adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                }
              }

              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            } else {
              embedText = s"${Config.noEmoji} The player **[$playerName](${charUrl(playerName)})** already exists in the allies list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            }
          } else {
            embedText = s"${Config.noEmoji} The player **$subOptionValueLower** does not exist."
            embedBuild.setDescription(embedText)
            callback(embedBuild.build())

          }
        }
      }
    } else {
      embedText = s"${Config.noEmoji} You need to run `/setup` and add a world first."
      embedBuild.setDescription(embedText)
      callback(embedBuild.build())

    }
  }

  def removeHunted(event: SlashCommandInteractionEvent, subCommand: String, subOptionValue: String, callback: MessageEmbed => Unit): Unit = {
    // get command option
    val subOptionValueLower = subOptionValue.toLowerCase()
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    var embedText = s"${Config.noEmoji} An error occurred while running the /removehunted command"
    if (checkConfigDatabase(guild)) {
      val guildId = guild.getId
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      // depending on if guild or player supplied
      if (subCommand == "guild") {
        var guildString = subOptionValueLower
        // run api against guild
        val guildCheck: Future[Either[String, GuildResponse]] = tibiaDataClient.getGuild(subOptionValueLower)
        guildCheck.map {
          case Right(guildResponse) =>
            val guildName = guildResponse.guild.name
            guildName
          case Left(errorMessage) =>
            ""
        }.map { guildName =>
          if (guildName != "") {
            guildString = s"[$guildName](${guildUrl(guildName)})"
          }
          val huntedGuildsList = huntedGuildsData.getOrElse(guildId, List())
          huntedGuildsList.find(_.name.toLowerCase == subOptionValueLower) match {
            case Some(_) =>
              val updatedList = huntedGuildsList.filterNot(_.name.toLowerCase == subOptionValueLower)
              // Remove guilds from cache and db
              huntedGuildsData = huntedGuildsData.updated(guildId, updatedList)
              removeHuntedFromDatabase(guild, "guild", subOptionValueLower)

              modifyActivityData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(_.guild.equalsIgnoreCase(subOptionValueLower))))
              removeGuildActivityfromDatabase(guild, subOptionValueLower)

              // Remove players that the bot auto-hunted due to being in that guild from cache and db
              val filteredPlayers: List[Players] = {
                huntedPlayersData.getOrElse(guildId, List()).filter(_.reasonText.toLowerCase == s"was originally in hunted guild ${subOptionValueLower}".toLowerCase)
              }
              val huntedPlayersList = huntedPlayersData.getOrElse(guildId, List())
              val updatedHuntedPlayersList = huntedPlayersList.filterNot(player => filteredPlayers.exists(_.name == player.name))
              modifyHuntedPlayersData(m => m.updated(guildId, updatedHuntedPlayersList))

              modifyActivityData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(player => filteredPlayers.map(_.name.toLowerCase).contains(player.name.toLowerCase))))
              filteredPlayers.foreach { filterPlayer =>
                removeHuntedFromDatabase(guild, "player", filterPlayer.name)
                removePlayerActivityfromDatabase(guild, filterPlayer.name)
              }

              // send embed to admin channel
              if (adminChannel != null) {
                if (adminChannel.canTalk() || !(Config.prod)) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> removed guild **$guildString** from the hunted list.")
                  adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Stone_Coffin.gif")
                  adminEmbed.setColor(3092790)
                  adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                }
              }

              embedText = s":gear: The guild **$guildString** was removed from the hunted list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())
            case None =>
              embedText = s"${Config.noEmoji} The guild **$guildString** is not on the hunted list."

              // Remove players that the bot auto-hunted due to being in that guild from cache and db
              val filteredPlayers: List[Players] = {
                huntedPlayersData.getOrElse(guildId, List()).filter(_.reasonText.toLowerCase == s"was originally in hunted guild ${subOptionValueLower}".toLowerCase)
              }
              if (filteredPlayers.nonEmpty){
                val huntedPlayersList = huntedPlayersData.getOrElse(guildId, List())
                val updatedHuntedPlayersList = huntedPlayersList.filterNot(player => filteredPlayers.exists(_.name == player.name))
                modifyHuntedPlayersData(m => m.updated(guildId, updatedHuntedPlayersList))

                modifyActivityData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(player => filteredPlayers.map(_.name.toLowerCase).contains(player.name.toLowerCase))))
                filteredPlayers.foreach { filterPlayer =>
                  removeHuntedFromDatabase(guild, "player", filterPlayer.name)
                  removePlayerActivityfromDatabase(guild, filterPlayer.name)
                }
                embedText = s":gear: The guild **$guildString** had stale records that have now been removed from the hunted list."
              }

              embedBuild.setDescription(embedText)
              callback(embedBuild.build())
          }
        }
      } else if (subCommand == "player") {
        var playerString = subOptionValueLower
        // run api against player
        val playerCheck: Future[Either[String, CharacterResponse]] = tibiaDataClient.getCharacter(subOptionValueLower)
        playerCheck.map {
          case Right(charResponse) =>
            val character = charResponse.character.character
            (character.name, character.world, vocEmoji(charResponse), character.level.toInt)
          case Left(errorMessage) =>
            ("", "", s"${Config.noEmoji}", 0)
        }.map { case (playerName, world, vocation, level) =>
          if (playerName != "") {
            playerString = s"[$playerName](${charUrl(playerName)})"
          }
          val huntedPlayersList = huntedPlayersData.getOrElse(guildId, List())
          huntedPlayersList.find(_.name.toLowerCase == subOptionValueLower) match {
            case Some(_) =>
              val updatedList = huntedPlayersList.filterNot(_.name.toLowerCase == subOptionValueLower)

              modifyHuntedPlayersData(m => m.updated(guildId, updatedList))
              removeHuntedFromDatabase(guild, "player", subOptionValueLower)

              modifyActivityData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(_.name.equalsIgnoreCase(subOptionValueLower))))
              removePlayerActivityfromDatabase(guild, subOptionValueLower)

              // send embed to admin channel
              if (adminChannel != null) {
                if (adminChannel.canTalk() || !(Config.prod)) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> removed the player\n$vocation **$level** — **$playerString**\nfrom the hunted list for **$world**.")
                  adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Stone_Coffin.gif")
                  adminEmbed.setColor(3092790)
                  adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                }
              }

              embedText = s":gear: The player **$playerString** was removed from the hunted list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())
            case None =>
              embedText = s"${Config.noEmoji} The player **$playerString** is not on the hunted list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())
          }
        }
      }
    } else {
      embedText = s"${Config.noEmoji} You need to run `/setup` and add a world first."
      embedBuild.setDescription(embedText)
      callback(embedBuild.build())
    }
  }

  def removeAlly(event: SlashCommandInteractionEvent, subCommand: String, subOptionValue: String, callback: MessageEmbed => Unit): Unit = {
    // get command option
    val subOptionValueLower = subOptionValue.toLowerCase()
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    var embedText = s"${Config.noEmoji} An error occurred while running the /removehunted command"
    if (checkConfigDatabase(guild)) {
      val guildId = guild.getId
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      // depending on if guild or player supplied
      if (subCommand == "guild") {
        var guildString = subOptionValueLower
        // run api against guild
        val guildCheck: Future[Either[String, GuildResponse]] = tibiaDataClient.getGuild(subOptionValueLower)
        guildCheck.map {
          case Right(guildResponse) =>
            val guildName = guildResponse.guild.name
            guildName
          case Left(errorMessage) =>
            ""
        }.map { guildName =>
          if (guildName != "") {
            guildString = s"[$guildName](${guildUrl(guildName)})"
          }
          val alliedGuildsList = alliedGuildsData.getOrElse(guildId, List())
          alliedGuildsList.find(_.name.toLowerCase == subOptionValueLower) match {
            case Some(_) =>
              val updatedList = alliedGuildsList.filterNot(_.name.toLowerCase == subOptionValueLower)
              alliedGuildsData = alliedGuildsData.updated(guildId, updatedList)
              removeAllyFromDatabase(guild, "guild", subOptionValueLower)

              modifyActivityData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(_.guild.equalsIgnoreCase(subOptionValueLower))))
              removeGuildActivityfromDatabase(guild, subOptionValueLower)

              // send embed to admin channel
              if (adminChannel != null) {
                if (adminChannel.canTalk() || !(Config.prod)) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> removed **$guildString** from the allies list.")
                  adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Angel_Statue.gif")
                  adminEmbed.setColor(3092790)
                  adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                }
              }

              embedText = s":gear: The guild **$guildString** was removed from the allies list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            case None =>
              embedText = s"${Config.noEmoji} The guild **$guildString** is not on the allies list."
              embedBuild.setDescription(embedText)

              callback(embedBuild.build())
          }
        }
      } else if (subCommand == "player") {
        var playerString = subOptionValueLower
        // run api against player
        val playerCheck: Future[Either[String, CharacterResponse]] = tibiaDataClient.getCharacter(subOptionValueLower)
        playerCheck.map {
          case Right(charResponse) =>
            val character = charResponse.character.character
            (character.name, character.world, vocEmoji(charResponse), character.level.toInt)
          case Left(errorMessage) =>
            ("", "", s"${Config.noEmoji}", 0)
        }.map { case (playerName, world, vocation, level) =>
          if (playerName != "") {
            playerString = s"[$playerName](${charUrl(playerName)})"
          }
          val alliedPlayersList = alliedPlayersData.getOrElse(guildId, List())
          alliedPlayersList.find(_.name.toLowerCase == subOptionValueLower) match {
            case Some(_) =>
              val updatedList = alliedPlayersList.filterNot(_.name.toLowerCase == subOptionValueLower)
              modifyAlliedPlayersData(m => m.updated(guildId, updatedList))
              removeAllyFromDatabase(guild, "player", subOptionValueLower)

              modifyActivityData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(_.name.equalsIgnoreCase(subOptionValueLower))))
              removePlayerActivityfromDatabase(guild, subOptionValueLower)

              // send embed to admin channel
              if (adminChannel != null) {
                if (adminChannel.canTalk() || !(Config.prod)) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> removed the player\n$vocation **$level** — **$playerString**\nfrom the allies list for **$world**.")
                  adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Angel_Statue.gif")
                  adminEmbed.setColor(3092790)
                  adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                }
              }

              embedText = s":gear: The player **$playerString** was removed from the allies list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            case None =>
              embedText = s"${Config.noEmoji} The player **$playerString** is not on the allies list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())
          }
        }
      }
    } else {
      embedText = s"${Config.noEmoji} You need to run `/setup` and add a world first."
      embedBuild.setDescription(embedText)
      callback(embedBuild.build())

    }
  }

  def addHuntedToDatabase(guild: Guild, option: String, name: String, reason: String, reasonText: String, addedBy: String): Unit =
    huntedAlliedRepository.addHunted(guild.getId, option, name, reason, reasonText, addedBy)

  def addActivityToDatabase(guild: Guild, name: String, formerNames: List[String], guildName: String, updatedTime: ZonedDateTime): Unit =
    activityRepository.add(guild.getId, name, formerNames, guildName, updatedTime)

  def updateActivityToDatabase(guild: Guild, name: String, formerNames: List[String], guildName: String, updatedTime: ZonedDateTime, newName: String): Unit =
    activityRepository.update(guild.getId, name, formerNames, guildName, updatedTime, newName)

  def updateHuntedOrAllyNameToDatabase(guild: Guild, option: String, oldName: String, newName: String): Unit =
    huntedAlliedRepository.rename(guild.getId, option, oldName, newName)

  private def addAllyToDatabase(guild: Guild, option: String, name: String, reason: String, reasonText: String, addedBy: String): Unit =
    huntedAlliedRepository.addAllied(guild.getId, option, name, reason, reasonText, addedBy)

  def removeHuntedFromDatabase(guild: Guild, option: String, name: String): Unit =
    huntedAlliedRepository.removeHunted(guild.getId, option, name)

  private def removeGuildActivityfromDatabase(guild: Guild, guildName: String): Unit =
    activityRepository.removeByGuild(guild.getId, guildName)

  def removePlayerActivityfromDatabase(guild: Guild, playerName: String): Unit =
    activityRepository.removeByName(guild.getId, playerName)

  def removeAllyFromDatabase(guild: Guild, option: String, name: String): Unit =
    huntedAlliedRepository.removeAllied(guild.getId, option, name)

  private def checkConfigDatabase(guild: Guild): Boolean = schemaInitializer.guildDatabaseExists(guild.getId)

  private def createPremiumDatabase(): Unit = schemaInitializer.initPremium()

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

  private def createConfigDatabase(guild: Guild): Unit = schemaInitializer.initGuild(guild.getId, guild.getName)

  private def getConnection(guild: Guild): Connection =
    connectionProvider.guild(guild.getId)

  private def playerConfig(guild: Guild, query: String): List[Players] =
    huntedAlliedRepository.getPlayers(guild.getId, query)

  private def guildConfig(guild: Guild, query: String): List[Guilds] =
    huntedAlliedRepository.getGuilds(guild.getId, query)

  private def activityConfig(guild: Guild, query: String): List[PlayerCache] =
    activityRepository.getActivity(guild.getId)

  def discordRetrieveConfig(guild: Guild): Map[String, String] =
    discordConfigRepository.getConfig(guild.getId)

  private def worldConfig(guild: Guild): List[Worlds] =
    worldConfigRepository.listWorlds(guild.getId)

  private def worldCreateConfig(guild: Guild, world: String, alliesChannel: String, enemiesChannel: String, neutralsChannels: String, levelsChannel: String, deathsChannel: String, category: String, fullblessRole: String, nemesisRole: String, allyPkRole: String, masslogRole: String, fullblessChannel: String, nemesisChannel: String, activityChannel: String): Unit =
    worldConfigRepository.createWorld(guild.getId, world, alliesChannel, enemiesChannel, neutralsChannels, levelsChannel, deathsChannel, category, fullblessRole, nemesisRole, allyPkRole, masslogRole, fullblessChannel, nemesisChannel, activityChannel)

  private def discordCreateConfig(guild: Guild, guildName: String, guildOwner: String, adminCategory: String, adminChannel: String, boostedChannel: String, boostedMessageId: String, created: ZonedDateTime): Unit =
    discordConfigRepository.create(guild.getId, guildName, guildOwner, adminCategory, adminChannel, boostedChannel, boostedMessageId, created)

  private def discordUpdateConfig(guild: Guild, adminCategory: String, adminChannel: String, boostedChannel: String, boostedMessage: String, lastWorld: String): Unit =
    discordConfigRepository.update(guild.getId, adminCategory, adminChannel, boostedChannel, boostedMessage, lastWorld)

  def worldRetrieveConfig(guild: Guild, world: String): Map[String, String] =
    worldConfigRepository.retrieveWorld(guild.getId, world)

  private def worldRemoveConfig(guild: Guild, query: String): Unit =
    worldConfigRepository.removeWorld(guild.getId, query)

  def createChannels(event: SlashCommandInteractionEvent): MessageEmbed = {
    // get guild & world information from the slash interaction
    val world: String = event.getInteraction.getOptions.asScala.find(_.getName == "world").map(_.getAsString).getOrElse("").trim().toLowerCase().capitalize
    val embedText = if (worlds.contains(world)) {
      // get guild id
      val guild = event.getGuild

      // assume initial run on this server and attempt to create core databases
      createConfigDatabase(guild)

      val botRole = guild.getBotRole
      val fullblessRoleString = s"$world Fullbless"
      val fullblessRoleCheck = guild.getRolesByName(fullblessRoleString, true)
      val fullblessRole = if (!fullblessRoleCheck.isEmpty) fullblessRoleCheck.get(0) else guild.createRole().setName(fullblessRoleString).setColor(new Color(0, 156, 70)).complete()

      val nemesisRoleString = s"$world Rare Boss"
      val nemesisRoleCheck = guild.getRolesByName(nemesisRoleString, true)
      val nemesisRole = if (!nemesisRoleCheck.isEmpty) nemesisRoleCheck.get(0) else guild.createRole().setName(nemesisRoleString).setColor(new Color(164, 76, 230)).complete()

      val allyPkRoleString = s"$world PVP"
      val allyPkCheck = guild.getRolesByName(allyPkRoleString, true)
      val allyPkRole = if (!allyPkCheck.isEmpty) allyPkCheck.get(0) else guild.createRole().setName(allyPkRoleString).setColor(new Color(220, 0, 0)).complete()

      val masslogRoleString = s"$world Masslog"
      val masslogCheck = guild.getRolesByName(masslogRoleString, true)
      val masslogRole = if (!masslogCheck.isEmpty) masslogCheck.get(0) else guild.createRole().setName(masslogRoleString).setColor(new Color(219, 175, 72)).complete()

      val worldCount = worldConfig(guild)
      val count = worldCount.length

      // see if admin channels exist
      val discordConfig = discordRetrieveConfig(guild)
      if (discordConfig.isEmpty) {
        val adminCategory = guild.createCategory("Violent Bot").complete()
        adminCategory.upsertPermissionOverride(botRole)
          .grant(Permission.VIEW_CHANNEL)
          .grant(Permission.MESSAGE_SEND)
          .complete()
        adminCategory.upsertPermissionOverride(guild.getPublicRole).grant(Permission.VIEW_CHANNEL).queue()
        val adminChannel = guild.createTextChannel("🖥️・ᴄᴏᴍᴍᴀɴᴅ ʟᴏɢ", adminCategory).complete()
        // restrict the channel so only roles with Permission.MANAGE_MESSAGES can write to the channels
        adminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
        adminChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
        adminChannel.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
        val guildOwner = if (guild.getOwner == null) "Not Available" else guild.getOwner.getEffectiveName
        discordCreateConfig(guild, guild.getName, guildOwner, adminCategory.getId, adminChannel.getId, "0", "0", ZonedDateTime.now())

        val boostedChannel = guild.createTextChannel("👑・ɴᴏᴛɪғɪᴄᴀᴛɪᴏɴs", adminCategory).complete()
        boostedChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
        boostedChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
        boostedChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_EMBED_LINKS).complete()
        boostedChannel.upsertPermissionOverride(guild.getPublicRole).grant(Permission.VIEW_CHANNEL).queue()
        discordUpdateConfig(guild, "", "", boostedChannel.getId, "", world)

        val galthenEmbed = new EmbedBuilder()
        galthenEmbed.setColor(3092790)
        galthenEmbed.setDescription("This is a **[Galthen's Satchel](https://www.tibiawiki.com.br/wiki/Galthen's_Satchel)** cooldown tracker.\nManage your cooldowns here:")
        galthenEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
        boostedChannel.sendMessageEmbeds(galthenEmbed.build()).addActionRow(
          Button.primary("galthen default", "Cooldowns").withEmoji(Emoji.fromFormatted(Config.satchelEmoji))
        ).queue()

        // Boosted Boss
        val boostedBoss: Future[Either[String, BoostedResponse]] = tibiaDataClient.getBoostedBoss()
        val bossEmbedFuture: Future[MessageEmbed] = boostedBoss.map {
          case Right(boostedResponse) =>
            val boostedBoss = boostedResponse.boostable_bosses.boosted.name
            createBoostedEmbed("Boosted Boss", Config.bossEmoji, "https://www.tibia.com/library/?subtopic=boostablebosses", creatureImageUrl(boostedBoss), s"The boosted boss today is:\n### ${Config.indentEmoji}${Config.archfoeEmoji} **[$boostedBoss](${creatureWikiUrl(boostedBoss)})**")

          case Left(errorMessage) =>
            val boostedBoss = "Podium_of_Vigour"
            createBoostedEmbed("Boosted Boss", Config.bossEmoji, "https://www.tibia.com/library/?subtopic=boostablebosses", creatureImageUrl(boostedBoss), "The boosted boss today failed to load?")
        }

        // Boosted Creature
        val boostedCreature: Future[Either[String, CreatureResponse]] = tibiaDataClient.getBoostedCreature()
        val creatureEmbedFuture: Future[MessageEmbed] = boostedCreature.map {
          case Right(creatureResponse) =>
            val boostedCreature = creatureResponse.creatures.boosted.name
            createBoostedEmbed("Boosted Creature", Config.creatureEmoji, "https://www.tibia.com/library/?subtopic=creatures", creatureImageUrl(boostedCreature), s"The boosted creature today is:\n### ${Config.indentEmoji}${Config.levelUpEmoji} **[$boostedCreature](${creatureWikiUrl(boostedCreature)})**")

          case Left(errorMessage) =>
            val boostedCreature = "Podium_of_Tenacity"
            createBoostedEmbed("Boosted Creature", Config.creatureEmoji, "https://www.tibia.com/library/?subtopic=creatures", creatureImageUrl(boostedCreature), "The boosted creature today failed to load?")
        }

        // Combine both futures and send the message
        val combinedFutures: Future[List[MessageEmbed]] = for {
          bossEmbed <- bossEmbedFuture
          creatureEmbed <- creatureEmbedFuture
        } yield List(bossEmbed, creatureEmbed)

        combinedFutures.map { embeds =>

          val dreamScarDaily =
            dreamScar.getOrElse(world, "World not found")

          val rashidLocation = ServerSaveSchedule.rashidLocation(ZonedDateTime.now(ZoneId.of("Europe/Berlin")).minusHours(10).getDayOfWeek)

          val rashidEmbed = new EmbedBuilder()
            .setDescription(
              s"Today Rashid can be found in:\n### ${Config.indentEmoji}${Config.goldEmoji} **[${rashidLocation}](https://tibia.fandom.com/wiki/Rashid)**"
            )
            .setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Rashid.gif")
            .setColor(3092790)
            .build()

          val dreamScarEmbed = new EmbedBuilder()
            .setDescription(
              s"The Dream Courts boss for **$world** is:\n### ${Config.indentEmoji}${Config.dreamScarEmoji} **[${dreamScarDaily}](https://tibia.fandom.com/wiki/Dream_Scar/Boss_of_the_Day)**"
            )
            .setThumbnail(creatureImageUrl(dreamScarDaily))
            .setColor(3092790)
            .build()

          // Drome Timer
          val now = Instant.now()
          val dromeShow = ServerSaveSchedule.shouldShowDrome(now, dromeTime)
          val dromeEmbed = new EmbedBuilder()
            .setDescription(s"The current Drome cycle will end:\n### ${Config.indentEmoji}${Config.dromeEmoji} ${TimeFormat.RELATIVE.format(dromeTime)}")
            .setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Phant.gif")
            .setColor(3092790)
            .build()

          val embedsList = if (dromeShow) List(rashidEmbed, dreamScarEmbed, dromeEmbed) else List(rashidEmbed, dreamScarEmbed)

          val addRashidDreamScarEmbeds: List[MessageEmbed] =
            embeds ++ embedsList

          boostedChannel
            .sendMessageEmbeds(addRashidDreamScarEmbeds.asJava)
            .setActionRow(
              Button.primary(
                "boosted list",
                "Server Save Notifications"
              ).withEmoji(Emoji.fromFormatted(Config.letterEmoji))
            )
            .queue(
              (message: Message) => {
                discordUpdateConfig(
                  guild,
                  "",
                  "",
                  "",
                  message.getId,
                  world
                )
              },
              (e: Throwable) => {
                logger.warn(
                  s"Failed to send boosted boss/creature message for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}':",
                  e
                )
              }
            )
        }
      } else {
        var adminCategoryCheck = guild.getCategoryById(discordConfig("admin_category"))
        val adminChannelCheck = guild.getTextChannelById(discordConfig("admin_channel"))
        val boostedChannelCheck = guild.getTextChannelById(discordConfig("boosted_channel"))
        if (adminCategoryCheck == null) {
          // admin category has been deleted
          val adminCategory = guild.createCategory("Violent Bot").complete()
          adminCategory.upsertPermissionOverride(botRole)
            .grant(Permission.VIEW_CHANNEL)
            .grant(Permission.MESSAGE_SEND)
            .complete()
          adminCategory.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
          discordUpdateConfig(guild, adminCategory.getId, "", "", "", world)
          adminCategoryCheck = adminCategory
        }
        if (adminChannelCheck == null) {
          // admin channel has been deleted
          val adminChannel = guild.createTextChannel("🖥️・ᴄᴏᴍᴍᴀɴᴅ ʟᴏɢ", adminCategoryCheck).complete()
          adminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
          adminChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
          adminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_EMBED_LINKS).complete()
          adminChannel.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
          discordUpdateConfig(guild, "", adminChannel.getId, "", "", world)
        }
        if (boostedChannelCheck == null) {
          // admin category still exists
          val boostedChannel = guild.createTextChannel("👑・ɴᴏᴛɪғɪᴄᴀᴛɪᴏɴs", adminCategoryCheck).complete()
          boostedChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
          boostedChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
          boostedChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_EMBED_LINKS).complete()
          boostedChannel.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
          discordUpdateConfig(guild, "", "", boostedChannel.getId, "", world)

          val galthenEmbed = new EmbedBuilder()
          galthenEmbed.setColor(3092790)
          galthenEmbed.setDescription("This is a **[Galthen's Satchel](https://www.tibiawiki.com.br/wiki/Galthen's_Satchel)** cooldown tracker.\nManage your cooldowns here:")
          galthenEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
          boostedChannel.sendMessageEmbeds(galthenEmbed.build()).addActionRow(
            Button.primary("galthen default", "Cooldowns").withEmoji(Emoji.fromFormatted(Config.satchelEmoji))
          ).queue()

          // Boosted Boss
          val boostedBoss: Future[Either[String, BoostedResponse]] = tibiaDataClient.getBoostedBoss()
          val bossEmbedFuture: Future[MessageEmbed] = boostedBoss.map {
            case Right(boostedResponse) =>
              val boostedBoss = boostedResponse.boostable_bosses.boosted.name
              createBoostedEmbed("Boosted Boss", Config.bossEmoji, "https://www.tibia.com/library/?subtopic=boostablebosses", creatureImageUrl(boostedBoss), s"The boosted boss today is:\n### ${Config.indentEmoji}${Config.archfoeEmoji} **[$boostedBoss](${creatureWikiUrl(boostedBoss)})**")

            case Left(errorMessage) =>
              val boostedBoss = "Podium_of_Vigour"
              createBoostedEmbed("Boosted Boss", Config.bossEmoji, "https://www.tibia.com/library/?subtopic=boostablebosses", creatureImageUrl(boostedBoss), "The boosted boss today failed to load?")
          }

          // Boosted Creature
          val boostedCreature: Future[Either[String, CreatureResponse]] = tibiaDataClient.getBoostedCreature()
          val creatureEmbedFuture: Future[MessageEmbed] = boostedCreature.map {
            case Right(creatureResponse) =>
              val boostedCreature = creatureResponse.creatures.boosted.name
              createBoostedEmbed("Boosted Creature", Config.creatureEmoji, "https://www.tibia.com/library/?subtopic=creatures", creatureImageUrl(boostedCreature), s"The boosted creature today is:\n### ${Config.indentEmoji}${Config.levelUpEmoji} **[$boostedCreature](${creatureWikiUrl(boostedCreature)})**")

            case Left(errorMessage) =>
              val boostedCreature = "Podium_of_Tenacity"
              createBoostedEmbed("Boosted Creature", Config.creatureEmoji, "https://www.tibia.com/library/?subtopic=creatures", creatureImageUrl(boostedCreature), "The boosted creature today failed to load?")
          }

          // Combine both futures and send the message
          val combinedFutures: Future[List[MessageEmbed]] = for {
            bossEmbed <- bossEmbedFuture
            creatureEmbed <- creatureEmbedFuture
          } yield List(bossEmbed, creatureEmbed)

          combinedFutures.map { embeds =>
              val dreamScarDaily =
                dreamScar.getOrElse(world, "World not found")

              val rashidLocation = ServerSaveSchedule.rashidLocation(ZonedDateTime.now(ZoneId.of("Europe/Berlin")).minusHours(10).getDayOfWeek)

              val rashidEmbed = new EmbedBuilder()
                .setDescription(
                  s"Today Rashid can be found in:\n### ${Config.indentEmoji}${Config.goldEmoji} **[${rashidLocation}](https://tibia.fandom.com/wiki/Rashid)**"
                )
                .setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Rashid.gif")
                .setColor(3092790)
                .build()

              val dreamScarEmbed = new EmbedBuilder()
                .setDescription(
                  s"The Dream Courts boss for **$world** is:\n### ${Config.indentEmoji}${Config.dreamScarEmoji} **[${dreamScarDaily}](https://tibia.fandom.com/wiki/Dream_Scar/Boss_of_the_Day)**"
                )
                .setThumbnail(creatureImageUrl(dreamScarDaily))
                .setColor(3092790)
                .build()

              // Drome Timer
              val now = Instant.now()
              val dromeShow = ServerSaveSchedule.shouldShowDrome(now, dromeTime)
              val dromeEmbed = new EmbedBuilder()
                .setDescription(s"The current Drome cycle will end:\n### ${Config.indentEmoji}${Config.dromeEmoji} ${TimeFormat.RELATIVE.format(dromeTime)}")
                .setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Phant.gif")
                .setColor(3092790)
                .build()

              val embedsList = if (dromeShow) List(rashidEmbed, dreamScarEmbed, dromeEmbed) else List(rashidEmbed, dreamScarEmbed)
              val addRashidDreamScarEmbeds: List[MessageEmbed] =
                embeds ++ embedsList

              boostedChannel
                .sendMessageEmbeds(addRashidDreamScarEmbeds.asJava)
                .setActionRow(
                  Button.primary(
                    "boosted list",
                    "Server Save Notifications"
                  ).withEmoji(Emoji.fromFormatted(Config.letterEmoji))
                )
                .queue((message: Message) => {
                //updateBoostedMessage(guild.getId, message.getId)
                discordUpdateConfig(guild, "", "", "", message.getId, world)
              }, (e: Throwable) => {
                logger.warn(s"Failed to send boosted boss/creature message for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}':", e)
              })
            }
        }
      }
      // check is world has already been setup
      val worldConfigData = worldRetrieveConfig(guild, world)
      // it it doesn't create it
      if (worldConfigData.isEmpty) {
        // create the category
        val newCategory = guild.createCategory(world).complete()
        newCategory.upsertPermissionOverride(botRole)
          .grant(Permission.VIEW_CHANNEL)
          .grant(Permission.MESSAGE_SEND)
          .grant(Permission.MESSAGE_MENTION_EVERYONE)
          .grant(Permission.MESSAGE_EMBED_LINKS)
          .grant(Permission.MESSAGE_HISTORY)
          .grant(Permission.MANAGE_CHANNEL)
          .complete()
        newCategory.upsertPermissionOverride(guild.getPublicRole).deny(Permission.MESSAGE_SEND).complete()
        // create the channels
        val alliesChannel = guild.createTextChannel("📈・ᴏɴʟɪɴᴇ", newCategory).complete()
        //val enemiesChannel = guild.createTextChannel("enemies", newCategory).complete()
        //val neutralsChannel = guild.createTextChannel("neutrals", newCategory).complete()

        val deathsChannel = guild.createTextChannel("💀・ᴅᴇᴀᴛʜs", newCategory).complete()
        val levelsChannel = guild.createTextChannel("💖・ʟᴇᴠᴇʟs", newCategory).complete()
        val activityChannel = guild.createTextChannel("📝・ᴀᴄᴛɪᴠɪᴛʏ", newCategory).complete()

        val publicRole = guild.getPublicRole
        val channelList = List(alliesChannel, levelsChannel, deathsChannel, activityChannel)
        channelList.asInstanceOf[Iterable[TextChannel]].foreach { channel =>
          channel.upsertPermissionOverride(botRole)
            .grant(Permission.VIEW_CHANNEL)
            .grant(Permission.MESSAGE_SEND)
            .grant(Permission.MESSAGE_MENTION_EVERYONE)
            .grant(Permission.MESSAGE_EMBED_LINKS)
            .grant(Permission.MESSAGE_HISTORY)
            .grant(Permission.MANAGE_CHANNEL)
            .complete()
          channel.upsertPermissionOverride(publicRole)
            .deny(Permission.MESSAGE_SEND)
            .complete()
        }

        val notificationsConfig = discordRetrieveConfig(guild)
        val notificationsChannel = guild.getTextChannelById(notificationsConfig("boosted_channel"))

        if (notificationsChannel != null) {
          if (notificationsChannel.canTalk()) {

            // Fullbless Role
            val fullblessEmbed = new EmbedBuilder()
            val fullblessEmbedText = s"The bot will poke:\n${Config.inqEmoji}<@&${fullblessRole.getId}> If an enemy fullblesses and is over level `250`\n${Config.bossEmoji}<@&${nemesisRole.getId}> If anyone dies to a rare boss\n${Config.hazardEmoji}<@&${allyPkRole.getId}> If an ally gets pked\n${Config.masslogEmoji}<@&${masslogRole.getId}> If enemies masslog on **$world**"
            fullblessEmbed.setTitle(s":crossed_swords: $world :crossed_swords:", s"https://www.tibia.com/community/?subtopic=worlds&world=$world")
            fullblessEmbed.setThumbnail(s"https://raw.githubusercontent.com/Leo32onGIT/tibia-bot-resources/main/Phantasmal_Ooze.gif")
            fullblessEmbed.setColor(3092790)
            fullblessEmbed.setFooter("Add or remove yourself from the role using the buttons below:")
            fullblessEmbed.setDescription(fullblessEmbedText)
            notificationsChannel.sendMessageEmbeds(fullblessEmbed.build())
              .setActionRow(
                Button.success("fullbless", " ").withEmoji(Emoji.fromFormatted(s"${Config.inqEmoji}")),
                Button.primary("nemesis", " ").withEmoji(Emoji.fromFormatted(s"${Config.bossEmoji}")),
                Button.danger("allypk", " ").withEmoji(Emoji.fromFormatted(s"${Config.hazardEmoji}")),
                Button.secondary("masslog", " ").withEmoji(Emoji.fromFormatted(s"${Config.masslogEmoji}"))
              )
              .queue()
            }
        }

        val alliesId = alliesChannel.getId
        val enemiesId = "0" //enemiesChannel.getId
        val neutralsId = "0" //neutralsChannel.getId
        val levelsId = levelsChannel.getId
        val deathsId = deathsChannel.getId
        val categoryId = newCategory.getId
        val activityId = activityChannel.getId

        // post initial embed in levels channel
        val levelsTextChannel: TextChannel = guild.getTextChannelById(levelsId)
        if (levelsTextChannel != null) {
          val levelsEmbed = new EmbedBuilder()
          levelsEmbed.setDescription(s":speech_balloon: This channel shows levels that have been gained on this world.\n\nYou can filter what appears in this channel using the **`/levels filter`** command.")
          levelsEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Sign_(Library).gif")
          levelsEmbed.setColor(3092790)
          levelsTextChannel.sendMessageEmbeds(levelsEmbed.build()).queue()
        }

        // post initial embed in deaths channel
        val deathsTextChannel: TextChannel = guild.getTextChannelById(deathsId)
        if (deathsTextChannel != null) {
          val deathsEmbed = new EmbedBuilder()
          deathsEmbed.setDescription(s":speech_balloon: This channel shows deaths that occur on this world.\n\nYou can filter what appears in this channel using the **`/deaths filter`** command.")
          deathsEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Sign_(Library).gif")
          deathsEmbed.setColor(3092790)
          deathsTextChannel.sendMessageEmbeds(deathsEmbed.build()).queue()
        }

        // post initial embed in activity channel
        val activityTextChannel: TextChannel = guild.getTextChannelById(activityId)
        if (activityTextChannel != null) {
          val activityEmbed = new EmbedBuilder()
          activityEmbed.setDescription(s":speech_balloon: This channel shows change activity for *allied* or *enemy* players.\n\nIt will show events when a players **joins** or **leaves** one of these tracked guilds or **changes their name**.")
          activityEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Sign_(Library).gif")
          activityEmbed.setColor(3092790)
          activityTextChannel.sendMessageEmbeds(activityEmbed.build()).queue()
        }

        // update the database
        worldCreateConfig(guild, world, alliesId, enemiesId, neutralsId, levelsId, deathsId, categoryId, fullblessRole.getId, nemesisRole.getId, allyPkRole.getId, masslogRole.getId, "0", "0", activityId)
        startBot(Some(guild), Some(world))
        s":gear: The channels for **$world** have been configured successfully.\n⚠️ *You should probably mute the <#$levelsId> channel*"
      } else {
        // channels already exist
        logger.info(s"The channels have already been setup on '${guild.getName} - ${guild.getId}'.")
        s"${Config.noEmoji} The channels for **$world** have already been setup.\nUse `/repair` if you need to recreate channels for **$world** that you have deleted."
      }
    } else {
      s"${Config.noEmoji} This is not a valid World on Tibia."
    }
    // embed reply
    new EmbedBuilder()
      .setColor(3092790)
      .setDescription(embedText)
      .build()
  }

  def detectHunted(event: SlashCommandInteractionEvent): MessageEmbed = {
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val worldOption: String = options.getOrElse("world", "")
    val settingOption: String = options.getOrElse("option", "")
    val worldFormal = worldOption.toLowerCase().capitalize.trim
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    val cache = worldsData.getOrElse(guild.getId, List()).filter(w => w.name.toLowerCase() == worldOption.toLowerCase())
    val detectSetting = cache.headOption.map(_.detectHunteds).getOrElse(null)
    if (detectSetting != null) {
      if (detectSetting == settingOption) {
        // embed reply
        embedBuild.setDescription(s"${Config.noEmoji} **Automatic enemy detection** is already set to **$settingOption** for the world **$worldFormal**.")
        embedBuild.build()
      } else {
        // set the setting here
        val modifiedWorlds = worldsData(guild.getId).map { w =>
          if (w.name.toLowerCase() == worldOption.toLowerCase()) {
            w.copy(detectHunteds = settingOption)
          } else {
            w
          }
        }
        worldsData = worldsData + (guild.getId -> modifiedWorlds)
        detectHuntedsToDatabase(guild, worldFormal, settingOption)

        val discordConfig = discordRetrieveConfig(guild)
        val adminChannelId = if (discordConfig.nonEmpty) discordConfig("admin_channel") else ""
        val adminChannel: TextChannel = guild.getTextChannelById(adminChannelId)
        if (adminChannel != null) {
          if (adminChannel.canTalk() || !(Config.prod)) {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> set **automatic enemy detection** to **$settingOption** for the world **$worldFormal**.")
            adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Armillary_Sphere_(TibiaMaps).gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          }
        }

        embedBuild.setDescription(s":gear: **Automatic enemy detection** is now set to **$settingOption** for the world **$worldFormal**.")
        embedBuild.build()
      }
    } else {
      embedBuild.setDescription(s"${Config.noEmoji} You need to run `/setup` and add **$worldFormal** before you can configure this setting.")
      embedBuild.build()
    }
  }

  private def detectHuntedsToDatabase(guild: Guild, world: String, detectSetting: String): Unit =
    worldConfigRepository.updateWorldString(guild.getId, world.toLowerCase().capitalize, "detect_hunteds", detectSetting)

  def deathsLevelsHideShow(event: SlashCommandInteractionEvent, world: String, setting: String, playerType: String, channelType: String): MessageEmbed = {
    val worldFormal = world.toLowerCase().capitalize
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    val settingType = if (setting == "show") "true" else "false"
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    val thumbnailIcon = playerType match {
      case "allies"   => "Angel_Statue"
      case "neutrals" => "Guardian_Statue"
      case "enemies"  => "Stone_Coffin"
      case _          => ""
    }
    val cache = worldsData.getOrElse(guild.getId, List()).filter(w => w.name.toLowerCase() == world.toLowerCase())
    val selectedSetting: Option[String] = playerType match {
      case "allies" =>
        if (channelType == "deaths") {
          cache.headOption.map(_.showAlliesDeaths)
        } else if (channelType == "levels") {
          cache.headOption.map(_.showAlliesLevels)
        } else {
          None
        }
      case "neutrals" =>
        if (channelType == "deaths") {
          cache.headOption.map(_.showNeutralDeaths)
        } else if (channelType == "levels") {
          cache.headOption.map(_.showNeutralLevels)
        } else {
          None
        }
      case "enemies" =>
        if (channelType == "deaths") {
          cache.headOption.map(_.showEnemiesDeaths)
        } else if (channelType == "levels") {
          cache.headOption.map(_.showEnemiesLevels)
        } else {
          None
        }
      case _ => None
    }
    if (selectedSetting.isDefined) {
      if (selectedSetting.get == settingType) {
        // embed reply
        embedBuild.setDescription(s"${Config.noEmoji} The **$channelType** channel is already set to **$setting $playerType** for the world **$worldFormal**.")
        embedBuild.build()
      } else {
        // set the setting here
        val modifiedWorlds = worldsData(guild.getId).map { w =>
          if (w.name.toLowerCase() == world.toLowerCase()) {
            playerType match {
              case "allies" =>
                if (channelType == "deaths") w.copy(showAlliesDeaths = settingType)
                else if (channelType == "levels") w.copy(showAlliesLevels = settingType)
                else w
              case "neutrals" =>
                if (channelType == "deaths") w.copy(showNeutralDeaths = settingType)
                else if (channelType == "levels") w.copy(showNeutralLevels = settingType)
                else w
              case "enemies" =>
                if (channelType == "deaths") w.copy(showEnemiesDeaths = settingType)
                else if (channelType == "levels") w.copy(showEnemiesLevels = settingType)
                else w
              case _ => w
            }
          } else {
            w
          }
        }
        worldsData = worldsData + (guild.getId -> modifiedWorlds)
        deathsLevelsHideShowToDatabase(guild, world, settingType, playerType, channelType)

        val discordConfig = discordRetrieveConfig(guild)
        val adminChannelId = if (discordConfig.nonEmpty) discordConfig("admin_channel") else ""
        val adminChannel: TextChannel = guild.getTextChannelById(adminChannelId)
        if (adminChannel != null) {
          if (adminChannel.canTalk() || !(Config.prod)) {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> set the **$channelType** channel to **$setting $playerType** for the world **$worldFormal**.")
            adminEmbed.setThumbnail(s"https://www.tibiawiki.com.br/wiki/Special:Redirect/file/$thumbnailIcon.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          }
        }

        embedBuild.setDescription(s":gear: The **$channelType** channel is now set to **$setting $playerType** for the world **$worldFormal**.")
        embedBuild.build()
      }
    } else {
      embedBuild.setDescription(s"${Config.noEmoji} You need to run `/setup` and add **$worldFormal** before you can configure this setting.")
      embedBuild.build()
    }
  }

  def exivaList(event: SlashCommandInteractionEvent): MessageEmbed = {
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val worldOption: String = options.getOrElse("world", "")
    val settingOption: String = options.getOrElse("option", "")
    val settingType = if (settingOption == "show") "true" else "false"
    val worldFormal = worldOption.toLowerCase().capitalize.trim
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    val cache = worldsData.getOrElse(guild.getId, List()).filter(w => w.name.toLowerCase() == worldOption.toLowerCase())
    val detectSetting = cache.headOption.map(_.exivaList).getOrElse(null)
    if (detectSetting != null) {
      if (detectSetting == settingType) {
        // embed reply
        embedBuild.setDescription(s"${Config.noEmoji} The **exiva list on deaths** is already set to **$settingOption** for the world **$worldFormal**.")
        embedBuild.build()
      } else {
        // set the setting here
        val modifiedWorlds = worldsData(guild.getId).map { w =>
          if (w.name.toLowerCase() == worldOption.toLowerCase()) {
            w.copy(exivaList = settingType)
          } else {
            w
          }
        }
        worldsData = worldsData + (guild.getId -> modifiedWorlds)
        exivaListToDatabase(guild, worldFormal, settingType)

        val discordConfig = discordRetrieveConfig(guild)
        val adminChannelId = if (discordConfig.nonEmpty) discordConfig("admin_channel") else ""
        val adminChannel: TextChannel = guild.getTextChannelById(adminChannelId)
        if (adminChannel != null) {
          if (adminChannel.canTalk() || !(Config.prod)) {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> set **exiva list on deaths** to **$settingOption** for the world **$worldFormal**.")
            adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Find_Person.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          }
        }

        embedBuild.setDescription(s":gear: **exiva list on deaths** is now set to **$settingOption** for the world **$worldFormal**.")
        embedBuild.build()
      }
    } else {
      embedBuild.setDescription(s"${Config.noEmoji} You need to run `/setup` and add **$worldFormal** before you can configure this setting.")
      embedBuild.build()
    }
  }

  private def exivaListToDatabase(guild: Guild, world: String, detectSetting: String): Unit =
    worldConfigRepository.updateWorldString(guild.getId, world.toLowerCase().capitalize, "exiva_list", detectSetting)

  def onlineListConfig(event: SlashCommandInteractionEvent, world: String, setting: String): MessageEmbed = {
    val worldFormal = world.toLowerCase().capitalize
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    val settingType = if (setting == "combine") "true" else "false"
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    val thumbnailIcon = "Blackboard"
    val cache = worldsData.getOrElse(guild.getId, List()).filter(w => w.name.toLowerCase() == world.toLowerCase())
    val existingSetting = cache.headOption.map(_.onlineCombined)
    if (existingSetting.isDefined) {
      if (existingSetting.get == settingType) {
        // embed reply
        embedBuild.setDescription(s"${Config.noEmoji} The online list is already set to **$setting** for the world **$worldFormal**.")
        embedBuild.build()
      } else {

        var disclaimer = ""

        val cache: Option[List[Worlds]] = worldsData.get(guild.getId) match {
          case Some(worlds) =>
            val filteredWorlds = worlds.filter(w => w.name.toLowerCase() == world.toLowerCase())
            if (filteredWorlds.nonEmpty) Some(filteredWorlds)
            else None
          case None => None
        }

        val categoryInfo: Option[String] = cache.flatMap(_.headOption.map(_.category))
        val alliesChannelInfo: Option[String] = cache.flatMap(_.headOption.map(_.alliesChannel))
        val enemiesChannelInfo: Option[String] = cache.flatMap(_.headOption.map(_.enemiesChannel))
        val neutralsChannelInfo: Option[String] = cache.flatMap(_.headOption.map(_.neutralsChannel))

        var category = guild.getCategoryById(categoryInfo.getOrElse("0"))
        val alliesChannel = guild.getTextChannelById(alliesChannelInfo.getOrElse("0"))
        val enemiesChannel = guild.getTextChannelById(enemiesChannelInfo.getOrElse("0"))
        val neutralsChannel = guild.getTextChannelById(neutralsChannelInfo.getOrElse("0"))

        val botRole = guild.getBotRole
        val publicRole = guild.getPublicRole

        if (setting == "combine") {

          if (event.getChannel.getId == alliesChannelInfo.getOrElse("0") || event.getChannel.getId == enemiesChannelInfo.getOrElse("0") || event.getChannel.getId == neutralsChannelInfo.getOrElse("0")) {
            embedBuild.setDescription(s"${Config.noEmoji} That command would delete this channel, run it somewhere else.")
            return embedBuild.build()
          }

          if (alliesChannel != null) {
            try {
              alliesChannel.delete().queue()
              disclaimer += s"\n- *The now unused `allies` channel has been deleted.*"
            } catch {
              case ex: Throwable => logger.info(s"Failed to delete Channel ID: '${alliesChannelInfo}' for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}' while combining the online list", ex)
            }
          }

          if (enemiesChannel != null) {
            try {
              enemiesChannel.delete().queue()
              disclaimer += s"\n- *The now unused `enemies` channel has been deleted.*"
            } catch {
              case ex: Throwable => logger.info(s"Failed to delete Channel ID: '${enemiesChannelInfo}' for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}' while combining the online list", ex)
            }
          }

          if (neutralsChannel != null) {
            try {
              neutralsChannel.delete().queue()
              disclaimer += s"\n- *The now unused `neutrals` channel has been deleted.*"
            } catch {
              case ex: Throwable => logger.info(s"Failed to delete Channel ID: '${neutralsChannelInfo}' for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}' while combining the online list", ex)
            }
          }

          // Now that separate channels are deleted, create a new 'online' channel
          try {
            if (category == null) {
              // create the category
              val newCategory = guild.createCategory(worldFormal).complete()
              newCategory.upsertPermissionOverride(botRole)
                .grant(Permission.VIEW_CHANNEL)
                .grant(Permission.MESSAGE_SEND)
                .grant(Permission.MESSAGE_MENTION_EVERYONE)
                .grant(Permission.MESSAGE_EMBED_LINKS)
                .grant(Permission.MESSAGE_HISTORY)
                .grant(Permission.MANAGE_CHANNEL)
                .complete()
              newCategory.upsertPermissionOverride(publicRole).deny(Permission.MESSAGE_SEND).complete()
              category = newCategory
              worldRepairConfig(guild, worldFormal, "category", newCategory.getId)

              // update the record in worldsData
              if (worldsData.contains(guild.getId)) {
                val worldsList = worldsData(guild.getId)
                val updatedWorldsList = worldsList.map { world =>
                  if (world.name.toLowerCase == worldFormal.toLowerCase) {
                    world.copy(category = newCategory.getId)
                  } else {
                    world
                  }
                }
                worldsData += (guild.getId -> updatedWorldsList)
              }
            }
            // create the online channel
            val recreateAlliesChannel = guild.createTextChannel("📈・ᴏɴʟɪɴᴇ", category).complete()
            worldRepairConfig(guild, worldFormal, "allies_channel", recreateAlliesChannel.getId)
            // update the record in worldsData
            if (worldsData.contains(guild.getId)) {
              val worldsList = worldsData(guild.getId)
              val updatedWorldsList = worldsList.map { world =>
                if (world.name.toLowerCase == worldFormal.toLowerCase) {
                  world.copy(alliesChannel = recreateAlliesChannel.getId)
                } else {
                  world
                }
              }
              worldsData += (guild.getId -> updatedWorldsList)
            }
            // apply permissions to created channel
            recreateAlliesChannel.upsertPermissionOverride(botRole)
              .grant(Permission.VIEW_CHANNEL)
              .grant(Permission.MESSAGE_SEND)
              .grant(Permission.MESSAGE_MENTION_EVERYONE)
              .grant(Permission.MESSAGE_EMBED_LINKS)
              .grant(Permission.MESSAGE_HISTORY)
              .grant(Permission.MANAGE_CHANNEL)
              .complete()
            recreateAlliesChannel.upsertPermissionOverride(publicRole)
              .deny(Permission.MESSAGE_SEND)
              .complete()
            disclaimer += s"\n- *You may want to move the new <#${recreateAlliesChannel.getId}> channel.*"
          } catch {
            case ex: Throwable => logger.info(s"Failed to create category or online channels for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}' while combining the online list", ex)
          }

        } else {
          // setting == "separate"

          if (event.getChannel.getId == alliesChannelInfo.getOrElse("0")) {
            embedBuild.setDescription(s"${Config.noEmoji} That command would delete this channel, run it somewhere else.")
            return embedBuild.build()
          }

          // get the bots main roles
          try {
            if (category == null) {
              // create the category
              val newCategory = guild.createCategory(worldFormal).complete()
              newCategory.upsertPermissionOverride(botRole)
                .grant(Permission.VIEW_CHANNEL)
                .grant(Permission.MESSAGE_SEND)
                .grant(Permission.MESSAGE_MENTION_EVERYONE)
                .grant(Permission.MESSAGE_EMBED_LINKS)
                .grant(Permission.MESSAGE_HISTORY)
                .grant(Permission.MANAGE_CHANNEL)
                .complete()
              newCategory.upsertPermissionOverride(publicRole).deny(Permission.MESSAGE_SEND).complete()
              category = newCategory
              worldRepairConfig(guild, worldFormal, "category", newCategory.getId)

              // update the record in worldsData
              if (worldsData.contains(guild.getId)) {
                val worldsList = worldsData(guild.getId)
                val updatedWorldsList = worldsList.map { world =>
                  if (world.name.toLowerCase == worldFormal.toLowerCase) {
                    world.copy(category = newCategory.getId)
                  } else {
                    world
                  }
                }
                worldsData += (guild.getId -> updatedWorldsList)
              }
            } else {
              try {
                val categoryName = category.getName
                if (categoryName != s"${worldFormal}") {
                  val channelManager = category.getManager
                  channelManager.setName(s"${worldFormal}").queue()
                }
              } catch {
                case ex: Throwable => logger.info(s"Failed to rename category for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}' while separating the online list", ex)
              }
            }
            val channelList = ListBuffer[(TextChannel, Boolean)]()

            // delete the combined 'online' channel
            if (alliesChannel != null) {
              try {
                alliesChannel.delete().queue()
                disclaimer += s"\n- *The now unused `online` channel has been deleted.*"
              } catch {
                case ex: Throwable => logger.info(s"Failed to delete Channel ID: '${alliesChannelInfo}' for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}' while separating the online list", ex)
              }
            }

            // create the channels underneath the new/existing category
            val recreateAlliesChannel = guild.createTextChannel("🤍・ᴀʟʟɪᴇs", category).complete()
            channelList += ((recreateAlliesChannel, false))
            worldRepairConfig(guild, worldFormal, "allies_channel", recreateAlliesChannel.getId)
            // update the record in worldsData
            if (worldsData.contains(guild.getId)) {
              val worldsList = worldsData(guild.getId)
              val updatedWorldsList = worldsList.map { world =>
                if (world.name.toLowerCase == worldFormal.toLowerCase) {
                  world.copy(alliesChannel = recreateAlliesChannel.getId)
                } else {
                  world
                }
              }
              worldsData += (guild.getId -> updatedWorldsList)
            }
            disclaimer += s"\n- *The channel <#${recreateAlliesChannel.getId}> has been recreated (you may want to move it).*"

            if (enemiesChannel == null) {
              val recreateEnemiesChannel = guild.createTextChannel("☠️・ᴇɴᴇᴍɪᴇs", category).complete()
              channelList += ((recreateEnemiesChannel, false))
              worldRepairConfig(guild, worldFormal, "enemies_channel", recreateEnemiesChannel.getId)
              // update the record in worldsData
              if (worldsData.contains(guild.getId)) {
                val worldsList = worldsData(guild.getId)
                val updatedWorldsList = worldsList.map { world =>
                  if (world.name.toLowerCase == worldFormal.toLowerCase) {
                    world.copy(enemiesChannel = recreateEnemiesChannel.getId)
                  } else {
                    world
                  }
                }
                worldsData += (guild.getId -> updatedWorldsList)
              }
              disclaimer += s"\n- *The channel <#${recreateEnemiesChannel.getId}> has been recreated (you may want to move it).*"
            }

            if (neutralsChannel == null) {
              val recreateNeutralsChannel = guild.createTextChannel("📈・ɴᴇᴜᴛʀᴀʟs", category).complete()
              channelList += ((recreateNeutralsChannel, false))
              worldRepairConfig(guild, worldFormal, "neutrals_channel", recreateNeutralsChannel.getId)
              // update the record in worldsData
              if (worldsData.contains(guild.getId)) {
                val worldsList = worldsData(guild.getId)
                val updatedWorldsList = worldsList.map { world =>
                  if (world.name.toLowerCase == worldFormal.toLowerCase) {
                    world.copy(neutralsChannel = recreateNeutralsChannel.getId)
                  } else {
                    world
                  }
                }
                worldsData += (guild.getId -> updatedWorldsList)
              }
              disclaimer += s"\n- *The channel <#${recreateNeutralsChannel.getId}> has been recreated (you may want to move it).*"
            }
            // apply required permissions to the new channel(s)
            if (channelList.nonEmpty) {
              channelList.foreach { case (channel, webhooks) =>
                channel.upsertPermissionOverride(botRole)
                  .grant(Permission.VIEW_CHANNEL)
                  .grant(Permission.MESSAGE_SEND)
                  .grant(Permission.MESSAGE_MENTION_EVERYONE)
                  .grant(Permission.MESSAGE_EMBED_LINKS)
                  .grant(Permission.MESSAGE_HISTORY)
                  .grant(Permission.MANAGE_CHANNEL)
                  .complete()
                channel.upsertPermissionOverride(publicRole)
                  .deny(Permission.MESSAGE_SEND)
                  .complete()
              }
            }
          } catch {
            case ex: Throwable => logger.info(s"Failed to create category, allies, enemies or neutrals channels for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}' while separating the online list", ex)
          }
        }

        // set the setting here
        val modifiedWorlds = worldsData(guild.getId).map { w =>
          if (w.name.toLowerCase() == world.toLowerCase()) {
            w.copy(onlineCombined = settingType)
          } else {
            w
          }
        }

        worldsData = worldsData + (guild.getId -> modifiedWorlds)
        onlineListConfigToDatabase(guild, world, settingType)

        val discordConfig = discordRetrieveConfig(guild)
        val adminChannelId = if (discordConfig.nonEmpty) discordConfig("admin_channel") else ""
        val adminChannel: TextChannel = guild.getTextChannelById(adminChannelId)
        if (adminChannel != null) {
          if (adminChannel.canTalk() || !(Config.prod)) {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> set the online list channel to **$setting** for the world **$worldFormal**.\n$disclaimer")
            adminEmbed.setThumbnail(s"https://www.tibiawiki.com.br/wiki/Special:Redirect/file/$thumbnailIcon.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          }
        }

        embedBuild.setDescription(s":gear: The online list channel is now set to **$setting** for the world **$worldFormal**.\n$disclaimer")
        embedBuild.build()
      }
    } else {
      embedBuild.setDescription(s"${Config.noEmoji} You need to run `/setup` and add **$worldFormal** before you can configure this setting.")
      embedBuild.build()
    }
  }

  private def onlineListConfigToDatabase(guild: Guild, world: String, setting: String): Unit =
    worldConfigRepository.updateWorldString(guild.getId, world.toLowerCase().capitalize, "online_combined", setting)

  private def customSortConfig(guild: Guild, query: String): List[CustomSort] =
    customSortRepository.getAll(guild.getId)

  def addOnlineListCategory(event: SlashCommandInteractionEvent, guildOrPlayer: String, name: String, label: String, emoji: String, callback: MessageEmbed => Unit): Unit = {
    // get command information
    val commandUser = event.getUser.getId
    val nameLower = name.toLowerCase
    val labelCapital = label.capitalize
    val guild = event.getGuild
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    // default embed content
    var embedText = s"${Config.noEmoji} An error occurred while running the `/online` command"
    if (checkConfigDatabase(guild)) {
      val guildId = guild.getId
      // get admin channel info from database
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (guildOrPlayer == "guild") { // command run with 'guild'
        // run api against guild
        val guildCheck: Future[Either[String, GuildResponse]] = tibiaDataClient.getGuild(nameLower)
        guildCheck.map {
          case Right(guildResponse) =>
            val guildName = guildResponse.guild.name
            guildName
          case Left(errorMessage) =>
            ""
        }.map { guildName =>
          if (guildName != "") {
            if (!customSortData.getOrElse(guildId, List()).exists(g => g.entityType == "guild" && g.name.toLowerCase == nameLower)) {

              val emojiDupeOption = customSortData.getOrElse(guildId, List()).find(g => g.label == labelCapital)
              val emojiDupe = emojiDupeOption.map(_.emoji).getOrElse(emoji)

              // add guild to hunted list and database
              // case class CustomSort(type: String, name: String, emoji: String, label: String)
              customSortData = customSortData + (guildId -> (CustomSort(guildOrPlayer, guildName, labelCapital, emojiDupe) :: customSortData.getOrElse(guildId, List())))
              addOnlineListCategoryToDatabase(guild, guildOrPlayer, guildName, labelCapital, emojiDupe)
              embedText = s":gear: The guild **[$guildName](${guildUrl(guildName)})** has been tagged with: $emojiDupe **$labelCapital** $emojiDupe"

              // send embed to admin channel
              if (adminChannel != null) {
                if (adminChannel.canTalk() || !(Config.prod)) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> tagged the guild **[$guildName](${guildUrl(guildName)})** with: $emojiDupe **$labelCapital** $emojiDupe")
                  adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Library_Ticket.gif")
                  adminEmbed.setColor(3092790)
                  adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                }
              }

              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            } else {
              embedText = s"${Config.noEmoji} The guild **[$guildName](${guildUrl(guildName)})** already has a tag assigned."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            }
          } else {
            embedText = s"${Config.noEmoji} The guild **$nameLower** does not exist."
            embedBuild.setDescription(embedText)
            callback(embedBuild.build())

          }
        }
      } else if (guildOrPlayer == "player") { // command run with 'player'
        // run api against player
        val playerCheck: Future[Either[String, CharacterResponse]] = tibiaDataClient.getCharacter(nameLower)
        playerCheck.map {
          case Right(charResponse) =>
            val character = charResponse.character.character
            (character.name, character.world, vocEmoji(charResponse), character.level.toInt)
          case Left(errorMessage) =>
            ("", "", s"${Config.noEmoji}", 0)
        }.map { case (playerName, world, vocation, level) =>
          if (playerName != "") {
            if (!customSortData.getOrElse(guildId, List()).exists(g => g.entityType == "player" && g.name.toLowerCase == nameLower)) {

              val emojiDupeOption = customSortData.getOrElse(guildId, List()).find(g => g.label == labelCapital)
              val emojiDupe = emojiDupeOption.map(_.emoji).getOrElse(emoji)

              // add player to hunted list and database
              customSortData = customSortData + (guildId -> (CustomSort(guildOrPlayer, playerName, labelCapital, emojiDupe) :: customSortData.getOrElse(guildId, List())))
              addOnlineListCategoryToDatabase(guild, guildOrPlayer, playerName, labelCapital, emojiDupe)
              embedText = s":gear: The player **[$playerName](${charUrl(playerName)})** has been tagged with: $emojiDupe **$labelCapital** $emojiDupe"

              // send embed to admin channel
              if (adminChannel != null) {
                if (adminChannel.canTalk() || !(Config.prod)) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> tagged the player\n$vocation **$level** — **[$playerName](${charUrl(playerName)})**\nwith: $emojiDupe **$labelCapital** $emojiDupe")
                  adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Library_Ticket.gif")
                  adminEmbed.setColor(3092790)
                  adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                }
              }

              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            } else {
              embedText = s"${Config.noEmoji} The player **[$playerName](${charUrl(playerName)})** already has a tag assigned."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            }
          } else {
            embedText = s"${Config.noEmoji} The player **$nameLower** does not exist."
            embedBuild.setDescription(embedText)
            callback(embedBuild.build())

          }
        }
      }
    } else {
      embedText = s"${Config.noEmoji} You need to run `/setup` and add a world first."
      embedBuild.setDescription(embedText)
      callback(embedBuild.build())
    }
  }

  private def addOnlineListCategoryToDatabase(guild: Guild, guildOrPlayer: String, name: String, label: String, emoji: String): Unit =
    customSortRepository.add(guild.getId, guildOrPlayer, name, label, emoji)

  def removeOnlineListCategory(event: SlashCommandInteractionEvent, guildOrPlayer: String, name: String): MessageEmbed = {
    // get command information
    val commandUser = event.getUser.getId
    val nameLower = name.toLowerCase
    val guild = event.getGuild
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    // default embed content
    var embedText = s"${Config.noEmoji} An error occurred while running the `/online` command"
    if (checkConfigDatabase(guild)) {
      val guildId = guild.getId
      // get admin channel info from database
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (guildOrPlayer == "guild") { // command run with 'guild'
        if (customSortData.getOrElse(guildId, List()).exists(g => g.entityType == "guild" && g.name.toLowerCase == nameLower)) {

          customSortData = customSortData + (guildId -> customSortData.getOrElse(guildId, List()).filterNot(entry => entry.entityType == "guild" && entry.name.equalsIgnoreCase(nameLower)))
          removeOnlineListCategoryFromDatabase(guild, guildOrPlayer, nameLower)

          embedText = s":gear: The guild **$nameLower** had its tag removed."

          // send embed to admin channel
          if (adminChannel != null) {
            if (adminChannel.canTalk() || !(Config.prod)) {
              val adminEmbed = new EmbedBuilder()
              adminEmbed.setTitle(s":gear: a command was run:")
              adminEmbed.setDescription(s"<@$commandUser> removed the guild **$nameLower** from custom tagging.")
              adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Library_Ticket.gif")
              adminEmbed.setColor(3092790)
              adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
            }
          }
        } else {
          embedText = s"${Config.noEmoji} The guild **$nameLower** does not have a tag assigned."

        }
      } else if (guildOrPlayer == "player") { // command run with 'player'
        if (customSortData.getOrElse(guildId, List()).exists(g => g.entityType == "player" && g.name.toLowerCase == nameLower)) {

          customSortData = customSortData + (guildId -> customSortData.getOrElse(guildId, List()).filterNot(entry => entry.entityType == "player" && entry.name.equalsIgnoreCase(nameLower)))
          removeOnlineListCategoryFromDatabase(guild, guildOrPlayer, nameLower)

          embedText = s":gear: The player **$nameLower** had its tag removed."

          // send embed to admin channel
          if (adminChannel != null) {
            if (adminChannel.canTalk() || !(Config.prod)) {
              val adminEmbed = new EmbedBuilder()
              adminEmbed.setTitle(s":gear: a command was run:")
              adminEmbed.setDescription(s"<@$commandUser> removed the player **$nameLower** from custom tagging.")
              adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Library_Ticket.gif")
              adminEmbed.setColor(3092790)
              adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
            }
          }
        } else {
          embedText = s"${Config.noEmoji} The player **$nameLower** already has a tag assigned."
        }
      }
    } else {
      embedText = s"${Config.noEmoji} You need to run `/setup` and add a world first."
    }
    embedBuild.setDescription(embedText)
    embedBuild.build()
  }

  private def removeOnlineListCategoryFromDatabase(guild: Guild, guildOrPlayer: String, name: String): Unit =
    customSortRepository.removeByNameEntity(guild.getId, guildOrPlayer, name)

  def clearOnlineListCategory(event: SlashCommandInteractionEvent, label: String): MessageEmbed = {
    // get command information
    val commandUser = event.getUser.getId
    val labelLower = label.toLowerCase
    val guild = event.getGuild
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    // default embed content
    var embedText = s"${Config.noEmoji} An error occurred while running the `/online` command"
    if (checkConfigDatabase(guild)) {
      val guildId = guild.getId
      // get admin channel info from database
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (customSortData.getOrElse(guildId, List()).exists(g => g.label.toLowerCase == labelLower)) {

        customSortData = customSortData + (guildId -> customSortData.getOrElse(guildId, List()).filterNot(entry => entry.label.equalsIgnoreCase(labelLower)))
        clearOnlineListCategoryFromDatabase(guild, labelLower)

        embedText = s":gear: The tag **$labelLower** has been cleared."

        // send embed to admin channel
        if (adminChannel != null) {
          if (adminChannel.canTalk() || !(Config.prod)) {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> cleared everyone from the tag **$labelLower**.")
            adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Library_Ticket.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          }
        }
      } else {
        embedText = s"${Config.noEmoji} The tag **$labelLower** does not exist."

      }
    } else {
      embedText = s"${Config.noEmoji} You need to run `/setup` and add a world first."
    }
    embedBuild.setDescription(embedText)
    embedBuild.build()
  }

  private def clearOnlineListCategoryFromDatabase(guild: Guild, label: String): Unit =
    customSortRepository.removeByLabel(guild.getId, label)

  def listOnlineListCategory(event: SlashCommandInteractionEvent): List[MessageEmbed] = {
    // get command information
    val guild = event.getGuild
    val embedBuffer = ListBuffer[MessageEmbed]()

    // default embed content
    val guildId = guild.getId
    val guildTags: List[CustomSort] = customSortData.getOrElse(guildId, List())

    if (guildTags.isEmpty) {
      val interimEmbed = new EmbedBuilder()
      interimEmbed.setDescription(s"${Config.noEmoji} You do not have any custom tags.")
      interimEmbed.setColor(3092790)
      embedBuffer += interimEmbed.build()
    } else {
      val groupedTags: Map[(String, String), List[CustomSort]] = guildTags.groupBy(tag => (tag.label, tag.emoji))
      val groupList = ListBuffer[String]()

      val infoEmbed = new EmbedBuilder()
      infoEmbed.setDescription(s":speech_balloon: Tags are for *players* or *guilds* that arn't in your **allies** or **enemies** lists.\n\n- Their deaths will be highlighted **yellow**.\n- If you use the **`/online list combine`** version of the online list they will appear under their own category.")
      infoEmbed.setColor(14397256)
      embedBuffer += infoEmbed.build()

      // guildTags contains data
      groupedTags.foreach { case ((label, emoji), tags) =>
        groupList += s"\n$emoji **$label** $emoji"
        val tagInformation = tags.map { customSort =>
          groupList += s"- ${customSort.name} *(${customSort.entityType})*"
        }
      }

      // build the embed
      var field = ""
      groupList.foreach { v =>
        val currentField = field + "\n" + v
        if (currentField.length <= 4096) { // don't add field yet, there is still room
          field = currentField
        } else { // it's full, add the field
          val interimEmbed = new EmbedBuilder()
          interimEmbed.setDescription(field)
          interimEmbed.setColor(14397256)
          embedBuffer += interimEmbed.build()
          field = v
        }
      }
      val finalEmbed = new EmbedBuilder()
      finalEmbed.setDescription(field)
      finalEmbed.setColor(14397256)
      embedBuffer += finalEmbed.build()

    }
    embedBuffer.toList
  }

  private def deathsLevelsHideShowToDatabase(guild: Guild, world: String, setting: String, playerType: String, channelType: String): Unit = {
    val worldFormal = world.toLowerCase().capitalize
    val tablePrefix = playerType match {
      case "allies" => "show_allies_"
      case "neutrals" => "show_neutral_"
      case "enemies" => "show_enemies_"
      case _ => ""
    }
    val tableName = s"$tablePrefix$channelType"
    worldConfigRepository.updateWorldString(guild.getId, worldFormal, tableName, setting)
  }

  def fullblessLevel(event: SlashCommandInteractionEvent, world: String, level: Int): MessageEmbed = {
    val worldFormal = world.toLowerCase().capitalize
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    val cache = worldsData.getOrElse(guild.getId, List()).filter(w => w.name.toLowerCase() == world.toLowerCase())
    val levelSetting = cache.headOption.map(_.fullblessLevel).getOrElse(null)
    if (levelSetting != null) {
      if (levelSetting == level) {
        // embed reply
        embedBuild.setDescription(s"${Config.noEmoji} The level to poke for **enemy fullblesses**\nis already set to **$level** for the world **$worldFormal**.")
        embedBuild.build()
      } else {
        // set the setting here
        val modifiedWorlds = worldsData(guild.getId).map { w =>
          if (w.name.toLowerCase() == world.toLowerCase()) {
            w.copy(fullblessLevel = level)
          } else {
            w
          }
        }
        worldsData = worldsData + (guild.getId -> modifiedWorlds)
        fullblessLevelToDatabase(guild, worldFormal, level)

        // edit the fullblesschannel embeds
        val worldConfigData = worldRetrieveConfig(guild, world)
        val discordConfig = discordRetrieveConfig(guild)
        val adminChannel = guild.getTextChannelById(discordConfig("admin_channel"))
        if (worldConfigData.nonEmpty) {
          val fullblessChannelId = worldConfigData("fullbless_channel")
          val channel: TextChannel = guild.getTextChannelById(fullblessChannelId)
          if (channel != null) {
            val messages = channel.getHistory.retrievePast(100).complete().asScala.filter(m => m.getAuthor.getId.equals(botUser))
            if (messages.nonEmpty) {
              val message = messages.head
              val fullblessRole = worldConfigData("fullbless_role")
              val nemesisRole = worldConfigData("nemesis_role")
              val allyPkRole = worldConfigData("allypk_role")
              val masslogRole = worldConfigData("masslog_role")

              // Fullbless Role
              val fullblessEmbed = new EmbedBuilder()
              val fullblessEmbedText = s"The bot will poke:\n${Config.inqEmoji}<@&${fullblessRole}> If an enemy fullblesses and is over level `${level}`\n${Config.bossEmoji}<@&${nemesisRole}> If anyone dies to a rare boss\n${Config.hazardEmoji}<@&${allyPkRole}> If an ally gets pked\n${Config.masslogEmoji}<@&${masslogRole}> If enemies masslog on **$worldFormal**"
              fullblessEmbed.setTitle(s":crossed_swords: $worldFormal :crossed_swords:", s"https://www.tibia.com/community/?subtopic=worlds&world=$worldFormal")
              fullblessEmbed.setThumbnail(s"https://raw.githubusercontent.com/Leo32onGIT/tibia-bot-resources/main/Phantasmal_Ooze.gif")
              fullblessEmbed.setColor(3092790)
              fullblessEmbed.setFooter("Add or remove yourself from the role using the buttons below:")
              fullblessEmbed.setDescription(fullblessEmbedText)
              message.editMessageEmbeds(fullblessEmbed.build())
                .setActionRow(
                  Button.success("fullbless", " ").withEmoji(Emoji.fromFormatted(s"${Config.inqEmoji}")),
                  Button.primary("nemesis", " ").withEmoji(Emoji.fromFormatted(s"${Config.bossEmoji}")),
                  Button.danger("allypk", " ").withEmoji(Emoji.fromFormatted(s"${Config.hazardEmoji}")),
                  Button.secondary("masslog", " ").withEmoji(Emoji.fromFormatted(s"${Config.masslogEmoji}"))
                )
                .queue()
            }
          }
        }
        if (adminChannel != null) {
          if (adminChannel.canTalk() || !(Config.prod)) {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> changed the level to poke for **enemy fullblesses**\nto **$level** for the world **$worldFormal**.")
            adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Amulet_of_Loss.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          }
        }

        embedBuild.setDescription(s":gear: The level to poke for **enemy fullblesses**\nis now set to **$level** for the world **$worldFormal**.")
        embedBuild.build()
      }
    } else {
      embedBuild.setDescription(s"${Config.noEmoji} You need to run `/setup` and add **$worldFormal** before you can configure this setting.")
      embedBuild.build()
    }
  }

  def leaderboards(event: SlashCommandInteractionEvent, world: String, callback: MessageEmbed => Unit): Unit = {
    val worldFormal = world.toLowerCase.capitalize
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)

    if (Config.worldList.exists(_.equalsIgnoreCase(world))) {
      // Get the high scores
      val highScores: Future[Either[String, HighscoresResponse]] = tibiaDataClient.getHighscores(worldFormal, 1)

      // Handle the Future result asynchronously
      highScores.onComplete {
        case scala.util.Success(Right(highscoreResponse)) =>
          val currentPage = highscoreResponse.highscores.highscore_page.current_page
          val totalPages = highscoreResponse.highscores.highscore_page.total_pages
          embedBuild.setDescription(s"Current page: $currentPage\nTotal pages: $totalPages.")
          callback(embedBuild.build())

        case scala.util.Success(Left(errorMessage)) =>
          embedBuild.setDescription(s"${Config.noEmoji} Failed to fetch highscores: $errorMessage")
          callback(embedBuild.build())

        case scala.util.Failure(exception) =>
          embedBuild.setDescription(s"${Config.noEmoji} An error occurred: ${exception.toString}")
          callback(embedBuild.build())
      }
    } else {
      embedBuild.setDescription(s"${Config.noEmoji} **$worldFormal** is not a valid world.")
      callback(embedBuild.build())
    }
  }


  def repairChannel(event: SlashCommandInteractionEvent, world: String): MessageEmbed = {
    val worldFormal = world.toLowerCase().capitalize
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    embedBuild.setDescription(s"${Config.noEmoji} No action was taken as all channels for **$worldFormal** still exist.")
    val cache: Option[List[Worlds]] = worldsData.get(guild.getId) match {
      case Some(worlds) =>
        val filteredWorlds = worlds.filter(w => w.name.toLowerCase() == world.toLowerCase())
        if (filteredWorlds.nonEmpty) Some(filteredWorlds)
        else None
      case None => None
    }
    if (cache.isDefined) {
      // get the bots main roles
      val botRole = guild.getBotRole
      val publicRole = guild.getPublicRole

      // get channel Ids
      val categoryInfo: Option[String] = cache.flatMap(_.headOption.map(_.category))
      val alliesChannelInfo: Option[String] = cache.flatMap(_.headOption.map(_.alliesChannel))
      val enemiesChannelInfo: Option[String] = cache.flatMap(_.headOption.map(_.enemiesChannel))
      val neutralsChannelInfo: Option[String] = cache.flatMap(_.headOption.map(_.neutralsChannel))
      val levelsChannelInfo: Option[String] = cache.flatMap(_.headOption.map(_.levelsChannel))
      val deathsChannelInfo: Option[String] = cache.flatMap(_.headOption.map(_.deathsChannel))
      val activityChannelInfo: Option[String] = cache.flatMap(_.headOption.map(_.activityChannel))
      val fullblessChannelInfo: Option[String] = cache.flatMap(_.headOption.map(_.fullblessChannel))
      val onlineCombinedInfo: Option[String] = cache.flatMap(_.headOption.map(_.onlineCombined))

      // get admin ids
      val discordConfig = discordRetrieveConfig(guild)
      var adminCategory = guild.getCategoryById(discordConfig("admin_category"))
      var adminChannel = guild.getTextChannelById(discordConfig("admin_channel"))
      var boostedChannel = guild.getTextChannelById(discordConfig("boosted_channel"))
      var boostedMessage = discordConfig("boosted_messageid")

      // get channel literals
      var category = guild.getCategoryById(categoryInfo.getOrElse("0"))
      val alliesChannel = guild.getTextChannelById(alliesChannelInfo.getOrElse("0"))
      val enemiesChannel = guild.getTextChannelById(enemiesChannelInfo.getOrElse("0"))
      val neutralsChannel = guild.getTextChannelById(neutralsChannelInfo.getOrElse("0"))
      val levelsChannel = guild.getTextChannelById(levelsChannelInfo.getOrElse("0"))
      val deathsChannel = guild.getTextChannelById(deathsChannelInfo.getOrElse("0"))
      val activityChannel = guild.getTextChannelById(activityChannelInfo.getOrElse("0"))
      val onlineCombinedVal = onlineCombinedInfo.getOrElse("true")

      val onlineCombineCheck = onlineCombinedVal == "false" && (enemiesChannel == null || neutralsChannel == null)

      val fullblessChannelId = fullblessChannelInfo.getOrElse("0")
      if (fullblessChannelId == event.getChannel.getId) {
        embedBuild.setDescription(s"${Config.noEmoji} That command would delete this channel, run it somewhere else.")
        return embedBuild.build()
      }
      if (fullblessChannelId != "0") {
        val fullblessChannel = guild.getTextChannelById(fullblessChannelId)
        try {
          fullblessChannel.delete.queue()
        } catch {
          case _: Throwable => //
        }
        worldRepairConfig(guild, worldFormal, "fullbless_channel", "0")
      }
      // check if any of the world channels need to be recreated
      if (boostedChannel != null) {
        if (boostedChannel.canTalk()) {
          var fullblessMessage = false
          var nemesisMessage = false
          var allyPkMessage = false
          val messages = boostedChannel.getHistory.retrievePast(100).complete().asScala.filter { m =>
            m.getAuthor.getId.equals(botUser) && !m.isEphemeral
          }

          if (messages.nonEmpty) {
            messages.foreach { message =>
              val messageEmbeds = message.getEmbeds
              if (messageEmbeds != null && !messageEmbeds.isEmpty){
                val messageEmbed = messageEmbeds.get(0)
                val messageTitle = messageEmbed.getTitle
                if (messageTitle != null) {
                  if (messageTitle.startsWith(s":crossed_swords: $worldFormal")) {
                    fullblessMessage = true
                  } else if (messageTitle.startsWith(s"${Config.nemesisEmoji} $worldFormal")) {
                    nemesisMessage = true
                  } else if (messageTitle.startsWith(s"${Config.hazardEmoji} $worldFormal")) {
                    allyPkMessage = true
                  }
                }
              }
            }
          }
          val worldConfigData = worldRetrieveConfig(guild, world)
          if (!fullblessMessage){
            val fullblessLevel = worldConfigData("fullbless_level")
            val fullblessRoleCheck = guild.getRoleById(worldConfigData("fullbless_role"))
            val fullblessRole = if (fullblessRoleCheck == null) guild.createRole().setName(s"$worldFormal Fullbless").setColor(new Color(0, 156, 70)).complete() else fullblessRoleCheck
            val nemesisRoleCheck = guild.getRoleById(worldConfigData("nemesis_role"))
            val nemesisRole = if (nemesisRoleCheck == null) guild.createRole().setName(s"$worldFormal Rare Boss").setColor(new Color(164, 76, 230)).complete() else nemesisRoleCheck
            val allyPkRoleCheck = guild.getRoleById(worldConfigData("allypk_role"))
            val allyPkRole = if (allyPkRoleCheck == null) guild.createRole().setName(s"$worldFormal PVP").setColor(new Color(220, 0, 0)).complete() else allyPkRoleCheck
            val masslogRoleCheck = guild.getRoleById(worldConfigData("masslog_role"))
            val masslogRole = if (masslogRoleCheck == null) guild.createRole().setName(s"$worldFormal Masslog").setColor(new Color(219, 175, 72)).complete() else masslogRoleCheck

            // Fullbless Role
            val fullblessEmbed = new EmbedBuilder()
            val fullblessEmbedText = s"The bot will poke:\n${Config.inqEmoji}<@&${fullblessRole.getId}> If an enemy fullblesses and is over level `${fullblessLevel}`\n${Config.bossEmoji}<@&${nemesisRole.getId}> If anyone dies to a rare boss\n${Config.hazardEmoji}<@&${allyPkRole.getId}> If an ally gets pked\n${Config.masslogEmoji}<@&${masslogRole.getId}> If enemies masslog on **$worldFormal**"
            fullblessEmbed.setTitle(s":crossed_swords: $worldFormal :crossed_swords:", s"https://www.tibia.com/community/?subtopic=worlds&world=$worldFormal")
            fullblessEmbed.setThumbnail(s"https://raw.githubusercontent.com/Leo32onGIT/tibia-bot-resources/main/Phantasmal_Ooze.gif")
            fullblessEmbed.setColor(3092790)
            fullblessEmbed.setFooter("Add or remove yourself from the role using the buttons below:")
            fullblessEmbed.setDescription(fullblessEmbedText)
            boostedChannel.sendMessageEmbeds(fullblessEmbed.build())
              .setActionRow(
                Button.success("fullbless", " ").withEmoji(Emoji.fromFormatted(s"${Config.inqEmoji}")),
                Button.primary("nemesis", " ").withEmoji(Emoji.fromFormatted(s"${Config.bossEmoji}")),
                Button.danger("allypk", " ").withEmoji(Emoji.fromFormatted(s"${Config.hazardEmoji}")),
                Button.secondary("masslog", " ").withEmoji(Emoji.fromFormatted(s"${Config.masslogEmoji}"))
              )
              .queue()

            // Update role id if it changed
            worldRepairConfig(guild, worldFormal, "fullbless_role", fullblessRole.getId)
            worldRepairConfig(guild, worldFormal, "nemesis_role", nemesisRole.getId)
            worldRepairConfig(guild, worldFormal, "allypk_role", allyPkRole.getId)
            worldRepairConfig(guild, worldFormal, "masslog_role", masslogRole.getId)

            // update the record in worldsData
            if (worldsData.contains(guild.getId)) {
              val worldsList = worldsData(guild.getId)
              val updatedWorldsList = worldsList.map { world =>
                if (world.name.toLowerCase == worldFormal.toLowerCase) {
                  world.copy(fullblessChannel = "0", fullblessRole = fullblessRole.getId)
                } else {
                  world
                }
              }
              worldsData += (guild.getId -> updatedWorldsList)
            }
            // update the record in worldsData
            if (worldsData.contains(guild.getId)) {
              val worldsList = worldsData(guild.getId)
              val updatedWorldsList = worldsList.map { world =>
                if (world.name.toLowerCase == worldFormal.toLowerCase) {
                  world.copy(nemesisChannel = "0", nemesisRole = nemesisRole.getId)
                } else {
                  world
                }
              }
              worldsData += (guild.getId -> updatedWorldsList)
            }
            // update the record in worldsData
            if (worldsData.contains(guild.getId)) {
              val worldsList = worldsData(guild.getId)
              val updatedWorldsList = worldsList.map { world =>
                if (world.name.toLowerCase == worldFormal.toLowerCase) {
                  world.copy(allyPkRole = allyPkRole.getId)
                } else {
                  world
                }
              }
              worldsData += (guild.getId -> updatedWorldsList)
            }
            // update the record in worldsData
            if (worldsData.contains(guild.getId)) {
              val worldsList = worldsData(guild.getId)
              val updatedWorldsList = worldsList.map { world =>
                if (world.name.toLowerCase == worldFormal.toLowerCase) {
                  world.copy(masslogRole = masslogRole.getId)
                } else {
                  world
                }
              }
              worldsData += (guild.getId -> updatedWorldsList)
            }
            embedBuild.setDescription(s"${Config.yesEmoji} Missing notification message was recreated.")
          }
          if (boostedMessage != "0") {
            val boostedMessageAction = boostedChannel.retrieveMessageById(boostedMessage)
            try {
              boostedMessageAction.complete()
            } catch {
              case e: Throwable =>
                // Boosted Boss
                val boostedBoss: Future[Either[String, BoostedResponse]] = tibiaDataClient.getBoostedBoss()
                val bossEmbedFuture: Future[MessageEmbed] = boostedBoss.map {
                  case Right(boostedResponse) =>
                    val boostedBoss = boostedResponse.boostable_bosses.boosted.name
                    createBoostedEmbed("Boosted Boss", Config.bossEmoji, "https://www.tibia.com/library/?subtopic=boostablebosses", creatureImageUrl(boostedBoss), s"The boosted boss today is:\n### ${Config.indentEmoji}${Config.archfoeEmoji} **[$boostedBoss](${creatureWikiUrl(boostedBoss)})**")

                  case Left(errorMessage) =>
                    val boostedBoss = "Podium_of_Vigour"
                    createBoostedEmbed("Boosted Boss", Config.bossEmoji, "https://www.tibia.com/library/?subtopic=boostablebosses", creatureImageUrl(boostedBoss), "The boosted boss today failed to load?")
                }

                // Boosted Creature
                val boostedCreature: Future[Either[String, CreatureResponse]] = tibiaDataClient.getBoostedCreature()
                val creatureEmbedFuture: Future[MessageEmbed] = boostedCreature.map {
                  case Right(creatureResponse) =>
                    val boostedCreature = creatureResponse.creatures.boosted.name
                    createBoostedEmbed("Boosted Creature", Config.creatureEmoji, "https://www.tibia.com/library/?subtopic=creatures", creatureImageUrl(boostedCreature), s"The boosted creature today is:\n### ${Config.indentEmoji}${Config.levelUpEmoji} **[$boostedCreature](${creatureWikiUrl(boostedCreature)})**")

                  case Left(errorMessage) =>
                    val boostedCreature = "Podium_of_Tenacity"
                    createBoostedEmbed("Boosted Creature", Config.creatureEmoji, "https://www.tibia.com/library/?subtopic=creatures", creatureImageUrl(boostedCreature), "The boosted creature today failed to load?")
                }

                // Combine both futures and send the message
                val combinedFutures: Future[List[MessageEmbed]] = for {
                  bossEmbed <- bossEmbedFuture
                  creatureEmbed <- creatureEmbedFuture
                } yield List(bossEmbed, creatureEmbed)

                combinedFutures.map { embeds =>
                  val dreamScarDaily = dreamScar.getOrElse(worldFormal, "World not found")
                  val rashidLocation = ServerSaveSchedule.rashidLocation(ZonedDateTime.now(ZoneId.of("Europe/Berlin")).minusHours(10).getDayOfWeek)

                  val rashidEmbed = new EmbedBuilder()
                    .setDescription(
                      s"Today Rashid can be found in:\n### ${Config.indentEmoji}${Config.goldEmoji} **[${rashidLocation}](https://tibia.fandom.com/wiki/Rashid)**"
                    )
                    .setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Rashid.gif")
                    .setColor(3092790)
                    .build()

                  val dreamScarEmbed = new EmbedBuilder()
                    .setDescription(
                      s"The Dream Courts boss for **$worldFormal** is:\n### ${Config.indentEmoji}${Config.dreamScarEmoji} **[${dreamScarDaily}](https://tibia.fandom.com/wiki/Dream_Scar/Boss_of_the_Day)**"
                    )
                    .setThumbnail(creatureImageUrl(dreamScarDaily))
                    .setColor(3092790)
                    .build()

                  // Drome Timer
                  val now = Instant.now()
                  val dromeShow = ServerSaveSchedule.shouldShowDrome(now, dromeTime)
                  val dromeEmbed = new EmbedBuilder()
                    .setDescription(s"The current Drome cycle will end:\n### ${Config.indentEmoji}${Config.dromeEmoji} ${TimeFormat.RELATIVE.format(dromeTime)}")
                    .setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Phant.gif")
                    .setColor(3092790)
                    .build()

                  val embedsList = if (dromeShow) List(rashidEmbed, dreamScarEmbed, dromeEmbed) else List(rashidEmbed, dreamScarEmbed)
                  val finalEmbeds =
                    embeds ++ embedsList

                  boostedChannel
                    .sendMessageEmbeds(finalEmbeds.asJava)
                    .setActionRow(
                      Button.primary(
                        "boosted list",
                        "Server Save Notifications"
                      ).withEmoji(Emoji.fromFormatted(Config.letterEmoji))
                    )
                    .queue(
                      (message: Message) => {
                        discordUpdateConfig(guild, "", "", "", message.getId, worldFormal)
                      },
                      (e: Throwable) => {
                        logger.warn(
                          s"Failed to send boosted boss/creature message for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}':",
                          e
                        )
                      }
                    )
                }
            }
          }
        } else {
          embedBuild.setDescription(s"${Config.noEmoji} The bot does not have VIEW/SEND permissions for the channel: **${boostedChannel.getName}**.\nI suggest you delete that channel and run the command again.")
        }
      }

      if (alliesChannel == null || onlineCombineCheck || levelsChannel == null || deathsChannel == null || activityChannel == null || adminChannel == null || boostedChannel == null) {
        if (category == null) { // category has been deleted:
          // create the category
          val newCategory = guild.createCategory(world).complete()
          newCategory.upsertPermissionOverride(botRole)
            .grant(Permission.VIEW_CHANNEL)
            .grant(Permission.MESSAGE_SEND)
            .grant(Permission.MESSAGE_MENTION_EVERYONE)
            .grant(Permission.MESSAGE_EMBED_LINKS)
            .grant(Permission.MESSAGE_HISTORY)
            .grant(Permission.MANAGE_CHANNEL)
            .complete()
          newCategory.upsertPermissionOverride(guild.getPublicRole).deny(Permission.MESSAGE_SEND).complete()
          category = newCategory
          worldRepairConfig(guild, worldFormal, "category", newCategory.getId)

          // update the record in worldsData
          if (worldsData.contains(guild.getId)) {
            val worldsList = worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(category = newCategory.getId)
              } else {
                world
              }
            }
            worldsData += (guild.getId -> updatedWorldsList)
          }
        }
        val channelList = ListBuffer[(TextChannel, Boolean)]()
        // create the channels underneath the new/existing category
        if (alliesChannel == null) {
          val alliesName = if (onlineCombinedVal == "false") "🤍・ᴀʟʟɪᴇs" else "📈・ᴏɴʟɪɴᴇ"
          val recreateAlliesChannel = guild.createTextChannel(s"$alliesName", category).complete()
          channelList += ((recreateAlliesChannel, false))
          worldRepairConfig(guild, worldFormal, "allies_channel", recreateAlliesChannel.getId)
          // update the record in worldsData
          if (worldsData.contains(guild.getId)) {
            val worldsList = worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(alliesChannel = recreateAlliesChannel.getId)
              } else {
                world
              }
            }
            worldsData += (guild.getId -> updatedWorldsList)
          }
        }
        if (enemiesChannel == null && onlineCombinedVal == "false") {
          val recreateEnemiesChannel = guild.createTextChannel("☠️・ᴇɴᴇᴍɪᴇs", category).complete()
          channelList += ((recreateEnemiesChannel, false))
          worldRepairConfig(guild, worldFormal, "enemies_channel", recreateEnemiesChannel.getId)
          // update the record in worldsData
          if (worldsData.contains(guild.getId)) {
            val worldsList = worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(enemiesChannel = recreateEnemiesChannel.getId)
              } else {
                world
              }
            }
            worldsData += (guild.getId -> updatedWorldsList)
          }
        }
        if (neutralsChannel == null && onlineCombinedVal == "false") {
          val recreateNeutralsChannel = guild.createTextChannel("📈・ɴᴇᴜᴛʀᴀʟs", category).complete()
          channelList += ((recreateNeutralsChannel, false))
          worldRepairConfig(guild, worldFormal, "neutrals_channel", recreateNeutralsChannel.getId)
          // update the record in worldsData
          if (worldsData.contains(guild.getId)) {
            val worldsList = worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(neutralsChannel = recreateNeutralsChannel.getId)
              } else {
                world
              }
            }
            worldsData += (guild.getId -> updatedWorldsList)
          }
        }
        if (deathsChannel == null) {
          val recreateDeathsChannel = guild.createTextChannel("💀・ᴅᴇᴀᴛʜs", category).complete()
          channelList += ((recreateDeathsChannel, false))
          worldRepairConfig(guild, worldFormal, "deaths_channel", recreateDeathsChannel.getId)
          // update the record in worldsData
          if (worldsData.contains(guild.getId)) {
            val worldsList = worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(deathsChannel = recreateDeathsChannel.getId)
              } else {
                world
              }
            }
            worldsData += (guild.getId -> updatedWorldsList)
          }
        }
        if (levelsChannel == null) {
          val recreateLevelsChannel = guild.createTextChannel("💖・ʟᴇᴠᴇʟs", category).complete()
          channelList += ((recreateLevelsChannel, true))
          worldRepairConfig(guild, worldFormal, "levels_channel", recreateLevelsChannel.getId)
          // update the record in worldsData
          if (worldsData.contains(guild.getId)) {
            val worldsList = worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(levelsChannel = recreateLevelsChannel.getId)
              } else {
                world
              }
            }
            worldsData += (guild.getId -> updatedWorldsList)
          }
        }
        if (activityChannel == null) {
          val recreateActivityChannel = guild.createTextChannel("📝・ᴀᴄᴛɪᴠɪᴛʏ", category).complete()
          channelList += ((recreateActivityChannel, false))
          worldRepairConfig(guild, worldFormal, "activity_channel", recreateActivityChannel.getId)
          // update the record in worldsData
          if (worldsData.contains(guild.getId)) {
            val worldsList = worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(activityChannel = recreateActivityChannel.getId)
              } else {
                world
              }
            }
            worldsData += (guild.getId -> updatedWorldsList)
          }
          // post initial embed in activity channel
          if (recreateActivityChannel != null) {
            val activityEmbed = new EmbedBuilder()
            activityEmbed.setDescription(s":speech_balloon: This channel shows change activity for *allied* or *enemy* players.\n\nIt will show events when a players **joins** or **leaves** one of these tracked guilds or **changes their name**.")
            activityEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Sign_(Library).gif")
            activityEmbed.setColor(3092790)
            recreateActivityChannel.sendMessageEmbeds(activityEmbed.build()).queue()
          }
        }

        if (boostedChannel == null) {
          if (adminCategory == null) {
            val newAdminCategory = guild.createCategory("Violent Bot").complete()
            newAdminCategory.upsertPermissionOverride(botRole)
              .grant(Permission.VIEW_CHANNEL)
              .grant(Permission.MESSAGE_SEND)
              .complete()
            newAdminCategory.upsertPermissionOverride(guild.getPublicRole).grant(Permission.VIEW_CHANNEL).queue()
            adminCategory = newAdminCategory
          }
          // create the channel
          val newBoostedChannel = guild.createTextChannel("👑・ɴᴏᴛɪғɪᴄᴀᴛɪᴏɴs", adminCategory).complete()

          // restrict the channel so only roles with Permission.MANAGE_MESSAGES can write to the channels
          newBoostedChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
          newBoostedChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
          newBoostedChannel.upsertPermissionOverride(guild.getPublicRole).grant(Permission.VIEW_CHANNEL).queue()
          boostedChannel = newBoostedChannel
          // update db & cache
          discordUpdateConfig(guild, adminCategory.getId, "", newBoostedChannel.getId, "", worldFormal)
          updateBoostedChannel(guild.getId, newBoostedChannel.getId)

          boostedChannel.upsertPermissionOverride(botRole)
            .grant(Permission.VIEW_CHANNEL)
            .grant(Permission.MESSAGE_SEND)
            .grant(Permission.MESSAGE_EMBED_LINKS)
            .grant(Permission.MESSAGE_HISTORY)
            .grant(Permission.MANAGE_CHANNEL)
            .complete()
          boostedChannel.upsertPermissionOverride(publicRole)
            .deny(Permission.MESSAGE_SEND)
            .complete()

          val galthenEmbed = new EmbedBuilder()
          galthenEmbed.setColor(3092790)
          galthenEmbed.setDescription("This is a **[Galthen's Satchel](https://www.tibiawiki.com.br/wiki/Galthen's_Satchel)** cooldown tracker.\nManage your cooldowns here:")
          galthenEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
          boostedChannel.sendMessageEmbeds(galthenEmbed.build()).addActionRow(
            Button.primary("galthen default", "Cooldowns").withEmoji(Emoji.fromFormatted(Config.satchelEmoji))
          ).queue()

          // Boosted Boss
          val boostedBoss: Future[Either[String, BoostedResponse]] = tibiaDataClient.getBoostedBoss()
          val bossEmbedFuture: Future[MessageEmbed] = boostedBoss.map {
            case Right(boostedResponse) =>
              val boostedBoss = boostedResponse.boostable_bosses.boosted.name
              createBoostedEmbed("Boosted Boss", Config.bossEmoji, "https://www.tibia.com/library/?subtopic=boostablebosses", creatureImageUrl(boostedBoss), s"The boosted boss today is:\n### ${Config.indentEmoji}${Config.archfoeEmoji} **[$boostedBoss](${creatureWikiUrl(boostedBoss)})**")

            case Left(errorMessage) =>
              val boostedBoss = "Podium_of_Vigour"
              createBoostedEmbed("Boosted Boss", Config.bossEmoji, "https://www.tibia.com/library/?subtopic=boostablebosses", creatureImageUrl(boostedBoss), "The boosted boss today failed to load?")
          }

          // Boosted Creature
          val boostedCreature: Future[Either[String, CreatureResponse]] = tibiaDataClient.getBoostedCreature()
          val creatureEmbedFuture: Future[MessageEmbed] = boostedCreature.map {
            case Right(creatureResponse) =>
              val boostedCreature = creatureResponse.creatures.boosted.name
              createBoostedEmbed("Boosted Creature", Config.creatureEmoji, "https://www.tibia.com/library/?subtopic=creatures", creatureImageUrl(boostedCreature), s"The boosted creature today is:\n### ${Config.indentEmoji}${Config.levelUpEmoji} **[$boostedCreature](${creatureWikiUrl(boostedCreature)})**")

            case Left(errorMessage) =>
              val boostedCreature = "Podium_of_Tenacity"
              createBoostedEmbed("Boosted Creature", Config.creatureEmoji, "https://www.tibia.com/library/?subtopic=creatures", creatureImageUrl(boostedCreature), "The boosted creature today failed to load?")
          }

          // Combine both futures and send the message
          val combinedFutures: Future[List[MessageEmbed]] = for {
            bossEmbed <- bossEmbedFuture
            creatureEmbed <- creatureEmbedFuture
          } yield List(bossEmbed, creatureEmbed)

          combinedFutures.map { embeds =>

              val dreamScarDaily =
                dreamScar.getOrElse(world, "World not found")

              val rashidLocation = ServerSaveSchedule.rashidLocation(ZonedDateTime.now(ZoneId.of("Europe/Berlin")).minusHours(10).getDayOfWeek)

              val rashidEmbed = new EmbedBuilder()
                .setDescription(
                  s"Today Rashid can be found in:\n### ${Config.indentEmoji}${Config.goldEmoji} **[${rashidLocation}](https://tibia.fandom.com/wiki/Rashid)**"
                )
                .setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Rashid.gif")
                .setColor(3092790)
                .build()

              val dreamScarEmbed = new EmbedBuilder()
                .setDescription(
                  s"The Dream Courts boss for **$world** is:\n### ${Config.indentEmoji}${Config.dreamScarEmoji} **[${dreamScarDaily}](https://tibia.fandom.com/wiki/Dream_Scar/Boss_of_the_Day)**"
                )
                .setThumbnail(creatureImageUrl(dreamScarDaily))
                .setColor(3092790)
                .build()

              // Drome Timer
              val now = Instant.now()
              val dromeShow = ServerSaveSchedule.shouldShowDrome(now, dromeTime)
              val dromeEmbed = new EmbedBuilder()
                .setDescription(s"The current Drome cycle will end:\n### ${Config.indentEmoji}${Config.dromeEmoji} ${TimeFormat.RELATIVE.format(dromeTime)}")
                .setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Phant.gif")
                .setColor(3092790)
                .build()

              val embedsList = if (dromeShow) List(rashidEmbed, dreamScarEmbed, dromeEmbed) else List(rashidEmbed, dreamScarEmbed)
              val addRashidDreamScarEmbeds: List[MessageEmbed] =
                embeds ++ embedsList

              boostedChannel
                .sendMessageEmbeds(addRashidDreamScarEmbeds.asJava)
                .setActionRow(
                  Button.primary(
                    "boosted list",
                    "Server Save Notifications"
                  ).withEmoji(Emoji.fromFormatted(Config.letterEmoji))
                )
                .queue((message: Message) => {
                //updateBoostedMessage(guild.getId, message.getId)
                discordUpdateConfig(guild, "", "", "", message.getId, worldFormal)
              }, (e: Throwable) => {
                logger.warn(s"Failed to send boosted boss/creature message for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}':", e)
              })
            }

          val worldConfigData = worldRetrieveConfig(guild, world)
          val fullblessLevel = worldConfigData("fullbless_level")
          val fullblessRoleCheck = guild.getRoleById(worldConfigData("fullbless_role"))
          val fullblessRole = if (fullblessRoleCheck == null) guild.createRole().setName(s"$worldFormal Fullbless").setColor(new Color(0, 156, 70)).complete() else fullblessRoleCheck
          val nemesisRoleCheck = guild.getRoleById(worldConfigData("nemesis_role"))
          val nemesisRole = if (nemesisRoleCheck == null) guild.createRole().setName(s"$worldFormal Rare Boss").setColor(new Color(164, 76, 230)).complete() else nemesisRoleCheck
          val allyPkRoleCheck = guild.getRoleById(worldConfigData("allypk_role"))
          val allyPkRole = if (allyPkRoleCheck == null) guild.createRole().setName(s"$worldFormal PVP").setColor(new Color(220, 0, 0)).complete() else allyPkRoleCheck
          val masslogRoleCheck = guild.getRoleById(worldConfigData("masslog_role"))
          val masslogRole = if (masslogRoleCheck == null) guild.createRole().setName(s"$worldFormal Masslog").setColor(new Color(219, 175, 72)).complete() else masslogRoleCheck

          // Fullbless Role
          val fullblessEmbed = new EmbedBuilder()
          val fullblessEmbedText = s"The bot will poke:\n${Config.inqEmoji}<@&${fullblessRole.getId}> If an enemy fullblesses and is over level `${fullblessLevel}`\n${Config.bossEmoji}<@&${nemesisRole.getId}> If anyone dies to a rare boss\n${Config.hazardEmoji}<@&${allyPkRole.getId}> If an ally gets pked\n${Config.masslogEmoji}<@&${masslogRole.getId}> If enemies masslog on **$worldFormal**"
          fullblessEmbed.setTitle(s":crossed_swords: $worldFormal :crossed_swords:", s"https://www.tibia.com/community/?subtopic=worlds&world=$worldFormal")
          fullblessEmbed.setThumbnail(s"https://raw.githubusercontent.com/Leo32onGIT/tibia-bot-resources/main/Phantasmal_Ooze.gif")
          fullblessEmbed.setColor(3092790)
          fullblessEmbed.setFooter("Add or remove yourself from the role using the buttons below:")
          fullblessEmbed.setDescription(fullblessEmbedText)
          boostedChannel.sendMessageEmbeds(fullblessEmbed.build())
            .setActionRow(
              Button.success("fullbless", " ").withEmoji(Emoji.fromFormatted(s"${Config.inqEmoji}")),
              Button.primary("nemesis", " ").withEmoji(Emoji.fromFormatted(s"${Config.bossEmoji}")),
              Button.danger("allypk", " ").withEmoji(Emoji.fromFormatted(s"${Config.hazardEmoji}")),
              Button.secondary("masslog", " ").withEmoji(Emoji.fromFormatted(s"${Config.masslogEmoji}"))
            )
            .queue()
          // Update role id if it changed
          worldRepairConfig(guild, worldFormal, "fullbless_role", fullblessRole.getId)
          // update the record in worldsData
          if (worldsData.contains(guild.getId)) {
            val worldsList = worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(fullblessChannel = "0", fullblessRole = fullblessRole.getId)
              } else {
                world
              }
            }
            worldsData += (guild.getId -> updatedWorldsList)
          }

          // Update role id if it changed
          worldRepairConfig(guild, worldFormal, "nemesis_role", nemesisRole.getId)

          // update the record in worldsData
          if (worldsData.contains(guild.getId)) {
            val worldsList = worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(nemesisChannel = "0", nemesisRole = nemesisRole.getId)
              } else {
                world
              }
            }
            worldsData += (guild.getId -> updatedWorldsList)
          }
          // Update role id if it changed
          worldRepairConfig(guild, worldFormal, "allypk_role", allyPkRole.getId)

          // update the record in worldsData
          if (worldsData.contains(guild.getId)) {
            val worldsList = worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(allyPkRole = allyPkRole.getId)
              } else {
                world
              }
            }
            worldsData += (guild.getId -> updatedWorldsList)
          }

          // Update role id if it changed
          worldRepairConfig(guild, worldFormal, "masslog_role", masslogRole.getId)

          // update the record in worldsData
          if (worldsData.contains(guild.getId)) {
            val worldsList = worldsData(guild.getId)
            val updatedWorldsList = worldsList.map { world =>
              if (world.name.toLowerCase == worldFormal.toLowerCase) {
                world.copy(masslogRole = masslogRole.getId)
              } else {
                world
              }
            }
            worldsData += (guild.getId -> updatedWorldsList)
          }
        }

        // apply required permissions to the new channel(s)
        if (channelList.nonEmpty) {
          channelList.foreach { case (channel, webhooks) =>
            channel.upsertPermissionOverride(botRole)
              .grant(Permission.VIEW_CHANNEL)
              .grant(Permission.MESSAGE_SEND)
              .grant(Permission.MESSAGE_MENTION_EVERYONE)
              .grant(Permission.MESSAGE_EMBED_LINKS)
              .grant(Permission.MESSAGE_HISTORY)
              .grant(Permission.MANAGE_CHANNEL)
              .complete()
            channel.upsertPermissionOverride(publicRole)
              .deny(Permission.MESSAGE_SEND)
              .complete()
            if (webhooks) {
              //
            }
          }
        }
        // recreate admin channel and/or category
        if (adminChannel == null) {
          if (adminCategory == null) {
            val newAdminCategory = guild.createCategory("Violent Bot").complete()
            newAdminCategory.upsertPermissionOverride(botRole)
              .grant(Permission.VIEW_CHANNEL)
              .grant(Permission.MESSAGE_SEND)
              .complete()
            newAdminCategory.upsertPermissionOverride(guild.getPublicRole).grant(Permission.VIEW_CHANNEL).queue()
            adminCategory = newAdminCategory
          }
          // create the channel
          val newAdminChannel = guild.createTextChannel("🖥️・ᴄᴏᴍᴍᴀɴᴅ ʟᴏɢ", adminCategory).complete()
          // restrict the channel so only roles with Permission.MANAGE_MESSAGES can write to the channels
          newAdminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
          newAdminChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
          newAdminChannel.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
          adminChannel = newAdminChannel
          // update db & cache
          discordUpdateConfig(guild, adminCategory.getId, newAdminChannel.getId, "", "", worldFormal)
          updateAdminChannel(guild.getId, newAdminChannel.getId)
        }
        if (adminChannel != null) {
          if (adminChannel.canTalk()) {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> has run `/repair` on the world **$worldFormal** and recreated missing channels.\n\nYou may need to rearrange their position within your discord server.")
            adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Hammer.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          }
        }
        embedBuild.setDescription(s":gear: The missing channels for **$worldFormal** have been recreated.\nYou may need to rearrange their position within your discord server.")
      }
    } else {
      embedBuild.setDescription(s"${Config.noEmoji} You cannot run a `/repair` on **$worldFormal** because that world has not been `/setup` yet.")
    }
    embedBuild.build()
  }

  private def worldRepairConfig(guild: Guild, world: String, tableName: String, newValue: String): Unit =
    worldConfigRepository.updateWorldString(guild.getId, world, tableName, newValue)

  def minLevel(event: SlashCommandInteractionEvent, world: String, level: Int, levelsOrDeaths: String): MessageEmbed = {
    val worldFormal = world.toLowerCase().capitalize
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    val cache = worldsData.getOrElse(guild.getId, List()).filter(w => w.name.toLowerCase() == world.toLowerCase())
    val levelSetting = cache.headOption.map(_.levelsMin).getOrElse(null)
    val deathSetting = cache.headOption.map(_.deathsMin).getOrElse(null)
    val chosenSetting = if (levelsOrDeaths == "levels") levelSetting else deathSetting
    if (chosenSetting != null) {
      if (chosenSetting == level) {
        // embed reply
        embedBuild.setDescription(s"${Config.noEmoji} The minimum level for the **$levelsOrDeaths channel**\nis already set to `$level` for the world **$worldFormal**.")
        embedBuild.build()
      } else {
        // set the setting here
        val modifiedWorlds = worldsData(guild.getId).map { w =>
          if (w.name.toLowerCase() == world.toLowerCase()) {
            if (levelsOrDeaths == "levels") {
              w.copy(levelsMin = level)
            } else { // deaths
              w.copy(deathsMin = level)
            }
          } else {
            w
          }
        }
        worldsData = worldsData + (guild.getId -> modifiedWorlds)
        minLevelToDatabase(guild, worldFormal, level, levelsOrDeaths)

        val discordConfig = discordRetrieveConfig(guild)
        val adminChannel = guild.getTextChannelById(discordConfig("admin_channel"))
        if (adminChannel != null) {
          if (adminChannel.canTalk() || !(Config.prod)) {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> changed the minimum level for the **$levelsOrDeaths channel**\nto `$level` for the world **$worldFormal**.")
            adminEmbed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Royal_Fanfare.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          }
        }
        embedBuild.setDescription(s":gear: The minimum level for the **$levelsOrDeaths channel**\nis now set to `$level` for the world **$worldFormal**.")
        embedBuild.build()
      }
    } else {
      embedBuild.setDescription(s"${Config.noEmoji} You need to run `/setup` and add **$worldFormal** before you can configure this setting.")
      embedBuild.build()
    }
  }

  private def fullblessLevelToDatabase(guild: Guild, world: String, level: Int): Unit =
    worldConfigRepository.updateWorldInt(guild.getId, world, "fullbless_level", level)

  private def minLevelToDatabase(guild: Guild, world: String, level: Int, levelOrDeath: String): Unit = {
    val columnName = if (levelOrDeath == "levels") "levels_min" else "deaths_min"
    worldConfigRepository.updateWorldInt(guild.getId, world, columnName, level)
  }

  def discordLeave(event: GuildLeaveEvent): Unit = {
    val guildId = event.getGuild.getId

    // Remove from worldsData if exists
    if (worldsData.contains(guildId)) {
      val updatedWorldsData = worldsData - guildId
      worldsData = updatedWorldsData
    }

    // Remove from discordsData if exists
    val updatedDiscordsData = discordsData.map { case (world, discordsList) =>
      if (discordsList.exists(_.id == guildId)) {
        val updatedDiscords = discordsList.filterNot(_.id == guildId)
        world -> updatedDiscords
      } else {
        world -> discordsList
      }
    }
    // Only update discordsData if the guild existed in it
    if (updatedDiscordsData != discordsData) {
      discordsData = updatedDiscordsData
    }

    // Remove this guild from every world stream, cancelling any left unused
    streamSupervisor.removeGuild(guildId)

    logger.info(guildId)

    if (guildId == "912739993015947324" || guildId == "1176279097001918516" || guildId == "1224670957466161234") {
      // Config is shared with Pulsera Bot
      logger.info("Config is shared between Pulsera Bot, will use as alpha environment will delete when guild wants it deleted")
    } else {
      removeConfigDatabase(guildId)
    }

  }

  def discordJoin(event: GuildJoinEvent): Unit = {
    val guild = event.getGuild
    val publicChannel = guild.getTextChannelById(guild.getDefaultChannel.getId)
    if (publicChannel != null) {
      if (publicChannel.canTalk() || !(Config.prod)) {
        val embedBuilder = new EmbedBuilder()
        val descripText = Config.helpText
        embedBuilder.setAuthor("Violent Beams", "https://www.tibia.com/community/?subtopic=characters&name=Violent+Beams", "https://github.com/Leo32onGIT.png")
        embedBuilder.setDescription(descripText)
        embedBuilder.setThumbnail(Config.webHookAvatar)
        embedBuilder.setColor(14397256) // orange for bot auto command
        try {
          publicChannel.sendMessageEmbeds(embedBuilder.build()).queue()
        } catch {
          case ex: Throwable => logger.error(s"Failed to send 'New Discord Join' message for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
        }
      }
    }
  }

  private def removeConfigDatabase(guildId: String): Unit = {
    val conn = connectionProvider.admin()
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT datname FROM pg_database WHERE datname = '_$guildId'")
    val exist = result.next()

    // if bot_configuration exists
    if (exist) {
      statement.executeUpdate(s"DROP DATABASE _$guildId;")
      logger.info(s"Database '$guildId' removed successfully")
      statement.close()
      conn.close()
    } else {
      logger.info(s"Database '$guildId' was not removed as it doesn't exist")
      statement.close()
      conn.close()
    }
  }

  def removeChannels(event: SlashCommandInteractionEvent): MessageEmbed = {
    // get guild & world information from the slash interaction
    val world: String = event.getInteraction.getOptions.asScala.find(_.getName == "world").map(_.getAsString).getOrElse("").trim().toLowerCase().capitalize
    val embedText = if (worlds.contains(world) || Config.mergedWorlds.contains(world)) {
      val guild = event.getGuild
      val worldConfigData = worldRetrieveConfig(guild, world)
      if (worldConfigData.nonEmpty) {
        // get channel ids
        val alliesChannelId = worldConfigData("allies_channel")
        val enemiesChannelId = worldConfigData("enemies_channel")
        val neutralsChannelId = worldConfigData("neutrals_channel")
        val levelsChannelId = worldConfigData("levels_channel")
        val deathsChannelId = worldConfigData("deaths_channel")
        val fullblessChannelId = worldConfigData("fullbless_channel")
        val nemesisChannelId = worldConfigData("nemesis_channel")
        val categoryId = worldConfigData("category")
        val activityChannelId = worldConfigData("activity_channel")
        val channelIds = List(alliesChannelId, enemiesChannelId, neutralsChannelId, levelsChannelId, deathsChannelId, fullblessChannelId, nemesisChannelId, activityChannelId)

        // check if command is being run in one of the channels being deleted
        if (channelIds.contains(event.getChannel.getId)) {
          return new EmbedBuilder()
          .setColor(3092790)
          .setDescription(s"${Config.noEmoji} That command would delete this channel, run it somewhere else.")
          .build()
        }

        val fullblessRoleId = worldConfigData("fullbless_role")
        val nemesisRoleId = worldConfigData("nemesis_role")
        val allyPkRoleId = worldConfigData("allypk_role")
        val masslogRoleId = worldConfigData("masslog_role")

        val fullblessRole = guild.getRoleById(nemesisRoleId)
        val nemesisRole = guild.getRoleById(fullblessRoleId)
        val allyPkRole = guild.getRoleById(allyPkRoleId)
        val masslogRole = guild.getRoleById(masslogRoleId)

        //@unkown role-fix WIP
        if (fullblessRole != null) {
          try {
            fullblessRole.delete().queue()
          } catch {
            case ex: Throwable => logger.info(s"Failed to delete Role ID: '${fullblessRoleId}' for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
          }
        }

        if (nemesisRole != null) {
          try {
            nemesisRole.delete().queue()
          } catch {
            case ex: Throwable => logger.info(s"Failed to delete Role ID: '${nemesisRoleId}' for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'")
          }
        }

        if (allyPkRole != null) {
          try {
            allyPkRole.delete().queue()
          } catch {
            case ex: Throwable => logger.info(s"Failed to delete Role ID: '${allyPkRoleId}' for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'")
          }
        }

        if (masslogRole != null) {
          try {
            masslogRole.delete().queue()
          } catch {
            case ex: Throwable => logger.info(s"Failed to delete Role ID: '${masslogRoleId}' for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'")
          }
        }

        // remove the guild from the world stream, cancelling it if now unused
        streamSupervisor.removeGuildFromWorld(world, guild.getId)

        // delete the channels & category
        channelIds.foreach { channelId =>
          val channel: TextChannel = guild.getTextChannelById(channelId)
          if (channel != null) {
            channel.delete().complete()
          }
        }

        val category = guild.getCategoryById(categoryId)
        if (category != null) {
          category.delete().complete()
        }

        // remove from worldsData
        val updatedWorldsData = worldsData.get(guild.getId)
          .map(_.filterNot(_.name.toLowerCase() == world.toLowerCase()))
          .map(worlds => worldsData + (guild.getId -> worlds))
          .getOrElse(worldsData)
        worldsData = updatedWorldsData

        // remove from discordsData
        discordsData.get(world)
          .foreach { discords =>
            val updatedDiscords = discords.filterNot(_.id == guild.getId)
            discordsData += (world -> updatedDiscords)
          }

        // update the database
        worldRemoveConfig(guild, world)

        s":gear: The world **$world** has been removed."
      } else {
        s"${Config.noEmoji} The world **$world** is not configured here."
      }
    } else {
      s"${Config.noEmoji} This is not a valid World on Tibia."
    }
    // embed reply
    new EmbedBuilder()
    .setColor(3092790)
    .setDescription(embedText)
    .build()
  }


  private def creatureImageUrl(creature: String): String = {
    val key = creature.toLowerCase

    key match {
      case "death" =>
        "https://raw.githubusercontent.com/Leo32onGIT/tibia-bot-resources/main/Death_Effect.gif"
      case "ice" =>
        "https://raw.githubusercontent.com/Leo32onGIT/tibia-bot-resources/main/Ice_Explosion_Effect.gif"
      case "drowning" =>
        "https://raw.githubusercontent.com/Leo32onGIT/tibia-bot-resources/main/Reaper_Effect.gif"
      case "pvp" =>
        "https://raw.githubusercontent.com/Leo32onGIT/tibia-bot-resources/main/Phantasmal_Ooze.gif"
      case "life drain" =>
        "https://raw.githubusercontent.com/Leo32onGIT/tibia-bot-resources/main/Red_Sparkles_Effect.gif"

      case _ =>
        presentation.Urls.creatureImageUrl(creature, Config.creatureUrlMappings)
    }
  }

  def creatureWikiUrl(creature: String): String =
    presentation.Urls.creatureWikiUrl(creature, Config.creatureUrlMappings)

  // V1.9 Boosted Command
  def createBoostedEmbed(name: String, emoji: String, wikiUrl: String, thumbnail: String, embedText: String): MessageEmbed =
    presentation.BoostedEmbeds.create(name, emoji, wikiUrl, thumbnail, embedText)

  // Death screenshot database methods
  def storeDeathScreenshot(guildId: String, world: String, characterName: String, deathTime: Long, screenshotUrl: String, addedBy: String, addedName: String, messageId: String): Unit =
    deathScreenshotRepository.store(guildId, world, characterName, deathTime, screenshotUrl, addedBy, addedName, messageId)

  def getDeathScreenshots(guildId: String, world: String, characterName: String, deathTime: Long): List[DeathScreenshot] =
    deathScreenshotRepository.get(guildId, world, characterName, deathTime)

  def deleteDeathScreenshot(guildId: String, world: String, characterName: String, deathTime: Long, screenshotUrl: String, userId: String): Boolean = {
    val guild = discordGateway.guildById(guildId)
    val member = guild.retrieveMemberById(userId).complete()
    val admin = member != null && (member.hasPermission(Permission.MANAGE_SERVER) || member.hasPermission(Permission.MESSAGE_MANAGE))
    deathScreenshotRepository.deleteIfPermitted(guildId, characterName, deathTime, screenshotUrl) { addedBy =>
      addedBy == userId || admin
    }
  }

  def fetchDreamScarBosses(): List[BossEntry] = wikiClient.dreamScarBosses()

  def fetchCreatureNames(): List[String] = wikiClient.creatureNames()

  def advanceDromeTime(inputTime: Instant): Unit =
    dromeTime = domain.time.DromeCycle.advanceFrom(dromeTime, inputTime)

  def shiftAllBossesUp(current: Map[String, String]): Map[String, String] =
    domain.time.DreamScarCycle.shiftAllBossesUp(current)

}
