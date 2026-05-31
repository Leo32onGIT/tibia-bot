package com.tibiabot

import akka.actor.{ActorSystem, Cancellable}
import akka.stream.ActorAttributes.supervisionStrategy
import akka.stream.scaladsl.{Flow, Keep, RunnableGraph, Sink, Source}
import akka.stream.{Attributes, Materializer, Supervision}
import com.tibiabot.BotApp.{alliedGuildsData, alliedPlayersData, discordsData, huntedGuildsData, huntedPlayersData, worldsData, activityData, customSortData, Players}
import com.tibiabot.tibiadata.{TibiaApi, TibiaDataClient}
import com.tibiabot.tibiadata.response.{CharacterResponse, Deaths, OnlinePlayers, WorldResponse}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.exceptions.ErrorHandler
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button

import java.time.ZonedDateTime
import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}
import java.time.OffsetDateTime
import java.time.{LocalTime, ZoneId}
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant

//noinspection FieldFromDelayedInit
class TibiaBot(world: String)(implicit system: ActorSystem, ex: ExecutionContextExecutor, mat: Materializer) extends StrictLogging {

  // A date-based "key" for a character, used to track recent deaths and recent online entries
  private case class CharKey(char: String, time: ZonedDateTime)
  private case class CharKeyBypass(char: String, level: Int, time: ZonedDateTime)
  private case class CharDeath(char: CharacterResponse, death: Deaths)
  private case class CharSort(guildName: String, allyGuild: Boolean, huntedGuild: Boolean, allyPlayer: Boolean, huntedPlayer: Boolean, vocation: String, level: Int, message: String)
  private case class OnlineListEntry(name: String, level: Int, lastUpdated: ZonedDateTime)

  private val recentDeaths = mutable.Set.empty[CharKey]
  private val levelTracker = new tracking.LevelTracker
  private val recentOnline = mutable.Set.empty[CharKey]
  private val recentOnlineBypass = mutable.Set.empty[CharKeyBypass]
  private val onlineTracker = new tracking.OnlineTracker
  val masspokeCooldowns = new ConcurrentHashMap[String, ZonedDateTime]()

  // Dedicated online list table for killer level lookups - updated every 5 minutes
  private val onlineListTable = mutable.Map.empty[String, OnlineListEntry]

  // initialize cached deaths/levels from database
  recentDeaths ++= BotApp.getDeathsCache(world).map(deathsCache => CharKey(deathsCache.name, ZonedDateTime.parse(deathsCache.time)))
  levelTracker.load(BotApp.getLevelsCache(world).map(levelsCache => tracking.LevelRecord(levelsCache.name, levelsCache.level.toInt, levelsCache.vocation, ZonedDateTime.parse(levelsCache.lastLogin), ZonedDateTime.parse(levelsCache.time))))

  private var onlineListTimer: Map[String, ZonedDateTime] = Map.empty
  private var onlineListCategoryTimer: Map[String, ZonedDateTime] = Map.empty
  private var cacheListTimer: Map[String, ZonedDateTime] = Map.empty
  private var alliesListPurgeTimer: Map[String, ZonedDateTime] = Map.empty
  private var enemiesListPurgeTimer: Map[String, ZonedDateTime] = Map.empty
  private var neutralsListPurgeTimer: Map[String, ZonedDateTime] = Map.empty
  private var onlineListTableUpdateTimer: ZonedDateTime = ZonedDateTime.now().minusMinutes(10) // Start immediately

  private val tibiaDataClient: TibiaApi =
    new tibiadata.CachingTibiaApi(new TibiaDataClient(), persistence.RedisCacheProvider.cache)(scala.concurrent.ExecutionContext.global)

  private val deathRecentDuration = 30 * 60 // 30 minutes for a death to count as recent enough to be worth notifying
  private val onlineRecentDuration = 10 * 60 // 10 minutes for a character to still be checked for deaths after logging off
  private val recentLevelExpiry = 25 * 60 * 60 // 25 hours before deleting recentLevel entry
  private val cooldowns = new ConcurrentHashMap[String, ZonedDateTime]()
  private val cooldownMinutes = 30L
  // Safety cap on the per-world outbound backlog (~drains 1 / message-delay-ms). Beyond
  // this the sender drops and logs rather than growing unbounded under a pathological burst.
  private val outboundQueueCapacity = 10000
  // Benign, operator-side send failures (channel deleted / perms removed) — ignore instead
  // of letting JDA's default handler spam them on every cycle; other errors still log.
  private val ignoreDeletedTarget = new ErrorHandler()
    .ignore(ErrorResponse.UNKNOWN_CHANNEL, ErrorResponse.MISSING_PERMISSIONS, ErrorResponse.MISSING_ACCESS)

  private val logAndResumeDecider: Supervision.Decider = { e =>
    logger.error("An exception has occurred in the TibiaBot:", e)
    Supervision.Resume
  }

  private val logAndResume: Attributes = supervisionStrategy(logAndResumeDecider)
  private lazy val sourceTick = Source.tick(2.seconds, 60.seconds, ())
  private lazy val getWorld = Flow[Unit].mapAsync(1) { _ =>
    logger.info(s"Running stream for world: '$world'")
    tibiaDataClient.getWorld(world) // Pull all online characters
  }.withAttributes(logAndResume)

  private lazy val getCharacterData = Flow[Either[String, WorldResponse]].mapAsync(1) {
    case Right(worldResponse) =>
      val now = ZonedDateTime.now()
      val online: List[OnlinePlayers] = worldResponse.world.online_players.getOrElse(List.empty[OnlinePlayers])

      // get online data with durations (carries over guild/duration/flag, drops log-offs)
      onlineTracker.updateFromOnline(online.map(player => (player.name, player.level.toInt, player.vocation)), now)
      val onlineWithVocLvlAndDuration = onlineTracker.snapshot

      // Update online list table every 5 minutes for killer level lookups
      if (now.isAfter(onlineListTableUpdateTimer.plusMinutes(5))) {
        logger.info(s"Updating online list table for world: $world with ${onlineWithVocLvlAndDuration.size} players")
        onlineListTable.clear()
        onlineWithVocLvlAndDuration.foreach { player =>
          onlineListTable.put(player.name.toLowerCase, OnlineListEntry(player.name, player.level, now))
        }
        onlineListTableUpdateTimer = now
        logger.info(s"Online list table updated with ${onlineListTable.size} entries")
      }

      // Remove existing online chars from the list...
      recentOnline.filterInPlace { i =>
        !online.exists(player => player.name == i.char)
      }
      recentOnline.addAll(online.map(player => CharKey(player.name, now)))

      // cache bypass for Seanera
      if (worldResponse.world.name == "Quidera" || worldResponse.world.name == "Runera") {
        // Remove existing online chars from the list...
        recentOnlineBypass.filterInPlace { i =>
          !online.exists(player => player.name == i.char)
        }
        recentOnlineBypass.addAll(online.map(player => CharKeyBypass(player.name, player.level.toInt, now)))
        val charsToCheck: Set[(String, Int)] = recentOnlineBypass.map { key =>
          (key.char, key.level.toInt)
        }.toSet
        Source(charsToCheck)
          .mapAsyncUnordered(32)(tibiaDataClient.getCharacterV2)
          .runWith(Sink.collection)
          .map(_.toSet)
      } else {
        val charsToCheck: Set[String] = recentOnline.map(_.char).toSet
        Source(charsToCheck)
          .mapAsyncUnordered(32)(tibiaDataClient.getCharacter)
          .runWith(Sink.collection)
          .map(_.toSet)
      }
    case Left(warning) =>
      if (world == "Quidera" || world == "Runera") {
        // use data from previous online list check
        val charsToCheck: Set[String] = recentOnlineBypass.map(_.char).toSet
        Source(charsToCheck)
          .mapAsyncUnordered(32)(tibiaDataClient.getCharacter)
          .runWith(Sink.collection)
          .map(_.toSet)
      } else {
        // use data from previous online list check
        val charsToCheck: Set[String] = recentOnline.map(_.char).toSet
        Source(charsToCheck)
          .mapAsyncUnordered(32)(tibiaDataClient.getCharacter)
          .runWith(Sink.collection)
          .map(_.toSet)
      }
  }.withAttributes(logAndResume)

