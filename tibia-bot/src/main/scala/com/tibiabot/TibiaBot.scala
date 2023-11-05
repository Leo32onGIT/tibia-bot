package com.tibiabot

import akka.actor.Cancellable
import akka.stream.ActorAttributes.supervisionStrategy
import akka.stream.scaladsl.{Flow, Keep, RunnableGraph, Sink, Source}
import akka.stream.{Attributes, Materializer, Supervision}
import com.tibiabot.BotApp.{alliedGuildsData, alliedPlayersData, discordsData, huntedGuildsData, huntedPlayersData, worldsData, activityData, customSortData, Players}
import com.tibiabot.tibiadata.TibiaDataClient
import com.tibiabot.tibiadata.response.{CharacterResponse, Deaths, OnlinePlayers, WorldResponse}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel

import java.time.ZonedDateTime
import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}
import java.time.OffsetDateTime

//noinspection FieldFromDelayedInit
class TibiaBot(world: String)(implicit ex: ExecutionContextExecutor, mat: Materializer) extends StrictLogging {

  // A date-based "key" for a character, used to track recent deaths and recent online entries
  private case class CharKey(char: String, time: ZonedDateTime)
  private case class CharKeyBypass(char: String, level: Int, time: ZonedDateTime)
  private case class CurrentOnline(name: String, level: Int, vocation: String, guildName: String, time: ZonedDateTime, duration: Long = 0L, flag: String)
  private case class CharDeath(char: CharacterResponse, death: Deaths)
  private case class CharLevel(name: String, level: Int, vocation: String, lastLogin: ZonedDateTime, time: ZonedDateTime)
  private case class CharSort(guildName: String, allyGuild: Boolean, huntedGuild: Boolean, allyPlayer: Boolean, huntedPlayer: Boolean, categoryEmoji: String, category: String, message: String)

  //val guildId: String = guild.getId

  private val recentDeaths = mutable.Set.empty[CharKey]
  private val recentLevels = mutable.Set.empty[CharLevel]
  private val recentOnline = mutable.Set.empty[CharKey]
  private val recentOnlineBypass = mutable.Set.empty[CharKeyBypass]
  private var currentOnline = mutable.Set.empty[CurrentOnline]

  // initialize cached deaths/levels from database
  recentDeaths ++= BotApp.getDeathsCache(world).map(deathsCache => CharKey(deathsCache.name, ZonedDateTime.parse(deathsCache.time)))
  recentLevels ++= BotApp.getLevelsCache(world).map(levelsCache => CharLevel(levelsCache.name, levelsCache.level.toInt, levelsCache.vocation, ZonedDateTime.parse(levelsCache.lastLogin), ZonedDateTime.parse(levelsCache.time)))

  private var onlineListTimer: Map[String, ZonedDateTime] = Map.empty
  private var cacheListTimer: Map[String, ZonedDateTime] = Map.empty
  private var alliesListPurgeTimer: Map[String, ZonedDateTime] = Map.empty
  private var enemiesListPurgeTimer: Map[String, ZonedDateTime] = Map.empty
  private var neutralsListPurgeTimer: Map[String, ZonedDateTime] = Map.empty
  // ZonedDateTime.parse("2022-01-01T01:00:00Z")

  private val tibiaDataClient = new TibiaDataClient()

  private val deathRecentDuration = 30 * 60 // 30 minutes for a death to count as recent enough to be worth notifying
  private val onlineRecentDuration = 10 * 60 // 10 minutes for a character to still be checked for deaths after logging off
  private val recentLevelExpiry = 25 * 60 * 60 // 25 hours before deleting recentLevel entry

  private val logAndResumeDecider: Supervision.Decider = { e =>
    logger.error("An exception has occurred in the TibiaBot:", e)
    Supervision.Resume
  }

  private val logAndResume: Attributes = supervisionStrategy(logAndResumeDecider)
  private lazy val sourceTick = if (world == "Double") Source.tick(2.seconds, 20.seconds, ()) else Source.tick(2.seconds, 60.seconds, ()) // im kinda cow-boying it here
  private lazy val getWorld = Flow[Unit].mapAsync(1) { _ =>
    logger.info(s"Running stream for World: '$world'")
    tibiaDataClient.getWorld(world) // Pull all online characters
  }.withAttributes(logAndResume)

