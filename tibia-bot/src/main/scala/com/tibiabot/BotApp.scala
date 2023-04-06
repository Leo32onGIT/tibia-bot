package com.tibiabot

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.tibiabot.discord.DiscordMessageSender
import com.tibiabot.discord.DiscordMessageEditor
import com.tibiabot.tibiadata.TibiaDataClient
import com.tibiabot.tibiadata.response.{CharacterResponse, GuildResponse, Members}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.{Guild, MessageEmbed}
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.interactions.commands.build.{Commands, OptionData, SlashCommandData, SubcommandData}
import net.dv8tion.jda.api.interactions.commands.{DefaultMemberPermissions, OptionType}
import net.dv8tion.jda.api.interactions.components.buttons._
import net.dv8tion.jda.api.{EmbedBuilder, JDABuilder, Permission}

import java.awt.Color
import java.sql.{Connection, DriverManager, Timestamp}
import java.time.ZonedDateTime
import scala.collection.immutable.ListMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

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
    deathsMin: Int
  )

  private case class Streams(stream: akka.actor.Cancellable, usedBy: List[Discords])
  case class Discords(id: String, adminChannel: String)
  case class Players(name: String, reason: String, reasonText: String, addedBy: String)
  case class Guilds(name: String, reason: String, reasonText: String, addedBy: String)
  case class DeathsCache(world: String, name: String, time: String)
  case class LevelsCache(world: String, name: String, level: String, vocation: String, lastLogin: String, time: String)

  implicit private val actorSystem: ActorSystem = ActorSystem()
  implicit private val ex: ExecutionContextExecutor = actorSystem.dispatcher
  private val tibiaDataClient = new TibiaDataClient()
  val sender = new DiscordMessageSender()
  val editor = new DiscordMessageEditor()

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
  var huntedPlayersData: Map[String, List[Players]] = Map.empty
  var alliedPlayersData: Map[String, List[Players]] = Map.empty
  var huntedGuildsData: Map[String, List[Guilds]] = Map.empty
  var alliedGuildsData: Map[String, List[Guilds]] = Map.empty

  var worldsData: Map[String, List[Worlds]] = Map.empty
  var discordsData: Map[String, List[Discords]] = Map.empty
  var worlds: List[String] = Config.worldList

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
  private val neutralsCommand: SlashCommandData = Commands.slash("neutral", "Show or hide neutral level or deaths entries")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
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

  lazy val commands = List(setupCommand, removeCommand, huntedCommand, alliesCommand, neutralsCommand, fullblessCommand, filterCommand)

  // create the deaths/levels cache db
  createCacheDatabase()

  // initialize the database
  guilds.foreach{g =>
    // update the commands
    if (g.getIdLong == 867319250708463628L){ // Violent Bot Discord
      lazy val adminCommands = List(setupCommand, removeCommand, huntedCommand, alliesCommand, neutralsCommand, fullblessCommand, filterCommand, adminCommand)
      g.updateCommands().addCommands(adminCommands.asJava).complete()
    } else {
      g.updateCommands().addCommands(commands.asJava).complete()
    }
  }

  startBot(None, None)

  actorSystem.scheduler.schedule(0.seconds, 60.minutes) {
    updateDashboard()
    guilds.foreach{g =>
      cleanHuntedList(g)
    }
    removeDeathsCache(ZonedDateTime.now())
    removeLevelsCache(ZonedDateTime.now())
  }

  private def startBot(guild: Option[Guild], world: Option[String]): Unit = {

    if (guild.isDefined && world.isDefined){

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

      val adminChannels = discordRetrieveConfig(guild.get)
      val adminChannelId = if (adminChannels.nonEmpty) adminChannels("admin_channel") else "0"

      worldsInfo.foreach{ w =>
        if (w.name == world.get){
          val discords = Discords(
            id = guildId,
            adminChannel = adminChannelId
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

        if (checkConfigDatabase(g)){
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

          val adminChannels = discordRetrieveConfig(g)
          val adminChannelId = if (adminChannels.nonEmpty) adminChannels("admin_channel") else "0"

          // populate a new Discords list so i can only run 1 stream per world
          worldsInfo.foreach{ w =>
            val discords = Discords(
              id = guildId,
              adminChannel = adminChannelId
            )
            discordsData = discordsData.updated(w.name, discords :: discordsData.getOrElse(w.name, Nil))
          }
        }
      }
      discordsData.foreach { case (worldName, discordsList) =>
        val botStream = new TibiaBot(worldName)
        botStreams += (worldName -> Streams(botStream.stream.run(), discordsList))
        Thread.sleep(3000) // space each stream out 3 seconds
      }
    }

    /***
    // check if world parameter has been passed, and convert to a list
    val guildWorlds = world match {
      case Some(worldName) => worldsData.getOrElse(guild.getId, List()).filter(w => w.name == worldName)
      case None => worldsData.getOrElse(guild.getId, List())
    }
    ***/
  }

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
            if (charResponse.characters.character.name != ""){
              val charName = charResponse.characters.character.name
              val charLevel = charResponse.characters.character.level.toInt
              val charGuild = charResponse.characters.character.guild
              val charGuildName = if(charGuild.isDefined) charGuild.head.name else ""
              val charWorld = charResponse.characters.character.world
              val charEmoji = vocEmoji(charResponse)

              val huntedGuildCheck = huntedGuildsData.getOrElse(guild.getId, List()).exists(_.name.toLowerCase() == charGuildName.toLowerCase())
              if (huntedGuildCheck && reason == "false" && reasonText == "killed an allied player") { // only remove players that were added by the bot, use the reason to check this
                listBuffer += name.toLowerCase
                removeHuntedFromDatabase(guild, "player", name.toLowerCase())

                if (adminChannel != null){
                  val commandUser = s"<@$botUser>"
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(":robot: hunted list cleanup:")
                  adminEmbed.setDescription(s"$commandUser removed the player\n$charEmoji $charLevel — **[$charName](${charUrl(charName)})**\nfrom the hunted list for **$charWorld**\n*(because they have joined an enemy guild and will be tracked that way)*.")
                  adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Broom.gif")
                  adminEmbed.setColor(14397256) // orange for bot auto command
                  adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                }
              }

              val alliedGuildCheck = alliedGuildsData.getOrElse(guild.getId, List()).exists(_.name.toLowerCase() == charGuildName.toLowerCase())
              if (alliedGuildCheck && reason == "false" && reasonText == "killed an allied player") { // only remove players that were added by the bot, use the reason to check this
                listBuffer += name.toLowerCase
                removeHuntedFromDatabase(guild, "player", name.toLowerCase())

                if (adminChannel != null){
                  val commandUser = s"<@$botUser>"
                  val adminEmbed = new EmbedBuilder()
                  adminEmbed.setTitle(":robot: hunted list cleanup:")
                  adminEmbed.setDescription(s"$commandUser removed the player\n$charEmoji $charLevel — **[$charName](${charUrl(charName)})**\nfrom the hunted list for **$charWorld**\n*(because they have joined an allied guild)*.")
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

  private def updateDashboard(): Unit = {
    // Violent Bot Support discord
    val dashboardGuild = jda.getGuildById(867319250708463628L)
    val dashboardDiscordsTotal = dashboardGuild.getVoiceChannelById(1076431727838380032L)
    val dashboardDiscordsActive = dashboardGuild.getVoiceChannelById(1082844559937114112L)
    val dashboardWorldSubscriptions = dashboardGuild.getVoiceChannelById(1076432500294955098L)
    val dashboardWorldStreams = dashboardGuild.getVoiceChannelById(1082844790439288872L)

    logger.info(s"Updating Violent Bot dashboard...")

    val guildCount = jda.getGuilds.asScala.toList.size
    val activeDiscordsCount: Int = worldsData.size
    val worldStreamCount: Int = discordsData.size
    val worldsTrackedCount: Int = worldsData.values.map(_.size).sum

    // total Discord count
    val dashboardDiscordsTotalName = dashboardDiscordsTotal.getName
    if (dashboardDiscordsTotalName != s"Discords (Total): $guildCount"){
      val dashboardDiscordsTotalManager = dashboardDiscordsTotal.getManager
      dashboardDiscordsTotalManager.setName(s"Discords (Total): $guildCount").queue()
    }

    // active Discord count
    val dashboardDiscordsActiveName = dashboardDiscordsActive.getName
    if (dashboardDiscordsActiveName != s"Discords (Active): $activeDiscordsCount"){
      val dashboardDiscordsActiveManager = dashboardDiscordsActive.getManager
      dashboardDiscordsActiveManager.setName(s"Discords (Active): $activeDiscordsCount").queue()
    }

    // total worlds setup by users
    val dashboardWorldSubscriptionsName = dashboardWorldSubscriptions.getName
    if (dashboardWorldSubscriptionsName != s"Worlds Setup: $worldsTrackedCount"){
      val dashboardWorldSubscriptionsManager = dashboardWorldSubscriptions.getManager
      dashboardWorldSubscriptionsManager.setName(s"Worlds Setup: $worldsTrackedCount").queue()
    }

    // world streams running out of 'how many tibia worlds exist'
    val dashboardWorldStreamsName = dashboardWorldStreams.getName
    if (dashboardWorldStreamsName != s"World Streams: $worldStreamCount of ${worlds.size}"){
      val dashboardWorldStreamsManager = dashboardWorldStreams.getManager
      dashboardWorldStreamsManager.setName(s"World Streams: $worldStreamCount of ${worlds.size}").queue()
    }
  }

  def infoHunted(event: SlashCommandInteractionEvent, subCommand: String, subOptionValue: String): MessageEmbed = {
    // get command option
    val subOptionValueLower = subOptionValue.toLowerCase()
    val guild = event.getGuild
    // default embed content
    var embedText = ":x: An error occurred while running the `info` command"
    if (checkConfigDatabase(guild)){
      val guildId = guild.getId
      if (subCommand == "guild"){ // command run with 'guild'
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
      } else if (subCommand == "player"){ // command run with 'player'
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
      embedText = s":x: You need to run `/setup` and add a world first."
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
    var embedText = ":x: An error occurred while running the `info` command"
    if (checkConfigDatabase(guild)){
      val guildId = guild.getId
      if (subCommand == "guild"){ // command run with 'guild'
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
      } else if (subCommand == "player"){ // command run with 'player'
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
      embedText = s":x: You need to run `/setup` and add a world first."
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
      val futureResults: Future[Seq[(GuildResponse, String, String)]] = guildListFlow.run()
      futureResults.onComplete {
        case Success(output) =>
          val guildApiBuffer = ListBuffer[String]()
          output.foreach { case (guildResponse, name, reason) =>
            val guildName = guildResponse.guilds.guild.name
            val reasonEmoji = if (reason == "true") ":pencil:" else ""
            if (guildName != ""){
              val guildMembers = guildResponse.guilds.guild.members_total.toInt
              val guildLine = s":busts_in_silhouette: $guildMembers — **[$guildName](${guildUrl(guildName)})** $reasonEmoji"
              guildApiBuffer += guildLine
            }
            else {
              guildApiBuffer += s"**$name** *(This guild doesn't exist)* $reasonEmoji"
            }
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
      //IN PROGRESS
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

  def listAlliesAndHuntedPlayers(event: SlashCommandInteractionEvent, arg: String, callback: List[MessageEmbed] => Unit): Unit = {
    // get command option
    val guild = event.getGuild
    val embedColor = 3092790

    //val playerHeader = if (arg == "allies") s"${Config.allyGuild} **Players** ${Config.allyGuild}" else if (arg == "hunted") s"${Config.enemy} **Players** ${Config.enemy}" else ""
    val playerHeader = s"__**Players:**__"
    val listPlayers: List[Players] = if (arg == "allies") alliedPlayersData.getOrElse(guild.getId, List.empty[Players]).map(g => g)
      else if (arg == "hunted") huntedPlayersData.getOrElse(guild.getId, List.empty[Players]).map(g => g)
      else List.empty
    val embedThumbnail = if (arg == "allies") "https://tibia.fandom.com/wiki/Special:Redirect/file/Golden_Newspaper.gif" else if (arg == "hunted") "https://tibia.fandom.com/wiki/Special:Redirect/file/Armageddon_Plans.gif" else ""
    val playerBuffer = ListBuffer[MessageEmbed]()
    if (listPlayers.nonEmpty) {
      // run api against players
      val listPlayersFlow = Source(listPlayers.map(p => (p.name, p.reason, p.reasonText)).toSet).mapAsyncUnordered(4)(tibiaDataClient.getCharacterWithInput).toMat(Sink.seq)(Keep.right)
      val futureResults: Future[Seq[(CharacterResponse, String, String, String)]] = listPlayersFlow.run()
      futureResults.onComplete {
        case Success(output) =>
          val vocationBuffers = ListMap(
            "druid" -> ListBuffer[(Int, String, String)](),
            "knight" -> ListBuffer[(Int, String, String)](),
            "paladin" -> ListBuffer[(Int, String, String)](),
            "sorcerer" -> ListBuffer[(Int, String, String)](),
            "none" -> ListBuffer[(Int, String, String)]()
          )
          output.foreach { case (charResponse, name, reason, _) =>
            if (charResponse.characters.character.name != ""){
              val reasonEmoji = if (reason == "true") ":pencil:" else ""
              val charName = charResponse.characters.character.name
              val charLevel = charResponse.characters.character.level.toInt
              val charGuild = charResponse.characters.character.guild
              val charGuildName = if(charGuild.isDefined) charGuild.head.name else ""
              val guildIcon = if (charGuildName != "" && arg == "allies") Config.allyGuild else if (charGuildName != "" && arg == "hunted") Config.enemyGuild else if (charGuildName == "" && arg == "hunted") Config.enemy else ""
              val charVocation = charResponse.characters.character.vocation
              val charWorld = charResponse.characters.character.world
              val charLink = charUrl(charName)
              val charEmoji = vocEmoji(charResponse)
              val voc = charVocation.toLowerCase.split(' ').last
              vocationBuffers(voc) += ((charLevel, charWorld, s"$charEmoji ${charLevel.toString} — **[$charName]($charLink)** $guildIcon $reasonEmoji"))
            } else {
              vocationBuffers("none") += ((0, "Character does not exist", s":x: N/A — **$name**"))
            }
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
    val voc = char.characters.character.vocation.toLowerCase.split(' ').last
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

  def charUrl(char: String): String =
    s"https://www.tibia.com/community/?name=${char.replaceAll(" ", "+")}"

  def guildUrl(guild: String): String =
    s"https://www.tibia.com/community/?subtopic=guilds&page=view&GuildName=${guild.replaceAll(" ", "+")}"

  def addHunted(event: SlashCommandInteractionEvent, subCommand: String, subOptionValue: String, subOptionReason: String, callback: MessageEmbed => Unit): Unit = {
    // get command option
    val subOptionValueLower = subOptionValue.toLowerCase()
    val reason = if (subOptionReason == "none") "false" else "true"
    val commandUser = event.getUser.getId
    val guild = event.getGuild
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    // default embed content
    var embedText = ":x: An error occurred while running the /hunted command"
    if (checkConfigDatabase(guild)){
      val guildId = guild.getId
      // get admin channel info from database
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (subCommand == "guild"){ // command run with 'guild'
        // run api against guild
        val guildCheck: Future[GuildResponse] = tibiaDataClient.getGuild(subOptionValueLower)
        guildCheck.map { guildResponse =>
          val guildName = guildResponse.guilds.guild.name
          val guildMembers = guildResponse.guilds.guild.members.getOrElse(List.empty[Members])
          (guildName, guildMembers)
        }.map { case (guildName, _) =>
          if (guildName != ""){
            if (!huntedGuildsData.getOrElse(guildId, List()).exists(g => g.name == subOptionValueLower)) {
              // add guild to hunted list and database
              huntedGuildsData = huntedGuildsData + (guildId -> (Guilds(subOptionValueLower, reason, subOptionReason, commandUser) :: huntedGuildsData.getOrElse(guildId, List())))
              addHuntedToDatabase(guild, "guild", subOptionValueLower, reason, subOptionReason, commandUser)
              embedText = s":gear: The guild **[$guildName](${guildUrl(guildName)})** has been added to the hunted list."

              // send embed to admin channel
              if (adminChannel != null){
                val adminEmbed = new EmbedBuilder()
                adminEmbed.setTitle(s":gear: a command was run:")
                adminEmbed.setDescription(s"<@$commandUser> added the guild **[$guildName](${guildUrl(guildName)})** to the hunted list.")
                adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Stone_Coffin.gif")
                adminEmbed.setColor(3092790)
                adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
              }

              // add each player in the guild to the hunted list
              /***
              guildMembers.foreach { member =>
                val guildPlayers = huntedPlayersData.getOrElse(guildId, List())
                if (!guildPlayers.exists(_.name == member.name)) {
                  huntedPlayersData = huntedPlayersData + (guildId -> (Players(member.name, "false", "this players guild was added to the hunted list", commandUser) :: guildPlayers))
                  addHuntedToDatabase(guild, "player", member.name, "false", "this players guild was added to the hunted list", commandUser)
                }
              }
              ***/
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            } else {
              embedText = s":x: The guild **[$guildName](${guildUrl(guildName)})** already exists in the hunted list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            }
          } else {
            embedText = s":x: The guild **$subOptionValueLower** does not exist."
            embedBuild.setDescription(embedText)
            callback(embedBuild.build())

          }
        }
      } else if (subCommand == "player"){ // command run with 'player'
        // run api against player
        val playerCheck: Future[CharacterResponse] = tibiaDataClient.getCharacter(subOptionValueLower)
        playerCheck.map { charResponse =>
          val character = charResponse.characters.character
          (character.name, character.world, vocEmoji(charResponse), character.level.toInt)
        }.map { case (playerName, world, vocation, level) =>
          if (playerName != ""){
            if (!huntedPlayersData.getOrElse(guildId, List()).exists(g => g.name == subOptionValueLower)) {
              // add player to hunted list and database
              huntedPlayersData = huntedPlayersData + (guildId -> (Players(subOptionValueLower, reason, subOptionReason, commandUser) :: huntedPlayersData.getOrElse(guildId, List())))
              addHuntedToDatabase(guild, "player", subOptionValueLower, reason, subOptionReason, commandUser)
              embedText = s":gear: The player **[$playerName](${charUrl(playerName)})** has been added to the hunted list."

              // send embed to admin channel
              if (adminChannel != null){
                val adminEmbed = new EmbedBuilder()
                adminEmbed.setTitle(s":gear: a command was run:")
                adminEmbed.setDescription(s"<@$commandUser> added the player\n$vocation $level — **[$playerName](${charUrl(playerName)})**\nto the hunted list for **$world**.")
                adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Stone_Coffin.gif")
                adminEmbed.setColor(3092790)
                adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
              }

              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            } else {
              embedText = s":x: The player **[$playerName](${charUrl(playerName)})** already exists in the hunted list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            }
          } else {
            embedText = s":x: The player **$subOptionValueLower** does not exist."
            embedBuild.setDescription(embedText)
            callback(embedBuild.build())

          }
        }
      }
    } else {
      embedText = s":x: You need to run `/setup` and add a world first."
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
    var embedText = ":x: An error occurred while running the /allies command"
    if (checkConfigDatabase(guild)){
      val guildId = guild.getId
      // get admin channel info from database
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (subCommand == "guild"){
        // run api against guild
        val guildCheck: Future[GuildResponse] = tibiaDataClient.getGuild(subOptionValueLower)
        guildCheck.map { guildResponse =>
          val guildName = guildResponse.guilds.guild.name
          val guildMembers = guildResponse.guilds.guild.members.getOrElse(List.empty[Members])
          (guildName, guildMembers)
        }.map { case (guildName, _) =>
          if (guildName != ""){
            if (!alliedGuildsData.getOrElse(guildId, List()).exists(g => g.name == subOptionValueLower)) {
              alliedGuildsData = alliedGuildsData + (guildId -> (Guilds(subOptionValueLower, reason, subOptionReason, commandUser) :: alliedGuildsData.getOrElse(guildId, List())))
              addAllyToDatabase(guild, "guild", subOptionValueLower, reason, subOptionReason, commandUser)
              embedText = s":gear: The guild **[$guildName](${guildUrl(guildName)})** has been added to the allies list."

              if (adminChannel != null){
                val adminEmbed = new EmbedBuilder()
                adminEmbed.setTitle(s":gear: a command was run:")
                adminEmbed.setDescription(s"<@$commandUser> added the guild **[$guildName](${guildUrl(guildName)})** to the allies list.")
                adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Angel_Statue.gif")
                adminEmbed.setColor(3092790)
                adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
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
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            } else {
              embedText = s":x: The guild **[$guildName](${guildUrl(guildName)})** already exists in the allies list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            }
          } else {
            embedText = s":x: The guild **$subOptionValueLower** does not exist."
            embedBuild.setDescription(embedText)
            callback(embedBuild.build())

          }
        }
      } else if (subCommand == "player"){
        // run api against player
        val playerCheck: Future[CharacterResponse] = tibiaDataClient.getCharacter(subOptionValueLower)
        playerCheck.map { charResponse =>
          val character = charResponse.characters.character
          (character.name, character.world, vocEmoji(charResponse), character.level.toInt)
        }.map { case (playerName, world, vocation, level) =>
          if (playerName != ""){
            if (!alliedPlayersData.getOrElse(guildId, List()).exists(g => g.name == subOptionValueLower)) {
              alliedPlayersData = alliedPlayersData + (guildId -> (Players(subOptionValueLower, reason, subOptionReason, commandUser) :: alliedPlayersData.getOrElse(guildId, List())))
              addAllyToDatabase(guild, "player", subOptionValueLower, reason, subOptionReason, commandUser)
              embedText = s":gear: The player **[$playerName](${charUrl(playerName)})** has been added to the allies list."

              if (adminChannel != null){
                val adminEmbed = new EmbedBuilder()
                adminEmbed.setTitle(s":gear: a command was run:")
                adminEmbed.setDescription(s"<@$commandUser> added the player\n$vocation $level — **[$playerName](${charUrl(playerName)})**\nto the allies list for **$world**.")
                adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Angel_Statue.gif")
                adminEmbed.setColor(3092790)
                adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
              }

              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            } else {
              embedText = s":x: The player **[$playerName](${charUrl(playerName)})** already exists in the allies list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())

            }
          } else {
            embedText = s":x: The player **$subOptionValueLower** does not exist."
            embedBuild.setDescription(embedText)
            callback(embedBuild.build())

          }
        }
      }
    } else {
      embedText = s":x: You need to run `/setup` and add a world first."
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
    var embedText = ":x: An error occurred while running the /removehunted command"
    if (checkConfigDatabase(guild)){
      val guildId = guild.getId
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      // depending on if guild or player supplied
      if (subCommand == "guild"){
        var guildString = subOptionValueLower
        // run api against guild
        val guildCheck: Future[GuildResponse] = tibiaDataClient.getGuild(subOptionValueLower)
        guildCheck.map { guildResponse =>
          val guildName = guildResponse.guilds.guild.name
          guildName
        }.map { guildName =>
          if (guildName != ""){
            guildString = s"[$guildName](${guildUrl(guildName)})"
          }
          val huntedGuildsList = huntedGuildsData.getOrElse(guildId, List())
          val updatedList = huntedGuildsList.find(_.name == subOptionValueLower) match {
            case Some(_) => huntedGuildsList.filterNot(_.name == subOptionValueLower)
            case None =>
              embedText = s":x: The guild **$guildString** is not on the hunted list."
              embedBuild.setDescription(embedText)

              return callback(embedBuild.build())
          }
          huntedGuildsData = huntedGuildsData.updated(guildId, updatedList)
          removeHuntedFromDatabase(guild, "guild", subOptionValueLower)

          // send embed to admin channel
          if (adminChannel != null){
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> removed guild **$guildString** from the hunted list.")
            adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Stone_Coffin.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          }

          embedText = s":gear: The guild **$guildString** was removed from the hunted list."
          embedBuild.setDescription(embedText)
          callback(embedBuild.build())

        }
      } else if (subCommand == "player"){
        var playerString = subOptionValueLower
        // run api against player
        val playerCheck: Future[CharacterResponse] = tibiaDataClient.getCharacter(subOptionValueLower)
        playerCheck.map { charResponse =>
          val character = charResponse.characters.character
          (character.name, character.world, vocEmoji(charResponse), character.level.toInt)
        }.map { case (playerName, world, vocation, level) =>
          if (playerName != ""){
            playerString = s"[$playerName](${charUrl(playerName)})"
          }
          val huntedPlayersList = huntedPlayersData.getOrElse(guildId, List())
          val updatedList = huntedPlayersList.find(_.name == subOptionValueLower) match {
            case Some(_) => huntedPlayersList.filterNot(_.name == subOptionValueLower)
            case None =>
              embedText = s":x: The player **$playerString** is not on the hunted list."
              embedBuild.setDescription(embedText)

              return callback(embedBuild.build())
          }
          huntedPlayersData = huntedPlayersData.updated(guildId, updatedList)
          removeHuntedFromDatabase(guild, "player", subOptionValueLower)

          // send embed to admin channel
          if (adminChannel != null){
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> removed the player\n$vocation $level — **$playerString**\nfrom the hunted list for **$world**.")
            adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Stone_Coffin.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          }

          embedText = s":gear: The player **$playerString** was removed from the hunted list."
          embedBuild.setDescription(embedText)
          callback(embedBuild.build())

        }
      }
    } else {
      embedText = s":x: You need to run `/setup` and add a world first."
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
    var embedText = ":x: An error occurred while running the /removehunted command"
    if (checkConfigDatabase(guild)){
      val guildId = guild.getId
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      // depending on if guild or player supplied
      if (subCommand == "guild"){
        var guildString = subOptionValueLower
        // run api against guild
        val guildCheck: Future[GuildResponse] = tibiaDataClient.getGuild(subOptionValueLower)
        guildCheck.map { guildResponse =>
          val guildName = guildResponse.guilds.guild.name
          guildName
        }.map { guildName =>
          if (guildName != ""){
            guildString = s"[$guildName](${guildUrl(guildName)})"
          }
          val alliedGuildsList = alliedGuildsData.getOrElse(guildId, List())
          val updatedList = alliedGuildsList.find(_.name == subOptionValueLower) match {
            case Some(_) => alliedGuildsList.filterNot(_.name == subOptionValueLower)
            case None =>
              embedText = s":x: The guild **$guildString** is not on the allies list."
              embedBuild.setDescription(embedText)

              return callback(embedBuild.build())
          }
          alliedGuildsData = alliedGuildsData.updated(guildId, updatedList)
          removeAllyFromDatabase(guild, "guild", subOptionValueLower)

          // send embed to admin channel
          if (adminChannel != null){
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> removed **$guildString** from the allies list.")
            adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Angel_Statue.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          }

          embedText = s":gear: The guild **$guildString** was removed from the allies list."
          embedBuild.setDescription(embedText)
          callback(embedBuild.build())

        }
      } else if (subCommand == "player"){
        var playerString = subOptionValueLower
        // run api against player
        val playerCheck: Future[CharacterResponse] = tibiaDataClient.getCharacter(subOptionValueLower)
        playerCheck.map { charResponse =>
          val character = charResponse.characters.character
          (character.name, character.world, vocEmoji(charResponse), character.level.toInt)
        }.map { case (playerName, world, vocation, level) =>
          if (playerName != ""){
            playerString = s"[$playerName](${charUrl(playerName)})"
          }
          val alliedPlayersList = alliedPlayersData.getOrElse(guildId, List())
          val updatedList = alliedPlayersList.find(_.name == subOptionValueLower) match {
            case Some(_) => alliedPlayersList.filterNot(_.name == subOptionValueLower)
            case None =>
              embedText = s":x: The player **$playerString** is not on the allies list."
              embedBuild.setDescription(embedText)

              return callback(embedBuild.build())
          }
          alliedPlayersData = alliedPlayersData.updated(guildId, updatedList)
          removeAllyFromDatabase(guild, "player", subOptionValueLower)

          // send embed to admin channel
          if (adminChannel != null){
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> removed the player\n$vocation $level — **$playerString**\nfrom the allies list for **$world**.")
            adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Angel_Statue.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          }

          embedText = s":gear: The player **$playerString** was removed from the allies list."
          embedBuild.setDescription(embedText)
          callback(embedBuild.build())

        }
      }
    } else {
      embedText = s":x: You need to run `/setup` and add a world first."
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

  private def removeHuntedFromDatabase(guild: Guild, option: String, name: String): Unit = {
    val conn = getConnection(guild)
    val table = (if (option == "guild") "hunted_guilds" else if (option == "player") "hunted_players").toString
    val statement = conn.prepareStatement(s"DELETE FROM $table WHERE name = ?;")
    statement.setString(1, name)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  private def removeAllyFromDatabase(guild: Guild, option: String, name: String): Unit = {
    val conn = getConnection(guild)
    val table = (if (option == "guild") "allied_guilds" else if (option == "player") "allied_players").toString
    val statement = conn.prepareStatement(s"DELETE FROM $table WHERE name = ?;")
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
      if (time.isAfter(timeToDate.plusMinutes(30)) && id != 0L){
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
      if (time.isAfter(timeToDate.plusHours(25)) && id != 0L){
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

  private def discordRetrieveConfig(guild: Guild): Map[String, String] = {
    val conn = getConnection(guild)
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT * FROM discord_info")

    var configMap = Map[String, String]()
    while (result.next()) {
      configMap += ("guild_name" -> result.getString("guild_name"))
      configMap += ("guild_owner" -> result.getString("guild_owner"))
      configMap += ("admin_category" -> result.getString("admin_category"))
      configMap += ("admin_channel" -> result.getString("admin_channel"))
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
    val result = statement.executeQuery(s"SELECT name,allies_channel,enemies_channel,neutrals_channel,levels_channel,deaths_channel,category,fullbless_role,nemesis_role,fullbless_channel,nemesis_channel,fullbless_level,show_neutral_levels,show_neutral_deaths,show_allies_levels,show_allies_deaths,show_enemies_levels,show_enemies_deaths,detect_hunteds,levels_min,deaths_min FROM worlds")

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
      results += Worlds(name, alliesChannel, enemiesChannel, neutralsChannel, levelsChannel, deathsChannel, category, fullblessRole, nemesisRole, fullblessChannel, nemesisChannel, fullblessLevel, showNeutralLevels, showNeutralDeaths, showAlliesLevels, showAlliesDeaths, showEnemiesLevels, showEnemiesDeaths, detectHunteds, levelsMin, deathsMin)
    }

    statement.close()
    conn.close()
    results.toList
  }

  private def worldCreateConfig(guild: Guild, world: String, alliesChannel: String, enemiesChannel: String, neutralsChannels: String, levelsChannel: String, deathsChannel: String, category: String, fullblessRole: String, nemesisRole: String, fullblessChannel: String, nemesisChannel: String): Unit = {
    val conn = getConnection(guild)
    val statement = conn.prepareStatement("INSERT INTO worlds(name, allies_channel, enemies_channel, neutrals_channel, levels_channel, deaths_channel, category, fullbless_role, nemesis_role, fullbless_channel, nemesis_channel, fullbless_level, show_neutral_levels, show_neutral_deaths, show_allies_levels, show_allies_deaths, show_enemies_levels, show_enemies_deaths, detect_hunteds, levels_min, deaths_min) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (name) DO UPDATE SET allies_channel = ?, enemies_channel = ?, neutrals_channel = ?, levels_channel = ?, deaths_channel = ?, category = ?, fullbless_role = ?, nemesis_role = ?, fullbless_channel = ?, nemesis_channel = ?, fullbless_level = ?, show_neutral_levels = ?, show_neutral_deaths = ?, show_allies_levels = ?, show_allies_deaths = ?, show_enemies_levels = ?, show_enemies_deaths = ?, detect_hunteds = ?, levels_min = ?, deaths_min = ?;")
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
    statement.setString(22, alliesChannel)
    statement.setString(23, enemiesChannel)
    statement.setString(24, neutralsChannels)
    statement.setString(25, levelsChannel)
    statement.setString(26, deathsChannel)
    statement.setString(27, category)
    statement.setString(28, fullblessRole)
    statement.setString(29, nemesisRole)
    statement.setString(30, fullblessChannel)
    statement.setString(31, nemesisChannel)
    statement.setInt(32, 250)
    statement.setString(33, "true")
    statement.setString(34, "true")
    statement.setString(35, "true")
    statement.setString(36, "true")
    statement.setString(37, "true")
    statement.setString(38, "true")
    statement.setString(39, "on")
    statement.setInt(40, 8)
    statement.setInt(41, 8)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  private def discordCreateConfig(guild: Guild, guildName: String, guildOwner: String, adminCategory: String, adminChannel: String, created: ZonedDateTime): Unit = {
    val conn = getConnection(guild)
    val statement = conn.prepareStatement("INSERT INTO discord_info(guild_name, guild_owner, admin_category, admin_channel, flags, created) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(guild_name) DO UPDATE SET guild_owner = EXCLUDED.guild_owner, admin_category = EXCLUDED.admin_category, admin_channel = EXCLUDED.admin_channel, flags = EXCLUDED.flags, created = EXCLUDED.created;")
    statement.setString(1, guildName)
    statement.setString(2, guildOwner)
    statement.setString(3, adminCategory)
    statement.setString(4, adminChannel)
    statement.setString(5, "none")
    statement.setTimestamp(6, Timestamp.from(created.toInstant))
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  private def discordUpdateConfig(guild: Guild, adminCategory: String, adminChannel: String): Unit = {
    val conn = getConnection(guild)
    // update category if exists
    if (adminCategory != ""){
      val statement = conn.prepareStatement("UPDATE discord_info SET admin_category = ?;")
      statement.setString(1, adminCategory)
      statement.executeUpdate()
      statement.close()
    }
    // update channel
    val statement = conn.prepareStatement("UPDATE discord_info SET admin_channel = ?;")
    statement.setString(1, adminChannel)
    statement.executeUpdate()

    statement.close()
    conn.close()
  }

  private def worldRetrieveConfig(guild: Guild, world: String): Map[String, String] = {
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

      // see if admin channels exist
      val discordConfig = discordRetrieveConfig(guild)
      if (discordConfig.isEmpty){
        val adminCategory = guild.createCategory("Violent Bot Administration").complete()
        adminCategory.upsertPermissionOverride(botRole)
          .grant(Permission.VIEW_CHANNEL)
          .grant(Permission.MESSAGE_SEND)
          .complete()
        adminCategory.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
        val adminChannel = guild.createTextChannel("bot-activity", adminCategory).complete()
        // restrict the channel so only roles with Permission.MANAGE_MESSAGES can write to the channels
        adminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
        adminChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
        adminChannel.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
        val guildOwner = if (guild.getOwner == null) "Not Available" else guild.getOwner.getEffectiveName
        discordCreateConfig(guild, guild.getName, guildOwner, adminCategory.getId, adminChannel.getId, ZonedDateTime.now())
      } else {
        val adminCategoryCheck = guild.getCategoryById(discordConfig("admin_category"))
        val adminChannelCheck = guild.getTextChannelById(discordConfig("admin_channel"))
        if (adminChannelCheck == null){
          // admin channel has been deleted
          if (adminCategoryCheck == null){
            // admin category has been deleted
            val adminCategory = guild.createCategory("Violent Bot Administration").complete()
            adminCategory.upsertPermissionOverride(botRole)
              .grant(Permission.VIEW_CHANNEL)
              .grant(Permission.MESSAGE_SEND)
              .complete()
            adminCategory.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
            val adminChannel = guild.createTextChannel("bot-activity", adminCategory).complete()
            adminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
            adminChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
            adminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_EMBED_LINKS).complete()
            adminChannel.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
            discordUpdateConfig(guild, adminCategory.getId, adminChannel.getId)
          } else {
            // admin category still exists
            val adminChannel = guild.createTextChannel("bot-activity", adminCategoryCheck).complete()
            adminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_SEND).complete()
            adminChannel.upsertPermissionOverride(botRole).grant(Permission.VIEW_CHANNEL).complete()
            adminChannel.upsertPermissionOverride(botRole).grant(Permission.MESSAGE_EMBED_LINKS).complete()
            adminChannel.upsertPermissionOverride(guild.getPublicRole).deny(Permission.VIEW_CHANNEL).queue()
            discordUpdateConfig(guild, "", adminChannel.getId)
          }
        }
      }

      // get all categories in the discord
      val categories = guild.getCategories.asScala
      val targetCategory = categories.find(_.getName == world).getOrElse(null)
      // it it doesn't create it
      if (targetCategory == null){
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
        val alliesChannel = guild.createTextChannel("allies", newCategory).complete()
        val enemiesChannel = guild.createTextChannel("enemies", newCategory).complete()
        val neutralsChannel = guild.createTextChannel("neutrals", newCategory).complete()
        val levelsChannel = guild.createTextChannel("levels", newCategory).complete()
        val deathsChannel = guild.createTextChannel("deaths", newCategory).complete()
        val fullblessChannel = guild.createTextChannel("fullbless-notifications", newCategory).complete()
        val nemesisChannel = guild.createTextChannel("boss-notifications", newCategory).complete()

        val publicRole = guild.getPublicRole
        val channelList = List(alliesChannel, enemiesChannel, neutralsChannel, levelsChannel, deathsChannel, fullblessChannel, nemesisChannel)
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

        val fullblessEmbedText = s"The bot will poke <@&${fullblessRole.getId}>\n\nIf an enemy player dies fullbless and is over level 250.\nAdd or remove yourself from the role using the buttons below."
        val fullblessEmbed = new EmbedBuilder()
        fullblessEmbed.setTitle(s":crossed_swords: $world :crossed_swords:", s"https://www.tibia.com/community/?subtopic=worlds&world=$world")
        fullblessEmbed.setThumbnail(Config.aolThumbnail)
        fullblessEmbed.setColor(3092790)
        fullblessEmbed.setDescription(fullblessEmbedText)
        fullblessChannel.sendMessageEmbeds(fullblessEmbed.build())
          .setActionRow(
            Button.success(s"add", "Add Role"),
            Button.danger(s"remove", "Remove Role")
          )
          .queue()

        // Nemesis role
        val nemesisRoleString = s"$world Nemesis Boss"
        val nemesisRoleCheck = guild.getRolesByName(nemesisRoleString, true)
        val nemesisRole = if (!nemesisRoleCheck.isEmpty) nemesisRoleCheck.get(0) else guild.createRole().setName(nemesisRoleString).setColor(new Color(164, 76, 230)).complete()
        val worldCount = worldConfig(guild)
        val count = worldCount.length
        val nemesisList = List("Zarabustor", "Midnight_Panther", "Yeti", "Shlorg", "White_Pale", "Furyosa", "Jesse_the_Wicked", "The_Welter", "Tyrn", "Zushuka")
        val nemesisThumbnail = nemesisList(count % nemesisList.size)

        val nemesisEmbedText = s"The bot will poke <@&${nemesisRole.getId}>\n\nIf anyone dies to a rare boss (so you can go steal it).\nAdd or remove yourself from the role using the buttons below."
        val nemesisEmbed = new EmbedBuilder()
        nemesisEmbed.setTitle(s"${Config.nemesisEmoji} $world ${Config.nemesisEmoji}", s"https://www.tibia.com/community/?subtopic=worlds&world=$world")
        nemesisEmbed.setThumbnail(s"https://tibia.fandom.com/wiki/Special:Redirect/file/$nemesisThumbnail.gif")
        nemesisEmbed.setColor(3092790)
        nemesisEmbed.setDescription(nemesisEmbedText)
        nemesisChannel.sendMessageEmbeds(nemesisEmbed.build())
          .setActionRow(
            Button.success("add", "Add Role"),
            Button.danger("remove", "Remove Role")
          )
          .queue()

        val alliesId = alliesChannel.getId
        val enemiesId = enemiesChannel.getId
        val neutralsId = neutralsChannel.getId
        val levelsId = levelsChannel.getId
        val deathsId = deathsChannel.getId
        val categoryId = newCategory.getId
        val fullblessId = fullblessChannel.getId
        val nemesisId = nemesisChannel.getId

        // update the database
        worldCreateConfig(guild, world, alliesId, enemiesId, neutralsId, levelsId, deathsId, categoryId, fullblessRole.getId, nemesisRole.getId, fullblessId, nemesisId)
        startBot(Some(guild), Some(world))
        s":gear: The channels for **$world** have been configured successfully."
      } else {
        // channels already exist
        logger.info(s"The channels have already been setup on '${guild.getName} - ${guild.getId}'.")
        s":x: The channels for **$world** have already been setup."
      }
    } else {
      ":x: This is not a valid World on Tibia."
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
    if (detectSetting != null){
      if (detectSetting == settingOption){
        // embed reply
        embedBuild.setDescription(s":x: **Automatic enemy detection** is already set to **$settingOption** for the world **$worldFormal**.")
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
        if (adminChannel != null){
          val adminEmbed = new EmbedBuilder()
          adminEmbed.setTitle(s":gear: a command was run:")
          adminEmbed.setDescription(s"<@$commandUser> set **automatic enemy detection** to **$settingOption** for the world **$worldFormal**.")
          adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Armillary_Sphere_(TibiaMaps).gif")
          adminEmbed.setColor(3092790)
          adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
        }

        embedBuild.setDescription(s":gear: **Automatic enemy detection** is now set to **$settingOption** for the world **$worldFormal**.")
        embedBuild.build()
      }
    } else {
      embedBuild.setDescription(s":x: You need to run `/setup` and add **$worldFormal** before you can configure this setting.")
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
        if (channelType == "deaths"){
          cache.headOption.map(_.showAlliesDeaths)
        } else if (channelType == "levels"){
          cache.headOption.map(_.showAlliesLevels)
        } else {
          None
        }
      case "neutrals" =>
        if (channelType == "deaths"){
          cache.headOption.map(_.showNeutralDeaths)
        } else if (channelType == "levels"){
          cache.headOption.map(_.showNeutralLevels)
        } else {
          None
        }
      case "enemies" =>
        if (channelType == "deaths"){
          cache.headOption.map(_.showEnemiesDeaths)
        } else if (channelType == "levels"){
          cache.headOption.map(_.showEnemiesLevels)
        } else {
          None
        }
      case _ => None
    }
    if (selectedSetting.isDefined){
      if (selectedSetting.get == settingType){
        // embed reply
        embedBuild.setDescription(s":x: The **$channelType** channel is already set to **$setting $playerType** for the world **$worldFormal**.")
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
        if (adminChannel != null){
          val adminEmbed = new EmbedBuilder()
          adminEmbed.setTitle(s":gear: a command was run:")
          adminEmbed.setDescription(s"<@$commandUser> set the **$channelType** channel to **$setting $playerType** for the world **$worldFormal**.")
          adminEmbed.setThumbnail(s"https://tibia.fandom.com/wiki/Special:Redirect/file/$thumbnailIcon.gif")
          adminEmbed.setColor(3092790)
          adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
        }

        embedBuild.setDescription(s":gear: The **$channelType** channel is now set to **$setting $playerType** for the world **$worldFormal**.")
        embedBuild.build()
      }
    } else {
      embedBuild.setDescription(s":x: You need to run `/setup` and add **$worldFormal** before you can configure this setting.")
      embedBuild.build()
    }
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
    if (levelSetting != null){
      if (levelSetting == level){
        // embed reply
        embedBuild.setDescription(s":x: The level to poke for **enemy fullblesses**\nis already set to **$level** for the world **$worldFormal**.")
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
        val worldConfig = worldRetrieveConfig(guild, world)
        val discordConfig = discordRetrieveConfig(guild)
        val adminChannel = guild.getTextChannelById(discordConfig("admin_channel"))
        if (worldConfig.nonEmpty){
          val fullblessChannelId = worldConfig("fullbless_channel")
          val channel: TextChannel = guild.getTextChannelById(fullblessChannelId)
          if (channel != null) {
            val messages = channel.getHistory.retrievePast(100).complete().asScala.filter(m => m.getAuthor.getId.equals(botUser))
            if (messages.nonEmpty) {
              val message = messages.head
              val roleId = worldConfig("fullbless_role")
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
        if (adminChannel != null){
          val adminEmbed = new EmbedBuilder()
          adminEmbed.setTitle(s":gear: a command was run:")
          adminEmbed.setDescription(s"<@$commandUser> changed the level to poke for **enemy fullblesses**\nto **$level** for the world **$worldFormal**.")
          adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Amulet_of_Loss.gif")
          adminEmbed.setColor(3092790)
          adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
        }

        embedBuild.setDescription(s":gear: The level to poke for **enemy fullblesses**\nis now set to **$level** for the world **$worldFormal**.")
        embedBuild.build()
      }
    } else {
      embedBuild.setDescription(s":x: You need to run `/setup` and add **$worldFormal** before you can configure this setting.")
      embedBuild.build()
    }
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
    if (chosenSetting != null){
      if (chosenSetting == level){
        // embed reply
        embedBuild.setDescription(s":x: The minimum level for the **$levelsOrDeaths channel**\nis already set to `$level` for the world **$worldFormal**.")
        embedBuild.build()
      } else {
        // set the setting here
        val modifiedWorlds = worldsData(guild.getId).map { w =>
          if (w.name.toLowerCase() == world.toLowerCase()) {
            if (levelsOrDeaths == "levels"){
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
        if (adminChannel != null){
          val adminEmbed = new EmbedBuilder()
          adminEmbed.setTitle(s":gear: a command was run:")
          adminEmbed.setDescription(s"<@$commandUser> changed the minimum level for the **$levelsOrDeaths channel**\nto `$level` for the world **$worldFormal**.")
          adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Royal_Fanfare.gif")
          adminEmbed.setColor(3092790)
          adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
        }
        embedBuild.setDescription(s":gear: The minimum level for the **$levelsOrDeaths channel**\nis now set to `$level` for the world **$worldFormal**.")
        embedBuild.build()
      }
    } else {
      embedBuild.setDescription(s":x: You need to run `/setup` and add **$worldFormal** before you can configure this setting.")
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
    val embedText = if (worlds.contains(world)) {
      val guild = event.getGuild
      val worldConfig = worldRetrieveConfig(guild, world)
      if (worldConfig.nonEmpty){
        // get channel ids
        val alliesChannelId = worldConfig("allies_channel")
        val enemiesChannelId = worldConfig("enemies_channel")
        val neutralsChannelId = worldConfig("neutrals_channel")
        val levelsChannelId = worldConfig("levels_channel")
        val deathsChannelId = worldConfig("deaths_channel")
        val fullblessChannelId = worldConfig("fullbless_channel")
        val nemesisChannelId = worldConfig("nemesis_channel")
        val categoryId = worldConfig("category")
        val channelIds = List(alliesChannelId, enemiesChannelId, neutralsChannelId, levelsChannelId, deathsChannelId, fullblessChannelId, nemesisChannelId)

        // check if command is being run in one of the channels being deleted
        if (channelIds.contains(event.getChannel.getId)) {
          return new EmbedBuilder()
          .setColor(3092790)
          .setDescription(s":x: This command would delete this channel, run it somewhere else.")
          .build()
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
        s":x: The world **$world** is not configured here."
      }
    } else {
      ":x: This is not a valid World on Tibia."
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
      if (adminChannel != null){
        val adminEmbed = new EmbedBuilder()
        adminEmbed.setTitle(s":x: The creator of the bot has run a command:")
        adminEmbed.setDescription(s"<@$botUser> has left your discord because of the following reason:\n> ${reason}")
        adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Abacus.gif")
        adminEmbed.setColor(3092790)
        adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
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
      embedMessage = s":x: The Guild: **${guild.getName()}** doesn't have any worlds setup yet, so a message cannot be sent."
    } else {
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (adminChannel != null){
        val adminEmbed = new EmbedBuilder()
        adminEmbed.setTitle(s":x: The creator of the bot has run a command:")
        adminEmbed.setDescription(s"<@$botUser> has forwarded a message from the bot's creator:\n> ${message}")
        adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Letter.gif")
        adminEmbed.setColor(3092790)
        adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
      } else {
        embedMessage = s":x: The Guild: **${guild.getName()}** has deleted the `bot-activity` channel, so a message cannot be sent."
      }
      embedMessage = s":gear: The bot has left a message for the Guild: **${guild.getName()}**."
    }
    // embed reply
    new EmbedBuilder()
    .setColor(3092790)
    .setDescription(embedMessage)
    .build()
  }
//
}