  private lazy val scanForDeaths = Flow[Set[Either[String, CharacterResponse]]].mapAsync(1) { characterResponses =>
    val now = ZonedDateTime.now()

    // gather guild icons data for online player list
    val newDeaths = characterResponses.flatMap {
      case Right(char) =>
        val charName = char.character.character.name
        val guildName = char.character.character.guild.map(_.name).getOrElse("")

        val formerNamesList: List[String] = char.character.character.former_names.map(_.toList).getOrElse(Nil)

        // Caching attempt
        val cacheTimer = cacheListTimer.getOrElse(world, ZonedDateTime.parse("2022-01-01T01:00:00Z"))
        if (ZonedDateTime.now().isAfter(cacheTimer.plusMinutes(6))) {
          val cacheWorld = char.character.character.world
          val cacheFormerWorlds: List[String] = char.character.character.former_worlds.map(_.toList).getOrElse(Nil)
          BotApp.addListToCache(charName, formerNamesList, cacheWorld, cacheFormerWorlds, guildName, char.character.character.level.toInt.toString, char.character.character.vocation, char.character.character.last_login.getOrElse(""), ZonedDateTime.now())
          cacheListTimer = cacheListTimer + (world -> ZonedDateTime.now())
        }

        // update the guildIcon depending on the discord this would be posted to
        if (discordsData.contains(world)) {
          val discordsList = discordsData(world)
          discordsList.foreach { discords =>
            val guildId = discords.id
            val blocker = BotApp.activityCommandBlocker.getOrElse(guildId, false)
            val allyGuildCheck = alliedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == guildName.toLowerCase())
            val huntedGuildCheck = huntedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == guildName.toLowerCase())

            val guildAlliedPlayers: List[Players] = alliedPlayersData.getOrElse(guildId, List())
            val guildHuntedPlayers: List[Players] = huntedPlayersData.getOrElse(guildId, List())
            val allyPlayerCheck = guildAlliedPlayers.exists(player =>
              player.name.toLowerCase() == charName.toLowerCase() ||
              formerNamesList.exists(formerName => formerName.toLowerCase == player.name.toLowerCase())
            )
            val huntedPlayerCheck = guildHuntedPlayers.exists(player =>
              player.name.toLowerCase() == charName.toLowerCase() ||
              formerNamesList.exists(formerName => formerName.toLowerCase == player.name.toLowerCase())
            )

            // add guild to online list cache
            onlineTracker.setGuild(charName, guildName)

            // Activity channel
            if (!blocker) {
              val guild = BotApp.discordGateway.guildById(discords.id)
              val worldData = worldsData.getOrElse(guildId, List()).filter(w => w.name.toLowerCase() == world.toLowerCase())
              val activityChannel = worldData.headOption.map(_.activityChannel).getOrElse("0")
              val activityTextChannel = guild.getTextChannelById(activityChannel)
              val adminChannel = discords.adminChannel
              val charVocation = vocEmoji(char.character.character.vocation)
              val charLevel = char.character.character.level.toInt

              var skipJoinLeave = false
              var buggedName = false

              // Check formerNames
              var nameChangeCheck = false
              formerNamesList.foreach { formerName =>
                if (charName != "") {
                  // Hotfix for this:
                  // Unsure how this occurs, maybe namelock/manual cipsoft intervention
                  // Name:	         Trombadinha De Rua
                  // Former Names:	 Trombadinha De Rua
                  if (charName.toLowerCase == formerName.toLowerCase) {
                    buggedName = true
                  }
                  if (activityData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == formerName.toLowerCase())) {
                    nameChangeCheck = true
                  }
                }
              }

              // Player has changed their name
              if (nameChangeCheck && !buggedName) {
                var oldName = ""
                var timeDelay: Option[ZonedDateTime] = None
                val playerType = if (huntedPlayerCheck || huntedGuildCheck) 13773097 else if (allyPlayerCheck || allyGuildCheck) 36941 else 3092790
                // update activity cache
                val updatedActivityData = activityData.getOrElse(guildId, List()).map { activity =>
                  val updatedActivity = if (formerNamesList.exists(_.toLowerCase == activity.name.toLowerCase)) {
                    oldName = activity.name
                    timeDelay = Some(activity.updatedTime)
                    activity.copy(name = charName, formerNames = formerNamesList, updatedTime = ZonedDateTime.now())
                  } else {
                    activity
                  }
                  updatedActivity
                }
                if (oldName != ""){
                  // update name in cache and db
                  BotApp.modifyActivityData(m => m + (guildId -> updatedActivityData))
                  BotApp.updateActivityToDatabase(guild, oldName, formerNamesList, guildName, ZonedDateTime.now(), charName)
                  skipJoinLeave = true
                  if (timeDelay.isDefined) {
                    val delayEndTime = timeDelay.map(_.plusMinutes(6))
                    if (delayEndTime.exists(_.isBefore(ZonedDateTime.now()))) {
                      // if player is in hunted or allied 'players' list, update information there too
                      if (huntedPlayerCheck) {
                        // change name in hunted players cache and db
                        BotApp.updateHuntedOrAllyNameToDatabase(guild, "hunted", oldName, charName)
                        val updatedHuntedPlayersData = huntedPlayersData.getOrElse(guildId, List()).map { player =>
                          if (player.name.toLowerCase == oldName.toLowerCase) {
                            player.copy(name = charName.toLowerCase)
                          } else {
                            player
                          }
                        }
                        BotApp.modifyHuntedPlayersData(m => m + (guildId -> updatedHuntedPlayersData))
                      }
                      if (allyPlayerCheck) {
                        // change name in allied players cache and db
                        BotApp.updateHuntedOrAllyNameToDatabase(guild, "allied", oldName, charName)
                        val updatedAlliedPlayersData = alliedPlayersData.getOrElse(guildId, List()).map { player =>
                          if (player.name.toLowerCase == oldName.toLowerCase) {
                            player.copy(name = charName.toLowerCase)
                          } else {
                            player
                          }
                        }
                        BotApp.modifyAlliedPlayersData(m => m + (guildId -> updatedAlliedPlayersData))
                      }
                      if (activityTextChannel != null) {
                        if (activityTextChannel.canTalk() || (!Config.prod)) {
                          // send message to activity channel
                          val activityEmbed = new EmbedBuilder()
                          activityEmbed.setDescription(s"$charVocation **$charLevel** — **[$oldName](${charUrl(oldName)})** changed their name to **[$charName](${charUrl(charName)})**.")
                          activityEmbed.setColor(playerType)
                          activityEmbed.setThumbnail(Config.nameChangeThumbnail)
                          sendMessageWithRateLimit(activityTextChannel, embed = Some(activityEmbed))
                        }
                      }
                    }
                  }
                }
              }

              // Player hasn't changed their name
              if (!skipJoinLeave) {

                // Check charName
                val currentNameCheck = activityData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == charName.toLowerCase())

                // Did they just join one the tracked guilds?
                var joinGuild = false
                if (!currentNameCheck) {
                  if (allyGuildCheck || huntedGuildCheck) {
                    joinGuild = true
                  }
                }

                // Player is already tracked
                if (currentNameCheck) {
                  val matchingActivityOption = activityData.getOrElse(guildId, List()).find(_.name.toLowerCase == charName.toLowerCase())
                  val guildNameFromActivityData = matchingActivityOption.map(_.guild).getOrElse("")
                  val updatesTimeFromActivityData = matchingActivityOption.map(_.updatedTime).getOrElse(ZonedDateTime.parse("2022-01-01T01:00:00Z"))

                  if (updatesTimeFromActivityData.plusMinutes(6).isBefore(ZonedDateTime.now())) {

                    //charResponse.character.character.world
                    // Guild has changed
                    if (guildName != guildNameFromActivityData) {
                      val newGuildLess = if (guildName == "") true else false
                      val oldGuildLess = if (guildNameFromActivityData == "") true else false
                      val wasInHuntedGuild = huntedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == guildNameFromActivityData.toLowerCase())
                      val wasInAlliedGuild = alliedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == guildNameFromActivityData.toLowerCase())
                      // Left a tracked guild
                      if (wasInHuntedGuild || wasInAlliedGuild) {
                        val guildType = presentation.GuildActivity.guildType(wasInHuntedGuild, wasInAlliedGuild)
                        // No guild now
                        if (newGuildLess) {
                          // send message to activity channel
                          if (activityTextChannel != null) {
                            if (activityTextChannel.canTalk() || (!Config.prod)) {
                              val activityEmbed = new EmbedBuilder()
                              activityEmbed.setDescription(s"$charVocation **$charLevel** — **[$charName](${charUrl(charName)})** has left the **${guildType}** guild **[${guildNameFromActivityData}](${guildUrl(guildNameFromActivityData)})**.")
                              activityEmbed.setColor(14397256)
                              activityEmbed.setThumbnail(Config.guildLeaveThumbnail)
                              sendMessageWithRateLimit(activityTextChannel, embed = Some(activityEmbed))
                            }
                          }
                        } else { // Left a tracked guild, but joined a new one in the same turn
                          val colorType = presentation.GuildActivity.activityColor(huntedGuildCheck, allyGuildCheck)
                          // send message to activity channel
                          if (activityTextChannel != null) {
                            if (activityTextChannel.canTalk() || (!Config.prod)) {
                              val activityEmbed = new EmbedBuilder()
                              val thumbnailType = colorType match {
                                case 13773097 => Config.guildSwapRed
                                case 36941 => Config.guildSwapGreen
                                case _ => Config.guildSwapGrey
                              }
                              activityEmbed.setDescription(s"$charVocation **$charLevel** — **[$charName](${charUrl(charName)})** has left the **${guildType}** guild **[${guildNameFromActivityData}](${guildUrl(guildNameFromActivityData)})** and joined the guild **[${guildName}](${guildUrl(guildName)})**.")
                              activityEmbed.setColor(colorType)
                              activityEmbed.setThumbnail(thumbnailType)
                              sendMessageWithRateLimit(activityTextChannel, embed = Some(activityEmbed))
                            }
                          }
                          // remove from hunted list if in allied guild
                          if (allyGuildCheck) {
                            BotApp.modifyHuntedPlayersData(m => m.updated(guildId, m.getOrElse(guildId, List.empty).filterNot(_.name == charName)))
                            BotApp.removeHuntedFromDatabase(guild, "player", charName.toLowerCase())
                            val adminTextChannel = guild.getTextChannelById(adminChannel)
                            if (adminTextChannel != null) {
                              if (adminTextChannel.canTalk() || (!Config.prod)) {
                                // send embed to admin channel
                                val commandUser = s"<@${BotApp.botUser}>"
                                val adminEmbed = new EmbedBuilder()
                                adminEmbed.setTitle(":robot: enemy joined an allied guild:")
                                adminEmbed.setDescription(s"$commandUser removed the player\n$charVocation **$charLevel** — **[$charName](${charUrl(charName)})**\nfrom the hunted list for **$world**\n*(they left a hunted guild & joined an allied one)*.")
                                adminEmbed.setThumbnail(creatureImageUrl("Broom"))
                                adminEmbed.setColor(14397256) // orange for bot auto command
                                sendMessageWithRateLimit(adminTextChannel, embed = Some(adminEmbed), suppressNotifications = true)
                              }
                            }
                          }
                        }

                        // if he was in hunted guild add to hunted players list
                        if (wasInHuntedGuild) {
                          if (!allyGuildCheck && !huntedGuildCheck && !huntedPlayerCheck && !allyPlayerCheck) {
                            // add them to cached huntedPlayersData list
                            BotApp.modifyHuntedPlayersData(m => m + (guildId -> (BotApp.Players(charName.toLowerCase(), "false", s"was originally in hunted guild ${guildNameFromActivityData}", BotApp.botUser) :: m.getOrElse(guildId, List()))))
                            BotApp.addHuntedToDatabase(guild, "player", charName.toLowerCase(), "false", s"was originally in hunted guild ${guildNameFromActivityData}", BotApp.botUser)
                            val adminTextChannel = guild.getTextChannelById(adminChannel)
                            if (adminTextChannel != null) {
                              if (adminTextChannel.canTalk() || (!Config.prod)) {
                                // send embed to admin channel
                                val commandUser = s"<@${BotApp.botUser}>"
                                val adminEmbed = new EmbedBuilder()
                                adminEmbed.setTitle(":robot: enemy automatically detected:")
                                adminEmbed.setDescription(s"$commandUser added the player\n$charVocation **$charLevel** — **[$charName](${charUrl(charName)})**\nto the hunted list for **$world**\n*(they left a hunted guild, so they will remain hunted)*.")
                                adminEmbed.setThumbnail(creatureImageUrl("Stone_Coffin"))
                                adminEmbed.setColor(14397256) // orange for bot auto command
                                sendMessageWithRateLimit(adminTextChannel, embed = Some(adminEmbed), suppressNotifications = true)
                              }
                            }
                          }
                        } else if (wasInAlliedGuild){
                          if (!allyGuildCheck && !huntedGuildCheck && !huntedPlayerCheck && !allyPlayerCheck) {
                            // remove from activity
                            BotApp.modifyActivityData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(_.name.equalsIgnoreCase(charName.toLowerCase))))
                            BotApp.removePlayerActivityfromDatabase(guild, charName.toLowerCase)
                          }
                        }
                      }

                      if (huntedPlayerCheck && oldGuildLess) {
                        val colorType = presentation.GuildActivity.activityColor(huntedGuildCheck, allyGuildCheck)
                        val guildType = presentation.GuildActivity.guildType(huntedGuildCheck, allyGuildCheck)
                        // joined a hunted guild
                        if (huntedGuildCheck) {
                          // remove from hunted 'Player' cache and db
                          BotApp.modifyHuntedPlayersData(m => m.updated(guildId, m.getOrElse(guildId, List.empty).filterNot(_.name.toLowerCase == charName.toLowerCase)))
                          BotApp.removeHuntedFromDatabase(guild, "player", charName.toLowerCase())
                          // send message to admin channel
                          val adminTextChannel = guild.getTextChannelById(adminChannel)
                          if (adminTextChannel != null) {
                            if (adminTextChannel.canTalk() || (!Config.prod)) {
                              // send embed to admin channel
                              val commandUser = s"<@${BotApp.botUser}>"
                              val adminEmbed = new EmbedBuilder()
                              adminEmbed.setTitle(":robot: hunted list cleanup:")
                              adminEmbed.setDescription(s"$commandUser removed the player\n$charVocation **$charLevel** — **[$charName](${charUrl(charName)})**\nfrom the hunted list for **$world**\n*(because they have joined an enemy guild and will be tracked that way)*.")
                              adminEmbed.setThumbnail(creatureImageUrl("Broom"))
                              adminEmbed.setColor(14397256) // orange for bot auto command
                              sendMessageWithRateLimit(adminTextChannel, embed = Some(adminEmbed), suppressNotifications = true)
                            }
                          }
                        } else if (allyGuildCheck) {
                          // remove from hunted 'Player' cache and db
                          BotApp.modifyHuntedPlayersData(m => m.updated(guildId, m.getOrElse(guildId, List.empty).filterNot(_.name.toLowerCase == charName.toLowerCase)))
                          BotApp.removeHuntedFromDatabase(guild, "player", charName.toLowerCase())
                          // send message to admin channel
                          val adminTextChannel = guild.getTextChannelById(adminChannel)
                          if (adminTextChannel != null) {
                            if (adminTextChannel.canTalk() || (!Config.prod)) {
                              // send embed to admin channel
                              val commandUser = s"<@${BotApp.botUser}>"
                              val adminEmbed = new EmbedBuilder()
                              adminEmbed.setTitle(":robot: hunted list cleanup:")
                              adminEmbed.setDescription(s"$commandUser removed the player\n$charVocation **$charLevel** — **[$charName](${charUrl(charName)})**\nfrom the hunted list for **$world**\n*(because they have joined an allied guild and will be tracked that way)*.")
                              adminEmbed.setThumbnail(creatureImageUrl("Broom"))
                              adminEmbed.setColor(14397256) // orange for bot auto command
                              sendMessageWithRateLimit(adminTextChannel, embed = Some(adminEmbed), suppressNotifications = true)
                            }
                          }
                        }
                        // send message to activity channel
                        if (activityTextChannel != null) {
                          if (activityTextChannel.canTalk() || (!Config.prod)) {
                            val activityEmbed = new EmbedBuilder()
                            val thumbnailType = guildType match {
                              case "hunted" => Config.guildJoinRed
                              case "allied" => Config.guildJoinGreen
                              case _ => Config.guildJoinGrey
                            }
                            activityEmbed.setDescription(s"$charVocation **$charLevel** — **[$charName](${charUrl(charName)})** joined the **${guildType}** guild **[${guildName}](${guildUrl(guildName)})**.")
                            activityEmbed.setColor(colorType)
                            activityEmbed.setThumbnail(thumbnailType)
                            sendMessageWithRateLimit(activityTextChannel, embed = Some(activityEmbed))
                          }
                        }
                      }

                      val updatedActivityData = matchingActivityOption.map { activity =>
                        val updatedActivity = activity.copy(guild = guildName, updatedTime = ZonedDateTime.now())
                        activityData.getOrElse(guildId, List()).filterNot(_.name.toLowerCase == charName.toLowerCase) :+ updatedActivity
                      }.getOrElse(activityData.getOrElse(guildId, List()))

                      // Update in cache and db
                      BotApp.modifyActivityData(m => m + (guildId -> updatedActivityData))
                      BotApp.updateActivityToDatabase(guild, charName, formerNamesList, guildName, ZonedDateTime.now(), charName)
                    }
                  }
                } else if (joinGuild) { // Character doesn't exist in tracking_activity but should be
                  // add to cache and db
                  val newActivity = BotApp.PlayerCache(charName, formerNamesList, guildName, ZonedDateTime.now())
                  val updatedActivityData = newActivity :: activityData.getOrElse(guildId, List())
                  BotApp.modifyActivityData(m => m + (guildId -> updatedActivityData))
                  BotApp.addActivityToDatabase(guild, charName, formerNamesList, guildName, ZonedDateTime.now())
                  // joined a hunted guild
                  if (huntedGuildCheck) {
                    if (huntedPlayerCheck) { // was he originally in hunted 'player' list?
                      // remove from hunted 'Player' cache and db
                      BotApp.modifyHuntedPlayersData(m => m.updated(guildId, m.getOrElse(guildId, List.empty).filterNot(_.name.toLowerCase == charName.toLowerCase)))
                      BotApp.removeHuntedFromDatabase(guild, "player", charName.toLowerCase())
                      // send message to admin channel
                      val adminTextChannel = guild.getTextChannelById(adminChannel)
                      if (adminTextChannel != null) {
                        if (adminTextChannel.canTalk() || (!Config.prod)) {
                          // send embed to admin channel
                          val commandUser = s"<@${BotApp.botUser}>"
                          val adminEmbed = new EmbedBuilder()
                          adminEmbed.setTitle(":robot: hunted list cleanup:")
                          adminEmbed.setDescription(s"$commandUser removed the player\n$charVocation **$charLevel** — **[$charName](${charUrl(charName)})**\nfrom the hunted list for **$world**\n*(because they have joined an enemy guild and will be tracked that way)*.")
                          adminEmbed.setThumbnail(creatureImageUrl("Broom"))
                          adminEmbed.setColor(14397256) // orange for bot auto command
                          sendMessageWithRateLimit(adminTextChannel, embed = Some(adminEmbed), suppressNotifications = true)
                        }
                      }
                    }
                  } else if (allyGuildCheck) { // joined an allied guild
                    if (allyPlayerCheck) {
                      // remove from allied 'Player' cache and db
                      BotApp.modifyAlliedPlayersData(m => m.updated(guildId, m.getOrElse(guildId, List.empty).filterNot(_.name.toLowerCase == charName.toLowerCase)))
                      BotApp.removeAllyFromDatabase(guild, "player", charName.toLowerCase())
                      // send message to admin channel
                      val adminTextChannel = guild.getTextChannelById(adminChannel)
                      if (adminTextChannel != null) {
                        if (adminTextChannel.canTalk() || (!Config.prod)) {
                          // send embed to admin channel
                          val commandUser = s"<@${BotApp.botUser}>"
                          val adminEmbed = new EmbedBuilder()
                          adminEmbed.setTitle(":robot: allied list cleanup:")
                          adminEmbed.setDescription(s"$commandUser removed the player\n$charVocation **$charLevel** — **[$charName](${charUrl(charName)})**\nfrom the allied list for **$world**\n*(because they have joined an allied guild and will be tracked that way)*.")
                          adminEmbed.setThumbnail(creatureImageUrl("Broom"))
                          adminEmbed.setColor(14397256) // orange for bot auto command
                          sendMessageWithRateLimit(adminTextChannel, embed = Some(adminEmbed), suppressNotifications = true)
                        }
                      }
                    }
                  }
                  val guildType = presentation.GuildActivity.guildType(huntedGuildCheck, allyGuildCheck)
                  val colorType = presentation.GuildActivity.activityColor(huntedGuildCheck, allyGuildCheck)
                  if (guildType != "neutral") { // ignore neutral guild changes, only show hunted/allied rejoins
                    if (activityTextChannel != null) {
                      if (activityTextChannel.canTalk() || (!Config.prod)) {
                        val activityEmbed = new EmbedBuilder()
                        val thumbnailType = guildType match {
                          case "hunted" => Config.guildJoinRed
                          case "allied" => Config.guildJoinGreen
                          case _ => Config.guildJoinGrey
                        }
                        activityEmbed.setDescription(s"$charVocation **$charLevel** — **[$charName](${charUrl(charName)})** joined the **${guildType}** guild **[${guildName}](${guildUrl(guildName)})**.")
                        activityEmbed.setColor(colorType)
                        activityEmbed.setThumbnail(thumbnailType)
                        sendMessageWithRateLimit(activityTextChannel, embed = Some(activityEmbed))
                      }
                    }
                  }
                }

              }
              // end name change check
            }
          }
        }
        // detecting new levels
        val deaths: List[Deaths] = char.character.deaths.getOrElse(List.empty)
        val sheetLevel = char.character.character.level
        val sheetVocation = char.character.character.vocation
        val sheetLastLogin = ZonedDateTime.parse(char.character.character.last_login.getOrElse("2022-01-01T01:00:00Z"))
        var recentlyDied = false
        if (deaths.nonEmpty) {
          val mostRecentDeath = deaths.maxBy(death => ZonedDateTime.parse(death.time))
          val mostRecentDeathTime = ZonedDateTime.parse(mostRecentDeath.time)
          val mostRecentDeathAge = java.time.Duration.between(mostRecentDeathTime, now).getSeconds
          if (mostRecentDeathAge <= 600) {
            recentlyDied = true
          }
        }
        if (!recentlyDied) {
          onlineTracker.find(charName).foreach { onlinePlayer =>
            // level (i need to add logic here to batch messages control throughput a bit)
            if (onlinePlayer.level > sheetLevel) {
              val newLevelRecord = tracking.LevelRecord(charName, onlinePlayer.level, sheetVocation, sheetLastLogin, now)
              // post level to each discord
              if (discordsData.contains(world)) {
                val discordsList = discordsData(world)
                discordsList.foreach { discords =>
                  val guild = BotApp.discordGateway.guildById(discords.id)
                  val guildId = discords.id

                  // get appropriate guildIcon
                  val allyGuildCheck = alliedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == guildName.toLowerCase())
                  val huntedGuildCheck = huntedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == guildName.toLowerCase())
                  val allyPlayerCheck = alliedPlayersData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == charName.toLowerCase())
                  val huntedPlayerCheck = huntedPlayersData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == charName.toLowerCase())
                  val guildIcon = presentation.GuildIcons.guildIcon(guildName, allyGuildCheck, huntedGuildCheck, allyPlayerCheck, huntedPlayerCheck)
                  val worldData = worldsData.getOrElse(guildId, List()).filter(w => w.name.toLowerCase() == world.toLowerCase())
                  val levelsChannel = worldData.headOption.map(_.levelsChannel).getOrElse("0")
                  val webhookMessage = s"${vocEmoji(onlinePlayer.vocation)} **[$charName](${charUrl(charName)})** advanced to level **${onlinePlayer.level}** $guildIcon"
                  val levelsTextChannel = guild.getTextChannelById(levelsChannel)
                  if (levelsTextChannel != null) {
                    if (levelsTextChannel.canTalk() || (!Config.prod)) {
                      // check show_neutrals_levels setting
                      val showNeutralLevels = worldData.headOption.map(_.showNeutralLevels).getOrElse("true")
                      val showAlliesLevels = worldData.headOption.map(_.showAlliesLevels).getOrElse("true")
                      val showEnemiesLevels = worldData.headOption.map(_.showEnemiesLevels).getOrElse("true")
                      val minimumLevel = worldData.headOption.map(_.levelsMin).getOrElse(8)
                      val enemyIcons = List(Config.enemy, Config.enemyGuild, s"${Config.otherGuild}${Config.enemy}")
                      val alliesIcons = List(Config.allyGuild, Config.ally, s"${Config.otherGuild}${Config.ally}")
                      val neutralIcons = List(Config.otherGuild, "")
                      // suppress the level-up for a category whose show-flag is off, or below the minimum level
                      val levelsCheck = presentation.LevelVisibility.shouldPost(
                        neutralIcons.contains(guildIcon), alliesIcons.contains(guildIcon), enemyIcons.contains(guildIcon),
                        showNeutralLevels, showAlliesLevels, showEnemiesLevels, onlinePlayer.level, minimumLevel)
                      if (levelTracker.shouldRecord(charName, onlinePlayer.level, sheetLastLogin)) {
                        if (levelsCheck) {
                          sendMessageWithRateLimit(levelsTextChannel, message = webhookMessage)
                        }
                      }
                    }
                  }
                }
              }
              // add flag to onlineList if player has leveled
              onlineTracker.setFlag(charName, Config.levelUpEmoji)
              if (levelTracker.shouldRecord(charName, onlinePlayer.level, sheetLastLogin)) {
                levelTracker.record(newLevelRecord)
                BotApp.addLevelsCache(world, charName, onlinePlayer.level.toString, sheetVocation, sheetLastLogin.toString, now.toString)
              }
            }
          }
        }
        // parsing death info
        deaths.flatMap { death =>
          val deathTime = ZonedDateTime.parse(death.time)
          val deathAge = java.time.Duration.between(deathTime, now).getSeconds
          val charDeath = CharKey(char.character.character.name, deathTime)
          if (deathAge < deathRecentDuration && !recentDeaths.contains(charDeath)) {
            recentDeaths.add(charDeath)
            BotApp.addDeathsCache(world, char.character.character.name, deathTime.toString)
            Some(CharDeath(char, death))
          }
          else None
        }
      case Left(errorMessage) => None
    }
    // update online lists
    if (discordsData.contains(world)) {
      val discordsList = discordsData(world)
      discordsList.foreach { discords =>
        val guildId = discords.id
        val worldData = worldsData.getOrElse(guildId, List()).filter(w => w.name.toLowerCase() == world.toLowerCase())
        // update online list every 5 minutes
        val onlineTimer = onlineListTimer.getOrElse(guildId, ZonedDateTime.parse("2022-01-01T01:00:00Z"))
        if (ZonedDateTime.now().isAfter(onlineTimer.plusSeconds(90))) {
          // did the online list api call fail?
          val alliesChannel = worldData.headOption.map(_.alliesChannel).getOrElse("0")
          val neutralsChannel = worldData.headOption.map(_.neutralsChannel).getOrElse("0")
          val enemiesChannel = worldData.headOption.map(_.enemiesChannel).getOrElse("0")
          val categoryChannel = worldData.headOption.map(_.category).getOrElse("0")
          val onlineCombinedOption = worldData.headOption.map(_.onlineCombined).getOrElse("false")
          onlineListTimer = onlineListTimer + (guildId -> ZonedDateTime.now())
          onlineList(onlineTracker.snapshot, guildId, alliesChannel, neutralsChannel, enemiesChannel, categoryChannel, onlineCombinedOption, world)
        }
      }
    }

    Future.successful(newDeaths)
  }.withAttributes(logAndResume)

  private lazy val postToDiscordAndCleanUp = Flow[Set[CharDeath]].mapAsync(1) { charDeaths =>
    // post death to each discord
    if (discordsData.contains(world)) {
      val discordsList = discordsData(world)
      discordsList.foreach { discords =>
        val guild = BotApp.discordGateway.guildById(discords.id)
        val guildId = discords.id
        val adminChannel = discords.adminChannel
        val worldData = worldsData.getOrElse(guildId, List()).filter(w => w.name.toLowerCase() == world.toLowerCase())
        val deathsChannel = worldData.headOption.map(_.deathsChannel).getOrElse("0")
        val nemesisRole = worldData.headOption.map(_.nemesisRole).getOrElse("0")
        val fullblessRole = worldData.headOption.map(_.fullblessRole).getOrElse("0")
        val allyHelpRole = worldData.headOption.map(_.allyPkRole).getOrElse("0")
        val exivaListCheck = worldData.headOption.map(_.exivaList).getOrElse("true")
        val deathsTextChannel = guild.getTextChannelById(deathsChannel)
        /**
        val activityChannel = worldData.headOption.map(_.activityChannel).getOrElse("0")
        val activityTextChannel = guild.getTextChannelById(activityChannel)
        if (activityTextChannel != null) {

        }
        **/
        if (deathsTextChannel != null) {
          if (deathsTextChannel.canTalk() || (!Config.prod)) {
            val embeds = charDeaths.toList.sortBy(_.death.time).map { charDeath =>
              var notablePoke = ""
              val charName = charDeath.char.character.character.name
              val killer = charDeath.death.killers.last.name
              var context = "Died"
              var embedColor = 3092790 // background default
              var embedThumbnail = presentation.DeathEffect.thumbnail(killer).getOrElse(creatureImageUrl(killer))
              var vowelCheck = "" // this is for adding "an" or "a" in front of creature names
              val killerBuffer = ListBuffer[String]()
              val exivaBuffer = ListBuffer[String]()
              var exivaList = ""
              val killerList = charDeath.death.killers // get all killers

              // guild rank and name
              val guildName = charDeath.char.character.character.guild.map(_.name).getOrElse("")
              val guildRank = charDeath.char.character.character.guild.map(_.rank).getOrElse("")
              var guildText = ""

              // guild
              // does player have guild?
              var guildIcon = Config.otherGuild
              var huntedGuilds = false
              var allyGuilds = false
              if (guildName != "") {
                // if untracked neutral guild show grey
                if (embedColor == 3092790) {
                  embedColor = 4540237
                }
                val customSortGuildCheck = customSortData.getOrElse(guildId, List()).exists(g => g.entityType == "guild" && g.name.toLowerCase == guildName.toLowerCase)
                if (customSortGuildCheck) {
                  embedColor = 14397256 // yellow
                }
                // is player an ally
                allyGuilds = alliedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == guildName.toLowerCase())
                if (allyGuilds) {
                  embedColor = 13773097 // bright red
                  guildIcon = Config.allyGuild
                }
                // is player in hunted guild
                huntedGuilds = huntedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == guildName.toLowerCase())
                if (huntedGuilds) {
                  embedColor = 36941 // bright green
                  if (context == "Died") {
                    notablePoke = "fullbless" // PVE fullbless opportuniy (only poke for level 400+)
                  }
                }
                guildText = s"$guildIcon *$guildRank* of the [$guildName](${guildUrl(guildName)})\n"
              }

              // player
              val customSortPlayerCheck = customSortData.getOrElse(guildId, List()).exists(g => g.entityType == "player" && g.name.toLowerCase == charName.toLowerCase)
              if (customSortPlayerCheck) {
                embedColor = 14397256 // yellow
              }
              // ally player
              val allyPlayers = alliedPlayersData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == charName.toLowerCase())
              if (allyPlayers) {
                embedColor = 13773097 // bright red
              }
              // hunted player
              val huntedPlayers = huntedPlayersData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == charName.toLowerCase())
              if (huntedPlayers) {
                embedColor = 36941 // bright green
                if (context == "Died") {
                  notablePoke = "fullbless" // PVE fullbless opportuniy
                }
              }

              // poke if killer is in notable-creatures config
              val poke = Config.notableCreatures.contains(killer.toLowerCase())
              if (poke) {
                notablePoke = "nemesis"
                embedColor = 11563775 // bright purple
              }

              if (killerList.nonEmpty) {
                killerList.foreach { k =>
                  if (k.player) {
                    if (k.name != charName) { // ignore 'self' entries on deathlist
                      context = "Killed"
                      if (allyPlayers || allyGuilds) {
                        notablePoke = "allypk"
                      } else if (huntedPlayers || huntedGuilds) {
                        notablePoke = "screenshot"
                      } else {
                        notablePoke = "" // reset poke as its not a fullbless
                      }
                      if (embedColor == 3092790 || embedColor == 4540237) {
                        embedColor = 14869218 // bone white
                      }
                      embedThumbnail = presentation.DeathEffect.pvp
                      domain.Killers.parseSummon(k.name) match {
                        case Some((creature, summoner)) => // e.g: fire elemental of Violent Beams
                          val vowel = domain.Killers.article(creature)
                          val summonerLevelText = getKillerLevel(summoner).map(level => s" [$level]").getOrElse("")
                          killerBuffer += s"$vowel ${Config.summonEmoji} **$creature of [$summoner$summonerLevelText](${charUrl(summoner)})**"
                          if (embedColor == 13773097) {
                            if (exivaListCheck == "true") {
                              exivaBuffer += summoner
                            }
                          }
                        case None => // a player (incl. names with " of " like "Knight of Flame") or an undetected summon
                          val levelText = getKillerLevel(k.name).map(level => s" [$level]").getOrElse("")
                          killerBuffer += s"**[${k.name}$levelText](${charUrl(k.name)})**"
                          if (embedColor == 13773097) {
                            if (exivaListCheck == "true") {
                              exivaBuffer += k.name
                            }
                          }
                      }
                    }
                  } else {
                    // map boss lists to their respective emojis (built once in BossEmoji)
                    val bossIcon = presentation.BossEmoji.of(k.name)

                    // add "an" or "a" depending on first letter of creatures name
                    // ignore capitalized names (nouns) as they are bosses
                    // if player dies to a neutral source show 'died by energy' instead of 'died by an energy'
                    if (!k.name.exists(_.isUpper)) {
                      vowelCheck = domain.Killers.sourceArticle(k.name)
                    }
                    killerBuffer += s"$vowelCheck$bossIcon**${k.name}**"
                  }
                }
              }

              if (exivaBuffer.nonEmpty) {
                exivaBuffer.zipWithIndex.foreach { case (exiva, i) =>
                  if (i == 0) {
                    exivaList += s"""\n${Config.exivaEmoji} `exiva "$exiva"`""" // add exiva emoji
                  } else {
                    exivaList += s"""\n${Config.indentEmoji} `exiva "$exiva"`""" // just use indent emoji for further player names
                  }
                }

                // see if detectHunted is toggled on or off
                val detectHunteds = worldData.headOption.map(_.detectHunteds).getOrElse("on")
                if (detectHunteds == "on") {
                  // scan exiva list for enemies to be added to hunted
                  val exivaBufferFlow = Source(exivaBuffer.toSet).mapAsyncUnordered(16)(tibiaDataClient.getCharacter).toMat(Sink.seq)(Keep.right)
                  val futureResults: Future[Seq[Either[String, CharacterResponse]]] = exivaBufferFlow.run()
                  futureResults.onComplete {
                    case Success(output) =>
                      val huntedBuffer = ListBuffer[(String, String, String, Int)]()
                      output.foreach {
                        case Right(charResponse) =>
                          val killerName = charResponse.character.character.name
                          val killerGuild = charResponse.character.character.guild
                          val killerWorld = charResponse.character.character.world
                          val killerVocation = vocEmoji(charResponse.character.character.vocation)
                          val killerLevel = charResponse.character.character.level.toInt
                          val killerGuildName = if(killerGuild.isDefined) killerGuild.head.name else ""
                          var guildCheck = true
                          if (killerGuildName != "") {
                            if (alliedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == killerGuildName.toLowerCase()) || huntedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == killerGuildName.toLowerCase())) {
                              guildCheck = false // player guild is already ally/hunted
                            }
                          }
                          if (guildCheck) { // player is not in a guild or is in a guild that is not tracked
                            if (alliedPlayersData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == killerName.toLowerCase()) || huntedPlayersData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == killerName.toLowerCase())) {
                              // char is already on ally/hunted lis
                            } else {
                              // char is not on hunted list
                              if (!huntedBuffer.exists(_._1.toLowerCase == killerName.toLowerCase)) {
                                // add them to hunted list
                                huntedBuffer += ((killerName, killerWorld, killerVocation, killerLevel))
                              }
                            }
                          }
                        case Left(errorMessage) => // do nothing
                      }

                      // process the new batch of players to add to hunted list
                      if (huntedBuffer.nonEmpty) {
                        val adminTextChannel = guild.getTextChannelById(adminChannel)
                        if (adminTextChannel != null) {
                          huntedBuffer.foreach { case (player, world, vocation, level) =>
                            val playerString = player.toLowerCase()
                            // add them to cached huntedPlayersData list
                            BotApp.modifyHuntedPlayersData(m => m + (guildId -> (BotApp.Players(playerString, "false", "killed an allied player", BotApp.botUser) :: m.getOrElse(guildId, List()))))
                            // add them to the database
                            BotApp.addHuntedToDatabase(guild, "player", playerString, "false", "killed an allied player", BotApp.botUser)
                            // send embed to admin channel
                            val commandUser = s"<@${BotApp.botUser}>"
                            val adminEmbed = new EmbedBuilder()
                            adminEmbed.setTitle(":robot: enemy automatically detected:")
                            adminEmbed.setDescription(s"$commandUser added the player\n$vocation **$level** — **[$player](${charUrl(player)})**\nto the hunted list for **$world**\n*(they killed the allied player **[${charName}](${charUrl(charName)})***.")
                            adminEmbed.setThumbnail(creatureImageUrl("Dark_Mage_Statue"))
                            adminEmbed.setColor(14397256) // orange for bot auto command
                            sendMessageWithRateLimit(adminTextChannel, embed = Some(adminEmbed), suppressNotifications = true)
                          }
                        }
                      }
                    case Failure(exception) =>
                      logger.warn(s"Failed to scan the exiva list for auto-hunt detection on world '$world': ${exception.getMessage}")
                  }
                }
              }

              // convert formatted killer list to one string ("a, b and c")
              var killerText = domain.Killers.joinNatural(killerBuffer.toSeq)

              // this should only occur to pure suicides on bomb runes, or pure 'assists' deaths in yellow-skull friendy fire or retro/hardcore situations
              if (killerText == "") {
                  embedThumbnail = presentation.DeathEffect.suicide
                  killerText = s"""`suicide`"""
              }

              val epochSecond = ZonedDateTime.parse(charDeath.death.time).toEpochSecond

              // this is the actual embed description
              var embedText = s"$guildText$context <t:$epochSecond:R> at level ${charDeath.death.level.toInt}\nby $killerText.$exivaList"

              // if the length is over 4065 truncate it
              val embedLength = embedText.length
              val limit = 4065
              if (embedLength > limit) {
                val newlineIndex = embedText.lastIndexOf('\n', limit)
                embedText = embedText.substring(0, newlineIndex) + "\n:scissors: `out of space`"
              }

              val showNeutralDeaths = worldData.headOption.map(_.showNeutralDeaths).getOrElse("true")
              val showAlliesDeaths = worldData.headOption.map(_.showAlliesDeaths).getOrElse("true")
              val showEnemiesDeaths = worldData.headOption.map(_.showEnemiesDeaths).getOrElse("true")
              val embedCheck = presentation.DeathEmbeds.shouldShow(embedColor, showNeutralDeaths, showAlliesDeaths, showEnemiesDeaths)
              val embed = presentation.DeathEmbeds.build(charName, charDeath.char.character.character.vocation, embedText, embedThumbnail, embedColor)

              // return embed + poke
              (embed, notablePoke, charName, embedText, charDeath.death.level.toInt, embedCheck, epochSecond)
            }
            val fullblessLevel = worldData.headOption.map(_.fullblessLevel).getOrElse(250)
            val minimumLevel = worldData.headOption.map(_.deathsMin).getOrElse(8)
            // Process embeds with rate limiting
            val validEmbeds = embeds.filter(_._6) // Filter only valid embeds
            validEmbeds.grouped(Config.batchSize).zipWithIndex.foreach { case (batch, batchIndex) =>
              batch.zipWithIndex.foreach { case (embed, indexInBatch) =>
                try {
                  // Calculate delay for this message
                  val messageDelay = (batchIndex * Config.batchSize + indexInBatch) * Config.messageDelayMs
                  val additionalBatchDelay = batchIndex * Config.batchDelayMs
                  val totalDelay = messageDelay + additionalBatchDelay

                  // Schedule the message with delay
                  mat.system.scheduler.scheduleOnce(totalDelay.milliseconds) {
                    // Create screenshot button
                    val screenshotButton = Button.secondary(
                      s"death_screenshot_${embed._3}_${embed._7}_placeholder",
                      "Add Screenshot"
                    )
                    val actionRow = ActionRow.of(screenshotButton)

                    // nemesis and enemy fullbless ignore the level filter
                    if (embed._2 == "nemesis") {
                      if (guild.getRoleById(nemesisRole) != null) {
                        deathsTextChannel.sendMessage(s"<@&$nemesisRole>")
                          .setEmbeds(embed._1.build())
                          .queue()
                      } else {
                        deathsTextChannel.sendMessageEmbeds(embed._1.build())
                          .queue()
                      }
                      // WIP PVP COOLDOWN
                    } else if (embed._2 == "allypk") {
                      val shouldPing = guild.getRoleById(allyHelpRole) != null && canPing(deathsTextChannel.getId)
                      if (shouldPing) {
                        deathsTextChannel.sendMessage(s"<@&$allyHelpRole>")
                          .setEmbeds(embed._1.build())
                          .queue()
                      } else {
                        deathsTextChannel.sendMessageEmbeds(embed._1.build())
                          .queue()
                      }
                    } else if (embed._2 == "fullbless") {
                      // send adjusted embed for fullblesses
                      val adjustedMessage = embed._4 + s"""\n${Config.exivaEmoji} `exiva "${embed._3}"`"""
                      val adjustedEmbed = embed._1.setDescription(adjustedMessage)
                      if (embed._5 >= fullblessLevel && guild.getRoleById(fullblessRole) != null) { // only poke for 250+
                        deathsTextChannel.sendMessage(s"<@&$fullblessRole>")
                          .setEmbeds(adjustedEmbed.build())
                          .queue()
                      } else {
                        deathsTextChannel.sendMessageEmbeds(adjustedEmbed.build())
                          .queue()
                      }
                    } else if (embed._2 == "screenshot") {
                      deathsTextChannel.sendMessageEmbeds(embed._1.build())
                        .setComponents(actionRow)
                        .queue()
                    } else {
                      // for regular deaths check if level > /filter deaths <level>
                      if (embed._5 >= minimumLevel) {
                        deathsTextChannel.sendMessageEmbeds(embed._1.build())
                          .setSuppressedNotifications(true)
                          .queue()
                      }
                    }
                  }
                } catch {
                  case ex: Exception => logger.error(s"Failed to send message to 'deaths' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}': ${ex.getMessage}")
                  case _: Throwable => logger.error(s"Failed to send message to 'deaths' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}'")
                }
              }
            }
          }
        }
      }
    }

    cleanUp()

    Future.successful(())
  }.withAttributes(logAndResume)

  private def onlineList(onlineData: List[tracking.OnlinePlayer], guildId: String, alliesChannel: String, neutralsChannel: String, enemiesChannel: String, categoryChannel: String, onlineCombined: String, world: String): Unit = {

    val vocationBuffers = ListMap(
      domain.Vocations.displayOrder.map(_ -> ListBuffer[CharSort]()): _*
    )

    val sortedList = onlineData.sortWith(_.level > _.level)
    var zapCount = 0
    sortedList.foreach { player =>
      val voc = player.vocation.toLowerCase.split(' ').last
      val vocationEmoji = vocEmoji(voc)
      val durationInSec = player.duration
      val durationString = presentation.OnlineListEmbeds.durationString(durationInSec)
      val allyGuildCheck = alliedGuildsData.getOrElse(guildId, List())
        .exists(_.name.equalsIgnoreCase(player.guildName))
      val huntedGuildCheck = huntedGuildsData.getOrElse(guildId, List())
        .exists(_.name.equalsIgnoreCase(player.guildName))
      val allyPlayerCheck = alliedPlayersData.getOrElse(guildId, List())
        .exists(_.name.equalsIgnoreCase(player.name))
      val huntedPlayerCheck = huntedPlayersData.getOrElse(guildId, List())
        .exists(_.name.equalsIgnoreCase(player.name))
      val guildIcon = presentation.GuildIcons.guildIcon(player.guildName, allyGuildCheck, huntedGuildCheck, allyPlayerCheck, huntedPlayerCheck)

      // Masslog: only shows characters :zap: if they have only been logged in under 900 seconds (15 minutes)
      val justLogged = durationInSec < 900 && (huntedGuildCheck || huntedPlayerCheck)
      val masslogIcon = if (justLogged) " :zap:" else if (durationInSec > 18000 && (huntedGuildCheck || huntedPlayerCheck)) " :zzz:" else ""
      if (justLogged) zapCount += 1
      vocationBuffers(voc) += CharSort(player.guildName,allyGuildCheck,huntedGuildCheck,allyPlayerCheck,huntedPlayerCheck,voc,player.level.toInt,s"$vocationEmoji **${player.level}** — **[${player.name}](${charUrl(player.name)})** $guildIcon $durationString ${player.flag}${masslogIcon}"
      )
    }

    // run channel checks before updating the channels
    val guild = BotApp.discordGateway.guildById(guildId)

    // default online list
    val alliesList: List[String] = vocationBuffers.values
      .flatMap(_.filter(charSort => charSort.allyPlayer || charSort.allyGuild))
      .map(_.message)
      .toList
    val enemiesList: List[String] = vocationBuffers.values
      .flatMap(_.filter(charSort => charSort.huntedPlayer || charSort.huntedGuild))
      .map(_.message)
      .toList
    val neutralsList: List[String] = vocationBuffers.values
      .flatMap(_.filter(charSort => !charSort.huntedPlayer && !charSort.huntedGuild && !charSort.allyPlayer && !charSort.allyGuild))
      .map(_.message)
      .toList

    // Masslog mention
    val worldData = worldsData.getOrElse(guildId, List()).filter(w => w.name.toLowerCase() == world.toLowerCase())
    val activityChannel = worldData.headOption.map(_.activityChannel).getOrElse("0")
    val channelId = activityChannel
    val lastPokedAt = masspokeCooldowns.get(channelId)

    // Masslog cooldown
    val berlinNow = ZonedDateTime.now(ZoneId.of("Europe/Berlin")).toLocalTime
    val serverSaveCooldown = !berlinNow.isBefore(LocalTime.of(10, 0)) && berlinNow.isBefore(LocalTime.of(10, 45))
    val now = ZonedDateTime.now()
    val cutoff = now.minusMinutes(30)
    val recentStart = BotApp.startTime.atZone(now.getZone).isAfter(cutoff)
    val recentPoke  = Option(lastPokedAt).exists(_.isAfter(cutoff))
    val isOnCooldown = recentStart || recentPoke || serverSaveCooldown

    // Masslog formula
    val enemyCount = enemiesList.size

    // Masslog threshold (sensitivity fixed at 0 today; formula in tracking.MasslogDetector)
    val masslogCategory = tracking.MasslogDetector.isMasslog(zapCount, enemyCount, sensitivity = 0)
    if (masslogCategory && !isOnCooldown) {
      // get Activity channel
      val activityTextChannel = guild.getTextChannelById(activityChannel)
      if (activityTextChannel != null) {
        if (activityTextChannel.canTalk()) {
          val activityEmbed = new EmbedBuilder()
          activityEmbed.setDescription(s":zap: **${zapCount} enemies** have logged in recently.")
          activityEmbed.setColor(14397256)
          //activityEmbed.setThumbnail(
          //  "https://raw.githubusercontent.com/Leo32onGIT/tibia-bot-resources/main/masslogthumbnail.png"
          //)
          //)
          val masslogRole = worldData.headOption.map(_.masslogRole).getOrElse("0")
          if (masslogRole == "0") {
            sendMessageWithRateLimit(
              activityTextChannel,
              embed = Some(activityEmbed)
            )
            masspokeCooldowns.put(channelId, now)
          } else {
            sendMessageWithRateLimit(
              activityTextChannel,
              message = s"<@&${masslogRole}>",
              embed = Some(activityEmbed)
            )
            masspokeCooldowns.put(channelId, now)
          }
        }
      }
    }

    // combined online list into one channel
    if (onlineCombined == "true") {
      val combinedTextChannel = guild.getTextChannelById(alliesChannel)
      if (combinedTextChannel != null) {
        if (combinedTextChannel.canTalk() || (!Config.prod)) {

          // neutrals grouped by Guild
          val guildNameCounts: Map[String, Int] = vocationBuffers.values
            .flatMap(_.map(_.guildName))
            .groupBy(identity)
            .view.mapValues(_.size)
            .toMap

          val updatedVocationBuffers = vocationBuffers.view.mapValues { charSorts =>
            val updatedCharSorts = charSorts.map { charSort =>
              if (charSort.guildName != "" && guildNameCounts.getOrElse(charSort.guildName, 0) < 3) {
                charSort.copy(guildName = "")
              } else {
                charSort
              }
            }
            updatedCharSorts
          }

          val neutralsGroupedByGuild: List[(String, List[String])] = presentation.OnlineListGrouping.groupByGuild(
            updatedVocationBuffers.values.flatten
              .filter(charSort => !charSort.huntedPlayer && !charSort.huntedGuild && !charSort.allyPlayer && !charSort.allyGuild)
              .map(charSort => charSort.guildName -> charSort.message))

          val flattenedNeutralsList: List[String] =
            presentation.OnlineListGrouping.withHeaders(neutralsGroupedByGuild, n => s"### Others $n")

          val totalCount = alliesList.size + neutralsList.size + enemiesList.size

          val combinedList = presentation.OnlineListGrouping.combinedChannelBody(
            alliesList, enemiesList, neutralsList, flattenedNeutralsList, Config.ally, Config.enemy)

          // allow for custom channel names
          val channelName = combinedTextChannel.getName
          val customName = presentation.OnlineListEmbeds.baseName(channelName, "online")
          renameOnlineChannelIfDue(combinedTextChannel, s"$customName-$totalCount", "online list channel", guildId, guild.getName)

          if (combinedList.nonEmpty) {
            updateMultiFields(combinedList, combinedTextChannel, "allies", guildId, guild.getName)
          } else {
            updateMultiFields(List("*Nobody is online right now.*"), combinedTextChannel, "allies", guildId, guild.getName)
          }
        }
      }
      val neutralsTextChannel = guild.getTextChannelById(neutralsChannel)
      if (neutralsTextChannel != null) {
        if (neutralsTextChannel.canTalk() || (!Config.prod)) {
          // allow for custom channel names
          val channelName = neutralsTextChannel.getName
          val customName = presentation.OnlineListEmbeds.baseName(channelName, "neutrals")
          renameOnlineChannelIfDue(neutralsTextChannel, s"$customName-0", "disabled neutral channel", guildId, guild.getName)
          // placeholder message
          updateMultiFields(List("*This channel is `disabled` and can be deleted.*"), neutralsTextChannel, "neutrals", guildId, guild.getName)
        }
      }
      val enemiesTextChannel = guild.getTextChannelById(enemiesChannel)
      if (enemiesTextChannel != null) {
        if (enemiesTextChannel.canTalk() || (!Config.prod)) {
          // allow for custom channel names
          val channelName = enemiesTextChannel.getName
          val customName = presentation.OnlineListEmbeds.baseName(channelName, "enemies")
          renameOnlineChannelIfDue(enemiesTextChannel, s"$customName-0", "disabled enemies channel", guildId, guild.getName)
          // placeholder message
          updateMultiFields(List("*This channel is `disabled` and can be deleted.*"), enemiesTextChannel, "enemies", guildId, guild.getName)
        }
      }

      // add allies/enemies count to the category
      val now = Instant.now()
      val cutoff = now.minusSeconds(30 * 60)
      val recentStart = BotApp.startTime.isAfter(cutoff)
      val masslogIcon = if (masslogCategory && !recentStart) s"⚡" else ""
      renameOnlineCategoryIfDue(guild, categoryChannel, world, alliesList.size, enemiesList.size, masslogIcon)
    } else {
      // separated online list channels
      val alliesCount = alliesList.size
      val neutralsCount = neutralsList.size
      val enemiesCount = enemiesList.size
      val now = Instant.now()
      val cutoff = now.minusSeconds(30 * 60)
      val recentStart = BotApp.startTime.isAfter(cutoff)
      val masslogIcon = if (masslogCategory && !recentStart) s"⚡" else ""

      // add allies/enemies count to the category
      renameOnlineCategoryIfDue(guild, categoryChannel, world, alliesList.size, enemiesList.size, masslogIcon)
      // allies grouped by Guild
      val alliesGroupedByGuild: List[(String, List[String])] = presentation.OnlineListGrouping.groupByGuild(
        vocationBuffers.values.flatten
          .filter(charSort => charSort.allyPlayer || charSort.allyGuild)
          .map(charSort => charSort.guildName -> charSort.message))

      val flattenedAlliesList: List[String] =
        presentation.OnlineListGrouping.withHeaders(alliesGroupedByGuild, n => s"### No Guild  $n")

      val alliesTextChannel = guild.getTextChannelById(alliesChannel)
      if (alliesTextChannel != null) {
        if (alliesTextChannel.canTalk() || (!Config.prod)) {
          // allow for custom channel names
          val channelName = alliesTextChannel.getName
          val customName = presentation.OnlineListEmbeds.baseName(channelName, "allies")
          renameOnlineChannelIfDue(alliesTextChannel, s"$customName-$alliesCount", "allies channel", guildId, guild.getName)
          if (alliesList.nonEmpty) {
            updateMultiFields(flattenedAlliesList, alliesTextChannel, "allies", guildId, guild.getName)
          } else {
            updateMultiFields(List("*No `allies` are online right now.*"), alliesTextChannel, "allies", guildId, guild.getName)
          }
        }
      }

      // neutrals grouped by Guild
      val neutralsGroupedByGuild: List[(String, List[String])] = presentation.OnlineListGrouping.groupByGuild(
        vocationBuffers.values.flatten
          .filter(charSort => !charSort.huntedPlayer && !charSort.huntedGuild && !charSort.allyPlayer && !charSort.allyGuild)
          .map(charSort => charSort.guildName -> charSort.message))

      val flattenedNeutralsList: List[String] =
        presentation.OnlineListGrouping.withHeaders(neutralsGroupedByGuild, n => s"### No Guild  $n")

      val neutralsTextChannel = guild.getTextChannelById(neutralsChannel)
      if (neutralsTextChannel != null) {
        if (neutralsTextChannel.canTalk() || (!Config.prod)) {
          // allow for custom channel names
          val channelName = neutralsTextChannel.getName
          val customName = presentation.OnlineListEmbeds.baseName(channelName, "neutrals")
          renameOnlineChannelIfDue(neutralsTextChannel, s"$customName-$neutralsCount", "neutrals channel", guildId, guild.getName)
          if (neutralsList.nonEmpty) {
            updateMultiFields(flattenedNeutralsList, neutralsTextChannel, "neutrals", guildId, guild.getName)
          } else {
            updateMultiFields(List("*No `neutrals` are online right now.*"), neutralsTextChannel, "neutrals", guildId, guild.getName)
          }
        }
      }

      // enemies grouped by Guild
      val enemiesGroupedByGuild: List[(String, List[String])] = presentation.OnlineListGrouping.groupByGuild(
        vocationBuffers.values.flatten
          .filter(charSort => charSort.huntedPlayer || charSort.huntedGuild)
          .map(charSort => charSort.guildName -> charSort.message))

      val flattenedEnemiesList: List[String] =
        presentation.OnlineListGrouping.withHeaders(enemiesGroupedByGuild, n => s"### No Guild  $n")

      val enemiesTextChannel = guild.getTextChannelById(enemiesChannel)
      if (enemiesTextChannel != null) {
        if (enemiesTextChannel.canTalk() || (!Config.prod)) {
          // allow for custom channel names
          val channelName = enemiesTextChannel.getName
          val customName = presentation.OnlineListEmbeds.baseName(channelName, "enemies")
          renameOnlineChannelIfDue(enemiesTextChannel, s"$customName-$enemiesCount", "enemies channel", guildId, guild.getName)
          if (enemiesList.nonEmpty) {
            updateMultiFields(flattenedEnemiesList, enemiesTextChannel, "enemies", guildId, guild.getName)
          } else {
            updateMultiFields(List("*No `enemies` are online right now.*"), enemiesTextChannel, "enemies", guildId, guild.getName)
          }
        }
      }
    }

  }

  private def updateMultiFields(values: List[String], channel: TextChannel, purgeType: String, guildId: String, guildName: String): Unit = {
    val embedColor = 3092790
    //get messages
    try {
      var messages = channel.getHistory.retrievePast(100).complete().asScala.filter(m => m.getAuthor.getId.equals(BotApp.botUser)).toList.reverse.asJava

      // clear the channel every 6 hours
      val allyTimer = alliesListPurgeTimer.getOrElse(guildId, ZonedDateTime.parse("2022-01-01T01:00:00Z"))
      val neutralTimer = neutralsListPurgeTimer.getOrElse(guildId, ZonedDateTime.parse("2022-01-01T01:00:00Z"))
      val enemyTimer = enemiesListPurgeTimer.getOrElse(guildId, ZonedDateTime.parse("2022-01-01T01:00:00Z"))
      if (purgeType == "allies") {
        if (ZonedDateTime.now().isAfter(allyTimer.plusHours(6))) {
          channel.purgeMessages(messages)
          alliesListPurgeTimer = alliesListPurgeTimer + (guildId -> ZonedDateTime.now())
          messages = List.empty.asJava
        }
      } else if (purgeType == "neutrals") {
        if (ZonedDateTime.now().isAfter(neutralTimer.plusHours(6))) {
          channel.purgeMessages(messages)
          neutralsListPurgeTimer = neutralsListPurgeTimer + (guildId -> ZonedDateTime.now())
          messages = List.empty.asJava
        }
      } else if (purgeType == "enemies") {
        if (ZonedDateTime.now().isAfter(enemyTimer.plusHours(6))) {
          channel.purgeMessages(messages)
          enemiesListPurgeTimer = enemiesListPurgeTimer + (guildId -> ZonedDateTime.now())
          messages = List.empty.asJava
        }
      }

      // Pack the lines into embed-sized descriptions, then reconcile against the
      // existing messages: edit in place where one exists, otherwise post. Only
      // the trailing embed carries the "Last updated" footer + timestamp.
      val fields = presentation.OnlineListEmbeds.packFields(values)
      val lastIndex = fields.size - 1
      fields.zipWithIndex.foreach { case (field, currentMessage) =>
        val embed = new EmbedBuilder()
        embed.setDescription(field)
        embed.setColor(embedColor)
        if (currentMessage == lastIndex) {
          embed.setFooter("Last updated")
          embed.setTimestamp(OffsetDateTime.now())
        }
        if (currentMessage < messages.size) {
          messages.get(currentMessage).editMessageEmbeds(embed.build()).queue()
        } else {
          channel.sendMessageEmbeds(embed.build()).setSuppressedNotifications(true).queue()
        }
      }
      if (lastIndex < messages.size - 1) {
        // delete extra messages left over from a previously longer list
        val messagesToDelete = messages.subList(lastIndex + 1, messages.size)
        channel.purgeMessages(messagesToDelete)
      }
    } catch {
      case e: Exception =>
      logger.error(s"Failed to update online list for Guild ID: '$guildId' Guild Name: '$guildName': ${e.getMessage}")
    }
  }

  // Remove players from the list who haven't logged in for a while. Remove old saved deaths.
  private def cleanUp(): Unit = {
    val now = ZonedDateTime.now()
    recentOnline.filterInPlace { i =>
      val diff = java.time.Duration.between(i.time, now).getSeconds
      diff < onlineRecentDuration
    }
    recentOnlineBypass.filterInPlace { i =>
      val diff = java.time.Duration.between(i.time, now).getSeconds
      diff < onlineRecentDuration
    }
    recentDeaths.filterInPlace { i =>
      val diff = java.time.Duration.between(i.time, now).getSeconds
      diff < deathRecentDuration
    }
    levelTracker.prune(now, recentLevelExpiry)
  }

  private def vocEmoji(vocation: String): String = presentation.Emojis.vocEmoji(vocation)

  private def guildUrl(guild: String): String = presentation.Urls.guildUrl(guild)

  private def charUrl(char: String): String = presentation.Urls.charUrl(char)

  private def getKillerLevel(killerName: String): Option[Int] = {
    logger.info(s"getKillerLevel called for: $killerName")

    // Check the dedicated online list table for the killer
    val onlineLevel = onlineListTable.get(killerName.toLowerCase).map(_.level)
    if (onlineLevel.isDefined) {
      logger.info(s"Found level ${onlineLevel.get} for $killerName in online list table")
      onlineLevel
    } else {
      logger.info(s"$killerName not found in online list table - killer may be offline, trying TibiaData API")

      // Fallback to TibiaData API
      try {
        val characterResponse = Await.result(tibiaDataClient.getKillerFallback(killerName), Duration(10, "seconds"))
        characterResponse match {
          case Right(response) =>
            val level = response.character.character.level.toInt
            logger.info(s"Found level $level for $killerName via TibiaData API")
            Some(level)
          case Left(error) =>
            logger.warn(s"Failed to get character $killerName from TibiaData API: $error")
            None
        }
      } catch {
        case ex: Exception =>
          logger.warn(s"Exception when calling TibiaData API for $killerName: ${ex.getMessage}")
          None
      }
    }
  }

  private def creatureImageUrl(creature: String): String =
    presentation.Urls.creatureImageUrl(creature, Config.creatureUrlMappings)

  lazy val stream: RunnableGraph[Cancellable] =
    sourceTick via
      getWorld via
      getCharacterData via
      scanForDeaths via
      postToDiscordAndCleanUp to Sink.ignore

  // Message queue for rate limiting
  // Outbound message delivery: rate-limited and drained one message per tick.
  private val outboundSender = new discord.RateLimitedSender(drain => {
    val cancellable = mat.system.scheduler.scheduleWithFixedDelay(
      0.seconds,
      Config.messageDelayMs.milliseconds
    )(new Runnable { def run(): Unit = drain() })(mat.system.dispatcher)
    () => cancellable.cancel()
  }, outboundQueueCapacity)

  def canPing(channelId: String): Boolean = {
      pingCleanup()

      val now = ZonedDateTime.now()
      val lastPing = cooldowns.get(channelId)

      if (lastPing != null &&
          java.time.Duration.between(lastPing, now).toMinutes < cooldownMinutes) {

        false
      } else {
        cooldowns.put(channelId, now)
        true
      }
    }

  private def pingCleanup(): Unit = {
    val now = ZonedDateTime.now()

    cooldowns.entrySet().removeIf(entry =>
      java.time.Duration.between(entry.getValue, now).toMinutes >= cooldownMinutes
    )
  }

  /** Renames a world's online-list category to reflect the live ally/enemy
   *  counts (and the mass-log ⚡), throttled to once per 6-minute window. The
   *  name-change guard intentionally ignores the ⚡ suffix, matching the
   *  original — so the category re-renames once after a mass-log toggle. */
  private def renameOnlineCategoryIfDue(guild: Guild, categoryId: String, world: String, alliesCount: Int, enemiesCount: Int, masslogIcon: String): Unit = {
    val category = guild.getCategoryById(categoryId)
    if (category != null) {
      val lastRename = onlineListCategoryTimer.getOrElse(categoryId, ZonedDateTime.parse("2022-01-01T01:00:00Z"))
      if (ZonedDateTime.now().isAfter(lastRename.plusMinutes(6))) {
        onlineListCategoryTimer = onlineListCategoryTimer + (categoryId -> ZonedDateTime.now())
        try {
          val baseName = presentation.OnlineListEmbeds.categoryName(world, alliesCount, enemiesCount)
          if (category.getName != baseName) {
            category.getManager.setName(s"$baseName$masslogIcon").queue()
          }
        } catch {
          case ex: Throwable => logger.info(s"Failed to rename the category channel for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}': ${ex.getMessage}")
        }
      }
    }
  }

  /** Renames an online-list text channel to `targetName`, throttled to at most
   *  once per 6-minute window per channel (tracked in onlineListCategoryTimer)
   *  and skipped when the name is already correct. Rename failures (e.g. missing
   *  Manage Channels) are logged, not fatal — `label` names the channel in the
   *  log line. */
  private def renameOnlineChannelIfDue(channel: TextChannel, targetName: String, label: String, guildId: String, guildName: String): Unit = {
    val lastRename = onlineListCategoryTimer.getOrElse(channel.getId, ZonedDateTime.parse("2022-01-01T01:00:00Z"))
    if (ZonedDateTime.now().isAfter(lastRename.plusMinutes(6))) {
      onlineListCategoryTimer = onlineListCategoryTimer + (channel.getId -> ZonedDateTime.now())
      if (channel.getName != targetName) {
        try {
          channel.getManager.setName(targetName).queue()
        } catch {
          case ex: Throwable => logger.info(s"Failed to rename the $label for Guild ID: '$guildId' Guild Name: '$guildName': ${ex.getMessage}")
        }
      }
    }
  }

  // Helper method to queue messages with rate limiting
  private def sendMessageWithRateLimit(
    channel: TextChannel,
    message: String = "",
    embed: Option[EmbedBuilder] = None,
    suppressNotifications: Boolean = true
  ): Unit = {
    outboundSender.enqueue { () =>
      embed match {
        case Some(e) =>
          if (message.nonEmpty)
            channel.sendMessage(message).setEmbeds(e.build()).setSuppressedNotifications(suppressNotifications).queue(null, ignoreDeletedTarget)
          else
            channel.sendMessageEmbeds(e.build()).setSuppressedNotifications(suppressNotifications).queue(null, ignoreDeletedTarget)
        case None =>
          channel.sendMessage(message).setSuppressedNotifications(suppressNotifications).queue(null, ignoreDeletedTarget)
      }
    }
  }

}