  private lazy val getCharacterData = Flow[WorldResponse].mapAsync(1) { worldResponse =>
    val now = ZonedDateTime.now()
    val onlinePlayers: Option[List[OnlinePlayers]] = worldResponse.worlds.world.online_players
    val online: List[OnlinePlayers] = onlinePlayers match {
      case Some(players) => players
      case None => List.empty[OnlinePlayers]
    }

    // get online data with durations
    val onlineWithVocLvlAndDuration = online.map { player =>
      currentOnline.find(_.name == player.name) match {
        case Some(existingPlayer) =>
          val duration = now.toEpochSecond - existingPlayer.time.toEpochSecond
          CurrentOnline(player.name, player.level.toInt, player.vocation, "", now, existingPlayer.duration + duration, existingPlayer.flag)
        case None => CurrentOnline(player.name, player.level.toInt, player.vocation, "", now, 0L, "")
      }
    }

    // Add online data to sets
    currentOnline.clear()
    currentOnline.addAll(onlineWithVocLvlAndDuration)

    // Remove existing online chars from the list...
    recentOnline.filterInPlace { i =>
      !online.exists(player => player.name == i.char)
    }
    recentOnline.addAll(online.map(player => CharKey(player.name, now)))

    // cache bypass for Seanera
    if (world == "Double" && Config.prod) {
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
  }.withAttributes(logAndResume)

  private lazy val scanForDeaths = Flow[Set[CharacterResponse]].mapAsync(1) { characterResponses =>
    val now = ZonedDateTime.now()

    // gather guild icons data for online player list
    val newDeaths = characterResponses.flatMap { char =>
      val charName = char.characters.character.name
      val guildName = char.characters.character.guild.map(_.name).getOrElse("")

      val formerNamesList: List[String] = char.characters.character.former_names.map(_.toList).getOrElse(Nil)

      // Caching attempt
      val cacheTimer = cacheListTimer.getOrElse(world, ZonedDateTime.parse("2022-01-01T01:00:00Z"))
      if (ZonedDateTime.now().isAfter(cacheTimer.plusMinutes(6))) {
        val cacheWorld = char.characters.character.world
        val cacheFormerWorlds: List[String] = char.characters.character.former_worlds.map(_.toList).getOrElse(Nil)
        BotApp.addListToCache(charName, formerNamesList, cacheWorld, cacheFormerWorlds, guildName, char.characters.character.level.toInt.toString, char.characters.character.vocation, char.characters.character.last_login.getOrElse(""), ZonedDateTime.now())
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
          currentOnline.find(_.name == charName).foreach { onlinePlayer =>
            if (onlinePlayer.guildName != guildName){
              val updatedPlayer = onlinePlayer.copy(guildName = guildName)
              currentOnline = currentOnline.filterNot(_ == onlinePlayer) + updatedPlayer
            }
          }

          // Activity channel
          if (!blocker) {
            val guild = BotApp.jda.getGuildById(discords.id)
            val worldData = worldsData.getOrElse(guildId, List()).filter(w => w.name.toLowerCase() == world.toLowerCase())
            val activityChannel = worldData.headOption.map(_.activityChannel).getOrElse("0")
            val activityTextChannel = guild.getTextChannelById(activityChannel)
            val adminChannel = discords.adminChannel
            val charVocation = vocEmoji(char.characters.character.vocation)
            val charLevel = char.characters.character.level.toInt

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
                activityData = activityData + (guildId -> updatedActivityData)
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
                      huntedPlayersData = huntedPlayersData + (guildId -> updatedHuntedPlayersData)
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
                      alliedPlayersData = alliedPlayersData + (guildId -> updatedAlliedPlayersData)
                    }
                    if (activityTextChannel != null) {
                      // send message to activity channel
                      val activityEmbed = new EmbedBuilder()
                      activityEmbed.setDescription(s"$charVocation **$charLevel** — **[$oldName](${charUrl(oldName)})** changed their name to **[$charName](${charUrl(charName)})**.")
                      activityEmbed.setColor(playerType)
                      activityEmbed.setThumbnail(Config.nameChangeThumbnail)
                      try {
                        activityTextChannel.sendMessageEmbeds(activityEmbed.build()).setSuppressedNotifications(true).queue()
                      } catch {
                        case ex: Exception => logger.error(s"Failed to send message to 'activity' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}': ${ex.getMessage}")
                        case _: Throwable => logger.error(s"Failed to send message to 'activity' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}'")
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

                  //charResponse.characters.character.world
                  // Guild has changed
                  if (guildName != guildNameFromActivityData) {
                    //val newGuild = if (guildName == "") "None" else guildName
                    val newGuildLess = if (guildName == "") true else false
                    val oldGuildLess = if (guildNameFromActivityData == "") true else false
                    val wasInHuntedGuild = huntedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == guildNameFromActivityData.toLowerCase())
                    val wasInAlliedGuild = alliedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == guildNameFromActivityData.toLowerCase())
                    // Left a tracked guild
                    if (wasInHuntedGuild || wasInAlliedGuild) {
                      val guildType = if (wasInHuntedGuild) "hunted" else if (wasInAlliedGuild) "allied" else "neutral"
                      // No guild now
                      if (newGuildLess) {
                        // send message to activity channel
                        if (activityTextChannel != null) {
                          val activityEmbed = new EmbedBuilder()
                          activityEmbed.setDescription(s"$charVocation **$charLevel** — **[$charName](${charUrl(charName)})** has left the **${guildType}** guild **[${guildNameFromActivityData}](${guildUrl(guildNameFromActivityData)})**.")
                          activityEmbed.setColor(14397256)
                          activityEmbed.setThumbnail(Config.guildLeaveThumbnail)
                          try {
                            activityTextChannel.sendMessageEmbeds(activityEmbed.build()).setSuppressedNotifications(true).queue()
                          } catch {
                            case ex: Exception => logger.error(s"Failed to send message to 'activity' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}':", ex)
                            case _: Throwable => logger.info(s"Failed to send message to 'activity' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}'")
                          }
                        }
                      } else { // Left a tracked guild, but joined a new one in the same turn
                        val colorType = if (huntedGuildCheck) 13773097 else if (allyGuildCheck) 36941 else 14397256 // hunted join = red, allied join = green, otherwise = yellow
                        // send message to activity channel
                        if (activityTextChannel != null) {
                          val activityEmbed = new EmbedBuilder()
                          val thumbnailType = colorType match {
                            case 13773097 => Config.guildSwapRed
                            case 36941 => Config.guildSwapGreen
                            case _ => Config.guildSwapGrey
                          }
                          activityEmbed.setDescription(s"$charVocation **$charLevel** — **[$charName](${charUrl(charName)})** has left the **${guildType}** guild **[${guildNameFromActivityData}](${guildUrl(guildNameFromActivityData)})** and joined the guild **[${guildName}](${guildUrl(guildName)})**.")
                          activityEmbed.setColor(colorType)
                          activityEmbed.setThumbnail(thumbnailType)
                          try {
                            activityTextChannel.sendMessageEmbeds(activityEmbed.build()).setSuppressedNotifications(true).queue()
                          } catch {
                            case ex: Exception => logger.error(s"Failed to send message to 'activity' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}': ${ex.getMessage}", ex)
                            case _: Throwable => logger.info(s"Failed to send message to 'activity' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}'")
                          }
                        }
                        // remove from hunted list if in allied guild
                        if (allyGuildCheck) {
                          huntedPlayersData = huntedPlayersData.updated(guildId, huntedPlayersData.getOrElse(guildId, List.empty).filterNot(_.name == charName))
                          BotApp.removeHuntedFromDatabase(guild, "player", charName.toLowerCase())
                          val adminTextChannel = guild.getTextChannelById(adminChannel)
                          if (adminTextChannel != null) {
                            // send embed to admin channel
                            val commandUser = s"<@${BotApp.botUser}>"
                            val adminEmbed = new EmbedBuilder()
                            adminEmbed.setTitle(":robot: enemy joined an allied guild:")
                            adminEmbed.setDescription(s"$commandUser removed the player\n$charVocation **$charLevel** — **[$charName](${charUrl(charName)})**\nfrom the hunted list for **$world**\n*(they left a hunted guild & joined an allied one)*.")
                            adminEmbed.setThumbnail(creatureImageUrl("Broom"))
                            adminEmbed.setColor(14397256) // orange for bot auto command
                            try {
                              adminTextChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                            } catch {
                              case ex: Exception => logger.error(s"Failed to send message to 'command-log' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}': ${ex.getMessage}", ex)
                              case _: Throwable => logger.info(s"Failed to send message to 'command-log' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}'")
                            }
                          }
                        }
                      }

                      // if he was in hunted guild add to hunted players list
                      if (wasInHuntedGuild) {
                        if (!allyGuildCheck && !huntedGuildCheck && !huntedPlayerCheck && !allyPlayerCheck) {
                          val adminTextChannel = guild.getTextChannelById(adminChannel)
                          if (adminTextChannel != null) {
                            // add them to cached huntedPlayersData list
                            huntedPlayersData = huntedPlayersData + (guildId -> (BotApp.Players(charName.toLowerCase(), "false", s"was originally in hunted guild ${guildNameFromActivityData}", BotApp.botUser) :: huntedPlayersData.getOrElse(guildId, List())))
                            BotApp.addHuntedToDatabase(guild, "player", charName.toLowerCase(), "false", s"was originally in hunted guild ${guildNameFromActivityData}", BotApp.botUser)
                            // send embed to admin channel
                            val commandUser = s"<@${BotApp.botUser}>"
                            val adminEmbed = new EmbedBuilder()
                            adminEmbed.setTitle(":robot: enemy automatically detected:")
                            adminEmbed.setDescription(s"$commandUser added the player\n$charVocation **$charLevel** — **[$charName](${charUrl(charName)})**\nto the hunted list for **$world**\n*(they left a hunted guild, so they will remain hunted)*.")
                            adminEmbed.setThumbnail(creatureImageUrl("Stone_Coffin"))
                            adminEmbed.setColor(14397256) // orange for bot auto command
                            try {
                              adminTextChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                            } catch {
                              case ex: Exception => logger.error(s"Failed to send message to 'command-log' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}': ${ex.getMessage}", ex)
                              case _: Throwable => logger.info(s"Failed to send message to 'command-log' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}'")
                            }
                          }
                        }
                      } else if (wasInAlliedGuild){
                        if (!allyGuildCheck && !huntedGuildCheck && !huntedPlayerCheck && !allyPlayerCheck) {
                          // remove from activity
                          activityData = activityData + (guildId -> activityData.getOrElse(guildId, List()).filterNot(_.name.equalsIgnoreCase(charName.toLowerCase)))
                          BotApp.removePlayerActivityfromDatabase(guild, charName.toLowerCase)
                        }
                      }
                    }

                    if (huntedPlayerCheck && oldGuildLess) {
                      val colorType = if (huntedGuildCheck) 13773097 else if (allyGuildCheck) 36941 else 14397256 // hunted join = red, allied join = green, otherwise = yellow
                      val guildType = if (huntedGuildCheck) "hunted" else if (allyGuildCheck) "allied" else "neutral"
                      // joined a hunted guild
                      if (huntedGuildCheck) {
                        // remove from hunted 'Player' cache and db
                        huntedPlayersData = huntedPlayersData.updated(guildId, huntedPlayersData.getOrElse(guildId, List.empty).filterNot(_.name.toLowerCase == charName.toLowerCase))
                        BotApp.removeHuntedFromDatabase(guild, "player", charName.toLowerCase())
                        // send message to admin channel
                        val adminTextChannel = guild.getTextChannelById(adminChannel)
                        if (adminTextChannel != null) {
                          // send embed to admin channel
                          val commandUser = s"<@${BotApp.botUser}>"
                          val adminEmbed = new EmbedBuilder()
                          adminEmbed.setTitle(":robot: hunted list cleanup:")
                          adminEmbed.setDescription(s"$commandUser removed the player\n$charVocation **$charLevel** — **[$charName](${charUrl(charName)})**\nfrom the hunted list for **$world**\n*(because they have joined an enemy guild and will be tracked that way)*.")
                          adminEmbed.setThumbnail(creatureImageUrl("Broom"))
                          adminEmbed.setColor(14397256) // orange for bot auto command
                          try {
                            adminTextChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                          } catch {
                            case ex: Exception => logger.error(s"Failed to send message to 'command-log' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}': ${ex.getMessage}", ex)
                            case _: Throwable => logger.info(s"Failed to send message to 'command-log' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}'")
                          }
                        }
                      } else if (allyGuildCheck) {
                        // remove from hunted 'Player' cache and db
                        huntedPlayersData = huntedPlayersData.updated(guildId, huntedPlayersData.getOrElse(guildId, List.empty).filterNot(_.name.toLowerCase == charName.toLowerCase))
                        BotApp.removeHuntedFromDatabase(guild, "player", charName.toLowerCase())
                        // send message to admin channel
                        val adminTextChannel = guild.getTextChannelById(adminChannel)
                        if (adminTextChannel != null) {
                          // send embed to admin channel
                          val commandUser = s"<@${BotApp.botUser}>"
                          val adminEmbed = new EmbedBuilder()
                          adminEmbed.setTitle(":robot: hunted list cleanup:")
                          adminEmbed.setDescription(s"$commandUser removed the player\n$charVocation **$charLevel** — **[$charName](${charUrl(charName)})**\nfrom the hunted list for **$world**\n*(because they have joined an allied guild and will be tracked that way)*.")
                          adminEmbed.setThumbnail(creatureImageUrl("Broom"))
                          adminEmbed.setColor(14397256) // orange for bot auto command
                          try {
                            adminTextChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                          } catch {
                            case ex: Exception => logger.error(s"Failed to send message to 'command-log' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}': ${ex.getMessage}", ex)
                            case _: Throwable => logger.info(s"Failed to send message to 'command-log' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}'")
                          }
                        }
                      }
                      // send message to activity channel
                      if (activityTextChannel != null) {
                        val activityEmbed = new EmbedBuilder()
                        val thumbnailType = guildType match {
                          case "hunted" => Config.guildJoinRed
                          case "allied" => Config.guildJoinGreen
                          case _ => Config.guildJoinGrey
                        }
                        activityEmbed.setDescription(s"$charVocation **$charLevel** — **[$charName](${charUrl(charName)})** joined the **${guildType}** guild **[${guildName}](${guildUrl(guildName)})**.")
                        activityEmbed.setColor(colorType)
                        activityEmbed.setThumbnail(thumbnailType)
                        try {
                          activityTextChannel.sendMessageEmbeds(activityEmbed.build()).setSuppressedNotifications(true).queue()
                        } catch {
                          case ex: Exception => logger.error(s"Failed to send message to 'activity' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}': ${ex.getMessage}", ex)
                          case _: Throwable => logger.info(s"Failed to send message to 'activity' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}'")
                        }
                      }
                    }

                    val updatedActivityData = matchingActivityOption.map { activity =>
                      val updatedActivity = activity.copy(guild = guildName, updatedTime = ZonedDateTime.now())
                      activityData.getOrElse(guildId, List()).filterNot(_.name.toLowerCase == charName.toLowerCase) :+ updatedActivity
                    }.getOrElse(activityData.getOrElse(guildId, List()))

                    // Update in cache and db
                    activityData = activityData + (guildId -> updatedActivityData)
                    BotApp.updateActivityToDatabase(guild, charName, formerNamesList, guildName, ZonedDateTime.now(), charName)
                  }
                }
              } else if (joinGuild) { // Character doesn't exist in tracking_activity but should be
                // add to cache and db
                val newActivity = BotApp.PlayerCache(charName, formerNamesList, guildName, ZonedDateTime.now())
                val updatedActivityData = newActivity :: activityData.getOrElse(guildId, List())
                activityData = activityData + (guildId -> updatedActivityData)
                BotApp.addActivityToDatabase(guild, charName, formerNamesList, guildName, ZonedDateTime.now())
                // joined a hunted guild
                if (huntedGuildCheck) {
                  if (huntedPlayerCheck) { // was he originally in hunted 'player' list?
                    // remove from hunted 'Player' cache and db
                    huntedPlayersData = huntedPlayersData.updated(guildId, huntedPlayersData.getOrElse(guildId, List.empty).filterNot(_.name.toLowerCase == charName.toLowerCase))
                    BotApp.removeHuntedFromDatabase(guild, "player", charName.toLowerCase())
                    // send message to admin channel
                    val adminTextChannel = guild.getTextChannelById(adminChannel)
                    if (adminTextChannel != null) {
                      // send embed to admin channel
                      val commandUser = s"<@${BotApp.botUser}>"
                      val adminEmbed = new EmbedBuilder()
                      adminEmbed.setTitle(":robot: hunted list cleanup:")
                      adminEmbed.setDescription(s"$commandUser removed the player\n$charVocation **$charLevel** — **[$charName](${charUrl(charName)})**\nfrom the hunted list for **$world**\n*(because they have joined an enemy guild and will be tracked that way)*.")
                      adminEmbed.setThumbnail(creatureImageUrl("Broom"))
                      adminEmbed.setColor(14397256) // orange for bot auto command
                      try {
                        adminTextChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                      } catch {
                        case ex: Exception => logger.error(s"Failed to send message to 'command-log' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}': ${ex.getMessage}", ex)
                        case _: Throwable => logger.info(s"Failed to send message to 'command-log' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}'")
                      }
                    }
                  }
                } else if (allyGuildCheck) { // joined an allied guild
                  if (allyPlayerCheck) {
                    // remove from allied 'Player' cache and db
                    alliedPlayersData = alliedPlayersData.updated(guildId, alliedPlayersData.getOrElse(guildId, List.empty).filterNot(_.name.toLowerCase == charName.toLowerCase))
                    BotApp.removeAllyFromDatabase(guild, "player", charName.toLowerCase())
                    // send message to admin channel
                    val adminTextChannel = guild.getTextChannelById(adminChannel)
                    if (adminTextChannel != null) {
                      // send embed to admin channel
                      val commandUser = s"<@${BotApp.botUser}>"
                      val adminEmbed = new EmbedBuilder()
                      adminEmbed.setTitle(":robot: allied list cleanup:")
                      adminEmbed.setDescription(s"$commandUser removed the player\n$charVocation **$charLevel** — **[$charName](${charUrl(charName)})**\nfrom the allied list for **$world**\n*(because they have joined an allied guild and will be tracked that way)*.")
                      adminEmbed.setThumbnail(creatureImageUrl("Broom"))
                      adminEmbed.setColor(14397256) // orange for bot auto command
                      try {
                        adminTextChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                      } catch {
                        case ex: Exception => logger.error(s"Failed to send message to 'command-log' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}': ${ex.getMessage}")
                        case _: Throwable => logger.error(s"Failed to send message to 'command-log' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}'")
                      }
                    }
                  }
                }
                val guildType = if (huntedGuildCheck) "hunted" else if (allyGuildCheck) "allied" else "neutral"
                val colorType = if (huntedGuildCheck) 13773097 else if (allyGuildCheck) 36941 else 14397256
                if (guildType != "neutral") { // ignore neutral guild changes, only show hunted/allied rejoins
                  if (activityTextChannel != null) {
                    val activityEmbed = new EmbedBuilder()
                    val thumbnailType = guildType match {
                      case "hunted" => Config.guildJoinRed
                      case "allied" => Config.guildJoinGreen
                      case _ => Config.guildJoinGrey
                    }
                    activityEmbed.setDescription(s"$charVocation **$charLevel** — **[$charName](${charUrl(charName)})** joined the **${guildType}** guild **[${guildName}](${guildUrl(guildName)})**.")
                    activityEmbed.setColor(colorType)
                    activityEmbed.setThumbnail(thumbnailType)
                    try {
                      activityTextChannel.sendMessageEmbeds(activityEmbed.build()).setSuppressedNotifications(true).queue()
                    } catch {
                      case ex: Exception => logger.error(s"Failed to send message to 'activity' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}': ${ex.getMessage}")
                      case _: Throwable => logger.error(s"Failed to send message to 'activity' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}'")
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
      val deaths: List[Deaths] = char.characters.deaths.getOrElse(List.empty)
      val sheetLevel = char.characters.character.level
      val sheetVocation = char.characters.character.vocation
      val sheetLastLogin = ZonedDateTime.parse(char.characters.character.last_login.getOrElse("2022-01-01T01:00:00Z"))
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
        currentOnline.find(_.name == charName).foreach { onlinePlayer =>
          if (onlinePlayer.level > sheetLevel) {
            val newCharLevel = CharLevel(charName, onlinePlayer.level, sheetVocation, sheetLastLogin, now)
            // post level to each discord
            if (discordsData.contains(world)) {
              val discordsList = discordsData(world)
              discordsList.foreach { discords =>
                val guild = BotApp.jda.getGuildById(discords.id)
                val guildId = discords.id

                // get appropriate guildIcon
                val allyGuildCheck = alliedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == guildName.toLowerCase())
                val huntedGuildCheck = huntedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == guildName.toLowerCase())
                val allyPlayerCheck = alliedPlayersData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == charName.toLowerCase())
                val huntedPlayerCheck = huntedPlayersData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == charName.toLowerCase())
                val guildIcon = (guildName, allyGuildCheck, huntedGuildCheck, allyPlayerCheck, huntedPlayerCheck) match {
                  case (_, true, _, _, _) => Config.allyGuild // allied-guilds
                  case (_, _, true, _, _) => Config.enemyGuild // hunted-guilds
                  case ("", _, _, true, _) => Config.ally // allied-players not in any guild
                  case (_, _, _, true, _) => s"${Config.otherGuild}${Config.ally}" // allied-players but in neutral guild
                  case ("", _, _, _, true) => Config.enemy // hunted-players no guild
                  case (_, _, _, _, true) => s"${Config.otherGuild}${Config.enemy}" // hunted-players but in neutral guild
                  case ("", _, _, _, _) => "" // no guild (not ally or hunted)
                  case _ => Config.otherGuild // guild (not ally or hunted)
                }
                val worldData = worldsData.getOrElse(guildId, List()).filter(w => w.name.toLowerCase() == world.toLowerCase())
                val levelsChannel = worldData.headOption.map(_.levelsChannel).getOrElse("0")
                val webhookMessage = s"${vocEmoji(onlinePlayer.vocation)} **[$charName](${charUrl(charName)})** advanced to level **${onlinePlayer.level}** $guildIcon"
                val levelsTextChannel = guild.getTextChannelById(levelsChannel)
                if (levelsTextChannel != null && onlinePlayer.level >= 100) {
                  // check show_neutrals_levels setting
                  val showNeutralLevels = worldData.headOption.map(_.showNeutralLevels).getOrElse("true")
                  val showAlliesLevels = worldData.headOption.map(_.showAlliesLevels).getOrElse("true")
                  val showEnemiesLevels = worldData.headOption.map(_.showEnemiesLevels).getOrElse("true")
                  val minimumLevel = worldData.headOption.map(_.levelsMin).getOrElse(8)
                  val enemyIcons = List(Config.enemy, Config.enemyGuild, s"${Config.otherGuild}${Config.enemy}")
                  val alliesIcons = List(Config.allyGuild, Config.ally, s"${Config.otherGuild}${Config.ally}")
                  val neutralIcons = List(Config.otherGuild, "")
                  // don't post level if showNeutrals is set to false and its a neutral level
                  val levelsCheck =
                    if (showNeutralLevels == "false" && neutralIcons.contains(guildIcon)) {
                      false
                    } else if (showAlliesLevels == "false" && alliesIcons.contains(guildIcon)) {
                      false
                    } else if (showEnemiesLevels == "false" && enemyIcons.contains(guildIcon)) {
                      false
                    } else if (onlinePlayer.level < minimumLevel) {
                      false
                    } else {
                      true
                    }
                  if (recentLevels.exists(x => x.name == charName && x.level == onlinePlayer.level)) {
                    val lastLoginInRecentLevels = recentLevels.filter(x => x.name == charName && x.level == onlinePlayer.level)
                    if (lastLoginInRecentLevels.forall(x => x.lastLogin.isBefore(sheetLastLogin))) {
                      if (levelsCheck) {
                        //createAndSendWebhookMessage(levelsTextChannel, webhookMessage, s"${world.capitalize}")
                        //sender.sendWebhookMessage(guild, levelsTextChannel, webhookMessage, s"${world.capitalize}")
                        try {
                          levelsTextChannel.sendMessage(webhookMessage).setSuppressedNotifications(true).queue()
                        } catch {
                          case ex: Exception => logger.error(s"Failed to send message to 'levels' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}': ${ex.getMessage}")
                          case _: Throwable => logger.error(s"Failed to send message to 'levels' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}'")
                        }
                      }
                    }
                  } else {
                    if (levelsCheck) {
                      //createAndSendWebhookMessage(levelsTextChannel, webhookMessage, s"${world.capitalize}")
                      //sender.sendWebhookMessage(guild, levelsTextChannel, webhookMessage, s"${world.capitalize}")
                      try {
                        levelsTextChannel.sendMessage(webhookMessage).setSuppressedNotifications(true).queue()
                      } catch {
                        case ex: Exception => logger.error(s"Failed to send message to 'levels' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}': ${ex.getMessage}")
                        case _: Throwable => logger.error(s"Failed to send message to 'levels' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}'")
                      }
                    }
                  }
                }
              }
            }
            // add flag to onlineList if player has leveled
            currentOnline.find(_.name == charName).foreach { onlinePlayer =>
              currentOnline -= onlinePlayer
              currentOnline += onlinePlayer.copy(flag = Config.levelUpEmoji)
            }
            if (recentLevels.exists(x => x.name == charName && x.level == onlinePlayer.level)) {
              val lastLoginInRecentLevels = recentLevels.filter(x => x.name == charName && x.level == onlinePlayer.level)
              if (lastLoginInRecentLevels.forall(x => x.lastLogin.isBefore(sheetLastLogin))) {
                recentLevels += newCharLevel
                BotApp.addLevelsCache(world, charName, onlinePlayer.level.toString, sheetVocation, sheetLastLogin.toString, now.toString)
              }
            } else {
              recentLevels += newCharLevel
              BotApp.addLevelsCache(world, charName, onlinePlayer.level.toString, sheetVocation, sheetLastLogin.toString, now.toString)
            }
          }
        }
      }
      // parsing death info
      deaths.flatMap { death =>
        val deathTime = ZonedDateTime.parse(death.time)
        val deathAge = java.time.Duration.between(deathTime, now).getSeconds
        val charDeath = CharKey(char.characters.character.name, deathTime)
        if (deathAge < deathRecentDuration && !recentDeaths.contains(charDeath)) {
          recentDeaths.add(charDeath)
          BotApp.addDeathsCache(world, char.characters.character.name, deathTime.toString)
          Some(CharDeath(char, death))
        }
        else None
      }
    }
    // update online lists
    if (discordsData.contains(world)) {
      val discordsList = discordsData(world)
      discordsList.foreach { discords =>
        val guildId = discords.id
        val worldData = worldsData.getOrElse(guildId, List()).filter(w => w.name.toLowerCase() == world.toLowerCase())
        // update online list every 5 minutes
        val onlineTimer = onlineListTimer.getOrElse(guildId, ZonedDateTime.parse("2022-01-01T01:00:00Z"))
        if (ZonedDateTime.now().isAfter(onlineTimer.plusMinutes(6))) {
          // did the online list api call fail?
          val alliesChannel = worldData.headOption.map(_.alliesChannel).getOrElse("0")
          val neutralsChannel = worldData.headOption.map(_.neutralsChannel).getOrElse("0")
          val enemiesChannel = worldData.headOption.map(_.enemiesChannel).getOrElse("0")
          val categoryChannel = worldData.headOption.map(_.category).getOrElse("0")
          val onlineCombinedOption = worldData.headOption.map(_.onlineCombined).getOrElse("false")
          //if (currentOnlineList.size > 1) {
            onlineListTimer = onlineListTimer + (guildId -> ZonedDateTime.now())
            onlineList(currentOnline.toList, guildId, alliesChannel, neutralsChannel, enemiesChannel, categoryChannel, onlineCombinedOption, world)
          //}
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
        val guild = BotApp.jda.getGuildById(discords.id)
        val guildId = discords.id
        val adminChannel = discords.adminChannel
        val worldData = worldsData.getOrElse(guildId, List()).filter(w => w.name.toLowerCase() == world.toLowerCase())
        val deathsChannel = worldData.headOption.map(_.deathsChannel).getOrElse("0")
        val nemesisRole = worldData.headOption.map(_.nemesisRole).getOrElse("0")
        val fullblessRole = worldData.headOption.map(_.fullblessRole).getOrElse("0")
        val exivaListCheck = worldData.headOption.map(_.exivaList).getOrElse("true")
        val deathsTextChannel = guild.getTextChannelById(deathsChannel)
        /**
        val activityChannel = worldData.headOption.map(_.activityChannel).getOrElse("0")
        val activityTextChannel = guild.getTextChannelById(activityChannel)
        if (activityTextChannel != null) {

        }
        **/
        if (deathsTextChannel != null) {
          val embeds = charDeaths.toList.sortBy(_.death.time).map { charDeath =>
            var notablePoke = ""
            val charName = charDeath.char.characters.character.name
            val killer = charDeath.death.killers.last.name
            var context = "Died"
            var embedColor = 3092790 // background default
            var embedThumbnail = creatureImageUrl(killer)
            var vowelCheck = "" // this is for adding "an" or "a" in front of creature names
            val killerBuffer = ListBuffer[String]()
            val exivaBuffer = ListBuffer[String]()
            var exivaList = ""
            val killerList = charDeath.death.killers // get all killers

            // guild rank and name
            val guildName = charDeath.char.characters.character.guild.map(_.name).getOrElse("")
            val guildRank = charDeath.char.characters.character.guild.map(_.rank).getOrElse("")
            //var guildText = ":x: **No Guild**\n"
            var guildText = ""

            // guild
            // does player have guild?
            var guildIcon = Config.otherGuild
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
              val allyGuilds = alliedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == guildName.toLowerCase())
              if (allyGuilds) {
                embedColor = 13773097 // bright red
                guildIcon = Config.allyGuild
              }
              // is player in hunted guild
              val huntedGuilds = huntedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == guildName.toLowerCase())
              if (huntedGuilds) {
                embedColor = 36941 // bright green
                if (context == "Died") {
                  notablePoke = "fullbless" // PVE fullbless opportuniy (only poke for level 400+)
                }
              }
              guildText = s"$guildIcon *$guildRank* of the [$guildName](https://www.tibia.com/community/?subtopic=guilds&page=view&GuildName=${guildName.replace(" ", "%20")})\n"
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
                    notablePoke = "" // reset poke as its not a fullbless
                    if (embedColor == 3092790 || embedColor == 4540237) {
                      embedColor = 14869218 // bone white
                    }
                    embedThumbnail = creatureImageUrl("Phantasmal_Ooze")
                    val isSummon = k.name.split(" of ", 2) // e.g: fire elemental of Violent Beams
                    if (isSummon.length > 1) {
                      if (!isSummon(0).exists(_.isUpper)) { // summons will be lowercase, a player with " of " in their name will have a capital letter
                        val vowel = isSummon(0).take(1) match {
                        case "a" => "an"
                        case "e" => "an"
                        case "i" => "an"
                        case "o" => "an"
                        case "u" => "an"
                        case _ => "a"
                        }
                        killerBuffer += s"$vowel ${Config.summonEmoji} **${isSummon(0)} of [${isSummon(1)}](${charUrl(isSummon(1))})**"
                        if (embedColor == 13773097) {
                          if (exivaListCheck == "true") {
                            exivaBuffer += isSummon(1)
                          }
                        }
                      } else {
                        killerBuffer += s"**[${k.name}](${charUrl(k.name)})**" // player with " of " in the name e.g: Knight of Flame
                        if (embedColor == 13773097) {
                          if (exivaListCheck == "true") {
                            exivaBuffer += k.name
                          }
                        }
                      }
                    } else {
                      killerBuffer += s"**[${k.name}](${charUrl(k.name)})**" // summon not detected
                      if (embedColor == 13773097) {
                        if (exivaListCheck == "true") {
                          exivaBuffer += k.name
                        }
                      }
                    }
                  }
                } else {
                  // custom emojis for flavour
                  // map boss lists to their respesctive emojis
                  val creatureEmojis: Map[List[String], String] = Map(
                    Config.nemesisCreatures -> Config.nemesisEmoji,
                    Config.archfoeCreatures -> Config.archfoeEmoji,
                    Config.baneCreatures -> Config.baneEmoji,
                    Config.bossSummons -> Config.summonEmoji,
                    Config.cubeBosses -> Config.cubeEmoji,
                    Config.mkBosses -> Config.mkEmoji,
                    Config.svarGreenBosses -> Config.svarGreenEmoji,
                    Config.svarScrapperBosses -> Config.svarScrapperEmoji,
                    Config.svarWarlordBosses -> Config.svarWarlordEmoji,
                    Config.zelosBosses -> Config.zelosEmoji,
                    Config.libBosses -> Config.libEmoji,
                    Config.hodBosses -> Config.hodEmoji,
                    Config.feruBosses -> Config.feruEmoji,
                    Config.inqBosses -> Config.inqEmoji,
                    Config.kilmareshBosses -> Config.kilmareshEmoji,
                    Config.primalCreatures -> Config.primalEmoji,
                    Config.hazardCreatures -> Config.hazardEmoji
                  )
                  // assign the appropriate emoji
                  val bossIcon = creatureEmojis.find {
                    case (creatures, _) => creatures.contains(k.name.toLowerCase())
                  }.map(_._2 + " ").getOrElse("")

                  // add "an" or "a" depending on first letter of creatures name
                  // ignore capitalized names (nouns) as they are bosses
                  // if player dies to a neutral source show 'died by energy' instead of 'died by an energy'
                  if (!k.name.exists(_.isUpper)) {
                    val elements = List("death", "earth", "energy", "fire", "ice", "holy", "a trap", "agony", "life drain", "drowning")
                    vowelCheck = k.name.take(1) match {
                      case _ if elements.contains(k.name) => ""
                      case "a" => "an "
                      case "e" => "an "
                      case "i" => "an "
                      case "o" => "an "
                      case "u" => "an "
                      case _ => "a "
                    }
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
                val futureResults: Future[Seq[CharacterResponse]] = exivaBufferFlow.run()
                futureResults.onComplete {
                  case Success(output) =>
                    val huntedBuffer = ListBuffer[(String, String, String, Int)]()
                    output.foreach { charResponse =>
                      val killerName = charResponse.characters.character.name
                      val killerGuild = charResponse.characters.character.guild
                      val killerWorld = charResponse.characters.character.world
                      val killerVocation = vocEmoji(charResponse.characters.character.vocation)
                      val killerLevel = charResponse.characters.character.level.toInt
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
                          if (!huntedBuffer.exists(_._1 == killerName)) {
                            // add them to hunted list
                            huntedBuffer += ((killerName, killerWorld, killerVocation, killerLevel))
                          }
                        }
                      }
                    }

                    // process the new batch of players to add to hunted list
                    if (huntedBuffer.nonEmpty) {
                      val adminTextChannel = guild.getTextChannelById(adminChannel)
                      if (adminTextChannel != null) {
                        huntedBuffer.foreach { case (player, world, vocation, level) =>
                          val playerString = player.toLowerCase()
                          // add them to cached huntedPlayersData list
                          huntedPlayersData = huntedPlayersData + (guildId -> (BotApp.Players(playerString, "false", "killed an allied player", BotApp.botUser) :: huntedPlayersData.getOrElse(guildId, List())))
                          // add them to the database
                          BotApp.addHuntedToDatabase(guild, "player", playerString, "false", "killed an allied player", BotApp.botUser)
                          // send embed to admin channel
                          val commandUser = s"<@${BotApp.botUser}>"
                          val adminEmbed = new EmbedBuilder()
                          adminEmbed.setTitle(":robot: enemy automatically detected:")
                          adminEmbed.setDescription(s"$commandUser added the player\n$vocation **$level** — **[$player](${charUrl(player)})**\nto the hunted list for **$world**.")
                          adminEmbed.setThumbnail(creatureImageUrl("Stone_Coffin"))
                          adminEmbed.setColor(14397256) // orange for bot auto command
                          try {
                            adminTextChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                          } catch {
                            case ex: Exception => logger.error(s"Failed to send message to 'command-log' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}': ${ex.getMessage}")
                            case _: Throwable => logger.error(s"Failed to send message to 'command-log' channel for Guild ID: '${guildId}' Guild Name: '${guild.getName}'")
                          }
                        }
                      }
                    }
                  case Failure(_) => // e.printStackTrace
                }
              }
            }

            // convert formatted killer list to one string
            val killerInit = if (killerBuffer.nonEmpty) killerBuffer.view.init else None
            var killerText =
              //noinspection ScalaDeprecation
              if (killerInit.iterator.nonEmpty) {
                //noinspection ScalaDeprecation
                killerInit.iterator.mkString(", ") + " and " + killerBuffer.last
              } else killerBuffer.headOption.getOrElse("")

            // this should only occur to pure suicides on bomb runes, or pure 'assists' deaths in yellow-skull friendy fire or retro/hardcore situations
            if (killerText == "") {
                embedThumbnail = creatureImageUrl("Red_Skull_(Item)")
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
            var embedCheck = true
            if (embedColor == 3092790 || embedColor == 14869218 || embedColor == 4540237 || embedColor == 14397256) {
              if(showNeutralDeaths == "false") {
                embedCheck = false
              }
            } else if (embedColor == 36941) {
              if(showEnemiesDeaths == "false") {
                embedCheck = false
              }
            } else if (embedColor == 13773097) {
              if(showAlliesDeaths == "false") {
                embedCheck = false
              }
            }
            val embed = new EmbedBuilder()
            embed.setTitle(s"${vocEmoji(charDeath.char.characters.character.vocation)} $charName ${vocEmoji(charDeath.char.characters.character.vocation)}", charUrl(charName))
            embed.setDescription(embedText)
            embed.setThumbnail(embedThumbnail)
            embed.setColor(embedColor)

            // return embed + poke
            (embed, notablePoke, charName, embedText, charDeath.death.level.toInt, embedCheck)
          }
          val fullblessLevel = worldData.headOption.map(_.fullblessLevel).getOrElse(250)
          val minimumLevel = worldData.headOption.map(_.deathsMin).getOrElse(8)
          embeds.foreach { embed =>
            if (embed._6) { // embedCheck
              try {
                // nemesis and enemy fullbless ignore the level filter
                if (embed._2 == "nemesis") {
                  deathsTextChannel.sendMessage(s"<@&$nemesisRole>").setEmbeds(embed._1.build()).queue()
                } else if (embed._2 == "fullbless") {
                  // send adjusted embed for fullblesses
                  val adjustedMessage = embed._4 + s"""\n${Config.exivaEmoji} `exiva "${embed._3}"`"""
                  val adjustedEmbed = embed._1.setDescription(adjustedMessage)
                  if (embed._5 >= fullblessLevel) { // only poke for 250+
                    deathsTextChannel.sendMessage(s"<@&$fullblessRole>").setEmbeds(adjustedEmbed.build()).queue()
                  } else {
                    deathsTextChannel.sendMessageEmbeds(adjustedEmbed.build()).queue()
                  }
                } else {
                  // for regular deaths check if level > /filter deaths <level>
                  if (embed._5 >= minimumLevel) {
                    deathsTextChannel.sendMessageEmbeds(embed._1.build()).setSuppressedNotifications(true).queue()
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

    cleanUp()

    Future.successful()
  }.withAttributes(logAndResume)

  private def onlineList(onlineData: List[CurrentOnline], guildId: String, alliesChannel: String, neutralsChannel: String, enemiesChannel: String, categoryChannel: String, onlineCombined: String, world: String): Unit = {

    val vocationBuffers = ListMap(
      "druid" -> ListBuffer[CharSort](),
      "knight" -> ListBuffer[CharSort](),
      "paladin" -> ListBuffer[CharSort](),
      "sorcerer" -> ListBuffer[CharSort](),
      "none" -> ListBuffer[CharSort]()
    )

    val sortedList = onlineData.sortWith(_.level > _.level)
    sortedList.foreach { player =>
      val voc = player.vocation.toLowerCase.split(' ').last
      val vocationEmoji = vocEmoji(voc)
      val durationInSec = player.duration
      val durationInMin = durationInSec / 60
      val durationStr = if (durationInMin >= 60) {
        val hours = durationInMin / 60
        val mins = durationInMin % 60
        s"${hours}hr ${mins}min"
      } else {
        s"${durationInMin}min"
      }
      val durationString = s"`$durationStr`"
      // get appropriate guild icon
      val allyGuildCheck = alliedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == player.guildName.toLowerCase())
      val huntedGuildCheck = huntedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == player.guildName.toLowerCase())
      val allyPlayerCheck = alliedPlayersData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == player.name.toLowerCase())
      val huntedPlayerCheck = huntedPlayersData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == player.name.toLowerCase())
      val guildIcon = (player.guildName, allyGuildCheck, huntedGuildCheck, allyPlayerCheck, huntedPlayerCheck) match {
        case (_, true, _, _, _) => Config.allyGuild // allied-guilds
        case (_, _, true, _, _) => Config.enemyGuild // hunted-guilds
        case ("", _, _, true, _) => Config.ally // allied-players not in any guild
        case (_, _, _, true, _) => s"${Config.otherGuild}${Config.ally}" // allied-players but in neutral guild
        case ("", _, _, _, true) => Config.enemy // hunted-players no guild
        case (_, _, _, _, true) => s"${Config.otherGuild}${Config.enemy}" // hunted-players but in neutral guild
        case ("", _, _, _, _) => "" // no guild (not ally or hunted)
        case _ => Config.otherGuild // guild (not ally or hunted)
      }

      // Initialize with empty strings
      var (categoryLabel, categoryEmoji) = ("", "")

      // Query customSortData for the guildId and match player.guildName within the guild's data
      val customSortGuildMatches = customSortData.getOrElse(guildId, List())
        .filter(entry => entry.entityType == "guild" && entry.name.toLowerCase() == player.guildName.toLowerCase())
      if (customSortGuildMatches.nonEmpty) {
        val matchData = customSortGuildMatches.head // Use the first match found
        categoryLabel = matchData.label
        categoryEmoji = matchData.emoji
      }

      // Query customSortData for the guildId and match player.name within the player's data
      val customSortPlayerMatches = customSortData.getOrElse(guildId, List())
        .filter(entry => entry.entityType == "player" && entry.name.toLowerCase() == player.name.toLowerCase())
      if (customSortPlayerMatches.nonEmpty) {
        val matchData = customSortPlayerMatches.head // Use the first match found
        categoryLabel = matchData.label
        categoryEmoji = matchData.emoji
      }

      vocationBuffers(voc) += CharSort(player.guildName, allyGuildCheck, huntedGuildCheck, allyPlayerCheck, huntedPlayerCheck, categoryEmoji, categoryLabel, s"$vocationEmoji **${player.level.toString}** — **[${player.name}](${charUrl(player.name)})** $guildIcon $durationString ${player.flag}")
    }
    val pattern = "^(.*?)(?:-[0-9]+)?$".r

    // run channel checks before updating the channels
    val guild = BotApp.jda.getGuildById(guildId)

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

    // combined online list into one channel
    if (onlineCombined == "true") {
      val combinedTextChannel = guild.getTextChannelById(alliesChannel)
      if (combinedTextChannel != null) {

        val groupedNeutrals: List[(String, List[String])] = vocationBuffers.values
          .flatMap(_.filter(charSort => !charSort.huntedPlayer && !charSort.huntedGuild && !charSort.allyPlayer && !charSort.allyGuild))
          .groupBy(_.category)
          .toList
          .sortBy { case (category, _) => (category.isEmpty, category) }
          .map { case (category, charSorts) =>
            val (countOfItems, messages) = charSorts.foldLeft(0 -> List.empty[String]) {
              case ((count, accMessages), charSort) =>
                count + 1 -> (accMessages :+ charSort.message)
            }
            val categoryHeader = if (category.isEmpty) {
              s"### ${Config.neutral} Others ${Config.neutral}"
            } else {
              val categoryEmoji = charSorts.headOption.map(_.categoryEmoji).getOrElse("") // Use categoryEmoji from the first CharSort if available
              s"### $categoryEmoji ${category.capitalize} $categoryEmoji"
            }
            (s"$categoryHeader $countOfItems", messages)
          }

        val groupedNeutralsList: List[String] = groupedNeutrals.flatMap { case (categoryHeader, messages) =>
          categoryHeader :: messages
        }
        val totalCount = alliesList.size + neutralsList.size + enemiesList.size

        val modifiedAlliesList = if (alliesList.nonEmpty) {
          if (neutralsList.nonEmpty || enemiesList.nonEmpty) {
            List(s"### ${Config.ally} **Allies** ${Config.ally} ${alliesList.size}") ++ alliesList
          } else {
            alliesList
          }
        } else {
          alliesList
        }
        val modifiedEnemiesList = if (enemiesList.nonEmpty) {
          if (alliesList.nonEmpty || neutralsList.nonEmpty) {
            List(s"### ${Config.enemy} **Enemies** ${Config.enemy} ${enemiesList.size}") ++ enemiesList
          } else {
            enemiesList
          }
        } else {
          enemiesList
        }

        val combinedList = {
          val headerToRemove = s"### ${Config.neutral} Others ${Config.neutral}"
          val hasOtherHeaders = groupedNeutralsList.exists(header => header.startsWith("### ") && !header.startsWith(headerToRemove))

          if (modifiedAlliesList.isEmpty && modifiedEnemiesList.isEmpty && !hasOtherHeaders) {
            groupedNeutralsList.filterNot(header => header.startsWith(headerToRemove))
          } else {
            modifiedAlliesList ++ modifiedEnemiesList ++ groupedNeutralsList
          }
        }

        // allow for custom channel names
        val channelName = combinedTextChannel.getName
        val extractName = pattern.findFirstMatchIn(channelName)
        val customName = if (extractName.isDefined) {
          val m = extractName.get
          m.group(1)
        } else "online"
        if (channelName != s"$customName-$totalCount") {
          try {
            val channelManager = combinedTextChannel.getManager
            channelManager.setName(s"$customName-$totalCount").queue()
          } catch {
            case ex: Throwable => logger.info(s"Failed to rename the online list channel for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
          }
        }
        if (combinedList.nonEmpty) {
          updateMultiFields(combinedList, combinedTextChannel, "allies", guildId, guild.getName)
        } else {
          updateMultiFields(List("*Nobody is online right now.*"), combinedTextChannel, "allies", guildId, guild.getName)
        }
      }
      val neutralsTextChannel = guild.getTextChannelById(neutralsChannel)
      if (neutralsTextChannel != null) {
        // allow for custom channel names
        val channelName = neutralsTextChannel.getName
        val extractName = pattern.findFirstMatchIn(channelName)
        val customName = if (extractName.isDefined) {
          val m = extractName.get
          m.group(1)
        } else "neutrals"
        if (channelName != s"$customName-0") {
          try {
            val channelManager = neutralsTextChannel.getManager
            channelManager.setName(s"$customName-0").queue()
          } catch {
            case ex: Throwable => logger.info(s"Failed to rename the disabled neutral channel for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
          }
        }
        // placeholder message
        updateMultiFields(List("*This channel is `disabled` and can be deleted.*"), neutralsTextChannel, "neutrals", guildId, guild.getName)
      }
      val enemiesTextChannel = guild.getTextChannelById(enemiesChannel)
      if (enemiesTextChannel != null) {
        // allow for custom channel names
        val channelName = enemiesTextChannel.getName
        val extractName = pattern.findFirstMatchIn(channelName)
        val customName = if (extractName.isDefined) {
          val m = extractName.get
          m.group(1)
        } else "enemies"
        if (channelName != s"$customName-0") {
          try {
            val channelManager = enemiesTextChannel.getManager
            channelManager.setName(s"$customName-0").queue()
          } catch {
            case ex: Throwable => logger.info(s"Failed to rename the disabled enemies channel for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
          }
        }
        // placeholder message
        updateMultiFields(List("*This channel is `disabled` and can be deleted.*"), enemiesTextChannel, "enemies", guildId, guild.getName)
      }

      // add allies/enemies count to the category
      val categoryLiteral = guild.getCategoryById(categoryChannel)
      if (categoryLiteral != null){
        try {
          val categoryName = categoryLiteral.getName
          val categoryAllies = if (alliesList.size > 0) s"🤍${alliesList.size}" else ""
          val categoryEnemies = if (enemiesList.size > 0) s"💀${enemiesList.size}" else ""
          val categorySpacer = if (alliesList.size > 0 || enemiesList.size > 0) "・" else ""
          if (categoryName != s"${world}$categorySpacer$categoryAllies$categoryEnemies") {
            val channelManager = categoryLiteral.getManager
            channelManager.setName(s"${world}$categorySpacer$categoryAllies$categoryEnemies").queue()
          }
        } catch {
          case ex: Throwable => logger.info(s"Failed to rename the category channel for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
        }
      }
    }

    // separated online list channels
    else {

      val alliesCount = alliesList.size
      val neutralsCount = neutralsList.size
      val enemiesCount = enemiesList.size

      val alliesTextChannel = guild.getTextChannelById(alliesChannel)
      if (alliesTextChannel != null) {
        // allow for custom channel names
        val channelName = alliesTextChannel.getName
        val extractName = pattern.findFirstMatchIn(channelName)
        val customName = if (extractName.isDefined) {
          val m = extractName.get
          m.group(1)
        } else "allies"
        if (channelName != s"$customName-$alliesCount") {
          try {
            val channelManager = alliesTextChannel.getManager
            channelManager.setName(s"$customName-$alliesCount").queue()
          } catch {
            case ex: Throwable => logger.info(s"Failed to rename the allies channel for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
          }
        }
        if (alliesList.nonEmpty) {
          updateMultiFields(alliesList, alliesTextChannel, "allies", guildId, guild.getName)
        } else {
          updateMultiFields(List("*No `allies` are online right now.*"), alliesTextChannel, "allies", guildId, guild.getName)
        }
      }
      val neutralsTextChannel = guild.getTextChannelById(neutralsChannel)
      if (neutralsTextChannel != null) {
        // allow for custom channel names
        val channelName = neutralsTextChannel.getName
        val extractName = pattern.findFirstMatchIn(channelName)
        val customName = if (extractName.isDefined) {
          val m = extractName.get
          m.group(1)
        } else "neutrals"
        if (channelName != s"$customName-$neutralsCount") {
          try {
            val channelManager = neutralsTextChannel.getManager
            channelManager.setName(s"$customName-$neutralsCount").queue()
          } catch {
            case ex: Throwable => logger.info(s"Failed to rename the neutrals channel for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
          }
        }
        if (neutralsList.nonEmpty) {
          updateMultiFields(neutralsList, neutralsTextChannel, "neutrals", guildId, guild.getName)
        } else {
          updateMultiFields(List("*No `neutrals` are online right now.*"), neutralsTextChannel, "neutrals", guildId, guild.getName)
        }
      }
      val enemiesTextChannel = guild.getTextChannelById(enemiesChannel)
      if (enemiesTextChannel != null) {
        // allow for custom channel names
        val channelName = enemiesTextChannel.getName
        val extractName = pattern.findFirstMatchIn(channelName)
        val customName = if (extractName.isDefined) {
          val m = extractName.get
          m.group(1)
        } else "enemies"
        if (channelName != s"$customName-$enemiesCount") {
          try {
            val channelManager = enemiesTextChannel.getManager
            channelManager.setName(s"$customName-$enemiesCount").queue()
          } catch {
            case ex: Throwable => logger.info(s"Failed to rename the enemies channel for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
          }
        }
        if (enemiesList.nonEmpty) {
          updateMultiFields(enemiesList, enemiesTextChannel, "enemies", guildId, guild.getName)
        } else {
          updateMultiFields(List("*No `enemies` are online right now.*"), enemiesTextChannel, "enemies", guildId, guild.getName)
        }
      }
    }

  }

  private def updateMultiFields(values: List[String], channel: TextChannel, purgeType: String, guildId: String, guildName: String): Unit = {
    var field = ""
    val embedColor = 3092790
    //get messages
    try {
      var messages = channel.getHistory.retrievePast(100).complete().asScala.filter(m => m.getAuthor.getId.equals(BotApp.botUser)).toList.reverse.asJava

      // val enemyTimer = enemiesListPurgeTimer.getOrElse(guildId, ZonedDateTime.parse("2022-01-01T01:00:00Z"))
      // if (ZonedDateTime.now().isAfter(neutralTimer.plusHours(6))) {
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

      var currentMessage = 0
      values.foreach { v =>
        val currentField = field + "\n" + v
        if (currentField.length >= 4096) { // don't add field yet, there is still room
          val interimEmbed = new EmbedBuilder()
          interimEmbed.setDescription(field)
          interimEmbed.setColor(embedColor)
          if (currentMessage < messages.size) {
            // edit the existing message
            messages.get(currentMessage).editMessageEmbeds(interimEmbed.build()).queue()
          }
          else {
            // there isn't an existing message to edit, so post a new one
            channel.sendMessageEmbeds(interimEmbed.build()).setSuppressedNotifications(true).queue()
          }
          field = v
          currentMessage += 1
        } else if (v.startsWith(s"### ")) {
          if (field == "") {
            field = currentField
          } else {
            val interimEmbed = new EmbedBuilder()
            interimEmbed.setDescription(field)
            interimEmbed.setColor(embedColor)
            if (currentMessage < messages.size) {
              // edit the existing message
              messages.get(currentMessage).editMessageEmbeds(interimEmbed.build()).queue()
            }
            else {
              // there isn't an existing message to edit, so post a new one
              channel.sendMessageEmbeds(interimEmbed.build()).setSuppressedNotifications(true).queue()
            }
            field = v
            currentMessage += 1
          }
        } else { // it's full, add the field
          field = currentField
        }
      }
      val finalEmbed = new EmbedBuilder()
      finalEmbed.setDescription(field)
      finalEmbed.setColor(embedColor)
      finalEmbed.setFooter("Last updated")
      val timestamp = OffsetDateTime.now()
      finalEmbed.setTimestamp(timestamp)
      if (currentMessage < messages.size) {
        // edit the existing message
        messages.get(currentMessage).editMessageEmbeds(finalEmbed.build()).queue()
      }
      else {
        // there isn't an existing message to edit, so post a new one
        channel.sendMessageEmbeds(finalEmbed.build()).setSuppressedNotifications(true).queue()
      }
      if (currentMessage < messages.size - 1) {
        // delete extra messages
        val messagesToDelete = messages.subList(currentMessage + 1, messages.size)
        channel.purgeMessages(messagesToDelete)
      }
    } catch {
      case e: Exception =>
      logger.error(s"Failed to update online list for Guild ID: '$guildId' Guild Name: '$guildName' because of an error: ${e.getMessage()}" )
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
    recentLevels.filterInPlace { i =>
      val diff = java.time.Duration.between(i.time, now).getSeconds
      diff < recentLevelExpiry
    }
  }

  private def vocEmoji(vocation: String): String = {
    val voc = vocation.toLowerCase.split(' ').last
    voc match {
      case "knight" => ":shield:"
      case "druid" => ":snowflake:"
      case "sorcerer" => ":fire:"
      case "paladin" => ":bow_and_arrow:"
      case "none" => ":hatching_chick:"
      case _ => ""
    }
  }

  private def guildUrl(guild: String): String =
    s"https://www.tibia.com/community/?subtopic=guilds&page=view&GuildName=${guild.replaceAll(" ", "+")}"

  private def charUrl(char: String): String =
    s"https://www.tibia.com/community/?name=${char.replaceAll(" ", "+")}"

  private def creatureImageUrl(creature: String): String = {
    val finalCreature = Config.creatureUrlMappings.getOrElse(creature.toLowerCase, {
      // Capitalise the start of each word, including after punctuation e.g. "Mooh'Tah Warrior", "Two-Headed Turtle"
      val rx1 = """([^\w]\w)""".r
      val parsed1 = rx1.replaceAllIn(creature, m => m.group(1).toUpperCase)

      // Lowercase the articles, prepositions etc., e.g. "The Voice of Ruin"
      val rx2 = """( A| Of| The| In| On| To| And| With| From)(?=( ))""".r
      val parsed2 = rx2.replaceAllIn(parsed1, m => m.group(1).toLowerCase)

      // Replace spaces with underscores and make sure the first letter is capitalised
      parsed2.replaceAll(" ", "_").capitalize
    })
    s"https://tibia.fandom.com/wiki/Special:Redirect/file/$finalCreature.gif"
  }

  lazy val stream: RunnableGraph[Cancellable] =
    sourceTick via
      getWorld via
      getCharacterData via
      scanForDeaths via
      postToDiscordAndCleanUp to Sink.ignore

}
