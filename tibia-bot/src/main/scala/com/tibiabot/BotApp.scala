package com.tibiabot

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.tibiabot.tibiadata.TibiaDataClient
import com.tibiabot.tibiadata.response.{CharacterResponse, GuildResponse, BoostedResponse, CreatureResponse, RaceResponse, Members}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.{Guild, MessageEmbed}
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.interactions.commands.build.{Commands, OptionData, SlashCommandData, SubcommandData, SubcommandGroupData}
import net.dv8tion.jda.api.interactions.commands.{DefaultMemberPermissions, OptionType}
import net.dv8tion.jda.api.interactions.components.buttons._
import net.dv8tion.jda.api.{EmbedBuilder, JDABuilder, Permission}
import org.postgresql.util.PSQLException
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.Message

import java.awt.Color
import java.sql.{Connection, DriverManager, Timestamp}
import java.time.{Instant, ZoneOffset, ZonedDateTime}
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

object BotApp extends App with StrictLogging {

  case class Worlds(name: String,
    alliesChannel: String,
    enemiesChannel: String,
    neutralsChannel: String,
    levelsChannel: String,
    deathsChannel: String,
    category: String,
    fullblessRole: String,
    nemesisRole: String,
    fullblessChannel: String,
    nemesisChannel: String,
    fullblessLevel: Int,
    showNeutralLevels: String,
    showNeutralDeaths: String,
    showAlliesLevels: String,
    showAlliesDeaths: String,
    showEnemiesLevels: String,
    showEnemiesDeaths: String,
    detectHunteds: String,
    levelsMin: Int,
    deathsMin: Int,
    exivaList: String,
    activityChannel: String,
    onlineCombined: String
  )

  private case class Streams(stream: akka.actor.Cancellable, usedBy: List[Discords])
  case class Discords(id: String, adminChannel: String, boostedChannel: String, boostedMessage: String)
  case class Players(name: String, reason: String, reasonText: String, addedBy: String)
  case class BoostedCache(boss: String, creature: String)
  case class PlayerCache(name: String, formerNames: List[String], guild: String, updatedTime: ZonedDateTime)
  case class Guilds(name: String, reason: String, reasonText: String, addedBy: String)
  case class DeathsCache(world: String, name: String, time: String)
  case class LevelsCache(world: String, name: String, level: String, vocation: String, lastLogin: String, time: String)
  case class ListCache(name: String, formerNames: List[String], world: String, formerWorlds: List[String], guild: String, level: String, vocation: String, last_login: String, updatedTime: ZonedDateTime)
  case class SatchelStamp(user: String, when: ZonedDateTime, tag: String)
  case class BoostedStamp(user: String, boostedType: String, boostedName: String)
  case class CustomSort(entityType: String, name: String, label: String, emoji: String)

  implicit private val actorSystem: ActorSystem = ActorSystem()
  implicit private val ex: ExecutionContextExecutor = actorSystem.dispatcher
  private val tibiaDataClient = new TibiaDataClient()

  // Let the games begin
  logger.info("Starting up")

  val jda = JDABuilder.createDefault(Config.token)
    .addEventListeners(new BotListener())
    .build()

  jda.awaitReady()
  logger.info("JDA ready")

  // get the discord servers the bot is in
  private val guilds: List[Guild] = jda.getGuilds.asScala.toList

  // stream list
  private var botStreams = Map[String, Streams]()

  // get bot userID (used to stamp automated enemy detection messages)
  val botUser = jda.getSelfUser.getId
  private val botName = jda.getSelfUser.getName

  // initialize core hunted/allied list
  var customSortData: Map[String, List[CustomSort]] = Map.empty
  var huntedPlayersData: Map[String, List[Players]] = Map.empty
  var alliedPlayersData: Map[String, List[Players]] = Map.empty
  var huntedGuildsData: Map[String, List[Guilds]] = Map.empty
  var alliedGuildsData: Map[String, List[Guilds]] = Map.empty
  var activityData: Map[String, List[PlayerCache]] = Map.empty
  var activityCommandBlocker: Map[String, Boolean] = Map.empty

  var worldsData: Map[String, List[Worlds]] = Map.empty
  var discordsData: Map[String, List[Discords]] = Map.empty
  var worlds: List[String] = Config.worldList

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
  val bossesFutures: Future[List[String]] = for {
    bosses <- bossFuture
  } yield bosses

  val boostedBossesList: List[String] = Await.result(bossesFutures, 10.seconds)

  // create the command to set up the bot
  private val setupCommand: SlashCommandData = Commands.slash("setup", "Setup a world to be tracked")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addOptions(new OptionData(OptionType.STRING, "world", "The world you want to track")
    .setRequired(true))

  // remove world command
  private val removeCommand: SlashCommandData = Commands.slash("remove", "Remove a world from being tracked")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addOptions(new OptionData(OptionType.STRING, "world", "The world you want to remove")
    .setRequired(true))

  // hunted command
  private val huntedCommand: SlashCommandData = Commands.slash("hunted", "Manage the hunted list")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addSubcommands(
      new SubcommandData("guild", "Manage guilds in the hunted list")
      .addOptions(
        new OptionData(OptionType.STRING, "option", "Would you like to add or remove a guild?").setRequired(true)
          .addChoices(
            new Choice("add", "add"),
            new Choice("remove", "remove")
          ),
        new OptionData(OptionType.STRING, "name", "The guild name you want to add to the hunted list").setRequired(true)
        ),
      new SubcommandData("player", "Manage players in the hunted list")
      .addOptions(
        new OptionData(OptionType.STRING, "option", "Would you like to add or remove a player?").setRequired(true)
          .addChoices(
            new Choice("add", "add"),
            new Choice("remove", "remove")
          ),
        new OptionData(OptionType.STRING, "name", "The player name you want to add to the hunted list").setRequired(true),
        new OptionData(OptionType.STRING, "reason", "You can add a reason when players are added to the hunted list")
        ),
      new SubcommandData("list", "List players & guilds in the hunted list"),
      new SubcommandData("info", "Show detailed info on a hunted player")
        .addOptions(new OptionData(OptionType.STRING, "name", "The player name you want to check").setRequired(true)
      ),
      new SubcommandData("autodetect", "Configure the auto-detection on or off")
        .addOptions(
          new OptionData(OptionType.STRING, "option", "Would you like to toggle it on or off?").setRequired(true)
            .addChoices(
              new Choice("on", "on"),
              new Choice("off", "off")
            ),
          new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
        ),
      new SubcommandData("levels", "Show or hide hunted levels")
        .addOptions(
          new OptionData(OptionType.STRING, "option", "Would you like to show or hide hunted levels?").setRequired(true)
            .addChoices(
              new Choice("show", "show"),
              new Choice("hide", "hide")
            ),
          new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
        ),
      new SubcommandData("deaths", "Show or hide hunted deaths")
        .addOptions(
          new OptionData(OptionType.STRING, "option", "Would you like to show or hide hunted deaths?").setRequired(true)
            .addChoices(
              new Choice("show", "show"),
              new Choice("hide", "hide")
            ),
          new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
        )
      )

  // allies command
  private val alliesCommand: SlashCommandData = Commands.slash("allies", "Manage the allies list")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addSubcommands(
      new SubcommandData("guild", "Manage guilds in the allies list")
      .addOptions(
        new OptionData(OptionType.STRING, "option", "Would you like to add or remove a guild?").setRequired(true)
          .addChoices(
            new Choice("add", "add"),
            new Choice("remove", "remove")
          ),
        new OptionData(OptionType.STRING, "name", "The guild name you want to add to the allies list").setRequired(true)
        ),
      new SubcommandData("player", "Manage players in the allies list")
      .addOptions(
        new OptionData(OptionType.STRING, "option", "Would you like to add or remove a player?").setRequired(true)
          .addChoices(
            new Choice("add", "add"),
            new Choice("remove", "remove")
          ),
        new OptionData(OptionType.STRING, "name", "The player name you want to add to the allies list").setRequired(true)
        ),
      new SubcommandData("list", "List players & guilds in the allies list"),
      new SubcommandData("info", "Show detailed info on a allies player")
        .addOptions(new OptionData(OptionType.STRING, "name", "The player name you want to check").setRequired(true)
      ),
      new SubcommandData("levels", "Show or hide ally levels")
        .addOptions(
          new OptionData(OptionType.STRING, "option", "Would you like to show or hide ally levels?").setRequired(true)
            .addChoices(
              new Choice("show", "show"),
              new Choice("hide", "hide")
            ),
          new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
        ),
      new SubcommandData("deaths", "Show or hide ally deaths")
        .addOptions(
          new OptionData(OptionType.STRING, "option", "Would you like to show or hide ally levels?").setRequired(true)
            .addChoices(
              new Choice("show", "show"),
              new Choice("hide", "hide")
            ),
          new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
        )
      )

  // neutrals command
  private val neutralsCommand: SlashCommandData = Commands.slash("neutral", "Configuration options for neutrals")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addSubcommandGroups(
      new SubcommandGroupData("tag", "Manage your custom tags for neutrals")
      .addSubcommands(
        new SubcommandData("add", "Tag a neutral guild or player for deaths and for the combined online list")
          .addOptions(
            new OptionData(OptionType.STRING, "type", "Would you like to tag a guild or a player?").setRequired(true)
              .addChoices(
                new Choice("guild", "guild"),
                new Choice("player", "player")
              ),
            new OptionData(OptionType.STRING, "name", "The name of the player or guild").setRequired(true),
            new OptionData(OptionType.STRING, "label", "The label you want for these tags in the combined online list")
              .setRequired(true)
              .setMaxLength(30),
            new OptionData(OptionType.STRING, "emoji", "The emoji you want for these tags in the combined online list").setRequired(true)
          ),
        new SubcommandData("remove", "Remove a tag")
          .addOptions(
            new OptionData(OptionType.STRING, "type", "Would you like to tag a guild or a player?").setRequired(true)
              .addChoices(
                new Choice("guild", "guild"),
                new Choice("player", "player")
              ),
            new OptionData(OptionType.STRING, "name", "The name of the player or guild").setRequired(true)
          ),
        new SubcommandData("clear", "Clear all entries everything for a specific tag")
          .addOptions(
            new OptionData(OptionType.STRING, "label", "What tag would you like to clear?").setRequired(true)
          ),
        new SubcommandData("list", "Show a list all of your custom tags")
      )
    )
    .addSubcommands(
      new SubcommandData("levels", "Show or hide neutral levels")
        .addOptions(
          new OptionData(OptionType.STRING, "option", "Would you like to show or hide neutral levels?").setRequired(true)
            .addChoices(
              new Choice("show", "show"),
              new Choice("hide", "hide")
            ),
          new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
        ),
      new SubcommandData("deaths", "Show or hide neutral deaths")
        .addOptions(
          new OptionData(OptionType.STRING, "option", "Would you like to show or hide neutral levels?").setRequired(true)
            .addChoices(
              new Choice("show", "show"),
              new Choice("hide", "hide")
            ),
          new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
        )
    )

  // fullbless command
  private val fullblessCommand: SlashCommandData = Commands.slash("fullbless", "Modify the level at which enemy fullblesses poke")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addOptions(
      new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true),
      new OptionData(OptionType.INTEGER, "level", "The minimum level you want to set for fullbless pokes").setRequired(true)
        .setMinValue(1)
        .setMaxValue(4000)
    )

  // minimum levels/deaths command
  private val filterCommand: SlashCommandData = Commands.slash("filter", "Set a minimum level for the levels or deaths channels")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addSubcommands(
      new SubcommandData("levels", "Hide events in the levels channel if the character is below a certain level")
      .addOptions(
        new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true),
        new OptionData(OptionType.INTEGER, "level", "The minimum level you want to set for the levels channel").setRequired(true)
          .setMinValue(1)
          .setMaxValue(4000)
      ),
      new SubcommandData("deaths", "Hide events in the deaths channel if the character is below a certain level")
      .addOptions(
        new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true),
        new OptionData(OptionType.INTEGER, "level", "The minimum level you want to set for the deaths channel").setRequired(true)
          .setMinValue(1)
          .setMaxValue(4000)
      )
    )

  // remove world command
  private val adminCommand: SlashCommandData = Commands.slash("admin", "Commands only available to the bot creator")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addSubcommands(
      new SubcommandData("leave", "Force the bot to leave a specific discord")
      .addOptions(
        new OptionData(OptionType.STRING, "guildid", "The guild ID you want the bot to leave").setRequired(true),
        new OptionData(OptionType.STRING, "reason", "What reason do you want to leave for the discord owner?").setRequired(true)
      ),
      new SubcommandData("message", "Send a message to a specific discord")
      .addOptions(
        new OptionData(OptionType.STRING, "guildid", "The guild ID you want the bot to leave").setRequired(true),
        new OptionData(OptionType.STRING, "message", "What message do you want to leave for the discord owner?").setRequired(true)
      )
    )

  // exiva command
  private val exivaCommand: SlashCommandData = Commands.slash("exiva", "Show or hide exiva lists on death posts")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addSubcommands(
      new SubcommandData("deaths", "Show or hide the exiva list in the deaths channel")
        .addOptions(
          new OptionData(OptionType.STRING, "option", "Would you like to show or hide the exiva list?").setRequired(true)
            .addChoices(
              new Choice("show", "show"),
              new Choice("hide", "hide")
            ),
          new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
        )
    )

    // exiva command
    private val helpCommand: SlashCommandData = Commands.slash("help", "Resend the welcome message & basic getting started information")
      .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))

  // recreate channel command
  private val repairCommand: SlashCommandData = Commands.slash("repair", "Repair & recreate channels that have been deleted for a specific world")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
      .addOptions(
        new OptionData(OptionType.STRING, "world", "What world are you trying to recreate channels for?").setRequired(true),
      )

  // set galthen satchel reminder
  private val galthenCommand: SlashCommandData = Commands.slash("galthen", "Use this to set a galthen satchel cooldown timer")
    .addSubcommands(
      new SubcommandData("satchel", "Use this to set a galthen satchel cooldown timer")
      .addOptions(
        new OptionData(OptionType.STRING, "character", "What character/tag is this for?")
      )
    )

  // online list config  command
  private val onlineCombineCommand: SlashCommandData = Commands.slash("online", "Configure how the online list is displayed")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addSubcommands(
      new SubcommandData("list", "Configure the online list")
        .addOptions(
          new OptionData(OptionType.STRING, "option", "Would you like to combine the list into one channel or keep them separate?").setRequired(true)
            .addChoices(
              new Choice("separate", "separate"),
              new Choice("combine", "combine")
            ),
          new OptionData(OptionType.STRING, "world", "The world you want to configure this setting for").setRequired(true)
        )
    )

  // online list config  command
  private val boostedCommand: SlashCommandData = Commands.slash("boosted", "Turn off these notifications or filter them")
    .addOptions(
      new OptionData(OptionType.STRING, "option", "Would you like to add/remove a boss or creature?").setRequired(true)
        .addChoices(
          new Choice("list", "list"),
          new Choice("disable", "disable")
        )
    )

  private val refreshCommand: SlashCommandData = Commands.slash("refresh", "Refresh the boosted boss and creature for all discords")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))

  lazy val commands = List(setupCommand, removeCommand, huntedCommand, alliesCommand, neutralsCommand, fullblessCommand, filterCommand, exivaCommand, helpCommand, repairCommand, onlineCombineCommand, galthenCommand, boostedCommand)

  // create the deaths/levels cache db
  createCacheDatabase()

  // initialize the database
  guilds.foreach{g =>
    // update the commands
    if (g.getIdLong == 867319250708463628L) { // Violent Bot Discord
      lazy val adminCommands =
        if (Config.prod)
          List(setupCommand, removeCommand, huntedCommand, alliesCommand, neutralsCommand, fullblessCommand, filterCommand, exivaCommand, helpCommand, adminCommand, repairCommand, onlineCombineCommand, refreshCommand, galthenCommand, boostedCommand)
        else
          List(setupCommand, removeCommand, huntedCommand, alliesCommand, neutralsCommand, fullblessCommand, filterCommand, exivaCommand, helpCommand, adminCommand, repairCommand, onlineCombineCommand, refreshCommand)
      g.updateCommands().addCommands(adminCommands.asJava).complete()
    } else if (g.getIdLong == 912739993015947324L) {
      // they are using Pulsera Bot commands, only /galthen appears here
      List(galthenCommand)
    } else {
      g.updateCommands().addCommands(commands.asJava).complete()
    }
  }

  // Start all world streams
  var startUpComplete = false
  startBot(None, None) // guild: Option[Guild], world: Option[String]

  // run the scheduler to clean cache and update dashboard every hour
  actorSystem.scheduler.schedule(60.seconds, 30.minutes) {
    if (Config.prod) {
      updateDashboard()
    }
    // set activity status
    // only do this every second cycle
    removeDeathsCache(ZonedDateTime.now())
    removeLevelsCache(ZonedDateTime.now())
    cleanHuntedList()
    cleanGalthenList()
  }

  actorSystem.scheduler.schedule(60.seconds, 5.minutes) {
    val currentTime = ZonedDateTime.now(ZoneId.of("Australia/Brisbane")).toLocalTime
    //if (currentTime.isAfter(LocalTime.of(19, 0)) && currentTime.isBefore(LocalTime.of(19, 10))) {
    if (currentTime.isAfter(LocalTime.of(19, 0)) && currentTime.isBefore(LocalTime.of(19, 45))) {
      try {
        boostedMessages().map { boostedBossAndCreature =>
          val currentBoss = boostedBossAndCreature.boss
          val currentCreature = boostedBossAndCreature.creature

          // Boosted Boss
          val boostedBoss: Future[Either[String, BoostedResponse]] = tibiaDataClient.getBoostedBoss()
          val bossEmbedFuture: Future[(MessageEmbed, Boolean, String)] = boostedBoss.map {
            case Right(boostedResponse) =>
              val boostedBoss = boostedResponse.boostable_bosses.boosted.name
              if (boostedBoss.toLowerCase != currentBoss.toLowerCase) {
                boostedMonsterUpdate(boostedBoss, "")
              }
              (
                createBoostedEmbed("Boosted Boss", Config.bossEmoji, "https://www.tibia.com/library/?subtopic=boostablebosses", creatureImageUrl(boostedBoss), s"The boosted boss today is:\n### ${Config.indentEmoji}${Config.archfoeEmoji} **[$boostedBoss](${creatureWikiUrl(boostedBoss)})**"),
                boostedBoss.toLowerCase != currentBoss.toLowerCase && currentBoss.toLowerCase != "none",
                boostedBoss
              )

            case Left(errorMessage) =>
              val boostedBoss = "Podium_of_Vigour"
              (
                createBoostedEmbed("Boosted Boss", Config.bossEmoji, "https://www.tibia.com/library/?subtopic=boostablebosses", creatureImageUrl(boostedBoss), "The boosted boss today failed to load?"),
                false,
                boostedBoss
              )
          }

          // Boosted Creature
          val boostedCreature: Future[Either[String, CreatureResponse]] = tibiaDataClient.getBoostedCreature()
          val creatureEmbedFuture: Future[(MessageEmbed, Boolean, String)] = boostedCreature.map {
            case Right(creatureResponse) =>
              val boostedCreature = creatureResponse.creatures.boosted.name
              if (boostedCreature.toLowerCase != currentCreature.toLowerCase) {
                boostedMonsterUpdate("", boostedCreature)
              }
              (
                createBoostedEmbed("Boosted Creature", Config.creatureEmoji, "https://www.tibia.com/library/?subtopic=creatures", creatureImageUrl(boostedCreature), s"The boosted creature today is:\n### ${Config.indentEmoji}${Config.levelUpEmoji} **[$boostedCreature](${creatureWikiUrl(boostedCreature)})**"),
                boostedCreature.toLowerCase != currentCreature.toLowerCase && currentCreature.toLowerCase != "none",
                boostedCreature
              )

            case Left(errorMessage) =>
              val boostedCreature = "Podium_of_Tenacity"
              (
                createBoostedEmbed("Boosted Creature", Config.creatureEmoji, "https://www.tibia.com/library/?subtopic=creatures", creatureImageUrl(boostedCreature), "The boosted creature today failed to load?"),
                false,
                boostedCreature
              )
          }

          // Combine both futures and send the message
          val combinedFutures: Future[List[(MessageEmbed, Boolean, String)]] = for {
            bossEmbed <- bossEmbedFuture
            creatureEmbed <- creatureEmbedFuture
          } yield List(bossEmbed, creatureEmbed)

          combinedFutures.map { boostedInfoList =>
            if (boostedInfoList.exists(_._2)) {
              // Do something if at least one of the embeds changed
              val embeds: List[MessageEmbed] = boostedInfoList.map { case (embed, _, _) => embed }.toList

              val notificationsList: List[BoostedStamp] = boostedAll()
              notificationsList.foreach { entry =>
                var matchedNotification = false
                boostedInfoList.foreach { case (_, _, boostedName) =>
                  if (boostedName.toLowerCase == entry.boostedName.toLowerCase || entry.boostedName.toLowerCase == "all") {
                    matchedNotification = true
                  }
                }
                if (matchedNotification) {
                  val user: User = jda.retrieveUserById(entry.user).complete()
                  if (user != null) {
                    try {
                      user.openPrivateChannel().queue { privateChannel =>
                        val messageText = s"🔔 ${boostedInfoList.head._3} • ${boostedInfoList.last._3}"
                        privateChannel.sendMessage(messageText).setEmbeds(embeds.asJava).setActionRow(
                          Button.primary("boosted list", " ").withEmoji(Emoji.fromFormatted(Config.letterEmoji))
                        ).queue()
                      }
                    } catch {
                      case ex: Exception => // Handle the exception appropriately
                    }
                  }
                }
              }

              jda.getGuilds.forEach { guild =>
                if (checkConfigDatabase(guild)) {
                  val discordInfo = discordRetrieveConfig(guild)
                  val channelId = if (discordInfo.nonEmpty) discordInfo("boosted_channel") else "0"
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
                        boostedChannel.sendMessageEmbeds(embeds.asJava)
                          .setActionRow(
                            Button.primary("boosted list", "Server Save Notifications").withEmoji(Emoji.fromFormatted(Config.letterEmoji))
                          )
                          .queue((message: Message) => {
                            //updateBoostedMessage(guild.getId, message.getId)
                            discordUpdateConfig(guild, "", "", "", message.getId)
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

  // run hunted list cleanup every day at 6:30 PM AEST
  private val currentTime = Instant.now
  private val targetTime = LocalDateTime.of(LocalDate.now, LocalTime.of(18, 30, 0)).atZone(ZoneId.of("Australia/Sydney")).toInstant
  private val initialDelay = Duration.fromNanos(targetTime.toEpochMilli - currentTime.toEpochMilli).toSeconds.seconds
  private val interval = 24.hours
  // Unused


  def refreshBoostedBoard(): MessageEmbed = {
    val replyEmbed = new EmbedBuilder()
    var replyText = s":x: Failed to update the boosted board messages"
    try {
      boostedMessages().map { boostedBossAndCreature =>
        val currentBoss = boostedBossAndCreature.boss
        val currentCreature = boostedBossAndCreature.creature

        // Boosted Boss
        val boostedBoss: Future[Either[String, BoostedResponse]] = tibiaDataClient.getBoostedBoss()
        val bossEmbedFuture: Future[(MessageEmbed, Boolean, String)] = boostedBoss.map {
          case Right(boostedResponse) =>
            val boostedBoss = boostedResponse.boostable_bosses.boosted.name
            if (boostedBoss.toLowerCase != currentBoss.toLowerCase) {
              boostedMonsterUpdate(boostedBoss, "")
            }
            (
              createBoostedEmbed("Boosted Boss", Config.bossEmoji, "https://www.tibia.com/library/?subtopic=boostablebosses", creatureImageUrl(boostedBoss), s"The boosted boss today is:\n### ${Config.indentEmoji}${Config.archfoeEmoji} **[$boostedBoss](${creatureWikiUrl(boostedBoss)})**"),
              boostedBoss.toLowerCase != currentBoss.toLowerCase && currentBoss.toLowerCase != "none",
              boostedBoss
            )

          case Left(errorMessage) =>
            val boostedBoss = "Podium_of_Vigour"
            (
              createBoostedEmbed("Boosted Boss", Config.bossEmoji, "https://www.tibia.com/library/?subtopic=boostablebosses", creatureImageUrl(boostedBoss), "The boosted boss today failed to load?"),
              false,
              boostedBoss
            )
        }

        // Boosted Creature
        val boostedCreature: Future[Either[String, CreatureResponse]] = tibiaDataClient.getBoostedCreature()
        val creatureEmbedFuture: Future[(MessageEmbed, Boolean, String)] = boostedCreature.map {
          case Right(creatureResponse) =>
            val boostedCreature = creatureResponse.creatures.boosted.name
            if (boostedCreature.toLowerCase != currentCreature.toLowerCase) {
              boostedMonsterUpdate("", boostedCreature)
            }
            (
              createBoostedEmbed("Boosted Creature", Config.creatureEmoji, "https://www.tibia.com/library/?subtopic=creatures", creatureImageUrl(boostedCreature), s"The boosted creature today is:\n### ${Config.indentEmoji}${Config.levelUpEmoji} **[$boostedCreature](${creatureWikiUrl(boostedCreature)})**"),
              boostedCreature.toLowerCase != currentCreature.toLowerCase && currentCreature.toLowerCase != "none",
              boostedCreature
            )

          case Left(errorMessage) =>
            val boostedCreature = "Podium_of_Tenacity"
            (
              createBoostedEmbed("Boosted Creature", Config.creatureEmoji, "https://www.tibia.com/library/?subtopic=creatures", creatureImageUrl(boostedCreature), "The boosted creature today failed to load?"),
              false,
              boostedCreature
            )
        }

        // Combine both futures and send the message
        val combinedFutures: Future[List[(MessageEmbed, Boolean, String)]] = for {
          bossEmbed <- bossEmbedFuture
          creatureEmbed <- creatureEmbedFuture
        } yield List(bossEmbed, creatureEmbed)

        combinedFutures.map { boostedInfoList =>
          val embeds: List[MessageEmbed] = boostedInfoList.map { case (embed, _, _) => embed }.toList
          jda.getGuilds.forEach { guild =>
            if (checkConfigDatabase(guild)) {
              val discordInfo = discordRetrieveConfig(guild)
              val channelId = if (discordInfo.nonEmpty) discordInfo("boosted_channel") else "0"
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
                    boostedChannel.sendMessageEmbeds(embeds.asJava)
                      .setActionRow(
                        Button.primary("boosted list", "Server Save Notifications").withEmoji(Emoji.fromFormatted(Config.letterEmoji))
                      )
                      .queue((message: Message) => {
                        //updateBoostedMessage(guild.getId, message.getId)
                        discordUpdateConfig(guild, "", "", "", message.getId)
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
      replyText = s":gear: Boosted messages queued for deletion and new ones sent"
    }
    catch {
      case _ : Throwable => logger.info("Failed to update the boosted board messages")
    }
    replyEmbed.setDescription(replyText)
    replyEmbed.build()
  }

  private def boostedMonsterUpdate(boss: String, creature: String): Unit = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword

    val conn = DriverManager.getConnection(url, username, password)
    val statement = conn.createStatement()

    val result = statement.executeQuery(s"SELECT boss,creature FROM boosted_info;")

    val results = new ListBuffer[BoostedCache]()
    while (result.next()) {
      val boss = Option(result.getString("boss")).getOrElse("None")
      val creature = Option(result.getString("creature")).getOrElse("None")

      results += BoostedCache(boss, creature)
    }
    statement.close()

    if (results.isEmpty) {
      // If the result list is empty, insert default values
      val insertStatement = conn.prepareStatement("INSERT INTO boosted_info (boss, creature) VALUES (?, ?);")
      insertStatement.setString(1, "None") // Default value for boss
      insertStatement.setString(2, "None") // Default value for creature
      insertStatement.executeUpdate()
      insertStatement.close()
    }

    // update category if exists
    if (boss != "") {
      val statement = conn.prepareStatement("UPDATE boosted_info SET boss = ?;")
      statement.setString(1, boss)

      statement.executeUpdate()
      statement.close()
    }
    if (creature != "") {
      val statement = conn.prepareStatement("UPDATE boosted_info SET creature = ?;")
      statement.setString(1, creature)
      statement.executeUpdate()
      statement.close()
    }

    conn.close()
  }

  private def boostedMessages(): List[BoostedCache] = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword

    val conn = DriverManager.getConnection(url, username, password)
    val statement = conn.createStatement()

    val tableExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'boosted_info'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    // Create the table if it doesn't exist
    if (!tableExists) {
      val createListTable =
        s"""CREATE TABLE boosted_info (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |boss VARCHAR(255) NOT NULL,
           |creature VARCHAR(255) NOT NULL
           );""".stripMargin

      statement.executeUpdate(createListTable)
    }

    val result = statement.executeQuery(s"SELECT boss,creature FROM boosted_info;")

    val results = new ListBuffer[BoostedCache]()
    while (result.next()) {
      val boss = Option(result.getString("boss")).getOrElse("None")
      val creature = Option(result.getString("creature")).getOrElse("None")

      results += BoostedCache(boss, creature)
    }

    if (results.isEmpty) {
      // If the result list is empty, insert default values
      val insertStatement = conn.prepareStatement("INSERT INTO boosted_info (boss, creature) VALUES (?, ?);")
      insertStatement.setString(1, "None") // Default value for boss
      insertStatement.setString(2, "None") // Default value for creature
      insertStatement.executeUpdate()
      insertStatement.close()

      results += BoostedCache("None", "None")
    }

    statement.close()
    conn.close()
    results.toList
  }

  private def startBot(guild: Option[Guild], world: Option[String]): Unit = {

    if (guild.isDefined && world.isDefined) {

      val guildId = guild.get.getId

      // get hunted Players
      val huntedPlayers = playerConfig(guild.get, "hunted_players")
      huntedPlayersData += (guildId -> huntedPlayers)

      // get allied Players
      val alliedPlayers = playerConfig(guild.get, "allied_players")
      alliedPlayersData += (guildId -> alliedPlayers)

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
      activityData += (guildId -> activityInfo)

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
          val botStream = if (botStreams.contains(world.get)) {
            // If the stream already exists, update its usedBy list
            val existingStream = botStreams(world.get)
            val updatedUsedBy = existingStream.usedBy :+ discords
            botStreams += (world.get -> existingStream.copy(usedBy = updatedUsedBy))
            existingStream
          } else {
            // If the stream doesn't exist, create a new one with an empty usedBy list
            val bot = new TibiaBot(world.get)
            Streams(bot.stream.run(), List(discords))
          }
          botStreams = botStreams + (world.get -> botStream)
        }
      }
    } else {
      // build guild specific data map
      guilds.foreach{g =>

        val guildId = g.getId

        if (guildId != "912739993015947324") { // handled by Pulsera Bot

          if (checkConfigDatabase(g)) {
            // get hunted Players
            val huntedPlayers = playerConfig(g, "hunted_players")
            huntedPlayersData += (guildId -> huntedPlayers)

            // get allied Players
            val alliedPlayers = playerConfig(g, "allied_players")
            alliedPlayersData += (guildId -> alliedPlayers)

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
            activityData += (guildId -> activityInfo)

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
        }
      }
      discordsData.foreach { case (worldName, discordsList) =>
        val botStream = new TibiaBot(worldName)
        botStreams += (worldName -> Streams(botStream.stream.run(), discordsList))
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

  /**
  private def cleanHuntedList(guild: Guild): Unit = {
    val listPlayers: List[Players] = huntedPlayersData.getOrElse(guild.getId, List.empty[Players])
    if (listPlayers.nonEmpty) {
      // run api against players
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      val listBuffer = ListBuffer[String]()
      val listPlayersFlow = Source(listPlayers.map(p => (p.name, p.reason, p.reasonText)).toSet).mapAsyncUnordered(2)(tibiaDataClient.getCharacterWithInput).toMat(Sink.seq)(Keep.right)
      val futureResults: Future[Seq[(CharacterResponse, String, String, String)]] = listPlayersFlow.run()
      futureResults.onComplete {
        case Success(output) =>
          output.foreach { case (charResponse, name, reason, reasonText) =>
            if (charResponse.character.character.name != "") {
              val charName = charResponse.character.character.name
              val charLevel = charResponse.character.character.level.toInt
              val charGuild = charResponse.character.character.guild
              val charGuildName = if(charGuild.isDefined) charGuild.head.name else ""
              val charWorld = charResponse.character.character.world
              val charEmoji = vocEmoji(charResponse)

              val huntedGuildCheck = huntedGuildsData.getOrElse(guild.getId, List()).exists(_.name.toLowerCase() == charGuildName.toLowerCase())
              if (huntedGuildCheck && reason == "false") { // only remove players that were added by the bot, use the reason to check this
                listBuffer += name.toLowerCase
                removeHuntedFromDatabase(guild, "player", name.toLowerCase())

                if (adminChannel != null) {
                  val commandUser = s"<@$botUser>"
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(":robot: hunted list cleanup:")
                  adminEmbed.setDescription(s"$commandUser removed the player\n$charEmoji **$charLevel** — **[$charName](${charUrl(charName)})**\nfrom the hunted list for **$charWorld**\n*(because they have joined an enemy guild and will be tracked that way)*.")
                  adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Broom.gif")
                  adminEmbed.setColor(14397256) // orange for bot auto command
                  adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                }
              }

              val alliedGuildCheck = alliedGuildsData.getOrElse(guild.getId, List()).exists(_.name.toLowerCase() == charGuildName.toLowerCase())
              if (alliedGuildCheck && reason == "false") { // only remove players that were added by the bot, use the reason to check this
                listBuffer += name.toLowerCase
                removeHuntedFromDatabase(guild, "player", name.toLowerCase())

                if (adminChannel != null) {
                  val commandUser = s"<@$botUser>"
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(":robot: hunted list cleanup:")
                  adminEmbed.setDescription(s"$commandUser removed the player\n$charEmoji **$charLevel** — **[$charName](${charUrl(charName)})**\nfrom the hunted list for **$charWorld**\n*(because they have joined an allied guild)*.")
                  adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Broom.gif")
                  adminEmbed.setColor(14397256) // orange for bot auto command
                  adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                }
              }
            }
          }
          val updatedList = listPlayers.filterNot(p => listBuffer.contains(p.name.toLowerCase))
          huntedPlayersData = huntedPlayersData.updated(guild.getId, updatedList)
        case Failure(_) => // e.printStackTrace
      }
    }
  }
  **/

  private def updateDashboard(): Unit = {

    // Violent Bot Support discord
    logger.info(s"Updating Violent Bot dashboard...")

    val guildCount = jda.getGuildCache.size
    val activeDiscordsCount: Int = worldsData.size
    val worldStreamCount: Int = discordsData.size
    val worldsTrackedCount: Int = worldsData.values.map(_.size).sum

    val dashboardGuild = jda.getGuildById(867319250708463628L)
    val dashboardDiscordsTotal = dashboardGuild.getVoiceChannelById(1076431727838380032L)
    val dashboardDiscordsActive = dashboardGuild.getVoiceChannelById(1082844559937114112L)
    val dashboardWorldSubscriptions = dashboardGuild.getVoiceChannelById(1076432500294955098L)
    val dashboardWorldStreams = dashboardGuild.getVoiceChannelById(1082844790439288872L)

    // total Discord count
    val dashboardDiscordsTotalName = dashboardDiscordsTotal.getName
    if (dashboardDiscordsTotalName != s"Discords (Total): $guildCount") {
      val dashboardDiscordsTotalManager = dashboardDiscordsTotal.getManager
      dashboardDiscordsTotalManager.setName(s"Discords (Total): $guildCount").queue()
    }

    // active Discord count
    val dashboardDiscordsActiveName = dashboardDiscordsActive.getName
    if (dashboardDiscordsActiveName != s"Discords (Active): $activeDiscordsCount") {
      val dashboardDiscordsActiveManager = dashboardDiscordsActive.getManager
      dashboardDiscordsActiveManager.setName(s"Discords (Active): $activeDiscordsCount").queue()
    }

    // total worlds setup by users
    val dashboardWorldSubscriptionsName = dashboardWorldSubscriptions.getName
    if (dashboardWorldSubscriptionsName != s"Worlds Setup: $worldsTrackedCount") {
      val dashboardWorldSubscriptionsManager = dashboardWorldSubscriptions.getManager
      dashboardWorldSubscriptionsManager.setName(s"Worlds Setup: $worldsTrackedCount").queue()
    }

    // world streams running out of 'how many tibia worlds exist'
    val dashboardWorldStreamsName = dashboardWorldStreams.getName
    if (dashboardWorldStreamsName != s"World Streams: $worldStreamCount of ${worlds.size}") {
      val dashboardWorldStreamsManager = dashboardWorldStreams.getManager
      dashboardWorldStreamsManager.setName(s"World Streams: $worldStreamCount of ${worlds.size}").queue()
    }

    try {
      val worldsString = if (worldStreamCount == 1) "world" else "worlds"
      val discordString = if (activeDiscordsCount == 1) "discord" else "discords"
      jda.getPresence().setActivity(Activity.of(Activity.ActivityType.WATCHING, s"${worldStreamCount} $worldsString for ${activeDiscordsCount} $discordString"))
    }
    catch {
      case _ : Throwable => logger.info("Failed to update the bots status counts")
    }
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
            embed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Tibiapedia.gif")
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
            embed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Tibiapedia.gif")
            embed.setColor(3092790)
            embed.setDescription(embedText)
            val returnEmbed = embed.build()
            return returnEmbed

        }.getOrElse {
          embedText = s":gear: The player **$subOptionValueLower** is not on the hunted list."
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
            embed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Tibiapedia.gif")
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
            embed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Tibiapedia.gif")
            embed.setColor(3092790)
            embed.setDescription(embedText)
            val returnEmbed = embed.build()
            return returnEmbed

        }.getOrElse {
          embedText = s":gear: The player **$subOptionValueLower** is not on the allied list."
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
    val guildThumbnail = if (arg == "allies") "https://tibia.fandom.com/wiki/Special:Redirect/file/Angel_Statue.gif" else if (arg == "hunted") "https://tibia.fandom.com/wiki/Special:Redirect/file/Stone_Coffin.gif" else ""
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

  private def getListTable(world: String): List[ListCache] = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword

    val conn = DriverManager.getConnection(url, username, password)
    val statement = conn.createStatement()

    // Check if the table already exists in bot_configuration
    val tableExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'list'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    // Create the table if it doesn't exist
    if (!tableExists) {
      val createListTable =
        s"""CREATE TABLE list (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |world VARCHAR(255) NOT NULL,
           |former_worlds VARCHAR(255),
           |name VARCHAR(255) NOT NULL,
           |former_names VARCHAR(1000),
           |level VARCHAR(255) NOT NULL,
           |guild_name VARCHAR(255),
           |vocation VARCHAR(255) NOT NULL,
           |last_login VARCHAR(255) NOT NULL,
           |time VARCHAR(255) NOT NULL
           |);""".stripMargin

      statement.executeUpdate(createListTable)
    }

    val result = statement.executeQuery(s"SELECT name,former_names,world,former_worlds,guild_name,level,vocation,last_login,time FROM list WHERE world = '$world';")

    val results = new ListBuffer[ListCache]()
    while (result.next()) {

      val guildName = Option(result.getString("guild_name")).getOrElse("")
      val name = Option(result.getString("name")).getOrElse("")
      val formerNames = Option(result.getString("former_names")).getOrElse("")
      val formerNamesList = formerNames.split(",").toList
      val world = Option(result.getString("world")).getOrElse("")
      val formerWorlds = Option(result.getString("former_worlds")).getOrElse("")
      val formerWorldsList = formerWorlds.split(",").toList
      val level = Option(result.getString("level")).getOrElse("")
      val vocation = Option(result.getString("vocation")).getOrElse("")
      val lastLogin = Option(result.getString("last_login")).getOrElse("")
      val updatedTimeTemporal = Option(result.getTimestamp("time").toInstant).getOrElse(Instant.parse("2022-01-01T01:00:00Z"))
      val updatedTime = updatedTimeTemporal.atZone(ZoneOffset.UTC)

      // ListCache(name: String, formerNames: List[String], world: String, formerWorlds: List[String], guild: String, level: String, vocation: String, last_login: String, updatedTime: ZonedDateTime)
      results += ListCache(name, formerNamesList, world, formerWorldsList, guildName, level, vocation, lastLogin, updatedTime)
    }

    statement.close()
    conn.close()
    results.toList
  }

  // V1.6 Galthen Satchel Command
  def getGalthenTable(userId: String): Option[List[SatchelStamp]] = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword

    val conn = DriverManager.getConnection(url, username, password)
    val statement = conn.createStatement()

    // Check if the table already exists in bot_configuration
    val tableExistsQuery =
      statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'satchel'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    // Create the table if it doesn't exist
    if (!tableExists) {
      val createListTable =
        s"""CREATE TABLE satchel (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |userid VARCHAR(255) NOT NULL,
           |time VARCHAR(255) NOT NULL,
           |tag VARCHAR(255)
           |);""".stripMargin

      statement.executeUpdate(createListTable)
    }

    val result = statement.executeQuery(s"SELECT time,tag FROM satchel WHERE userid = '$userId';")

    val satchelStampList: ListBuffer[SatchelStamp] = ListBuffer()

    while (result.next()) {
      val updatedTimeTemporal =
        Try(Option(result.getTimestamp("time").toInstant).getOrElse(Instant.parse("2022-01-01T01:00:00Z")))
          .getOrElse(Instant.parse("2022-01-01T01:00:00Z"))
      val updatedTime = updatedTimeTemporal.atZone(ZoneOffset.UTC)
      val tag = Option(result.getString("tag")).getOrElse("")

      val satchelStamp = SatchelStamp(userId, updatedTime, tag)
      satchelStampList += satchelStamp
    }

    statement.close()
    conn.close()
    Some(satchelStampList.toList)
  }

  def delGalthen(user: String, tag: String): Unit = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword

    val conn = DriverManager.getConnection(url, username, password)

    val deleteStatement = conn.prepareStatement("DELETE FROM satchel WHERE userid = ? AND COALESCE(tag, '') = ?;")
    deleteStatement.setString(1, user)
    deleteStatement.setString(2, tag)
    deleteStatement.executeUpdate()

    deleteStatement.close()
    conn.close()
  }

  def delAllGalthen(user: String): Unit = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword

    val conn = DriverManager.getConnection(url, username, password)

    val deleteStatement = conn.prepareStatement("DELETE FROM satchel WHERE userid = ?;")
    deleteStatement.setString(1, user)
    deleteStatement.executeUpdate()

    deleteStatement.close()
    conn.close()
  }

  def addGalthen(user: String, when: ZonedDateTime, tag: String): Unit = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword
    val conn = DriverManager.getConnection(url, username, password)
    val selectStatement = conn.prepareStatement("SELECT time FROM satchel WHERE userid = ? AND tag = ?;")
    selectStatement.setString(1, user)
    selectStatement.setString(2, tag)
    val resultSet = selectStatement.executeQuery()

    if (resultSet.next()) {
      // Update existing row
      val updateStatement = conn.prepareStatement(
        s"""
           |UPDATE satchel
           |SET time = ?
           |WHERE userid = ? AND tag = ?;
           |""".stripMargin
      )
      updateStatement.setTimestamp(1, Timestamp.from(when.toInstant))
      updateStatement.setString(2, user)
      updateStatement.setString(3, tag)
      updateStatement.executeUpdate()
      updateStatement.close()
    } else {
      // Insert new row
      val insertStatement = conn.prepareStatement(
        s"""
           |INSERT INTO satchel(userid, time, tag)
           |VALUES (?,?,?);
           |""".stripMargin
      )
      insertStatement.setString(1, user)
      insertStatement.setTimestamp(2, Timestamp.from(when.toInstant))
      insertStatement.setString(3, tag)
      insertStatement.executeUpdate()
      insertStatement.close()
    }

    selectStatement.close()
    conn.close()
  }

  def addListToCache(name: String, formerNames: List[String], world: String, formerWorlds: List[String], guild: String, level: String, vocation: String, lastLogin: String, updatedTime: ZonedDateTime): Unit = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword

    val conn = DriverManager.getConnection(url, username, password)
    val selectStatement = conn.prepareStatement("SELECT name FROM list WHERE LOWER(name) = LOWER(?);")
    selectStatement.setString(1, name)
    val resultSet = selectStatement.executeQuery()

    if (resultSet.next()) {
      // Update existing row
      val updateStatement = conn.prepareStatement(
        s"""
           |UPDATE list
           |SET former_names = ?, world = ?, former_worlds = ?, guild_name = ?, level = ?, vocation = ?, last_login = ?, time = ?
           |WHERE LOWER(name) = LOWER(?);
           |""".stripMargin
      )
      updateStatement.setString(1, formerNames.mkString(","))
      updateStatement.setString(2, world.capitalize)
      updateStatement.setString(3, formerWorlds.mkString(","))
      updateStatement.setString(4, guild)
      updateStatement.setString(5, level)
      updateStatement.setString(6, vocation)
      updateStatement.setString(7, lastLogin)
      updateStatement.setTimestamp(8, Timestamp.from(updatedTime.toInstant))
      updateStatement.setString(9, name)
      updateStatement.executeUpdate()
      updateStatement.close()
    } else {
      // Insert new row
      val insertStatement = conn.prepareStatement(
        s"""
           |INSERT INTO list(name, former_names, world, former_worlds, guild_name, level, vocation, last_login, time)
           |VALUES (?,?,?,?,?,?,?,?,?);
           |""".stripMargin
      )
      insertStatement.setString(1, name)
      insertStatement.setString(2, formerNames.mkString(","))
      insertStatement.setString(3, world.capitalize)
      insertStatement.setString(4, formerWorlds.mkString(","))
      insertStatement.setString(5, guild)
      insertStatement.setString(6, level)
      insertStatement.setString(7, vocation)
      insertStatement.setString(8, lastLogin)
      insertStatement.setTimestamp(9, Timestamp.from(updatedTime.toInstant))
      insertStatement.executeUpdate()
      insertStatement.close()
    }

    selectStatement.close()
    conn.close()
  }

  private def cleanHuntedList(): Unit = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword

    val conn = DriverManager.getConnection(url, username, password)

    // Modify the DELETE statement to include a WHERE clause with the condition for time
    val deleteStatement = conn.prepareStatement("DELETE FROM list WHERE time < ?;")
    deleteStatement.setTimestamp(1, Timestamp.from(ZonedDateTime.now().minus(180, ChronoUnit.DAYS).toInstant))
    deleteStatement.executeUpdate()
    deleteStatement.close()
    conn.close()
  }

  private def cleanGalthenList(): Unit = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword

    val conn = DriverManager.getConnection(url, username, password)

    // Retrieve the data before deletion
    val selectStatement = conn.prepareStatement("SELECT userid,time,tag FROM satchel WHERE time < ?;")
    selectStatement.setTimestamp(1, Timestamp.from(ZonedDateTime.now().minus(30, ChronoUnit.DAYS).toInstant))
    val resultSet = selectStatement.executeQuery()

    // Retrieve the data from the result set
    while (resultSet.next()) {
      val userId = resultSet.getString("userid")
      val tagId = Option(resultSet.getString("tag")).getOrElse("")
      val user: User = jda.retrieveUserById(userId).complete()
      val userTimeStamp = resultSet.getTimestamp("time").toInstant()
      val cooldown = userTimeStamp.plus(30, ChronoUnit.DAYS).getEpochSecond.toString()

      if (user != null) {
        try {
          user.openPrivateChannel().queue { privateChannel =>
            val embed = new EmbedBuilder()
            if (tagId.nonEmpty) embed.setFooter(s"Tag: ${tagId.toLowerCase}")
            val displayTag = if (tagId.nonEmpty) s"**`$tagId`**" else s"<@$userId>"
            embed.setColor(178877)
            embed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
            embed.setDescription(s"${Config.satchelEmoji} cooldown for $displayTag expired <t:$cooldown:R>\n\nMark it as **Collected** and I will message you: ```when the 30 day cooldown expires```")
            privateChannel.sendMessageEmbeds(embed.build()).addActionRow(
              Button.success("galthenRemind", "Collected"),
              Button.secondary("galthenClear", "Dismiss")
            ).queue()
          }
        } catch {
          case ex: Exception => //
        }
      }
    }

    selectStatement.close()

    // Now you have the list of userids and time before deletion, you can proceed with deletion
    val deleteStatement = conn.prepareStatement("DELETE FROM satchel WHERE time < ?;")
    deleteStatement.setTimestamp(1, Timestamp.from(ZonedDateTime.now().minus(30, ChronoUnit.DAYS).toInstant))
    deleteStatement.executeUpdate()
    deleteStatement.close()

    conn.close()
  }

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
    val embedThumbnail = if (arg == "allies") "https://tibia.fandom.com/wiki/Special:Redirect/file/Angel_Statue.gif" else if (arg == "hunted") "https://tibia.fandom.com/wiki/Special:Redirect/file/Stone_Coffin.gif" else ""
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
          // none
          val noneWorldLists = vocationWorldBuffers("none").map {
            case (world, worldBuffer) =>
              world -> worldBuffer.toList.sortBy(-_._1).map(_._3)
          }

          // combine these into one list now that its ordered by level and grouped by world
          val allPlayers = List(noneWorldLists, sorcerersWorldLists, paladinsWorldLists, knightsWorldLists, druidsWorldLists).foldLeft(Map.empty[String, List[String]]) {
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

  def vocEmoji(char: CharacterResponse): String = {
    val voc = char.character.character.vocation.toLowerCase.split(' ').last
    voc match {
      case "knight" => ":shield:"
      case "druid" => ":snowflake:"
      case "sorcerer" => ":fire:"
      case "paladin" => ":bow_and_arrow:"
      case "none" => ":hatching_chick:"
      case _ => ""
    }
  }

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

  def charUrl(char: String): String = {
    val encodedString = URLEncoder.encode(char, StandardCharsets.UTF_8.toString)
    s"https://www.tibia.com/community/?name=${encodedString}"
  }

  def guildUrl(guild: String): String = {
    val encodedString = URLEncoder.encode(guild, StandardCharsets.UTF_8.toString)
    s"https://www.tibia.com/community/?subtopic=guilds&page=view&GuildName=${encodedString}"
  }

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
                if (adminChannel.canTalk()) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> added the guild **[$guildName](${guildUrl(guildName)})** to the hunted list.")
                  adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Stone_Coffin.gif")
                  adminEmbed.setColor(3092790)
                  adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                }
              }

              // add each player in the guild to the activity list
              guildMembers.foreach { member =>
                val guildPlayers = activityData.getOrElse(guildId, List())
                if (!guildPlayers.exists(_.name == member.name)) {
                  val updatedTime = ZonedDateTime.now()
                  activityData = activityData + (guildId -> (PlayerCache(member.name, List(""), guildName, updatedTime) :: guildPlayers))
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
              huntedPlayersData = huntedPlayersData + (guildId -> (Players(subOptionValueLower, reason, subOptionReason, commandUser) :: huntedPlayersData.getOrElse(guildId, List())))
              addHuntedToDatabase(guild, "player", subOptionValueLower, reason, subOptionReason, commandUser)
              embedText = s":gear: The player **[$playerName](${charUrl(playerName)})** has been added to the hunted list."

              // send embed to admin channel
              if (adminChannel != null) {
                if (adminChannel.canTalk()) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> added the player\n$vocation **$level** — **[$playerName](${charUrl(playerName)})**\nto the hunted list for **$world**.")
                  adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Stone_Coffin.gif")
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
                if (adminChannel.canTalk()) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> added the guild **[$guildName](${guildUrl(guildName)})** to the allies list.")
                  adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Angel_Statue.gif")
                  adminEmbed.setColor(3092790)
                  adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                }
              }

              // add each player in the guild to the hunted list
              /***
              guildMembers.foreach { member =>
                val guildPlayers = alliedPlayersData.getOrElse(guildId, List())
                if (!guildPlayers.exists(_.name == member.name)) {
                  alliedPlayersData = alliedPlayersData + (guildId -> (Players(member.name, "false", "this players guild was added to the hunted list", commandUser) :: guildPlayers))
                  addAllyToDatabase(guild, "player", member.name, "false", "this players guild was added to the allies list", commandUser)
                }
              }
              ***/

              // add each player in the guild to the activity list
              guildMembers.foreach { member =>
                val guildPlayers = activityData.getOrElse(guildId, List())
                if (!guildPlayers.exists(_.name == member.name)) {
                  val updatedTime = ZonedDateTime.now()
                  activityData = activityData + (guildId -> (PlayerCache(member.name, List(""), guildName, updatedTime) :: guildPlayers))
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
              alliedPlayersData = alliedPlayersData + (guildId -> (Players(subOptionValueLower, reason, subOptionReason, commandUser) :: alliedPlayersData.getOrElse(guildId, List())))
              addAllyToDatabase(guild, "player", subOptionValueLower, reason, subOptionReason, commandUser)
              embedText = s":gear: The player **[$playerName](${charUrl(playerName)})** has been added to the allies list."

              if (adminChannel != null) {
                if (adminChannel.canTalk()) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> added the player\n$vocation **$level** — **[$playerName](${charUrl(playerName)})**\nto the allies list for **$world**.")
                  adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Angel_Statue.gif")
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

              activityData = activityData + (guildId -> activityData.getOrElse(guildId, List()).filterNot(_.guild.equalsIgnoreCase(subOptionValueLower)))
              removeGuildActivityfromDatabase(guild, subOptionValueLower)

              // Remove players that the bot auto-hunted due to being in that guild from cache and db
              val filteredPlayers: List[Players] = {
                huntedPlayersData.getOrElse(guildId, List()).filter(_.reasonText.toLowerCase == s"was originally in hunted guild ${subOptionValueLower}".toLowerCase)
              }
              val huntedPlayersList = huntedPlayersData.getOrElse(guildId, List())
              val updatedHuntedPlayersList = huntedPlayersList.filterNot(player => filteredPlayers.exists(_.name == player.name))
              huntedPlayersData = huntedPlayersData.updated(guildId, updatedHuntedPlayersList)

              activityData = activityData + (guildId -> activityData.getOrElse(guildId, List()).filterNot(player => filteredPlayers.map(_.name.toLowerCase).contains(player.name.toLowerCase)))
              filteredPlayers.foreach { filterPlayer =>
                removeHuntedFromDatabase(guild, "player", filterPlayer.name)
                removePlayerActivityfromDatabase(guild, filterPlayer.name)
              }

              // send embed to admin channel
              if (adminChannel != null) {
                if (adminChannel.canTalk()) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> removed guild **$guildString** from the hunted list.")
                  adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Stone_Coffin.gif")
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
                huntedPlayersData = huntedPlayersData.updated(guildId, updatedHuntedPlayersList)

                activityData = activityData + (guildId -> activityData.getOrElse(guildId, List()).filterNot(player => filteredPlayers.map(_.name.toLowerCase).contains(player.name.toLowerCase)))
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

              huntedPlayersData = huntedPlayersData.updated(guildId, updatedList)
              removeHuntedFromDatabase(guild, "player", subOptionValueLower)

              activityData = activityData + (guildId -> activityData.getOrElse(guildId, List()).filterNot(_.name.equalsIgnoreCase(subOptionValueLower)))
              removePlayerActivityfromDatabase(guild, subOptionValueLower)

              // send embed to admin channel
              if (adminChannel != null) {
                if (adminChannel.canTalk()) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> removed the player\n$vocation **$level** — **$playerString**\nfrom the hunted list for **$world**.")
                  adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Stone_Coffin.gif")
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

              activityData = activityData + (guildId -> activityData.getOrElse(guildId, List()).filterNot(_.guild.equalsIgnoreCase(subOptionValueLower)))
              removeGuildActivityfromDatabase(guild, subOptionValueLower)

              // send embed to admin channel
              if (adminChannel != null) {
                if (adminChannel.canTalk()) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> removed **$guildString** from the allies list.")
                  adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Angel_Statue.gif")
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
              alliedPlayersData = alliedPlayersData.updated(guildId, updatedList)
              removeAllyFromDatabase(guild, "player", subOptionValueLower)

              activityData = activityData + (guildId -> activityData.getOrElse(guildId, List()).filterNot(_.name.equalsIgnoreCase(subOptionValueLower)))
              removePlayerActivityfromDatabase(guild, subOptionValueLower)

              // send embed to admin channel
              if (adminChannel != null) {
                if (adminChannel.canTalk()) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> removed the player\n$vocation **$level** — **$playerString**\nfrom the allies list for **$world**.")
                  adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Angel_Statue.gif")
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

  def addHuntedToDatabase(guild: Guild, option: String, name: String, reason: String, reasonText: String, addedBy: String): Unit = {
    val conn = getConnection(guild)
    val table = (if (option == "guild") "hunted_guilds" else if (option == "player") "hunted_players").toString
    val statement = conn.prepareStatement(s"INSERT INTO $table(name, reason, reason_text, added_by) VALUES (?,?,?,?) ON CONFLICT (name) DO NOTHING;")
    statement.setString(1, name)
    statement.setString(2, reason)
    statement.setString(3, reasonText)
    statement.setString(4, addedBy)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  def addActivityToDatabase(guild: Guild, name: String, formerNames: List[String], guildName: String, updatedTime: ZonedDateTime): Unit = {
    val conn = getConnection(guild)
    val statement = conn.prepareStatement(
      s"""
         |INSERT INTO tracked_activity(name, former_names, guild_name, updated)
         |VALUES (?,?,?,?)
         |ON CONFLICT (name)
         |DO UPDATE SET
         |  former_names = excluded.former_names,
         |  guild_name = excluded.guild_name,
         |  updated = excluded.updated;
         |""".stripMargin
    )
    statement.setString(1, name)
    statement.setString(2, formerNames.mkString(","))
    statement.setString(3, guildName)
    statement.setTimestamp(4, Timestamp.from(updatedTime.toInstant))
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  def updateActivityToDatabase(guild: Guild, name: String, formerNames: List[String], guildName: String, updatedTime: ZonedDateTime, newName: String): Unit = {
    val conn = getConnection(guild)
    val statement = conn.prepareStatement("UPDATE tracked_activity SET name = ?, former_names = ?, guild_name = ?, updated = ? WHERE LOWER(name) = LOWER(?);")
    statement.setString(1, newName)
    statement.setString(2, formerNames.mkString(","))
    statement.setString(3, guildName)
    statement.setTimestamp(4, Timestamp.from(updatedTime.toInstant))
    statement.setString(5, name)

    try {
      statement.executeUpdate()
    } catch {
      case e: PSQLException if e.getMessage.contains("duplicate key value") =>
        val deleteStatement = conn.prepareStatement("DELETE FROM tracked_activity WHERE LOWER(name) = LOWER(?);")
        deleteStatement.setString(1, newName)
        deleteStatement.executeUpdate()
        deleteStatement.close()

        // Retry the update
        val retryStatement = conn.prepareStatement("UPDATE tracked_activity SET name = ?, former_names = ?, guild_name = ?, updated = ? WHERE LOWER(name) = LOWER(?);")
        retryStatement.setString(1, newName)
        retryStatement.setString(2, formerNames.mkString(","))
        retryStatement.setString(3, guildName)
        retryStatement.setTimestamp(4, Timestamp.from(updatedTime.toInstant))
        retryStatement.setString(5, name)
        retryStatement.executeUpdate()
        retryStatement.close()
    } finally {
      statement.close()
      conn.close()
    }
  }

  def updateHuntedOrAllyNameToDatabase(guild: Guild, option: String, oldName: String, newName: String): Unit = {
    val conn = getConnection(guild)
    val table = if (option == "hunted") "hunted_players" else if (option == "allied") "allied_players"

    val statement = conn.prepareStatement(s"UPDATE $table SET name = ? WHERE LOWER(name) = LOWER(?);")
    statement.setString(1, newName)
    statement.setString(2, oldName)

    try {
      statement.executeUpdate()
    } catch {
      case e: PSQLException if e.getMessage.contains("duplicate key value") =>
        // Handle duplicate key error
        val deleteStatement = conn.prepareStatement(s"DELETE FROM $table WHERE LOWER(name) = LOWER(?);")
        deleteStatement.setString(1, newName)
        deleteStatement.executeUpdate()
        deleteStatement.close()

        // Retry the update within the same transaction
        val retryStatement = conn.prepareStatement(s"UPDATE $table SET name = ? WHERE LOWER(name) = LOWER(?);")
        retryStatement.setString(1, newName)
        retryStatement.setString(2, oldName)
        retryStatement.executeUpdate()
        retryStatement.close()
    } finally {
      statement.close()
      conn.close()
    }
  }

  private def addAllyToDatabase(guild: Guild, option: String, name: String, reason: String, reasonText: String, addedBy: String): Unit = {
    val conn = getConnection(guild)
    val table = (if (option == "guild") "allied_guilds" else if (option == "player") "allied_players").toString
    val statement = conn.prepareStatement(s"INSERT INTO $table(name, reason, reason_text, added_by) VALUES (?,?,?,?) ON CONFLICT (name) DO NOTHING;")
    statement.setString(1, name)
    statement.setString(2, reason)
    statement.setString(3, reasonText)
    statement.setString(4, addedBy)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  def removeHuntedFromDatabase(guild: Guild, option: String, name: String): Unit = {
    val conn = getConnection(guild)
    val table = (if (option == "guild") "hunted_guilds" else if (option == "player") "hunted_players").toString
    val statement = conn.prepareStatement(s"DELETE FROM $table WHERE LOWER(name) = LOWER(?);")
    statement.setString(1, name)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  private def removeGuildActivityfromDatabase(guild: Guild, guildName: String): Unit = {
    val conn = getConnection(guild)

    val statement = conn.prepareStatement(s"DELETE FROM tracked_activity WHERE LOWER(guild_name) = LOWER(?);")
    statement.setString(1, guildName)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  def removePlayerActivityfromDatabase(guild: Guild, playerName: String): Unit = {
    val conn = getConnection(guild)
    val statement = conn.prepareStatement(s"DELETE FROM tracked_activity WHERE LOWER(name) = LOWER(?);")
    statement.setString(1, playerName)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  def removeAllyFromDatabase(guild: Guild, option: String, name: String): Unit = {
    val conn = getConnection(guild)
    val table = (if (option == "guild") "allied_guilds" else if (option == "player") "allied_players").toString
    val statement = conn.prepareStatement(s"DELETE FROM $table WHERE LOWER(name) = LOWER(?);")
    statement.setString(1, name)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  private def checkConfigDatabase(guild: Guild): Boolean = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/postgres"
    val username = "postgres"
    val password = Config.postgresPassword
    val guildId = guild.getId

    val conn = DriverManager.getConnection(url, username, password)
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT datname FROM pg_database WHERE datname = '_$guildId'")
    val exist = result.next()

    statement.close()
    conn.close()

    // check if database for discord exists
    if (exist) {
      true
    } else {
      false
    }
  }

  private def createPremiumDatabase(): Unit = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/postgres"
    val username = "postgres"
    val password = Config.postgresPassword

    val conn = DriverManager.getConnection(url, username, password)
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT datname FROM pg_database WHERE datname = 'premium'")
    val exist = result.next()

    // if bot_configuration doesn't exist
    if (!exist) {
      statement.executeUpdate(s"CREATE DATABASE bot_cache;")
      logger.info(s"Database 'bot_cache' created successfully")
      statement.close()
      conn.close()

      val newUrl = s"jdbc:postgresql://${Config.postgresHost}:5432/premium"
      val newConn = DriverManager.getConnection(newUrl, username, password)
      val newStatement = newConn.createStatement()
      // create the tables in bot_configuration
      val createPaymentsTable =
        s"""CREATE TABLE payments (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |discord_id VARCHAR(255) NOT NULL,
           |discord_name VARCHAR(255) NOT NULL,
           |user_id VARCHAR(255) NOT NULL,
           |user_name VARCHAR(255) NOT NULL,
           |expiry VARCHAR(255) NOT NULL
           |);""".stripMargin

      newStatement.executeUpdate(createPaymentsTable)
      logger.info("Table 'payments' created successfully")
      newStatement.close()
      newConn.close()
    } else {
      statement.close()
      conn.close()
    }
  }

  private def createCacheDatabase(): Unit = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/postgres"
    val username = "postgres"
    val password = Config.postgresPassword

    val conn = DriverManager.getConnection(url, username, password)
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT datname FROM pg_database WHERE datname = 'bot_cache'")
    val exist = result.next()

    // if bot_configuration doesn't exist
    if (!exist) {
      statement.executeUpdate(s"CREATE DATABASE bot_cache;")
      logger.info(s"Database 'bot_cache' created successfully")
      statement.close()
      conn.close()

      val newUrl = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
      val newConn = DriverManager.getConnection(newUrl, username, password)
      val newStatement = newConn.createStatement()
      // create the tables in bot_configuration
      val createDeathsTable =
        s"""CREATE TABLE deaths (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |world VARCHAR(255) NOT NULL,
           |name VARCHAR(255) NOT NULL,
           |time VARCHAR(255) NOT NULL
           |);""".stripMargin

      val createLevelsTable =
        s"""CREATE TABLE levels (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |world VARCHAR(255) NOT NULL,
           |name VARCHAR(255) NOT NULL,
           |level VARCHAR(255) NOT NULL,
           |vocation VARCHAR(255) NOT NULL,
           |last_login VARCHAR(255) NOT NULL,
           |time VARCHAR(255) NOT NULL
           |);""".stripMargin

      newStatement.executeUpdate(createDeathsTable)
      logger.info("Table 'deaths' created successfully")
      newStatement.executeUpdate(createLevelsTable)
      logger.info("Table 'levels' created successfully")
      newStatement.close()
      newConn.close()
    } else {
      statement.close()
      conn.close()
    }
  }

  def getDeathsCache(world: String): List[DeathsCache] = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword

    val conn = DriverManager.getConnection(url, username, password)
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT world,name,time FROM deaths WHERE world = '$world';")

    val results = new ListBuffer[DeathsCache]()
    while (result.next()) {
      val world = Option(result.getString("world")).getOrElse("")
      val name = Option(result.getString("name")).getOrElse("")
      val time = Option(result.getString("time")).getOrElse("")
      results += DeathsCache(world, name, time)
    }

    statement.close()
    conn.close()
    results.toList
  }

  def addDeathsCache(world: String, name: String, time: String): Unit = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword

    val conn = DriverManager.getConnection(url, username, password)
    val statement = conn.prepareStatement("INSERT INTO deaths(world,name,time) VALUES (?, ?, ?);")
    statement.setString(1, world)
    statement.setString(2, name)
    statement.setString(3, time)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  private def removeDeathsCache(time: ZonedDateTime): Unit = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword

    val conn = DriverManager.getConnection(url, username, password)
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT id,time from deaths;")
    val results = new ListBuffer[Long]()
    while (result.next()) {
      val id = Option(result.getLong("id")).getOrElse(0L)
      val timeDb = Option(result.getString("time")).getOrElse("")
      val timeToDate = ZonedDateTime.parse(timeDb)
      if (time.isAfter(timeToDate.plusMinutes(30)) && id != 0L) {
        results += id
      }
    }
    results.foreach { uid =>
      statement.executeUpdate(s"DELETE from deaths where id = $uid;")
    }
    statement.close()
    conn.close()
  }

  def getLevelsCache(world: String): List[LevelsCache] = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword

    val conn = DriverManager.getConnection(url, username, password)
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT world,name,level,vocation,last_login,time FROM levels WHERE world = '$world';")

    val results = new ListBuffer[LevelsCache]()
    while (result.next()) {
      val world = Option(result.getString("world")).getOrElse("")
      val name = Option(result.getString("name")).getOrElse("")
      val level = Option(result.getString("level")).getOrElse("")
      val vocation = Option(result.getString("vocation")).getOrElse("")
      val lastLogin = Option(result.getString("last_login")).getOrElse("")
      val time = Option(result.getString("time")).getOrElse("")
      results += LevelsCache(world, name, level, vocation, lastLogin, time)
    }

    statement.close()
    conn.close()
    results.toList
  }

  def addLevelsCache(world: String, name: String, level: String, vocation: String, lastLogin: String, time: String): Unit = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword

    val conn = DriverManager.getConnection(url, username, password)
    val statement = conn.prepareStatement("INSERT INTO levels(world,name,level,vocation,last_login,time) VALUES (?, ?, ?, ?, ?, ?);")
    statement.setString(1, world)
    statement.setString(2, name)
    statement.setString(3, level)
    statement.setString(4, vocation)
    statement.setString(5, lastLogin)
    statement.setString(6, time)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  private def removeLevelsCache(time: ZonedDateTime): Unit = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword

    val conn = DriverManager.getConnection(url, username, password)
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT id,time from levels;")
    val results = new ListBuffer[Long]()
    while (result.next()) {
      val id = Option(result.getLong("id")).getOrElse(0L)
      val timeDb = Option(result.getString("time")).getOrElse("")
      val timeToDate = ZonedDateTime.parse(timeDb)
      if (time.isAfter(timeToDate.plusHours(25)) && id != 0L) {
        results += id
      }
    }
    results.foreach { uid =>
      statement.executeUpdate(s"DELETE from levels where id = $uid;")
    }
    statement.close()
    conn.close()
  }

  private def createConfigDatabase(guild: Guild): Unit = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/postgres"
    val username = "postgres"
    val password = Config.postgresPassword
    val guildId = guild.getId
    val guildName = guild.getName

    val conn = DriverManager.getConnection(url, username, password)
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT datname FROM pg_database WHERE datname = '_$guildId'")
    val exist = result.next()

    // if bot_configuration doesn't exist
    if (!exist) {
      statement.executeUpdate(s"CREATE DATABASE _$guildId;")
      logger.info(s"Database '$guildId' for discord '$guildName' created successfully")
      statement.close()
      conn.close()

      val newUrl = s"jdbc:postgresql://${Config.postgresHost}:5432/_$guildId"
      val newConn = DriverManager.getConnection(newUrl, username, password)
      val newStatement = newConn.createStatement()
      // create the tables in bot_configuration
      val createDiscordInfoTable =
        s"""CREATE TABLE discord_info (
           |guild_name VARCHAR(255) NOT NULL,
           |guild_owner VARCHAR(255) NOT NULL,
           |admin_category VARCHAR(255) NOT NULL,
           |admin_channel VARCHAR(255) NOT NULL,
           |boosted_channel VARCHAR(255) NOT NULL,
           |boosted_messageid VARCHAR(255) NOT NULL,
           |flags VARCHAR(255) NOT NULL,
           |created TIMESTAMP NOT NULL,
           |PRIMARY KEY (guild_name)
           |);""".stripMargin

      val createHuntedPlayersTable =
        s"""CREATE TABLE hunted_players (
           |name VARCHAR(255) NOT NULL,
           |reason VARCHAR(255) NOT NULL,
           |reason_text VARCHAR(255) NOT NULL,
           |added_by VARCHAR(255) NOT NULL,
           |PRIMARY KEY (name)
           |);""".stripMargin

      val createHuntedGuildsTable =
        s"""CREATE TABLE hunted_guilds (
           |name VARCHAR(255) NOT NULL,
           |reason VARCHAR(255) NOT NULL,
           |reason_text VARCHAR(255) NOT NULL,
           |added_by VARCHAR(255) NOT NULL,
           |PRIMARY KEY (name)
           |);""".stripMargin

      val createAlliedPlayersTable =
        s"""CREATE TABLE allied_players (
           |name VARCHAR(255) NOT NULL,
           |reason VARCHAR(255) NOT NULL,
           |reason_text VARCHAR(255) NOT NULL,
           |added_by VARCHAR(255) NOT NULL,
           |PRIMARY KEY (name)
           |);""".stripMargin

      val createAlliedGuildsTable =
        s"""CREATE TABLE allied_guilds (
           |name VARCHAR(255) NOT NULL,
           |reason VARCHAR(255) NOT NULL,
           |reason_text VARCHAR(255) NOT NULL,
           |added_by VARCHAR(255) NOT NULL,
           |PRIMARY KEY (name)
           |);""".stripMargin

      val createWorldsTable =
         s"""CREATE TABLE worlds (
            |name VARCHAR(255) NOT NULL,
            |allies_channel VARCHAR(255) NOT NULL,
            |enemies_channel VARCHAR(255) NOT NULL,
            |neutrals_channel VARCHAR(255) NOT NULL,
            |levels_channel VARCHAR(255) NOT NULL,
            |deaths_channel VARCHAR(255) NOT NULL,
            |category VARCHAR(255) NOT NULL,
            |fullbless_role VARCHAR(255) NOT NULL,
            |nemesis_role VARCHAR(255) NOT NULL,
            |fullbless_channel VARCHAR(255) NOT NULL,
            |nemesis_channel VARCHAR(255) NOT NULL,
            |fullbless_level INT NOT NULL,
            |show_neutral_levels VARCHAR(255) NOT NULL,
            |show_neutral_deaths VARCHAR(255) NOT NULL,
            |show_allies_levels VARCHAR(255) NOT NULL,
            |show_allies_deaths VARCHAR(255) NOT NULL,
            |show_enemies_levels VARCHAR(255) NOT NULL,
            |show_enemies_deaths VARCHAR(255) NOT NULL,
            |detect_hunteds VARCHAR(255) NOT NULL,
            |levels_min INT NOT NULL,
            |deaths_min INT NOT NULL,
            |exiva_list VARCHAR(255) NOT NULL,
            |online_combined VARCHAR(255) NOT NULL,
            |PRIMARY KEY (name)
            |);""".stripMargin

      newStatement.executeUpdate(createDiscordInfoTable)
      logger.info("Table 'discord_info' created successfully")
      newStatement.executeUpdate(createHuntedPlayersTable)
      logger.info("Table 'hunted_players' created successfully")
      newStatement.executeUpdate(createHuntedGuildsTable)
      logger.info("Table 'hunted_guilds' created successfully")
      newStatement.executeUpdate(createAlliedPlayersTable)
      logger.info("Table 'allied_players' created successfully")
      newStatement.executeUpdate(createAlliedGuildsTable)
      logger.info("Table 'allied_guilds' created successfully")
      newStatement.executeUpdate(createWorldsTable)
      logger.info("Table 'worlds' created successfully")
      newStatement.close()
      newConn.close()
    } else {
      logger.info(s"Database '$guildId' already exists")
      statement.close()
      conn.close()
    }
  }

  private def getConnection(guild: Guild): Connection = {
    val guildId = guild.getId
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/_$guildId"
    val username = "postgres"
    val password = Config.postgresPassword
    DriverManager.getConnection(url, username, password)
  }

  private def playerConfig(guild: Guild, query: String): List[Players] = {
    val conn = getConnection(guild)
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT name,reason,reason_text,added_by FROM $query")

    val results = new ListBuffer[Players]()
    while (result.next()) {
      val name = Option(result.getString("name")).getOrElse("")
      val reason = Option(result.getString("reason")).getOrElse("")
      val reasonText = Option(result.getString("reason_text")).getOrElse("")
      val addedBy = Option(result.getString("added_by")).getOrElse("")
      results += Players(name, reason, reasonText, addedBy)
    }

    statement.close()
    conn.close()
    results.toList
  }

  private def guildConfig(guild: Guild, query: String): List[Guilds] = {
    val conn = getConnection(guild)
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT name,reason,reason_text,added_by FROM $query")

    val results = new ListBuffer[Guilds]()
    while (result.next()) {
      val name = Option(result.getString("name")).getOrElse("")
      val reason = Option(result.getString("reason")).getOrElse("")
      val reasonText = Option(result.getString("reason_text")).getOrElse("")
      val addedBy = Option(result.getString("added_by")).getOrElse("")
      results += Guilds(name, reason, reasonText, addedBy)
    }

    statement.close()
    conn.close()
    results.toList
  }

  private def activityConfig(guild: Guild, query: String): List[PlayerCache] = {
    val conn = getConnection(guild)
    val statement = conn.createStatement()

    // Check if the table already exists in bot_configuration
    val tableExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'tracked_activity'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    // Create the table if it doesn't exist
    if (!tableExists) {
      val createActivityTable =
        s"""CREATE TABLE tracked_activity (
           |name VARCHAR(255) NOT NULL,
           |former_names VARCHAR(255) NOT NULL,
           |guild_name VARCHAR(255) NOT NULL,
           |updated TIMESTAMP NOT NULL,
           |PRIMARY KEY (name)
           |);""".stripMargin

      statement.executeUpdate(createActivityTable)
    }

    val result = statement.executeQuery(s"SELECT name,former_names,guild_name,updated FROM $query")

    val results = new ListBuffer[PlayerCache]()
    while (result.next()) {
      val name = Option(result.getString("name")).getOrElse("")
      val formerNames = Option(result.getString("former_names")).getOrElse("")
      val guildName = Option(result.getString("guild_name")).getOrElse("")
      val formerNamesList = formerNames.split(",").toList
      val updatedTimeTemporal = Option(result.getTimestamp("updated").toInstant).getOrElse(Instant.parse("2022-01-01T01:00:00Z"))
      val updatedTime = updatedTimeTemporal.atZone(ZoneOffset.UTC)

      results += PlayerCache(name, formerNamesList, guildName, updatedTime)
    }

    statement.close()
    conn.close()
    results.toList
  }

  def discordRetrieveConfig(guild: Guild): Map[String, String] = {
    val conn = getConnection(guild)
    val statement = conn.createStatement()

    val channelExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'discord_info' AND COLUMN_NAME = 'boosted_channel'")
    val channelExists = channelExistsQuery.next()
    channelExistsQuery.close()

    // Add the column if it doesn't exist
    if (!channelExists) {
      statement.execute("ALTER TABLE discord_info ADD COLUMN boosted_channel VARCHAR(255) DEFAULT '0'")
    }

    val messageExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'discord_info' AND COLUMN_NAME = 'boosted_messageid'")
    val messageExists = messageExistsQuery.next()
    messageExistsQuery.close()

    // Add the column if it doesn't exist
    if (!messageExists) {
      statement.execute("ALTER TABLE discord_info ADD COLUMN boosted_messageid VARCHAR(255) DEFAULT '0'")
    }

    val result = statement.executeQuery(s"SELECT * FROM discord_info")
    var configMap = Map[String, String]()
    while (result.next()) {
      configMap += ("guild_name" -> result.getString("guild_name"))
      configMap += ("guild_owner" -> result.getString("guild_owner"))
      configMap += ("admin_category" -> result.getString("admin_category"))
      configMap += ("admin_channel" -> result.getString("admin_channel"))
      configMap += ("boosted_channel" -> result.getString("boosted_channel"))
      configMap += ("boosted_messageid" -> result.getString("boosted_messageid"))
      configMap += ("flags" -> result.getString("flags"))
      configMap += ("created" -> result.getString("created"))
    }

    statement.close()
    conn.close()
    configMap
  }

  private def worldConfig(guild: Guild): List[Worlds] = {
    val conn = getConnection(guild)
    val statement = conn.createStatement()


    // Check if the column already exists in the table
    val columnExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'worlds' AND COLUMN_NAME = 'exiva_list'")
    val columnExists = columnExistsQuery.next()
    columnExistsQuery.close()

    // Add the column if it doesn't exist
    if (!columnExists) {
      statement.execute("ALTER TABLE worlds ADD COLUMN exiva_list VARCHAR(255) DEFAULT 'false'")
    }

    // Check if the column already exists in the table
    val activityExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'worlds' AND COLUMN_NAME = 'activity_channel'")
    val activityExists = activityExistsQuery.next()
    activityExistsQuery.close()

    // Add the column if it doesn't exist
    if (!activityExists) {
      statement.execute("ALTER TABLE worlds ADD COLUMN activity_channel VARCHAR(255) DEFAULT '0'")
    }

    // Check if the column already exists in the table
    val onlineCombinedExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'worlds' AND COLUMN_NAME = 'online_combined'")
    val onlineCombinedExists = onlineCombinedExistsQuery.next()
    onlineCombinedExistsQuery.close()

    // Add the column if it doesn't exist
    if (!onlineCombinedExists) {
      statement.execute("ALTER TABLE worlds ADD COLUMN online_combined VARCHAR(255) DEFAULT 'false'")
    }

    val result = statement.executeQuery(s"SELECT name,allies_channel,enemies_channel,neutrals_channel,levels_channel,deaths_channel,category,fullbless_role,nemesis_role,fullbless_channel,nemesis_channel,fullbless_level,show_neutral_levels,show_neutral_deaths,show_allies_levels,show_allies_deaths,show_enemies_levels,show_enemies_deaths,detect_hunteds,levels_min,deaths_min,exiva_list,activity_channel,online_combined FROM worlds")

    val results = new ListBuffer[Worlds]()
    while (result.next()) {
      val name = Option(result.getString("name")).getOrElse("")
      val alliesChannel = Option(result.getString("allies_channel")).getOrElse(null)
      val enemiesChannel = Option(result.getString("enemies_channel")).getOrElse(null)
      val neutralsChannel = Option(result.getString("neutrals_channel")).getOrElse(null)
      val levelsChannel = Option(result.getString("levels_channel")).getOrElse(null)
      val deathsChannel = Option(result.getString("deaths_channel")).getOrElse(null)
      val category = Option(result.getString("category")).getOrElse(null)
      val fullblessRole = Option(result.getString("fullbless_role")).getOrElse(null)
      val nemesisRole = Option(result.getString("nemesis_role")).getOrElse(null)
      val fullblessChannel = Option(result.getString("fullbless_channel")).getOrElse(null)
      val nemesisChannel = Option(result.getString("nemesis_channel")).getOrElse(null)

      val fullblessLevel = Option(result.getInt("fullbless_level")).getOrElse(250)
      val showNeutralLevels = Option(result.getString("show_neutral_levels")).getOrElse("true")
      val showNeutralDeaths = Option(result.getString("show_neutral_deaths")).getOrElse("true")
      val showAlliesLevels = Option(result.getString("show_allies_levels")).getOrElse("true")
      val showAlliesDeaths = Option(result.getString("show_allies_deaths")).getOrElse("true")
      val showEnemiesLevels = Option(result.getString("show_enemies_levels")).getOrElse("true")
      val showEnemiesDeaths = Option(result.getString("show_enemies_deaths")).getOrElse("true")
      val detectHunteds = Option(result.getString("detect_hunteds")).getOrElse("on")
      val levelsMin = Option(result.getInt("levels_min")).getOrElse(8)
      val deathsMin = Option(result.getInt("deaths_min")).getOrElse(8)
      val exivaList = Option(result.getString("exiva_list")).getOrElse("false")
      val activityChannel = Option(result.getString("activity_channel")).getOrElse(null)
      val onlineCombined = Option(result.getString("online_combined")).getOrElse(null)

      // Ignore merged worlds (they are now effectively inactive and ignored but their data still exists in the db)
      if (!Config.mergedWorlds.exists(_.equalsIgnoreCase(name))) {
        results += Worlds(name, alliesChannel, enemiesChannel, neutralsChannel, levelsChannel, deathsChannel, category, fullblessRole, nemesisRole, fullblessChannel, nemesisChannel, fullblessLevel, showNeutralLevels, showNeutralDeaths, showAlliesLevels, showAlliesDeaths, showEnemiesLevels, showEnemiesDeaths, detectHunteds, levelsMin, deathsMin, exivaList, activityChannel, onlineCombined)
      }
    }

    statement.close()
    conn.close()
    results.toList
  }

  private def worldCreateConfig(guild: Guild, world: String, alliesChannel: String, enemiesChannel: String, neutralsChannels: String, levelsChannel: String, deathsChannel: String, category: String, fullblessRole: String, nemesisRole: String, fullblessChannel: String, nemesisChannel: String, activityChannel: String): Unit = {
    val conn = getConnection(guild)
    val statement = conn.prepareStatement("INSERT INTO worlds(name, allies_channel, enemies_channel, neutrals_channel, levels_channel, deaths_channel, category, fullbless_role, nemesis_role, fullbless_channel, nemesis_channel, fullbless_level, show_neutral_levels, show_neutral_deaths, show_allies_levels, show_allies_deaths, show_enemies_levels, show_enemies_deaths, detect_hunteds, levels_min, deaths_min, exiva_list, activity_channel, online_combined) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (name) DO UPDATE SET allies_channel = ?, enemies_channel = ?, neutrals_channel = ?, levels_channel = ?, deaths_channel = ?, category = ?, fullbless_role = ?, nemesis_role = ?, fullbless_channel = ?, nemesis_channel = ?, fullbless_level = ?, show_neutral_levels = ?, show_neutral_deaths = ?, show_allies_levels = ?, show_allies_deaths = ?, show_enemies_levels = ?, show_enemies_deaths = ?, detect_hunteds = ?, levels_min = ?, deaths_min = ?, exiva_list = ?, activity_channel = ?, online_combined = ?;")
    val formalQuery = world.toLowerCase().capitalize
    statement.setString(1, formalQuery)
    statement.setString(2, alliesChannel)
    statement.setString(3, enemiesChannel)
    statement.setString(4, neutralsChannels)
    statement.setString(5, levelsChannel)
    statement.setString(6, deathsChannel)
    statement.setString(7, category)
    statement.setString(8, fullblessRole)
    statement.setString(9, nemesisRole)
    statement.setString(10, fullblessChannel)
    statement.setString(11, nemesisChannel)
    statement.setInt(12, 250)
    statement.setString(13, "true")
    statement.setString(14, "true")
    statement.setString(15, "true")
    statement.setString(16, "true")
    statement.setString(17, "true")
    statement.setString(18, "true")
    statement.setString(19, "on")
    statement.setInt(20, 8)
    statement.setInt(21, 8)
    statement.setString(22, "false")
    statement.setString(23, activityChannel)
    statement.setString(24, "true")
    statement.setString(25, alliesChannel)
    statement.setString(26, enemiesChannel)
    statement.setString(27, neutralsChannels)
    statement.setString(28, levelsChannel)
    statement.setString(29, deathsChannel)
    statement.setString(30, category)
    statement.setString(31, fullblessRole)
    statement.setString(32, nemesisRole)
    statement.setString(33, fullblessChannel)
    statement.setString(34, nemesisChannel)
    statement.setInt(35, 250)
    statement.setString(36, "true")
    statement.setString(37, "true")
    statement.setString(38, "true")
    statement.setString(39, "true")
    statement.setString(40, "true")
    statement.setString(41, "true")
    statement.setString(42, "on")
    statement.setInt(43, 8)
    statement.setInt(44, 8)
    statement.setString(45, "false")
    statement.setString(46, activityChannel)
    statement.setString(47, "true")
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  private def discordCreateConfig(guild: Guild, guildName: String, guildOwner: String, adminCategory: String, adminChannel: String, boostedChannel: String, boostedMessageId: String, created: ZonedDateTime): Unit = {
    val conn = getConnection(guild)
    val statement = conn.prepareStatement("INSERT INTO discord_info(guild_name, guild_owner, admin_category, admin_channel, boosted_channel, boosted_messageid, flags, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(guild_name) DO UPDATE SET guild_owner = EXCLUDED.guild_owner, admin_category = EXCLUDED.admin_category, admin_channel = EXCLUDED.admin_channel, boosted_channel = EXCLUDED.boosted_channel, boosted_messageid = EXCLUDED.boosted_messageid, flags = EXCLUDED.flags, created = EXCLUDED.created;")
    statement.setString(1, guildName)
    statement.setString(2, guildOwner)
    statement.setString(3, adminCategory)
    statement.setString(4, adminChannel)
    statement.setString(5, boostedChannel)
    statement.setString(6, boostedMessageId)
    statement.setString(7, "none")
    statement.setTimestamp(8, Timestamp.from(created.toInstant))
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  private def discordUpdateConfig(guild: Guild, adminCategory: String, adminChannel: String, boostedChannel: String, boostedMessage: String): Unit = {
    val conn = getConnection(guild)
    // update category if exists
    if (adminCategory != "") {
      val statement = conn.prepareStatement("UPDATE discord_info SET admin_category = ?;")
      statement.setString(1, adminCategory)
      statement.executeUpdate()
      statement.close()
    }
    if (adminChannel != "") {
      // update channel
      val statement = conn.prepareStatement("UPDATE discord_info SET admin_channel = ?;")
      statement.setString(1, adminChannel)
      statement.executeUpdate()
      statement.close()
    }

    if (boostedChannel != "") {
      // update channel
      val statement = conn.prepareStatement("UPDATE discord_info SET boosted_channel = ?;")
      statement.setString(1, boostedChannel)
      statement.executeUpdate()
      statement.close()
    }

    if (boostedMessage != "") {
      // update channel
      val statement = conn.prepareStatement("UPDATE discord_info SET boosted_messageid = ?;")
      statement.setString(1, boostedMessage)
      statement.executeUpdate()
      statement.close()
    }

    conn.close()
  }

  def worldRetrieveConfig(guild: Guild, world: String): Map[String, String] = {
      val conn = getConnection(guild)
      val statement = conn.prepareStatement("SELECT * FROM worlds WHERE name = ?;")
      val formalWorld = world.toLowerCase().capitalize
      statement.setString(1, formalWorld)
      val result = statement.executeQuery()

      var configMap = Map[String, String]()
      while(result.next()) {
          configMap += ("name" -> result.getString("name"))
          configMap += ("allies_channel" -> result.getString("allies_channel"))
          configMap += ("enemies_channel" -> result.getString("enemies_channel"))
          configMap += ("neutrals_channel" -> result.getString("neutrals_channel"))
          configMap += ("levels_channel" -> result.getString("levels_channel"))
          configMap += ("deaths_channel" -> result.getString("deaths_channel"))
          configMap += ("category" -> result.getString("category"))
          configMap += ("fullbless_role" -> result.getString("fullbless_role"))
          configMap += ("nemesis_role" -> result.getString("nemesis_role"))
          configMap += ("fullbless_channel" -> result.getString("fullbless_channel"))
          configMap += ("nemesis_channel" -> result.getString("nemesis_channel"))
          configMap += ("fullbless_level" -> result.getInt("fullbless_level").toString)
          configMap += ("show_neutral_levels" -> result.getString("show_neutral_levels"))
          configMap += ("show_neutral_deaths" -> result.getString("show_neutral_deaths"))
          configMap += ("show_allies_levels" -> result.getString("show_allies_levels"))
          configMap += ("show_allies_deaths" -> result.getString("show_allies_deaths"))
          configMap += ("show_enemies_levels" -> result.getString("show_enemies_levels"))
          configMap += ("show_enemies_deaths" -> result.getString("show_enemies_deaths"))
          configMap += ("detect_hunteds" -> result.getString("detect_hunteds"))
          configMap += ("levels_min" -> result.getInt("levels_min").toString)
          configMap += ("deaths_min" -> result.getInt("deaths_min").toString)
          configMap += ("exiva_list" -> result.getString("exiva_list"))
          configMap += ("activity_channel" -> result.getString("activity_channel"))

          val combinedOnlineValue: String = Try(result.getString("combined_online")) match {
            case Success(value) => value // Column exists, use the retrieved value
            case Failure(_) => "false" // Column doesn't exist, use the default value
          }
          configMap += ("combined_online" -> combinedOnlineValue)
      }
      statement.close()
      conn.close()
      configMap
  }

  private def worldRemoveConfig(guild: Guild, query: String): Unit = {
    val conn = getConnection(guild)
    val statement = conn.prepareStatement("DELETE FROM worlds WHERE name = ?")
    val formalName = query.toLowerCase().capitalize
    statement.setString(1, formalName)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  def createChannels(event: SlashCommandInteractionEvent): MessageEmbed = {
    // get guild & world information from the slash interaction
    val world: String = event.getInteraction.getOptions.asScala.find(_.getName == "world").map(_.getAsString).getOrElse("").trim().toLowerCase().capitalize
    val embedText = if (worlds.contains(world)) {
      // get guild id
      val guild = event.getGuild

      // assume initial run on this server and attempt to create core databases
      createConfigDatabase(guild)

      val botRole = guild.getRolesByName(botName, true).get(0)
      val fullblessRoleString = s"$world Fullbless"
      val fullblessRoleCheck = guild.getRolesByName(fullblessRoleString, true)
      val fullblessRole = if (!fullblessRoleCheck.isEmpty) fullblessRoleCheck.get(0) else guild.createRole().setName(fullblessRoleString).setColor(new Color(0, 156, 70)).complete()

      val nemesisRoleString = s"$world Nemesis Boss"
      val nemesisRoleCheck = guild.getRolesByName(nemesisRoleString, true)
      val nemesisRole = if (!nemesisRoleCheck.isEmpty) nemesisRoleCheck.get(0) else guild.createRole().setName(nemesisRoleString).setColor(new Color(164, 76, 230)).complete()

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
        adminCategory.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
        val adminChannel = guild.createTextChannel("command-log", adminCategory).complete()
        // restrict the channel so only roles with Permission.MANAGE_MESSAGES can write to the channels
        adminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
        adminChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
        adminChannel.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
        val guildOwner = if (guild.getOwner == null) "Not Available" else guild.getOwner.getEffectiveName
        discordCreateConfig(guild, guild.getName, guildOwner, adminCategory.getId, adminChannel.getId, "0", "0", ZonedDateTime.now())

        val boostedChannel = guild.createTextChannel("notifications", adminCategory).complete()
        boostedChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
        boostedChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
        boostedChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_EMBED_LINKS).complete()
        boostedChannel.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
        discordUpdateConfig(guild, "", "", boostedChannel.getId, "")

        val galthenEmbed = new EmbedBuilder()
        galthenEmbed.setColor(3092790)
        galthenEmbed.setDescription("This is a **[Galthen's Satchel](https://tibia.fandom.com/wiki/Galthen's_Satchel)** cooldown tracker.\nManage your cooldowns here:")
        galthenEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
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

        combinedFutures
          .map(embeds => boostedChannel.sendMessageEmbeds(embeds.asJava)
            .setActionRow(
              Button.primary("boosted list", "Server Save Notifications").withEmoji(Emoji.fromFormatted(Config.letterEmoji))
            )
            .queue((message: Message) => {
              //updateBoostedMessage(guild.getId, message.getId)
              discordUpdateConfig(guild, "", "", "", message.getId)
            }, (e: Throwable) => {
              logger.warn(s"Failed to send boosted boss/creature message for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}':", e)
            })
          )
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
          discordUpdateConfig(guild, adminCategory.getId, "", "", "")
          adminCategoryCheck = adminCategory
        }
        if (adminChannelCheck == null) {
          // admin channel has been deleted
          val adminChannel = guild.createTextChannel("command-log", adminCategoryCheck).complete()
          adminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
          adminChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
          adminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_EMBED_LINKS).complete()
          adminChannel.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
          discordUpdateConfig(guild, "", adminChannel.getId, "", "")
        }
        if (boostedChannelCheck == null) {
          // admin category still exists
          val boostedChannel = guild.createTextChannel("notifications", adminCategoryCheck).complete()
          boostedChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
          boostedChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
          boostedChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_EMBED_LINKS).complete()
          boostedChannel.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
          discordUpdateConfig(guild, "", "", boostedChannel.getId, "")

          val galthenEmbed = new EmbedBuilder()
          galthenEmbed.setColor(3092790)
          galthenEmbed.setDescription("This is a **[Galthen's Satchel](https://tibia.fandom.com/wiki/Galthen's_Satchel)** cooldown tracker.\nManage your cooldowns here:")
          galthenEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
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

          combinedFutures
            .map(embeds => boostedChannel.sendMessageEmbeds(embeds.asJava)
              .setActionRow(
                Button.primary("boosted list", "Server Save Notifications").withEmoji(Emoji.fromFormatted(Config.letterEmoji))
              )
              .queue((message: Message) => {
                //updateBoostedMessage(guild.getId, message.getId)
                discordUpdateConfig(guild, "", "", "", message.getId)
              }, (e: Throwable) => {
                logger.warn(s"Failed to send boosted boss/creature message for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}':", e)
              })
            )
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
          .grant(Permission.MANAGE_WEBHOOKS)
          .complete()
        newCategory.upsertPermissionOverride(guild.getPublicRole).deny(Permission.MESSAGE_SEND).complete()
        // create the channels
        val alliesChannel = guild.createTextChannel("online", newCategory).complete()
        //val enemiesChannel = guild.createTextChannel("enemies", newCategory).complete()
        //val neutralsChannel = guild.createTextChannel("neutrals", newCategory).complete()
        val levelsChannel = guild.createTextChannel("levels", newCategory).complete()
        val deathsChannel = guild.createTextChannel("deaths", newCategory).complete()
        val activityChannel = guild.createTextChannel("activity", newCategory).complete()

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
        levelsChannel.upsertPermissionOverride(botRole).grant(Permission.MANAGE_WEBHOOKS).complete()

        val notificationsConfig = discordRetrieveConfig(guild)
        val notificationsChannel = guild.getTextChannelById(notificationsConfig("boosted_channel"))

        if (notificationsChannel != null) {
          if (notificationsChannel.canTalk()) {
            // Fullbless Role
            val fullblessEmbedText = s"The bot will poke <@&${fullblessRole.getId}>\n\nIf an enemy player dies fullbless and is over level `250`.\nAdd or remove yourself from the role using the buttons below."
            val fullblessEmbed = new EmbedBuilder()
            fullblessEmbed.setTitle(s":crossed_swords: $world :crossed_swords:", s"https://www.tibia.com/community/?subtopic=worlds&world=$world")
            fullblessEmbed.setThumbnail(Config.aolThumbnail)
            fullblessEmbed.setColor(3092790)
            fullblessEmbed.setDescription(fullblessEmbedText)
            notificationsChannel.sendMessageEmbeds(fullblessEmbed.build())
              .setActionRow(
                Button.success(s"add", "Add Role"),
                Button.danger(s"remove", "Remove Role")
              )
              .queue()

            // Nemesis role
            val nemesisList = List("Zarabustor", "Midnight_Panther", "Yeti", "Shlorg", "White_Pale", "Furyosa", "Jesse_the_Wicked", "The_Welter", "Tyrn", "Zushuka")
            val nemesisThumbnail = nemesisList(count % nemesisList.size)

            val nemesisEmbedText = s"The bot will poke <@&${nemesisRole.getId}>\n\nIf anyone dies to a rare boss (so you can go steal it).\nAdd or remove yourself from the role using the buttons below."
            val nemesisEmbed = new EmbedBuilder()
            nemesisEmbed.setTitle(s"${Config.nemesisEmoji} $world ${Config.nemesisEmoji}", s"https://www.tibia.com/community/?subtopic=worlds&world=$world")
            nemesisEmbed.setThumbnail(s"https://tibia.fandom.com/wiki/Special:Redirect/file/$nemesisThumbnail.gif")
            nemesisEmbed.setColor(3092790)
            nemesisEmbed.setDescription(nemesisEmbedText)
            notificationsChannel.sendMessageEmbeds(nemesisEmbed.build())
              .setActionRow(
                Button.success("add", "Add Role"),
                Button.danger("remove", "Remove Role")
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
          levelsEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Sign_(Library).gif")
          levelsEmbed.setColor(3092790)
          levelsTextChannel.sendMessageEmbeds(levelsEmbed.build()).queue()
        }

        // post initial embed in deaths channel
        val deathsTextChannel: TextChannel = guild.getTextChannelById(deathsId)
        if (deathsTextChannel != null) {
          val deathsEmbed = new EmbedBuilder()
          deathsEmbed.setDescription(s":speech_balloon: This channel shows deaths that occur on this world.\n\nYou can filter what appears in this channel using the **`/deaths filter`** command.")
          deathsEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Sign_(Library).gif")
          deathsEmbed.setColor(3092790)
          deathsTextChannel.sendMessageEmbeds(deathsEmbed.build()).queue()
        }

        // post initial embed in activity channel
        val activityTextChannel: TextChannel = guild.getTextChannelById(activityId)
        if (activityTextChannel != null) {
          val activityEmbed = new EmbedBuilder()
          activityEmbed.setDescription(s":speech_balloon: This channel shows change activity for *allied* or *enemy* players.\n\nIt will show events when a players **joins** or **leaves** one of these tracked guilds or **changes their name**.")
          activityEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Sign_(Library).gif")
          activityEmbed.setColor(3092790)
          activityTextChannel.sendMessageEmbeds(activityEmbed.build()).queue()
        }

        // update the database
        worldCreateConfig(guild, world, alliesId, enemiesId, neutralsId, levelsId, deathsId, categoryId, fullblessRole.getId, nemesisRole.getId, "0", "0", activityId)
        startBot(Some(guild), Some(world))
        s":gear: The channels for **$world** have been configured successfully."
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
          if (adminChannel.canTalk()) {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> set **automatic enemy detection** to **$settingOption** for the world **$worldFormal**.")
            adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Armillary_Sphere_(TibiaMaps).gif")
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

  private def detectHuntedsToDatabase(guild: Guild, world: String, detectSetting: String): Unit = {
    val worldFormal = world.toLowerCase().capitalize
    val conn = getConnection(guild)
    val statement = conn.prepareStatement("UPDATE worlds SET detect_hunteds = ? WHERE name = ?;")
    statement.setString(1, detectSetting)
    statement.setString(2, worldFormal)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

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
          if (adminChannel.canTalk()) {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> set the **$channelType** channel to **$setting $playerType** for the world **$worldFormal**.")
            adminEmbed.setThumbnail(s"https://tibia.fandom.com/wiki/Special:Redirect/file/$thumbnailIcon.gif")
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
          if (adminChannel.canTalk()) {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> set **exiva list on deaths** to **$settingOption** for the world **$worldFormal**.")
            adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Find_Person.gif")
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

  private def exivaListToDatabase(guild: Guild, world: String, detectSetting: String): Unit = {
    val worldFormal = world.toLowerCase().capitalize
    val conn = getConnection(guild)
    val statement = conn.prepareStatement("UPDATE worlds SET exiva_list = ? WHERE name = ?;")
    statement.setString(1, detectSetting)
    statement.setString(2, worldFormal)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

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

        val botRole = guild.getRolesByName(botName, true).get(0)
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
                .grant(Permission.MANAGE_WEBHOOKS)
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
            val recreateAlliesChannel = guild.createTextChannel("online", category).complete()
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
                .grant(Permission.MANAGE_WEBHOOKS)
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
            val recreateAlliesChannel = guild.createTextChannel("allies", category).complete()
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
              val recreateEnemiesChannel = guild.createTextChannel("enemies", category).complete()
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
              val recreateNeutralsChannel = guild.createTextChannel("neutrals", category).complete()
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
                if (webhooks) {
                  channel.upsertPermissionOverride(botRole).grant(Permission.MANAGE_WEBHOOKS).complete()
                }
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
          if (adminChannel.canTalk()) {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> set the online list channel to **$setting** for the world **$worldFormal**.\n$disclaimer")
            adminEmbed.setThumbnail(s"https://tibia.fandom.com/wiki/Special:Redirect/file/$thumbnailIcon.gif")
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

  private def onlineListConfigToDatabase(guild: Guild, world: String, setting: String): Unit = {
    val worldFormal = world.toLowerCase().capitalize
    val conn = getConnection(guild)
    val statement = conn.prepareStatement(s"UPDATE worlds SET online_combined = ? WHERE name = ?;")
    statement.setString(1, setting)
    statement.setString(2, worldFormal)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  private def customSortConfig(guild: Guild, query: String): List[CustomSort] = {
    val conn = getConnection(guild)
    val statement = conn.createStatement()

    // Check if the table already exists in bot_configuration
    val tableExistsQuery = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'online_list_categories'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    // Create the table if it doesn't exist
    if (!tableExists) {
      val createCustomSortTable =
        s"""CREATE TABLE online_list_categories (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |entity VARCHAR(255) NOT NULL,
           |name VARCHAR(255) NOT NULL,
           |label VARCHAR(255) NOT NULL,
           |emoji VARCHAR(255) NOT NULL,
           |added VARCHAR(255) NOT NULL
           |);""".stripMargin

      statement.executeUpdate(createCustomSortTable)
    }

    val result = statement.executeQuery(s"SELECT entity,name,label,emoji FROM $query")

    val results = new ListBuffer[CustomSort]()
    while (result.next()) {
      val entity = Option(result.getString("entity")).getOrElse("")
      val name = Option(result.getString("name")).getOrElse("")
      val label = Option(result.getString("label")).getOrElse("")
      val emoji = Option(result.getString("emoji")).getOrElse("")

      results += CustomSort(entity, name, label, emoji)
    }

    statement.close()
    conn.close()
    results.toList
  }

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
                if (adminChannel.canTalk()) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> tagged the guild **[$guildName](${guildUrl(guildName)})** with: $emojiDupe **$labelCapital** $emojiDupe")
                  adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Library_Ticket.gif")
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
                if (adminChannel.canTalk()) {
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(s":gear: a command was run:")
                  adminEmbed.setDescription(s"<@$commandUser> tagged the player\n$vocation **$level** — **[$playerName](${charUrl(playerName)})**\nwith: $emojiDupe **$labelCapital** $emojiDupe")
                  adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Library_Ticket.gif")
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

  private def addOnlineListCategoryToDatabase(guild: Guild, guildOrPlayer: String, name: String, label: String, emoji: String): Unit = {
    val conn = getConnection(guild)
    val query = "INSERT INTO online_list_categories(entity, name, label, emoji, added) VALUES (?, ?, ?, ?, ?);"
    val statement = conn.prepareStatement(query)
    statement.setString(1, guildOrPlayer)
    statement.setString(2, name)
    statement.setString(3, label)
    statement.setString(4, emoji)
    statement.setString(5, ZonedDateTime.now().toEpochSecond().toString)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

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
            if (adminChannel.canTalk()) {
              val adminEmbed = new EmbedBuilder()
              adminEmbed.setTitle(s":gear: a command was run:")
              adminEmbed.setDescription(s"<@$commandUser> removed the guild **$nameLower** from custom tagging.")
              adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Library_Ticket.gif")
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
            if (adminChannel.canTalk()) {
              val adminEmbed = new EmbedBuilder()
              adminEmbed.setTitle(s":gear: a command was run:")
              adminEmbed.setDescription(s"<@$commandUser> removed the player **$nameLower** from custom tagging.")
              adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Library_Ticket.gif")
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

  private def removeOnlineListCategoryFromDatabase(guild: Guild, guildOrPlayer: String, name: String): Unit = {
    val conn = getConnection(guild)
    val statement = conn.prepareStatement(s"DELETE FROM online_list_categories WHERE name = ? AND entity = ?;")
    statement.setString(1, name)
    statement.setString(2, guildOrPlayer)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

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
          if (adminChannel.canTalk()) {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> cleared everyone from the tag **$labelLower**.")
            adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Library_Ticket.gif")
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

  private def clearOnlineListCategoryFromDatabase(guild: Guild, label: String): Unit = {
    val conn = getConnection(guild)
    val statement = conn.prepareStatement(s"DELETE FROM online_list_categories WHERE LOWER(label) = LOWER(?);")
    statement.setString(1, label)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

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
    val conn = getConnection(guild)
    val tablePrefix = playerType match {
      case "allies" => "show_allies_"
      case "neutrals" => "show_neutral_"
      case "enemies" => "show_enemies_"
      case _ => ""
    }
    val tableName = s"$tablePrefix$channelType"
    val statement = conn.prepareStatement(s"UPDATE worlds SET $tableName = ? WHERE name = ?;")
    statement.setString(1, setting)
    statement.setString(2, worldFormal)
    statement.executeUpdate()

    statement.close()
    conn.close()
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
            val messages = channel.getHistory.retrievePast(100).complete().asScala.filter { m =>
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
                      val roleId = worldConfigData("fullbless_role")
                      val fullblessEmbedText = s"The bot will poke <@&$roleId>\n\nIf an enemy player dies fullbless and is over level `$level`.\nAdd or remove yourself from the role using the buttons below."
                      val fullblessEmbed = new EmbedBuilder()
                      fullblessEmbed.setTitle(s":crossed_swords: $worldFormal :crossed_swords:", s"https://www.tibia.com/community/?subtopic=worlds&world=$worldFormal")
                      fullblessEmbed.setThumbnail(Config.aolThumbnail)
                      fullblessEmbed.setColor(3092790)
                      fullblessEmbed.setDescription(fullblessEmbedText)
                      message.editMessageEmbeds(fullblessEmbed.build())
                      .setActionRow(
                        Button.success(s"add", "Add Role"),
                        Button.danger(s"remove", "Remove Role")
                      ).queue()
                    }
                  }
                }
              }
            }
          }
        }
        if (adminChannel != null) {
          if (adminChannel.canTalk()) {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> changed the level to poke for **enemy fullblesses**\nto **$level** for the world **$worldFormal**.")
            adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Amulet_of_Loss.gif")
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

  def repairChannel(event: SlashCommandInteractionEvent, world: String): MessageEmbed = {
    val worldFormal = world.toLowerCase().capitalize
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    val cache: Option[List[Worlds]] = worldsData.get(guild.getId) match {
      case Some(worlds) =>
        val filteredWorlds = worlds.filter(w => w.name.toLowerCase() == world.toLowerCase())
        if (filteredWorlds.nonEmpty) Some(filteredWorlds)
        else None
      case None => None
    }
    if (cache.isDefined) {
      // get the bots main roles
      val botRole = guild.getRolesByName(botName, true).get(0)
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
        var fullblessMessage = false
        var nemesisMessage = false
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

          // post fullbless message again
          val fullblessEmbedText = s"The bot will poke <@&${fullblessRole.getId}>\n\nIf an enemy player dies fullbless and is over level `${fullblessLevel}`.\nAdd or remove yourself from the role using the buttons below."
          val fullblessEmbed = new EmbedBuilder()
          fullblessEmbed.setTitle(s":crossed_swords: $worldFormal :crossed_swords:", s"https://www.tibia.com/community/?subtopic=worlds&world=$worldFormal")
          fullblessEmbed.setThumbnail(Config.aolThumbnail)
          fullblessEmbed.setColor(3092790)
          fullblessEmbed.setDescription(fullblessEmbedText)
          boostedChannel.sendMessageEmbeds(fullblessEmbed.build())
            .setActionRow(
              Button.success(s"add", "Add Role"),
              Button.danger(s"remove", "Remove Role")
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
          embedBuild.setDescription(s":gear: The missing channels for **$worldFormal** have been recreated.\nYou may need to rearrange their position within your discord server.")
        }
        if (!nemesisMessage) {
          // post nemesis message again
          val nemesisRoleCheck = guild.getRoleById(worldConfigData("nemesis_role"))
          val nemesisRole = if (nemesisRoleCheck == null) guild.createRole().setName(s"$worldFormal Nemesis Boss").setColor(new Color(164, 76, 230)).complete() else nemesisRoleCheck
          val worldCount = worldConfig(guild)
          val count = worldCount.length
          val nemesisList = List("Zarabustor", "Midnight_Panther", "Yeti", "Shlorg", "White_Pale", "Furyosa", "Jesse_the_Wicked", "The_Welter", "Tyrn", "Zushuka")
          val nemesisThumbnail = nemesisList(count % nemesisList.size)

          val nemesisEmbedText = s"The bot will poke <@&${nemesisRole.getId}>\n\nIf anyone dies to a rare boss (so you can go steal it).\nAdd or remove yourself from the role using the buttons below."
          val nemesisEmbed = new EmbedBuilder()
          nemesisEmbed.setTitle(s"${Config.nemesisEmoji} $worldFormal ${Config.nemesisEmoji}", s"https://www.tibia.com/community/?subtopic=worlds&world=$worldFormal")
          nemesisEmbed.setThumbnail(s"https://tibia.fandom.com/wiki/Special:Redirect/file/$nemesisThumbnail.gif")
          nemesisEmbed.setColor(3092790)
          nemesisEmbed.setDescription(nemesisEmbedText)
          boostedChannel.sendMessageEmbeds(nemesisEmbed.build())
            .setActionRow(
              Button.success("add", "Add Role"),
              Button.danger("remove", "Remove Role")
            )
            .queue()
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
          embedBuild.setDescription(s":gear: The missing channels for **$worldFormal** have been recreated.\nYou may need to rearrange their position within your discord server.")
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
            .grant(Permission.MANAGE_WEBHOOKS)
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
          val alliesName = if (onlineCombinedVal == "false") "allies" else "online"
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
          val recreateEnemiesChannel = guild.createTextChannel("enemies", category).complete()
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
          val recreateNeutralsChannel = guild.createTextChannel("neutrals", category).complete()
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
        if (levelsChannel == null) {
          val recreateLevelsChannel = guild.createTextChannel("levels", category).complete()
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
        if (deathsChannel == null) {
          val recreateDeathsChannel = guild.createTextChannel("deaths", category).complete()
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
        if (activityChannel == null) {
          val recreateActivityChannel = guild.createTextChannel("activity", category).complete()
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
            activityEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Sign_(Library).gif")
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
            newAdminCategory.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
            adminCategory = newAdminCategory
          }
          // create the channel
          val newBoostedChannel = guild.createTextChannel("notifications", adminCategory).complete()

          // restrict the channel so only roles with Permission.MANAGE_MESSAGES can write to the channels
          newBoostedChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
          newBoostedChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
          newBoostedChannel.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
          boostedChannel = newBoostedChannel
          // update db & cache
          discordUpdateConfig(guild, adminCategory.getId, "", newBoostedChannel.getId, "")
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
          galthenEmbed.setDescription("This is a **[Galthen's Satchel](https://tibia.fandom.com/wiki/Galthen's_Satchel)** cooldown tracker.\nManage your cooldowns here:")
          galthenEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
          newBoostedChannel.sendMessageEmbeds(galthenEmbed.build()).addActionRow(
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

          combinedFutures
            .map(embeds => boostedChannel.sendMessageEmbeds(embeds.asJava)
              .setActionRow(
                Button.primary("boosted list", "Server Save Notifications").withEmoji(Emoji.fromFormatted(Config.letterEmoji))
              )
              .queue((message: Message) => {
                //updateBoostedMessage(guild.getId, message.getId)
                discordUpdateConfig(guild, "", "", "", message.getId)
              }, (e: Throwable) => {
                logger.warn(s"Failed to send boosted boss/creature message for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}':", e)
              })
            )

          val worldConfigData = worldRetrieveConfig(guild, world)
          val fullblessLevel = worldConfigData("fullbless_level")
          val fullblessRoleCheck = guild.getRoleById(worldConfigData("fullbless_role"))
          val fullblessRole = if (fullblessRoleCheck == null) guild.createRole().setName(s"$worldFormal Fullbless").setColor(new Color(0, 156, 70)).complete() else fullblessRoleCheck

          // post fullbless message again
          val fullblessEmbedText = s"The bot will poke <@&${fullblessRole.getId}>\n\nIf an enemy player dies fullbless and is over level `${fullblessLevel}`.\nAdd or remove yourself from the role using the buttons below."
          val fullblessEmbed = new EmbedBuilder()
          fullblessEmbed.setTitle(s":crossed_swords: $worldFormal :crossed_swords:", s"https://www.tibia.com/community/?subtopic=worlds&world=$worldFormal")
          fullblessEmbed.setThumbnail(Config.aolThumbnail)
          fullblessEmbed.setColor(3092790)
          fullblessEmbed.setDescription(fullblessEmbedText)
          boostedChannel.sendMessageEmbeds(fullblessEmbed.build())
            .setActionRow(
              Button.success(s"add", "Add Role"),
              Button.danger(s"remove", "Remove Role")
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
          // post nemesis message again
          val nemesisRoleCheck = guild.getRoleById(worldConfigData("nemesis_role"))
          val nemesisRole = if (nemesisRoleCheck == null) guild.createRole().setName(s"$worldFormal Nemesis Boss").setColor(new Color(164, 76, 230)).complete() else nemesisRoleCheck
          val worldCount = worldConfig(guild)
          val count = worldCount.length
          val nemesisList = List("Zarabustor", "Midnight_Panther", "Yeti", "Shlorg", "White_Pale", "Furyosa", "Jesse_the_Wicked", "The_Welter", "Tyrn", "Zushuka")
          val nemesisThumbnail = nemesisList(count % nemesisList.size)

          val nemesisEmbedText = s"The bot will poke <@&${nemesisRole.getId}>\n\nIf anyone dies to a rare boss (so you can go steal it).\nAdd or remove yourself from the role using the buttons below."
          val nemesisEmbed = new EmbedBuilder()
          nemesisEmbed.setTitle(s"${Config.nemesisEmoji} $worldFormal ${Config.nemesisEmoji}", s"https://www.tibia.com/community/?subtopic=worlds&world=$worldFormal")
          nemesisEmbed.setThumbnail(s"https://tibia.fandom.com/wiki/Special:Redirect/file/$nemesisThumbnail.gif")
          nemesisEmbed.setColor(3092790)
          nemesisEmbed.setDescription(nemesisEmbedText)
          boostedChannel.sendMessageEmbeds(nemesisEmbed.build())
            .setActionRow(
              Button.success("add", "Add Role"),
              Button.danger("remove", "Remove Role")
            )
            .queue()
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
              channel.upsertPermissionOverride(botRole).grant(Permission.MANAGE_WEBHOOKS).complete()
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
            newAdminCategory.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
            adminCategory = newAdminCategory
          }
          // create the channel
          val newAdminChannel = guild.createTextChannel("command-log", adminCategory).complete()
          // restrict the channel so only roles with Permission.MANAGE_MESSAGES can write to the channels
          newAdminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
          newAdminChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
          newAdminChannel.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
          adminChannel = newAdminChannel
          // update db & cache
          discordUpdateConfig(guild, adminCategory.getId, newAdminChannel.getId, "", "")
          updateAdminChannel(guild.getId, newAdminChannel.getId)
        }
        if (adminChannel != null) {
          if (adminChannel.canTalk()) {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> has run `/repair` on the world **$worldFormal** and recreated missing channels.\n\nYou may need to rearrange their position within your discord server.")
            adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Hammer.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          }
        }
        embedBuild.setDescription(s":gear: The missing channels for **$worldFormal** have been recreated.\nYou may need to rearrange their position within your discord server.")
      } else {
        embedBuild.setDescription(s"${Config.noEmoji} No action was taken as all channels for **$worldFormal** still exist.")
      }
    } else {
      embedBuild.setDescription(s"${Config.noEmoji} You cannot run a `/repair` on **$worldFormal** because that world has not been `/setup` yet.")
    }
    embedBuild.build()
  }

  private def worldRepairConfig(guild: Guild, world: String, tableName: String, newValue: String): Unit = {
    val conn = getConnection(guild)
    val statement = conn.prepareStatement(s"UPDATE worlds SET $tableName = ? WHERE name = ?;")
    statement.setString(1, newValue)
    statement.setString(2, world)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

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
          if (adminChannel.canTalk()) {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> changed the minimum level for the **$levelsOrDeaths channel**\nto `$level` for the world **$worldFormal**.")
            adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Royal_Fanfare.gif")
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

  private def fullblessLevelToDatabase(guild: Guild, world: String, level: Int): Unit = {
    val conn = getConnection(guild)
    val statement = conn.prepareStatement("UPDATE worlds SET fullbless_level = ? WHERE name = ?;")
    statement.setInt(1, level)
    statement.setString(2, world)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  private def minLevelToDatabase(guild: Guild, world: String, level: Int, levelOrDeath: String): Unit = {
    val conn = getConnection(guild)
    val columnName = if (levelOrDeath == "levels") "levels_min" else "deaths_min"
    val statement = conn.prepareStatement(s"UPDATE worlds SET $columnName = ? WHERE name = ?;")
    statement.setInt(1, level)
    statement.setString(2, world)
    statement.executeUpdate()

    statement.close()
    conn.close()
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

    // Remove from botStreams if exists
    val updatedBotStreams = botStreams.map { case (world, streams) =>
      val updatedUsedBy = streams.usedBy.filterNot(_.id == guildId)
      if (updatedUsedBy.isEmpty) {
        streams.stream.cancel()
        None // Return None to indicate that this entry should be removed from the map
      } else if (streams.usedBy != updatedUsedBy) {
        // Only update the streams if the usedBy list has changed
        Some(world -> streams.copy(usedBy = updatedUsedBy)) // Return the updated entry wrapped in Some
      } else {
        Some(world -> streams) // Return the existing entry wrapped in Some
      }
    }.flatten.toMap // Convert the resulting Iterable[(String, Streams)] back into a Map

    // Only update botStreams if any changes were made
    if (updatedBotStreams != botStreams) {
      botStreams = updatedBotStreams
    }
    removeConfigDatabase(guildId)
  }

  def discordJoin(event: GuildJoinEvent): Unit = {
    val guild = event.getGuild
    val publicChannel = guild.getTextChannelById(guild.getDefaultChannel.getId)
    if (publicChannel != null) {
      if (publicChannel.canTalk()) {
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
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/postgres"
    val username = "postgres"
    val password = Config.postgresPassword

    val conn = DriverManager.getConnection(url, username, password)
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
        val discordInfo = discordRetrieveConfig(guild)
        val boostedChannelId = discordInfo("boosted_channel")

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

        val worldCount = worldConfig(guild)
        val count = worldCount.length

        // check if command is being run in one of the channels being deleted
        if (channelIds.contains(event.getChannel.getId)) {
          return new EmbedBuilder()
          .setColor(3092790)
          .setDescription(s"${Config.noEmoji} That command would delete this channel, run it somewhere else.")
          .build()
        }

        val boostedChannel = guild.getTextChannelById(boostedChannelId)
        if (boostedChannel != null){
          if (count == 1) {
            if (event.getChannel.getId == boostedChannelId) {
              return new EmbedBuilder()
              .setColor(3092790)
              .setDescription(s"${Config.noEmoji} That command would delete this channel, run it somewhere else.")
              .build()
            }
            try {
              boostedChannel.delete.queue()
              discordUpdateConfig(guild, "", "", "0", "0")
            } catch {
              case _: Throwable => //
            }
          } else {
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
                    if (messageTitle.startsWith(s":crossed_swords: $world")) {
                      try {
                        message.delete.queue()
                      } catch {
                        case _: Throwable => //
                      }
                    } else if (messageTitle.startsWith(s"${Config.nemesisEmoji} $world")) {
                      try {
                        message.delete.queue()
                      } catch {
                        case _: Throwable => //
                      }
                    }
                  }
                }
              }
            }
          }
        }

        val fullblessRoleId = worldConfigData("fullbless_role")
        val nemesisRoleId = worldConfigData("nemesis_role")

        val fullblessRole = guild.getRoleById(nemesisRoleId)
        val nemesisRole = guild.getRoleById(fullblessRoleId)

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

        // remove the guild from the world stream
        val getWorldStream = botStreams.get(world)
        getWorldStream match {
          case Some(streams) =>
            // remove the guild from the usedBy list
            val updatedUsedBy = streams.usedBy.filterNot(_.id == guild.getId)
            // if there are no more guilds in the usedBy list
            if (updatedUsedBy.isEmpty) {
              streams.stream.cancel()
              botStreams -= world
            } else {
              // update the botStreams map with the updated usedBy list
              botStreams += (world -> streams.copy(usedBy = updatedUsedBy))
            }
          case None =>
            logger.info(s"No stream found for guild '${guild.getName} - ${guild.getId}' and world '$world'.")
        }

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

  def adminLeave(event: SlashCommandInteractionEvent, guildId: String, reason: String): MessageEmbed = {
    // get guild & world information from the slash interaction
    val guildL: Long = java.lang.Long.parseLong(guildId)
    val guild = jda.getGuildById(guildL)
    val discordInfo = discordRetrieveConfig(guild)
    var embedMessage = ""

    if (discordInfo.isEmpty) {
      embedMessage = s":gear: The bot has left the Guild: **${guild.getName()}** without leaving a message for the owner."
    } else {
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (adminChannel != null) {
        if (adminChannel.canTalk()) {
          try {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s"${Config.noEmoji} The creator of the bot has run a command:")
            adminEmbed.setDescription(s"<@$botUser> has left your discord because of the following reason:\n> ${reason}")
            adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Abacus.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          } catch {
            case ex: Throwable => logger.info(s"Failed to send admin message for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'", ex)
          }
        }
      }
      embedMessage = s":gear: The bot has left the Guild: **${guild.getName()}** and left a message for the owner."
    }

    guild.leave().queue()
    // embed reply
    new EmbedBuilder()
    .setColor(3092790)
    .setDescription(embedMessage)
    .build()
  }

  def adminMessage(event: SlashCommandInteractionEvent, guildId: String, message: String): MessageEmbed = {
    // get guild & world information from the slash interaction
    val guildL: Long = java.lang.Long.parseLong(guildId)
    val guild = jda.getGuildById(guildL)
    val discordInfo = discordRetrieveConfig(guild)
    var embedMessage = ""

    if (discordInfo.isEmpty) {
      embedMessage = s"${Config.noEmoji} The Guild: **${guild.getName()}** doesn't have any worlds setup yet, so a message cannot be sent."
    } else {
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (adminChannel != null) {
        if (adminChannel.canTalk()) {
          try {
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s"${Config.noEmoji} The creator of the bot has run a command:")
            adminEmbed.setDescription(s"<@$botUser> has forwarded a message from the bot's creator:\n> ${message}")
            adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Letter.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          } catch {
            case ex: Throwable => logger.info(s"Failed to send admin message for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}'")
          }
        }
      } else {
        embedMessage = s"${Config.noEmoji} The Guild: **${guild.getName()}** has deleted the `command-log` channel, so a message cannot be sent."
      }
      embedMessage = s":gear: The bot has left a message for the Guild: **${guild.getName()}**."
    }
    // embed reply
    new EmbedBuilder()
    .setColor(3092790)
    .setDescription(embedMessage)
    .build()
  }

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

  private def creatureWikiUrl(creature: String): String = {
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
    s"https://tibia.fandom.com/wiki/$finalCreature"
  }

  // V1.9 Boosted Command
  def createBoostedEmbed(name: String, emoji: String, wikiUrl: String, thumbnail: String, embedText: String): MessageEmbed = {
    val embed = new EmbedBuilder()
    //embed.setTitle(s"$emoji $name $emoji", wikiUrl)
    embed.setThumbnail(thumbnail)
    embed.setColor(3092790)
    embed.setDescription(embedText)
    embed.build()
  }

  def capitalizeAllWords(s: String): String = {
    s.split(" ").map(_.capitalize).mkString(" ")
  }

  def boostedAll(): List[BoostedStamp] = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword
    val conn = DriverManager.getConnection(url, username, password)
    val statement = conn.createStatement()

    // Check if the table already exists in bot_configuration
    val tableExistsQuery =
      statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'boosted_notifications'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    // Create the table if it doesn't exist
    if (!tableExists) {
      val createListTable =
        s"""CREATE TABLE boosted_notifications (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |userid VARCHAR(255) NOT NULL,
           |name VARCHAR(255) NOT NULL,
           |type VARCHAR(255),
           |CONSTRAINT unique_user_name_constraint UNIQUE (userid, name)
           |);""".stripMargin

      statement.executeUpdate(createListTable)
    }

    val result = statement.executeQuery(s"SELECT userid,name,type FROM boosted_notifications;")
    val boostedStampList: ListBuffer[BoostedStamp] = ListBuffer()

    while (result.next()) {
      val boostedUserSql = Option(result.getString("userid")).getOrElse("")
      val boostedNameSql = Option(result.getString("name")).getOrElse("")
      val boostedTypeSql = Option(result.getString("type")).getOrElse("")

      val boostedStamp = BoostedStamp(boostedUserSql, boostedTypeSql, boostedNameSql)
      boostedStampList += boostedStamp
    }

    statement.close()
    conn.close()

    boostedStampList.toList
  }

  def boostedList(userId: String): Boolean = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword
    val conn = DriverManager.getConnection(url, username, password)
    val statement = conn.createStatement()

    // Check if the table already exists in bot_configuration
    val tableExistsQuery =
      statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'boosted_notifications'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    // Create the table if it doesn't exist
    if (!tableExists) {
      val createListTable =
        s"""CREATE TABLE boosted_notifications (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |userid VARCHAR(255) NOT NULL,
           |name VARCHAR(255) NOT NULL,
           |type VARCHAR(255),
           |CONSTRAINT unique_user_name_constraint UNIQUE (userid, name)
           |);""".stripMargin

      statement.executeUpdate(createListTable)
    }

    val result = statement.executeQuery(s"SELECT name,type FROM boosted_notifications WHERE userid = '$userId';")
    val boostedStampList: ListBuffer[BoostedStamp] = ListBuffer()

    while (result.next()) {
      val boostedNameSql = Option(result.getString("name")).getOrElse("")
      val boostedTypeSql = Option(result.getString("type")).getOrElse("")

      val boostedStamp = BoostedStamp(userId, boostedTypeSql, boostedNameSql)
      boostedStampList += boostedStamp
    }

    statement.close()
    conn.close()

    val existingNames = boostedStampList.toList
    existingNames.exists(bs => bs.user == userId && bs.boostedName.toLowerCase == "all")
  }

  def boosted(userId: String, boostedOption: String, boostedName: String): MessageEmbed = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
    val username = "postgres"
    val password = Config.postgresPassword
    val conn = DriverManager.getConnection(url, username, password)
    var embedMessage = s"${Config.noEmoji} This command failed to run, try again?"

    val statement = conn.createStatement()

    // Check if the table already exists in bot_configuration
    val tableExistsQuery =
      statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'boosted_notifications'")
    val tableExists = tableExistsQuery.next()
    tableExistsQuery.close()

    // Create the table if it doesn't exist
    if (!tableExists) {
      val createListTable =
        s"""CREATE TABLE boosted_notifications (
           |id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
           |userid VARCHAR(255) NOT NULL,
           |name VARCHAR(255) NOT NULL,
           |type VARCHAR(255),
           |CONSTRAINT unique_user_name_constraint UNIQUE (userid, name)
           |);""".stripMargin

      statement.executeUpdate(createListTable)
    }

    val result = statement.executeQuery(s"SELECT name,type FROM boosted_notifications WHERE userid = '$userId';")
    val boostedStampList: ListBuffer[BoostedStamp] = ListBuffer()

    while (result.next()) {
      val boostedNameSql = Option(result.getString("name")).getOrElse("")
      val boostedTypeSql = Option(result.getString("type")).getOrElse("")

      val boostedStamp = BoostedStamp(userId, boostedTypeSql, boostedNameSql)
      boostedStampList += boostedStamp
    }
    statement.close()

    val sanitizedName = boostedName.replaceAll("[^a-zA-Z'\\-\\s]", "").trim.toLowerCase
    val existingNames = boostedStampList.toList

    val replyEmbed = new EmbedBuilder()
    replyEmbed.setColor(3092790)
    if (boostedOption == "list") { // UNFINISHED
      if (existingNames.size > 0) {
        val listSetting = existingNames.exists(bs => bs.user == userId && bs.boostedName.toLowerCase == "all")
        val groupedAndSorted = existingNames
          .groupBy(_.boostedType)
          .mapValues(_.sortBy(_.boostedName.toLowerCase)) // Sort within each group by name
          .toSeq
          .sortBy(_._1) // Sort groups by type
          .flatMap { case (group, names) =>
            names.map { boosted =>
              val emoji =
                if (group == "boss") Config.bossEmoji
                else if (group == "creature") Config.creatureEmoji
                else Config.indentEmoji

              val nameWithLink =
                if (group == "boss" || group == "creature") s"**[${capitalizeAllWords(boosted.boostedName)}](${creatureWikiUrl(capitalizeAllWords(boosted.boostedName))})**"
                else s"**${capitalizeAllWords(boosted.boostedName)}**"

              s"$emoji $nameWithLink"
            }
          }.mkString("\n")
        embedMessage = if (listSetting) s"${Config.letterEmoji} You will be notified for **all** boosted **bosses** and **creatures** at *server save*." else s"${Config.letterEmoji} You will be messaged if any of the following **booses** or **creatures** are boosted:\n\n$groupedAndSorted"
        val combinedMessage = embedMessage
        if (combinedMessage.size >= 4096) {
          val substituteText = "\n\n*`...cannot display any more results`*"
          val lastLineIndex = embedMessage.lastIndexOf('\n', (4090 - (substituteText.size)))
          val truncatedMessage = embedMessage.substring(0, lastLineIndex)
          embedMessage = truncatedMessage + substituteText
        } else {
          embedMessage = combinedMessage
        }
      } else {
        embedMessage = s"${Config.letterEmoji} Your notification list is *empty*."
      }
    } else if (boostedOption == "add"){
      if (sanitizedName != "") {
        if (existingNames.exists(_.boostedName.replaceAll("[^a-zA-Z'\\-\\s]", "").trim.toLowerCase == sanitizedName)) {
          embedMessage = s"${Config.noEmoji} **$sanitizedName** already exists."
        } else {
          if (sanitizedName == "all") {
            val query =
              "INSERT INTO boosted_notifications (userid, name, type) VALUES (?, ?, ?) ON CONFLICT (userid, name) DO NOTHING"
            val preparedStatement = conn.prepareStatement(query)
            preparedStatement.setString(1, userId)
            preparedStatement.setString(2, sanitizedName)
            preparedStatement.setString(3, "all")
            preparedStatement.executeUpdate()
            preparedStatement.close()
            embedMessage = s"${Config.yesEmoji} you have enabled notifications for **all** bosses and creatures."
          } else {
            // Check if sanitizedName exists in boostedBossesList
            val isBoostedBoss = boostedBossesList.contains(sanitizedName)
            val creatureCheck: Boolean = if (Config.creaturesList.contains(sanitizedName.toLowerCase)) true else false
            val monsterType = if (isBoostedBoss) "boss" else if (creatureCheck) "creature" else "all"
            if (monsterType == "all") {
              val groupedAndSorted = existingNames
                .groupBy(_.boostedType)
                .mapValues(_.sortBy(_.boostedName.toLowerCase)) // Sort within each group by name
                .toSeq
                .sortBy(_._1) // Sort groups by type
                .flatMap { case (group, names) =>
                  names.map { boosted =>
                    val emoji =
                      if (group == "boss") Config.bossEmoji
                      else if (group == "creature") Config.creatureEmoji
                      else Config.indentEmoji

                    val nameWithLink =
                      if (group == "boss" || group == "creature") s"**[${capitalizeAllWords(boosted.boostedName)}](${creatureWikiUrl(capitalizeAllWords(boosted.boostedName))})**"
                      else s"**${capitalizeAllWords(boosted.boostedName)}**"

                    s"$emoji $nameWithLink"
                  }
                }.mkString("\n")
              val listMessage = if (groupedAndSorted.trim != "") s"${Config.letterEmoji} You will be messaged if any of the following **booses** or **creatures** are boosted:\n\n$groupedAndSorted" else s"${Config.letterEmoji} Your notification list is *empty*."
              val commandMessage = s"${Config.noEmoji} **$sanitizedName** is not a valid `boss` or `creature`."
              val combinedMessage = listMessage + s"\n\n$commandMessage"
              if (combinedMessage.size >= 4096) {
                val substituteText = "\n\n*`...cannot display any more results`*"
                val lastLineIndex = listMessage.lastIndexOf('\n', (4090 - (substituteText.size + commandMessage.size)))
                val truncatedMessage = listMessage.substring(0, lastLineIndex)
                embedMessage = truncatedMessage + substituteText + s"\n\n$commandMessage"
              } else {
                embedMessage = combinedMessage
              }
            } else {
              val query = "INSERT INTO boosted_notifications (userid, name, type) VALUES (?, ?, ?) ON CONFLICT (userid, name) DO NOTHING"
              val preparedStatement = conn.prepareStatement(query)
              preparedStatement.setString(1, userId)
              preparedStatement.setString(2, sanitizedName)
              preparedStatement.setString(3, monsterType)
              preparedStatement.executeUpdate()
              preparedStatement.close()

              val newNames = existingNames :+ BoostedStamp(userId, monsterType, sanitizedName)
              val groupedAndSorted = newNames
                .groupBy(_.boostedType)
                .mapValues(_.sortBy(_.boostedName.toLowerCase)) // Sort within each group by name
                .toSeq
                .sortBy(_._1) // Sort groups by type
                .flatMap { case (group, names) =>
                  names.map { boosted =>
                    val emoji =
                      if (group == "boss") Config.bossEmoji
                      else if (group == "creature") Config.creatureEmoji
                      else Config.indentEmoji

                    val nameWithLink =
                      if (group == "boss" || group == "creature") s"**[${capitalizeAllWords(boosted.boostedName)}](${creatureWikiUrl(capitalizeAllWords(boosted.boostedName))})**"
                      else s"**${capitalizeAllWords(boosted.boostedName)}**"

                    s"$emoji $nameWithLink"
                  }
                }.mkString("\n")
              val listMessage = if (groupedAndSorted.trim != "") s"${Config.letterEmoji} You will be messaged if any of the following **booses** or **creatures** are boosted:\n\n$groupedAndSorted" else s"${Config.letterEmoji} You will be notified for **all** boosted **bosses** and **creatures** at *server save*."
              val commandMessage = s"${Config.yesEmoji} **$sanitizedName** was added."
              //WIP
              val combinedMessage = listMessage + s"\n\n$commandMessage"
              if (combinedMessage.size >= 4096) {
                val substituteText = "\n\n*`...cannot display any more results`*"
                val lastLineIndex = listMessage.lastIndexOf('\n', (4090 - (substituteText.size + commandMessage.size)))
                val truncatedMessage = listMessage.substring(0, lastLineIndex)
                embedMessage = truncatedMessage + substituteText + s"\n\n$commandMessage"
              } else {
                embedMessage = combinedMessage
              }
            }
          }
        }
      } else {
        // Check if sanitizedName exists in boostedBossesList
        val isBoostedBoss = boostedBossesList.contains(sanitizedName)

        // Check if sanitizedName is a valid creature
        val creatureCheck: Boolean = if (Config.creaturesList.contains(sanitizedName.toLowerCase)) true else false
        val monsterType = if (isBoostedBoss) "boss" else if (creatureCheck) "creature" else "all"
        val listSetting = existingNames.exists(bs => bs.user == userId && bs.boostedName.toLowerCase == "all")
        val newNames = existingNames :+ BoostedStamp(userId, monsterType, boostedName)
        val groupedAndSorted = newNames
          .groupBy(_.boostedType)
          .mapValues(_.sortBy(_.boostedName.toLowerCase)) // Sort within each group by name
          .toSeq
          .sortBy(_._1) // Sort groups by type
          .flatMap { case (group, names) =>
            names.map { boosted =>
              val emoji =
                if (group == "boss") Config.bossEmoji
                else if (group == "creature") Config.creatureEmoji
                else Config.indentEmoji

              val nameWithLink =
                if (group == "boss" || group == "creature") s"**[${capitalizeAllWords(boosted.boostedName)}](${creatureWikiUrl(capitalizeAllWords(boosted.boostedName))})**"
                else s"**${capitalizeAllWords(boosted.boostedName)}**"

              s"$emoji $nameWithLink"
            }
          }.mkString("\n")
        val listMessage = if (listSetting) s"${Config.letterEmoji} You will be notified for **all** boosted **bosses** and **creatures** at *server save*." else s"${Config.letterEmoji} You will be messaged if any of the following **booses** or **creatures** are boosted:\n\n$groupedAndSorted"
        val commandMessage = s"${Config.noEmoji} **$sanitizedName** is not a valid `boss` or `creature`."
        val combinedMessage = listMessage + s"\n\n$commandMessage"
        if (combinedMessage.size >= 4096) {
          val substituteText = "\n\n*`...cannot display any more results`*"
          val lastLineIndex = listMessage.lastIndexOf('\n', (4090 - (substituteText.size + commandMessage.size)))
          val truncatedMessage = listMessage.substring(0, lastLineIndex)
          embedMessage = truncatedMessage + substituteText + s"\n\n$commandMessage"
        } else {
          embedMessage = combinedMessage
        }
      }
    } else if (boostedOption == "remove"){
      val filteredGroupedAndSorted = existingNames
        .groupBy(_.boostedType)
        .mapValues(_.sortBy(_.boostedName.toLowerCase)) // Sort within each group by name
        .toSeq
        .sortBy(_._1) // Sort groups by type
        .flatMap { case (group, names) =>
          val filteredNames = names.filterNot(bs => bs.boostedName.toLowerCase == sanitizedName)

          filteredNames.map { boosted =>
            val emoji =
              if (group == "boss") Config.bossEmoji
              else if (group == "creature") Config.creatureEmoji
              else Config.indentEmoji

            val nameWithLink =
              if (group == "boss" || group == "creature") s"**[${capitalizeAllWords(boosted.boostedName)}](${creatureWikiUrl(capitalizeAllWords(boosted.boostedName))})**"
              else s"**${capitalizeAllWords(boosted.boostedName)}**"

            s"$emoji $nameWithLink"
          }
        }.mkString("\n")
      if (sanitizedName == "all") {
        var query = "DELETE FROM boosted_notifications WHERE userid = ?"
        val preparedStatement = conn.prepareStatement(query)
        preparedStatement.setString(1, userId)
        preparedStatement.executeUpdate()
        preparedStatement.close()

        embedMessage = s"${Config.yesEmoji} you have disabled notifications for **all** bosses and creatures."
      } else if (existingNames.exists(_.boostedName.replaceAll("[^a-zA-Z'\\-\\s]", "").trim.toLowerCase == sanitizedName)) {
        var query = "DELETE FROM boosted_notifications WHERE userid = ? AND LOWER(name) = LOWER(?)"
        val preparedStatement = conn.prepareStatement(query)
        preparedStatement.setString(1, userId)
        preparedStatement.setString(2, sanitizedName)
        preparedStatement.executeUpdate()
        preparedStatement.close()

        val listMessage = if (filteredGroupedAndSorted.trim != "") s"${Config.letterEmoji} You will be messaged if any of the following **booses** or **creatures** are boosted:\n\n$filteredGroupedAndSorted" else s"${Config.letterEmoji} Your notification list is *empty*."
        val commandMessage = s"${Config.yesEmoji} you removed **$sanitizedName** from the list."
        val combinedMessage = listMessage + s"\n\n$commandMessage"
        if (combinedMessage.size >= 4096) {
          val substituteText = "\n\n*`...cannot display any more results`*"
          val lastLineIndex = listMessage.lastIndexOf('\n', (4090 - (substituteText.size + commandMessage.size)))
          val truncatedMessage = listMessage.substring(0, lastLineIndex)
          embedMessage = truncatedMessage + substituteText + s"\n\n$commandMessage"
        } else {
          embedMessage = combinedMessage
        }

      } else {

        val listMessage = if (filteredGroupedAndSorted.trim != "") s"${Config.letterEmoji} You will be messaged if any of the following **booses** or **creatures** are boosted:\n\n$filteredGroupedAndSorted" else s"${Config.letterEmoji} Your notification list is *empty*."
        val commandMessage = s"${Config.noEmoji} **$sanitizedName** is not on your list."
        val combinedMessage = listMessage + s"\n\n$commandMessage"
        if (combinedMessage.size >= 4096) {
          val substituteText = "\n\n*`...cannot display any more results`*"
          val lastLineIndex = listMessage.lastIndexOf('\n', (4090 - (substituteText.size + commandMessage.size)))
          val truncatedMessage = listMessage.substring(0, lastLineIndex)
          embedMessage = truncatedMessage + substituteText + s"\n\n$commandMessage"
        } else {
          embedMessage = combinedMessage
        }
      }
      //
    } else if (boostedOption == "toggle"){
      val existingSetting = existingNames.exists(bs => bs.user == userId && bs.boostedName.toLowerCase == "all")
      if (existingSetting) {
        var query = "DELETE FROM boosted_notifications WHERE userid = ?"
        val preparedStatement = conn.prepareStatement(query)
        preparedStatement.setString(1, userId)
        preparedStatement.executeUpdate()
        preparedStatement.close()
        // WIP Message
        embedMessage = s"${Config.letterEmoji} Your notification list is *empty*."
      } else {
        val query = "INSERT INTO boosted_notifications (userid, name, type) VALUES (?, ?, ?) ON CONFLICT (userid, name) DO NOTHING"
        val preparedStatement = conn.prepareStatement(query)
        preparedStatement.setString(1, userId)
        preparedStatement.setString(2, "all")
        preparedStatement.setString(3, "all")
        preparedStatement.executeUpdate()
        preparedStatement.close()
        embedMessage = s"${Config.letterEmoji} You will be notified for **all** boosted **bosses** and **creatures** at *server save*."
      }
      //
    } else if (boostedOption == "disable") {
      var query = "DELETE FROM boosted_notifications WHERE userid = ?"
      val preparedStatement = conn.prepareStatement(query)
      preparedStatement.setString(1, userId)
      preparedStatement.executeUpdate()
      preparedStatement.close()

      embedMessage = s"${Config.yesEmoji} you have **disabled** notifications for **all** bosses and creatures."
    }

    conn.close()
    replyEmbed.setDescription(embedMessage).build()
  }

  /**
  def discordChannelMessageEmbed(guild: Guild, channel: Option[TextChannel], title: String, description: String, thumbnail: String, colour: Int): Unit = {
    channel.foreach { actualChannel =>
      if (actualChannel.canTalk) {
        try {
          val messageEmbed = new EmbedBuilder()
          messageEmbed.setTitle(title)
          messageEmbed.setDescription(description)
          messageEmbed.setThumbnail(thumbnail)
          messageEmbed.setColor(colour)
          actualChannel.sendMessageEmbeds(messageEmbed.build()).queue()
        } catch {
          case ex: Throwable =>
            logger.info(s"Failed to send message:\nGuild ID: '${guild.getId}' Guild Name: '${guild.getName}' Channel ID: '${actualChannel.getId}' Channel Name: '${actualChannel.getName}':\n${ex.getMessage}")
        }
      }
    }
  }
  **/
}
