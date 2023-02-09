package com.tibiabot

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.TextChannel
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Category
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.components.buttons._
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.interactions.commands.build.{Commands, OptionData, SlashCommandData, SubcommandData, SubcommandGroupData}
import scala.jdk.CollectionConverters._
import java.sql.{Connection, DriverManager, ResultSet}
import scala.util.Success
import scala.util.Failure
import scala.collection.mutable.ListBuffer
import java.time.ZonedDateTime
import java.sql.Timestamp
import scala.concurrent.ExecutionContextExecutor
import com.tibiabot.tibiadata.TibiaDataClient
import com.tibiabot.tibiadata.response.CharacterResponse
import com.tibiabot.tibiadata.response.GuildResponse
import com.tibiabot.tibiadata.response.Members
import akka.stream.scaladsl.{Flow, Sink, Source, Keep}
import scala.concurrent.Future
import scala.collection.immutable.ListMap
import java.awt.Color

object BotApp extends App with StrictLogging {

  case class Players(name: String, reason: String, reasonText: String, addedBy: String)
  case class Guilds(name: String, reason: String, reasonText: String, addedBy: String)
  case class Worlds(name: String, fullblessLevel: Int, showNeutralLevels: String, showNeutralDeaths: String)

  implicit private val actorSystem: ActorSystem = ActorSystem()
  implicit private val ex: ExecutionContextExecutor = actorSystem.dispatcher

  // Let the games begin
  logger.info("Starting up")

  private val jda = JDABuilder.createDefault(Config.token)
    .addEventListeners(new BotListener())
    .build()

  jda.awaitReady()
  logger.info("JDA ready")

  // get the discord servers the bot is in
  private val guilds: List[Guild] = jda.getGuilds().asScala.toList

  // stream list
  var deathTrackerStreams = Map[(Guild, String), akka.actor.Cancellable]()

  // get bot userID (used to stamp automated enemy detection messages)
  val botUser = jda.getSelfUser().getId()

  // initialize core hunted/allied list
  var huntedPlayersData: Map[String, List[Players]] = Map.empty
  var alliedPlayersData: Map[String, List[Players]] = Map.empty
  var huntedGuildsData: Map[String, List[Guilds]] = Map.empty
  var alliedGuildsData: Map[String, List[Guilds]] = Map.empty

  var worldsData: Map[String, List[Worlds]] = Map.empty
  var worlds: List[String] = Config.worldList

  // create the command to set up the bot
  val setupCommand: SlashCommandData = Commands.slash("setup", "Setup a world to be trackedt")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addOptions(new OptionData(OptionType.STRING, "world", "The world you want to track")
    .setRequired(true))

  // remove world command
  val removeCommand: SlashCommandData = Commands.slash("remove", "Remove a world from being tracked")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addOptions(new OptionData(OptionType.STRING, "world", "The world you want to remove")
    .setRequired(true))

  // hunted command
  val huntedCommand: SlashCommandData = Commands.slash("hunted", "Manage the hunted list")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addSubcommandGroups(
      new SubcommandGroupData("guild", "Manage guilds in the hunted list")
        .addSubcommands(
          new SubcommandData("add", "Add a guild to the hunted list")
            .addOptions(new OptionData(OptionType.STRING, "name", "The guild name you want to add to the hunted list").setRequired(true)),
          new SubcommandData("remove", "Remove a guild from the hunted list")
            .addOptions(new OptionData(OptionType.STRING, "name", "The guild you want to remove from the hunted list").setRequired(true))
        ),
      new SubcommandGroupData("player", "Manage players in the hunted list")
        .addSubcommands(
          new SubcommandData("add", "Add a player to the hunted list")
            .addOptions(
              new OptionData(OptionType.STRING, "name", "The player name you want to add to the hunted list").setRequired(true),
              new OptionData(OptionType.STRING, "reason", "Optional 'reason' that can be viewed later on"),
            ),
          new SubcommandData("remove", "Remove a player from the hunted list")
            .addOptions(new OptionData(OptionType.STRING, "name", "The player you want to remove from the hunted list").setRequired(true))
        ),
      )
    .addSubcommands(
      new SubcommandData("list", "List players & guilds in the hunted list"),
      new SubcommandData("info", "Show detailed info on a hunted player")
        .addOptions(new OptionData(OptionType.STRING, "name", "The player name you want to check").setRequired(true))
    );

  // allies command
  val alliesCommand: SlashCommandData = Commands.slash("allies", "Manage the allies list")
    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
    .addSubcommandGroups(
      new SubcommandGroupData("guild", "Manage guilds in the allies list")
        .addSubcommands(
          new SubcommandData("add", "Add a guild to the allies list")
            .addOptions(new OptionData(OptionType.STRING, "name", "The guild name you want to add to the allies list").setRequired(true)),
          new SubcommandData("remove", "Remove a guild from the allies list")
            .addOptions(new OptionData(OptionType.STRING, "name", "The guild you want to remove from the allies list").setRequired(true))
        ),
      new SubcommandGroupData("player", "Manage players in the allies list")
        .addSubcommands(
          new SubcommandData("add", "Add a player to the allies list")
            .addOptions(
              new OptionData(OptionType.STRING, "name", "The player name you want to add to the allies list").setRequired(true),
              new OptionData(OptionType.STRING, "reason", "Optional 'reason' that can be viewed later on"),
            ),
          new SubcommandData("remove", "Remove a player from the allies list")
            .addOptions(new OptionData(OptionType.STRING, "name", "The player you want to remove from the allies list").setRequired(true))
        ),
      )
    .addSubcommands(
      new SubcommandData("list", "List players & guilds in the allies list"),
      new SubcommandData("info", "Show detailed info on a allies player")
        .addOptions(new OptionData(OptionType.STRING, "name", "The player name you want to check").setRequired(true))
    );

  val commands = List(setupCommand, removeCommand, huntedCommand, alliesCommand)

  // initialize the database
  guilds.foreach{g =>
      // update the commands
      g.updateCommands().addCommands(commands.asJava).complete()
      // check if database exists for discord server and start bot if it does
      if (checkConfigDatabase(g)){
        startBot(g, None)
      }
  }

  def startBot(guild: Guild, world: Option[String]) = {

    // build guild specific data map
    val guildId = guild.getId()

    // get hunted Players
    val huntedPlayers = playerConfig(guild, "hunted_players")
    huntedPlayersData += (guildId -> huntedPlayers)

    // get allied Players
    val alliedPlayers = playerConfig(guild, "allied_players")
    alliedPlayersData += (guildId -> alliedPlayers)

    // get hunted guilds
    val huntedGuilds = guildConfig(guild, "hunted_guilds")
    huntedGuildsData += (guildId -> huntedGuilds)

    // get allied guilds
    val alliedGuilds = guildConfig(guild, "allied_guilds")
    alliedGuildsData += (guildId -> alliedGuilds)

    // get worlds
    val worldsInfo = worldConfig(guild, "worlds")
    worldsData += (guildId -> worldsInfo)

    // check if world parameter has been passed, and convert to a list
    val guildWorlds = world match {
      case Some(worldName) => worldsData.getOrElse(guild.getId(), List()).filter(w => w.name == worldName)
      case None => worldsData.getOrElse(guild.getId(), List())
    }
    guildWorlds.foreach { guildWorld =>
      val formalName = guildWorld.name.toLowerCase().capitalize
      val worldChannels = worldRetrieveConfig(guild, formalName)
      val featuresChannelRetrieve = discordRetrieveConfig(guild)

      // get channels for this discord server
      if (worldChannels.nonEmpty && featuresChannelRetrieve.nonEmpty){

        val alliesChannel = worldChannels("allies_channel")
        val enemiesChannel = worldChannels("enemies_channel")
        val neutralsChannel = worldChannels("neutrals_channel")
        val levelsChannel = worldChannels("levels_channel")
        val deathsChannel = worldChannels("deaths_channel")

        val logChannel = featuresChannelRetrieve("admin_channel")

        val fullblessRoleId = worldChannels("fullbless_role")
        val nemesisRoleId = worldChannels("nemesis_role")
        //val fullblessLevel = worldChannels("fullbless_level")
        //val showNeutrals = worldChannels("show_neutrals")
        //val categories = guild.getCategories().asScala
        //val targetCategory = categories.find(_.getName == world).getOrElse(null)

        // run an instance of the tracker
        // ensure channels exist (haven't been deleted) before bothering to run the stream
        val deathTrackerStream = new DeathTrackerStream(guild, alliesChannel, enemiesChannel, neutralsChannel, levelsChannel, deathsChannel, logChannel, formalName, fullblessRoleId, nemesisRoleId)
        val key = (guild, formalName)
        // run stream and put it in the deathTrackerStreams buffer so it can be cancelled at will
        deathTrackerStreams += (key -> deathTrackerStream.stream.run())

      } else {
        logger.info(s"There was a problem getting channel information for '${guild.getName()} - ${guild.getId()}' - ${formalName}.")
      }
    }
  }

  def infoHunted(event: SlashCommandInteractionEvent, subCommand: String, subOptionValue: String): MessageEmbed = {
    // get command option
    val subOptionValueLower = subOptionValue.toLowerCase()
    val commandUser = event.getUser().getId()
    val guild = event.getGuild()
    // default embed content
    var embedText = ":x: An error occured while running the `info` command"
    if (checkConfigDatabase(guild)){
      val guildId = guild.getId()
      if (subCommand == "guild"){ // command run with 'guild'
        val huntedGuilds = huntedGuildsData.getOrElse(guildId, List.empty[Guilds])
        val guildsData = huntedGuilds.find(_.name == subOptionValueLower).map {
          case gData =>
            // add guild to hunted list and database
            val gName = gData.name
            val gReason = gData.reason
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
          embedText = s":gear: The guild **${subOptionValueLower}** is not on the hunted list."
        }
      } else if (subCommand == "player"){ // command run with 'player'
        val huntedPlayers = huntedPlayersData.getOrElse(guildId, List.empty[Players])
        val playersData = huntedPlayers.find(_.name == subOptionValueLower).map {
          case pData =>
            // add guild to hunted list and database
            val pName = pData.name
            val pReason = pData.reason
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
          embedText = s":gear: The player **${subOptionValueLower}** is not on the hunted list."
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
    val commandUser = event.getUser().getId()
    val guild = event.getGuild()
    // default embed content
    var embedText = ":x: An error occured while running the `info` command"
    if (checkConfigDatabase(guild)){
      val guildId = guild.getId()
      if (subCommand == "guild"){ // command run with 'guild'
        val alliedGuilds = alliedGuildsData.getOrElse(guildId, List.empty[Guilds])
        val guildsData = alliedGuilds.find(_.name == subOptionValueLower).map {
          case gData =>
            // add guild to hunted list and database
            val gName = gData.name
            val gReason = gData.reason
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
          embedText = s":gear: The guild **${subOptionValueLower}** is not on the allied list."
        }
      } else if (subCommand == "player"){ // command run with 'player'
        val alliedPlayers = alliedPlayersData.getOrElse(guildId, List.empty[Players])
        val playersData = alliedPlayers.find(_.name == subOptionValueLower).map {
          case pData =>
            // add guild to hunted list and database
            val pName = pData.name
            val pReason = pData.reason
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
          embedText = s":gear: The player **${subOptionValueLower}** is not on the allied list."
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

  def listAlliesAndHuntedGuilds(event: SlashCommandInteractionEvent, arg: String, callback: (List[MessageEmbed]) => Unit): Unit = {
    val guild = event.getGuild()
    val tibiaDataClient = new TibiaDataClient()
    val embedColor = 3092790

    val guildHeader = if (arg == "allies") s"${Config.allyGuild} **Guilds** ${Config.allyGuild}" else if (arg == "hunted") s"${Config.enemyGuild} **Guilds** ${Config.enemyGuild}" else ""
    val listGuilds: List[Guilds] = if (arg == "allies") alliedGuildsData.getOrElse(guild.getId(), List.empty[Guilds]).map(g => g)
      else if (arg == "hunted") huntedGuildsData.getOrElse(guild.getId(), List.empty[Guilds]).map(g => g)
      else List.empty
    val guildThumbnail = if (arg == "allies") "https://tibia.fandom.com/wiki/Special:Redirect/file/Angel_Statue.gif" else if (arg == "hunted") "https://tibia.fandom.com/wiki/Special:Redirect/file/Stone_Coffin.gif" else ""
    var guildBuffer = ListBuffer[MessageEmbed]()
    if (listGuilds.nonEmpty) {
      // run api against guild
      val guildListFlow = Source(listGuilds.map(p => (p.name, p.reason)).toSet).mapAsyncUnordered(16)(tibiaDataClient.getGuildWithInput).toMat(Sink.seq)(Keep.right)
      val futureResults: Future[Seq[(GuildResponse, String, String)]] = guildListFlow.run()
      futureResults.onComplete {
        case Success(output) => {
          var guildApiBuffer = ListBuffer[String]()
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
        }
        case Failure(e) => e.printStackTrace
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

  def listAlliesAndHuntedPlayers(event: SlashCommandInteractionEvent, arg: String, callback: (List[MessageEmbed]) => Unit): Unit = {
    // get command option
    val guild = event.getGuild()
    val tibiaDataClient = new TibiaDataClient()
    val embedColor = 3092790

    val playerHeader = if (arg == "allies") s"${Config.allyGuild} **Players** ${Config.allyGuild}" else if (arg == "hunted") s"${Config.enemy} **Players** ${Config.enemy}" else ""
    val listPlayers: List[Players] = if (arg == "allies") alliedPlayersData.getOrElse(guild.getId(), List.empty[Players]).map(g => g)
      else if (arg == "hunted") huntedPlayersData.getOrElse(guild.getId(), List.empty[Players]).map(g => g)
      else List.empty
    val embedThumbnail = if (arg == "allies") "https://tibia.fandom.com/wiki/Special:Redirect/file/Golden_Newspaper.gif" else if (arg == "hunted") "https://tibia.fandom.com/wiki/Special:Redirect/file/Armageddon_Plans.gif" else ""
    var playerBuffer = ListBuffer[MessageEmbed]()
    if (listPlayers.nonEmpty) {
      // run api against players
      val listPlayersFlow = Source(listPlayers.map(p => (p.name, p.reason)).toSet).mapAsyncUnordered(16)(tibiaDataClient.getCharacterWithInput).toMat(Sink.seq)(Keep.right)
      val futureResults: Future[Seq[(CharacterResponse, String, String)]] = listPlayersFlow.run()
      futureResults.onComplete {
        case Success(output) => {
          val vocationBuffers = ListMap(
            "druid" -> ListBuffer[(Int, String, String)](),
            "knight" -> ListBuffer[(Int, String, String)](),
            "paladin" -> ListBuffer[(Int, String, String)](),
            "sorcerer" -> ListBuffer[(Int, String, String)](),
            "none" -> ListBuffer[(Int, String, String)]()
          )
          output.foreach { case (charResponse, name, reason) =>
            if (charResponse.characters.character.name != ""){
              val reasonEmoji = if (reason == "true") ":pencil:" else ""
              val charName = charResponse.characters.character.name
              val charLevel = charResponse.characters.character.level.toInt
              val charGuild = charResponse.characters.character.guild
              val charGuildName = if(!(charGuild.isEmpty)) charGuild.head.name else ""
              val guildIcon = if (charGuildName != "" && arg == "allies") Config.allyGuild else if (charGuildName != "" && arg == "hunted") Config.enemyGuild else if (charGuildName == "" && arg == "hunted") Config.enemy else ""
              val charVocation = charResponse.characters.character.vocation
              val charWorld = charResponse.characters.character.world
              val charLink = charUrl(charName)
              val charEmoji = vocEmoji(charResponse)
              val voc = charVocation.toLowerCase.split(' ').last
              vocationBuffers(voc) += ((charLevel.toInt, charWorld, s"$charEmoji ${charLevel.toInt.toString} — **[$charName]($charLink)** $guildIcon $reasonEmoji"))
            } else {
              vocationBuffers("none") += ((0, "Character does not exist", s":x: N/A — **$name**"))
            }
          }
          // group by world
          val vocationWorldBuffers = vocationBuffers.map {
            case (voc, buffer) =>
              voc -> buffer.groupBy(_._2)
          }

          // druids grouped by world worted by level
          val druidsWorldLists = vocationWorldBuffers("druid").map {
            case (world, worldBuffer) =>
              world -> worldBuffer.toList.sortBy(-_._1).map(_._3)
          }.toMap
          // knights
          val knightsWorldLists = vocationWorldBuffers("knight").map {
            case (world, worldBuffer) =>
              world -> worldBuffer.toList.sortBy(-_._1).map(_._3)
          }.toMap
          // paladins
          val paladinsWorldLists = vocationWorldBuffers("paladin").map {
            case (world, worldBuffer) =>
              world -> worldBuffer.toList.sortBy(-_._1).map(_._3)
          }.toMap
          // sorcerers
          val sorcerersWorldLists = vocationWorldBuffers("sorcerer").map {
            case (world, worldBuffer) =>
              world -> worldBuffer.toList.sortBy(-_._1).map(_._3)
          }.toMap
          // none
          val noneWorldLists = vocationWorldBuffers("none").map {
            case (world, worldBuffer) =>
              world -> worldBuffer.toList.sortBy(-_._1).map(_._3)
          }.toMap

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
        }
        case Failure(e) => e.printStackTrace
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

  def createWorldList(worlds: Map[String, List[String]]): List[String] = {
    val sortedWorlds = worlds.toList.sortBy(_._1)
      .sortWith((a, b) => {
        if (a._1 == "Character does not exist") false
        else if (b._1 == "Character does not exist") true
        else a._1 < b._1
      })
    sortedWorlds.flatMap {
      case (world, players) =>
        s":globe_with_meridians: **$world**" :: players
    }
  }

  def charUrl(char: String): String =
    s"https://www.tibia.com/community/?name=${char.replaceAll(" ", "+")}"

  def guildUrl(guild: String): String =
    s"https://www.tibia.com/community/?subtopic=guilds&page=view&GuildName=${guild.replaceAll(" ", "+")}"

  def addHunted(event: SlashCommandInteractionEvent, subCommand: String, subOptionValue: String, subOptionReason: String, callback: (MessageEmbed) => Unit): Unit = {
    // get command option
    val subOptionValueLower = subOptionValue.toLowerCase()
    val reason = if (subOptionReason == "none") "false" else "true"
    val commandUser = event.getUser().getId()
    val guild = event.getGuild()
    val tibiaDataClient = new TibiaDataClient()
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    // default embed content
    var embedText = ":x: An error occured while running the /hunted command"
    if (checkConfigDatabase(guild)){
      val guildId = guild.getId()
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
        }.map { case (guildName, guildMembers) =>
          if (guildName != ""){
            if (!huntedGuildsData.getOrElse(guildId, List()).exists(g => g.name == subOptionValueLower)) {
              // add guild to hunted list and database
              huntedGuildsData = huntedGuildsData + (guildId -> (Guilds(subOptionValueLower, reason, subOptionReason, commandUser) :: huntedGuildsData.getOrElse(guildId, List())))
              addHuntedToDatabase(guild, "guild", subOptionValueLower, reason, subOptionReason, commandUser)
              embedText = s":gear: The guild **[${guildName}](${guildUrl(guildName)})** has been added to the hunted list."

              // send embed to admin channel
              if (adminChannel != null){
                val adminEmbed = new EmbedBuilder()
                adminEmbed.setTitle(s":gear: a command was run:")
                adminEmbed.setDescription(s"<@$commandUser> added the guild **[${guildName}](${guildUrl(guildName)})** to the hunted list.")
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
              embedText = s":x: The guild **[${guildName}](${guildUrl(guildName)})** already exists in the hunted list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())
            }
          } else {
            embedText = s":x: The guild **${subOptionValueLower}** does not exist."
            embedBuild.setDescription(embedText)
            callback(embedBuild.build())
          }
        }
      } else if (subCommand == "player"){ // command run with 'player'
        // run api against player
        val playerCheck: Future[CharacterResponse] = tibiaDataClient.getCharacter(subOptionValueLower)
        playerCheck.map { charResponse =>
          charResponse.characters.character.name
        }.map { playerName =>
          if (playerName != ""){
            if (!huntedPlayersData.getOrElse(guildId, List()).exists(g => g.name == subOptionValueLower)) {
              // add player to hunted list and database
              huntedPlayersData = huntedPlayersData + (guildId -> (Players(subOptionValueLower, reason, subOptionReason, commandUser) :: huntedPlayersData.getOrElse(guildId, List())))
              addHuntedToDatabase(guild, "player", subOptionValueLower, reason, subOptionReason, commandUser)
              embedText = s":gear: The player **[${playerName}](${charUrl(playerName)})** has been added to the hunted list."

              // send embed to admin channel
              if (adminChannel != null){
                val adminEmbed = new EmbedBuilder()
                adminEmbed.setTitle(s":gear: a command was run:")
                adminEmbed.setDescription(s"<@$commandUser> added the player **[${playerName}](${charUrl(playerName)})** to the hunted list.")
                adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Stone_Coffin.gif")
                adminEmbed.setColor(3092790)
                adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
              }

              embedBuild.setDescription(embedText)
              callback(embedBuild.build())
            } else {
              embedText = s":x: The player **[${playerName}](${charUrl(playerName)})** already exists in the hunted list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())
            }
          } else {
            embedText = s":x: The player **${subOptionValueLower}** does not exist."
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

  def addAlly(event: SlashCommandInteractionEvent, subCommand: String, subOptionValue: String, subOptionReason: String, callback: (MessageEmbed) => Unit): Unit = {
    // same scrucutre as addHunted, use comments there for understanding
    val subOptionValueLower = subOptionValue.toLowerCase()
    val reason = if (subOptionReason == "none") "false" else "true"
    val guild = event.getGuild()
    val commandUser = event.getUser().getId()
    val tibiaDataClient = new TibiaDataClient()
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    // default embed content
    var embedText = ":x: An error occured while running the /allies command"
    if (checkConfigDatabase(guild)){
      val guildId = guild.getId()
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
        }.map { case (guildName, guildMembers) =>
          if (guildName != ""){
            if (!alliedGuildsData.getOrElse(guildId, List()).exists(g => g.name == subOptionValueLower)) {
              alliedGuildsData = alliedGuildsData + (guildId -> (Guilds(subOptionValueLower, reason, subOptionReason, commandUser) :: alliedGuildsData.getOrElse(guildId, List())))
              addAllyToDatabase(guild, "guild", subOptionValueLower, reason, subOptionReason, commandUser)
              embedText = s":gear: The guild **[${guildName}](${guildUrl(guildName)})** has been added to the allies list."

              if (adminChannel != null){
                val adminEmbed = new EmbedBuilder()
                adminEmbed.setTitle(s":gear: a command was run:")
                adminEmbed.setDescription(s"<@$commandUser> added the guild **[${guildName}](${guildUrl(guildName)})** to the allies list.")
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
              embedText = s":x: The guild **[${guildName}](${guildUrl(guildName)})** already exists in the allies list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())
            }
          } else {
            embedText = s":x: The guild **${subOptionValueLower}** does not exist."
            embedBuild.setDescription(embedText)
            callback(embedBuild.build())
          }
        }
      } else if (subCommand == "player"){
        // run api against player
        val playerCheck: Future[CharacterResponse] = tibiaDataClient.getCharacter(subOptionValueLower)
        playerCheck.map { charResponse =>
          charResponse.characters.character.name
        }.map { playerName =>
          if (playerName != ""){
            if (!alliedPlayersData.getOrElse(guildId, List()).exists(g => g.name == subOptionValueLower)) {
              alliedPlayersData = alliedPlayersData + (guildId -> (Players(subOptionValueLower, reason, subOptionReason, commandUser) :: alliedPlayersData.getOrElse(guildId, List())))
              addAllyToDatabase(guild, "player", subOptionValueLower, reason, subOptionReason, commandUser)
              embedText = s":gear: The player **[${playerName}](${charUrl(playerName)})* has been added to the allies list."

              if (adminChannel != null){
                val adminEmbed = new EmbedBuilder()
                adminEmbed.setTitle(s":gear: a command was run:")
                adminEmbed.setDescription(s"<@$commandUser> added the player **${subOptionValueLower}** to the allies list.")
                adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Angel_Statue.gif")
                adminEmbed.setColor(3092790)
                adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
              }
            } else {
              embedText = s":x: The player **[${playerName}](${charUrl(playerName)})* already exists in the allies list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())
            }
          } else {
            embedText = s":x: The player **${subOptionValueLower}** does not exist."
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

  def removeHunted(event: SlashCommandInteractionEvent, subCommand: String, subOptionValue: String, subOptionReason: String, callback: (MessageEmbed) => Unit): Unit = {
    // get command option
    val subOptionValueLower = subOptionValue.toLowerCase()
    val guild = event.getGuild()
    val commandUser = event.getUser().getId()
    val tibiaDataClient = new TibiaDataClient()
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    var embedText = ":x: An error occured while running the /removehunted command"
    if (checkConfigDatabase(guild)){
      val guildId = guild.getId()
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
            guildString = s"[${guildName}](${guildUrl(guildName)})"
          }
          val huntedGuildsList = huntedGuildsData.getOrElse(guildId, List())
          val updatedList = huntedGuildsList.find(_.name == subOptionValueLower) match {
            case Some(_) => huntedGuildsList.filterNot(_.name == subOptionValueLower)
            case None =>
              embedText = s":x: The guild **${guildString}** is not on the hunted list."
              embedBuild.setDescription(embedText)
              return callback(embedBuild.build())
          }
          huntedGuildsData = huntedGuildsData.updated(guildId, updatedList)
          removeHuntedFromDatabase(guild, "guild", subOptionValueLower)

          // send embed to admin channel
          if (adminChannel != null){
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> removed guild **${guildString}** from the hunted list.")
            adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Stone_Coffin.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          }

          embedText = s":gear: The guild **${guildString}** was removed from the hunted list."
          embedBuild.setDescription(embedText)
          callback(embedBuild.build())
        }
      } else if (subCommand == "player"){
        var playerString = subOptionValueLower
        // run api against player
        val playerCheck: Future[CharacterResponse] = tibiaDataClient.getCharacter(subOptionValueLower)
        playerCheck.map { charResponse =>
          charResponse.characters.character.name
        }.map { playerName =>
          if (playerName != ""){
            playerString = s"[${playerName}](${charUrl(playerName)})"
          }
          val huntedPlayersList = huntedPlayersData.getOrElse(guildId, List())
          val updatedList = huntedPlayersList.find(_.name == subOptionValueLower) match {
            case Some(_) => huntedPlayersList.filterNot(_.name == subOptionValueLower)
            case None =>
              embedText = s":x: The player **${playerString}** is not on the hunted list."
              embedBuild.setDescription(embedText)
              return callback(embedBuild.build())
          }
          huntedPlayersData = huntedPlayersData.updated(guildId, updatedList)
          removeHuntedFromDatabase(guild, "player", subOptionValueLower)

          // send embed to admin channel
          if (adminChannel != null){
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> removed player **$playerString** from the hunted list.")
            adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Stone_Coffin.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          }

          embedText = s":gear: The player **${playerString}** was removed from the hunted list."
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

  def removeAlly(event: SlashCommandInteractionEvent, subCommand: String, subOptionValue: String, subOptionReason: String, callback: (MessageEmbed) => Unit): Unit = {
    // get command option
    val subOptionValueLower = subOptionValue.toLowerCase()
    val guild = event.getGuild()
    val commandUser = event.getUser().getId()
    val tibiaDataClient = new TibiaDataClient()
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)
    var embedText = ":x: An error occured while running the /removehunted command"
    if (checkConfigDatabase(guild)){
      val guildId = guild.getId()
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
            guildString = s"[${guildName}](${guildUrl(guildName)})"
          }
          val alliedGuildsList = alliedGuildsData.getOrElse(guildId, List())
          val updatedList = alliedGuildsList.find(_.name == subOptionValueLower) match {
            case Some(_) => alliedGuildsList.filterNot(_.name == subOptionValueLower)
            case None =>
              embedText = s":x: The guild **${guildString}** is not on the allies list."
              embedBuild.setDescription(embedText)
              return callback(embedBuild.build())
          }
          alliedGuildsData = alliedGuildsData.updated(guildId, updatedList)
          removeAllyFromDatabase(guild, "guild", subOptionValueLower)

          // send embed to admin channel
          if (adminChannel != null){
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> removed **${guildString}** from the allies list.")
            adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Angel_Statue.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          }

          embedText = s":gear: The guild **${guildString}** was removed from the allies list."
          embedBuild.setDescription(embedText)
          callback(embedBuild.build())
        }
      } else if (subCommand == "player"){
        var playerString = subOptionValueLower
        // run api against player
        val playerCheck: Future[CharacterResponse] = tibiaDataClient.getCharacter(subOptionValueLower)
        playerCheck.map { charResponse =>
          charResponse.characters.character.name
        }.map { playerName =>
          if (playerName != ""){
            playerString = s"[${playerName}](${charUrl(playerName)})"
          }
          val alliedPlayersList = alliedPlayersData.getOrElse(guildId, List())
          val updatedList = alliedPlayersList.find(_.name == subOptionValueLower) match {
            case Some(_) => alliedPlayersList.filterNot(_.name == subOptionValueLower)
            case None =>
              embedText = s":x: The player **${playerString}** is not on the allies list."
              embedBuild.setDescription(embedText)
              return callback(embedBuild.build())
          }
          alliedPlayersData = alliedPlayersData.updated(guildId, updatedList)
          removeAllyFromDatabase(guild, "player", subOptionValueLower)

          // send embed to admin channel
          if (adminChannel != null){
            val adminEmbed = new EmbedBuilder()
            adminEmbed.setTitle(s":gear: a command was run:")
            adminEmbed.setDescription(s"<@$commandUser> removed **$playerString** from the allies list.")
            adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Angel_Statue.gif")
            adminEmbed.setColor(3092790)
            adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
          }

          embedText = s":gear: The player **${playerString}** was removed from the allies list."
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

  def addHuntedToDatabase(guild: Guild, option: String, name: String, reason: String, reasonText: String, addedBy: String) = {
    val conn = getConnection(guild)
    val table = (if (option == "guild") "hunted_guilds" else if (option == "player") "hunted_players").toString
    val statement = conn.prepareStatement(s"INSERT INTO $table(name, reason, reason_text, added_by) VALUES (?,?,?,?) ON CONFLICT (name) DO NOTHING;")
    statement.setString(1, name)
    statement.setString(2, reason)
    statement.setString(3, reasonText)
    statement.setString(4, addedBy)
    val result = statement.executeUpdate()

    statement.close()
    conn.close()
  }

  def addAllyToDatabase(guild: Guild, option: String, name: String, reason: String, reasonText: String, addedBy: String) = {
    val conn = getConnection(guild)
    val table = (if (option == "guild") "allied_guilds" else if (option == "player") "allied_players").toString
    val statement = conn.prepareStatement(s"INSERT INTO $table(name, reason, reason_text, added_by) VALUES (?,?,?,?) ON CONFLICT (name) DO NOTHING;")
    statement.setString(1, name)
    statement.setString(2, reason)
    statement.setString(3, reasonText)
    statement.setString(4, addedBy)
    val result = statement.executeUpdate()

    statement.close()
    conn.close()
  }

  def removeHuntedFromDatabase(guild: Guild, option: String, name: String) = {
    val conn = getConnection(guild)
    val table = (if (option == "guild") "hunted_guilds" else if (option == "player") "hunted_players").toString
    val statement = conn.prepareStatement(s"DELETE FROM $table WHERE name = ?;")
    statement.setString(1, name)
    val result = statement.executeUpdate()

    statement.close()
    conn.close()
  }

  def removeAllyFromDatabase(guild: Guild, option: String, name: String) = {
    val conn = getConnection(guild)
    val table = (if (option == "guild") "allied_guilds" else if (option == "player") "allied_players").toString
    val statement = conn.prepareStatement(s"DELETE FROM $table WHERE name = ?;")
    statement.setString(1, name)
    val result = statement.executeUpdate()

    statement.close()
    conn.close()
  }

  def checkConfigDatabase(guild: Guild): Boolean = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/postgres"
    val username = "postgres"
    val password = Config.postgresPassword
    val guildId = guild.getId()
    val guildName = guild.getName()

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

  def createConfigDatabase(guild: Guild) = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/postgres"
    val username = "postgres"
    val password = Config.postgresPassword
    val guildId = guild.getId()
    val guildName = guild.getName()

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

  def getConnection(guild: Guild): Connection = {
    val guildId = guild.getId()
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/_$guildId"
    val username = "postgres"
    val password = Config.postgresPassword
    DriverManager.getConnection(url, username, password)
  }

  def playerConfig(guild: Guild, query: String): List[Players] = {
    val guildId = guild.getId()
    val conn = getConnection(guild)
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT name,reason,reason_text,added_by FROM $query")

    var results = new ListBuffer[Players]()
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

  def guildConfig(guild: Guild, query: String): List[Guilds] = {
    val conn = getConnection(guild)
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT name,reason,reason_text,added_by FROM $query")

    var results = new ListBuffer[Guilds]()
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

  def discordRetrieveConfig(guild: Guild): Map[String, String] = {
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

  def worldConfig(guild: Guild, query: String): List[Worlds] = {
    val conn = getConnection(guild)
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT name,fullbless_level,show_neutral_levels,show_neutral_deaths FROM $query")

    var results = new ListBuffer[Worlds]()
    while (result.next()) {
      val name = Option(result.getString("name")).getOrElse("")
      val fullblessLevel = Option(result.getInt("fullbless_level")).getOrElse(250)
      val showNeutralLevels = Option(result.getString("show_neutral_levels")).getOrElse("true")
      val showNeutralDeaths = Option(result.getString("show_neutral_deaths")).getOrElse("true")
      results += Worlds(name, fullblessLevel, showNeutralLevels, showNeutralDeaths)
    }

    statement.close()
    conn.close()
    results.toList
  }

  def worldCreateConfig(guild: Guild, world: String, alliesChannel: String, enemiesChannel: String, neutralsChannels: String, levelsChannel: String, deathsChannel: String, category: String, fullblessRole: String, nemesisRole: String, fullblessChannel: String, nemesisChannel: String, fullblessLevel: Int, showNeutralLevels: String, showNeutralDeaths: String) = {
    val conn = getConnection(guild)
    val statement = conn.prepareStatement("INSERT INTO worlds(name, allies_channel, enemies_channel, neutrals_channel, levels_channel, deaths_channel, category, fullbless_role, nemesis_role, fullbless_channel, nemesis_channel, fullbless_level, show_neutral_levels, show_neutral_deaths) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (name) DO UPDATE SET allies_channel = ?, enemies_channel = ?, neutrals_channel = ?, levels_channel = ?, deaths_channel = ?, category = ?, fullbless_role = ?, nemesis_role = ?, fullbless_channel = ?, nemesis_channel = ?, fullbless_level = ?, show_neutral_levels = ?, show_neutral_deaths = ?;")
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
    statement.setInt(12, fullblessLevel)
    statement.setString(13, showNeutralLevels)
    statement.setString(14, showNeutralDeaths)
    statement.setString(15, alliesChannel)
    statement.setString(16, enemiesChannel)
    statement.setString(17, neutralsChannels)
    statement.setString(18, levelsChannel)
    statement.setString(19, deathsChannel)
    statement.setString(20, category)
    statement.setString(21, fullblessRole)
    statement.setString(22, nemesisRole)
    statement.setString(23, fullblessChannel)
    statement.setString(24, nemesisChannel)
    statement.setInt(25, fullblessLevel)
    statement.setString(26, showNeutralLevels)
    statement.setString(27, showNeutralDeaths)
    val result = statement.executeUpdate()

    statement.close()
    conn.close()
  }

  def discordCreateConfig(guild: Guild, guildName: String, guildOwner: String, adminCategory: String, adminChannel: String, flags: String, created: ZonedDateTime) = {
    val conn = getConnection(guild)
    val statement = conn.prepareStatement("INSERT INTO discord_info(guild_name, guild_owner, admin_category, admin_channel, flags, created) VALUES (?, ?, ?, ?, ?, ?);")
    statement.setString(1, guildName)
    statement.setString(2, guildOwner)
    statement.setString(3, adminCategory)
    statement.setString(4, adminChannel)
    statement.setString(5, flags)
    statement.setTimestamp(6, Timestamp.from(created.toInstant))
    val result = statement.executeUpdate()

    statement.close()
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
      }
      statement.close()
      conn.close()
      configMap
  }

  def worldRemoveConfig(guild: Guild, query: String) = {
    val conn = getConnection(guild)
    val statement = conn.prepareStatement("DELETE FROM worlds WHERE name = ?")
    val formalName = query.toLowerCase().capitalize
    statement.setString(1, formalName)
    val result = statement.executeUpdate()

    statement.close()
    conn.close()
  }

  def createChannels(event: SlashCommandInteractionEvent): MessageEmbed = {
    // get guild & world information from the slash interaction
    val world: String = event.getInteraction.getOptions.asScala.find(_.getName == "world").map(_.getAsString).getOrElse("").trim().toLowerCase().capitalize
    val embedText = if (worlds.contains(world)) {
      // get guild id
      val guild = event.getGuild()

      // assume initial run on this server and attempt to create core databases
      createConfigDatabase(guild)

      // see if admin channels exist
      val discordConfig = discordRetrieveConfig(guild)
      if (discordConfig.isEmpty){
        val adminCategory = guild.createCategory("Violent Bot Administration").complete()
        val adminChannel = guild.createTextChannel("bot-activity", adminCategory).complete()
        // restrict the channel so only roles with Permission.MANAGE_MESSAGES can write to the channels
        val botRole = guild.getSelfMember().getRoles()

        botRole.forEach(role => {
          adminChannel.upsertPermissionOverride(role).grant(Permission.MESSAGE_SEND).complete()
          adminChannel.upsertPermissionOverride(role).grant(Permission.VIEW_CHANNEL).complete()
        });
        adminChannel.upsertPermissionOverride(guild.getPublicRole()).deny(Permission.VIEW_CHANNEL).queue()
        discordCreateConfig(guild, guild.getName(), guild.getOwner().getEffectiveName(), adminCategory.getId(), adminChannel.getId(), "none", ZonedDateTime.now())
      }

      // Fullbless role
      val fullblessRoleString = s"$world Fullbless"
      val fullblessRoleCheck = guild.getRolesByName(fullblessRoleString, true)
      val fullblessRole = if (!fullblessRoleCheck.isEmpty) fullblessRoleCheck.get(0) else guild.createRole().setName(fullblessRoleString).setColor(new Color(0, 156, 70)).complete()

      // get all categories in the discord
      val categories = guild.getCategories().asScala
      val targetCategory = categories.find(_.getName == world).getOrElse(null)
      // it it doesn't create it
      if (targetCategory == null){
        // create the category
        val newCategory = guild.createCategory(world).complete()

        // create the channels
        val alliesChannel = guild.createTextChannel("allies", newCategory).complete()
        val enemiesChannel = guild.createTextChannel("enemies", newCategory).complete()
        val neutralsChannel = guild.createTextChannel("neutrals", newCategory).complete()
        val levelsChannel = guild.createTextChannel("levels", newCategory).complete()
        val deathsChannel = guild.createTextChannel("deaths", newCategory).complete()
        val fullblessChannel = guild.createTextChannel("fullbless-notifications", newCategory).complete()
        val nemesisChannel = guild.createTextChannel("boss-notifications", newCategory).complete()

        val fullblessEmbedText = s"The bot will poke <@&${fullblessRole.getId()}>\n\nIf an enemy player dies fullbless and is over level 250.\nAdd or remove yourself from the role using the buttons below."
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
        val worldCount = worldConfig(guild, "worlds")
        val count = worldCount.length
        val nemesisList = List("Zarabustor", "Midnight_Panther", "Yeti", "Shlorg", "White_Pale", "Furyosa", "Jesse_the_Wicked", "The_Welter", "Tyrn", "Zushuka")
        val nemesisThumbnail = nemesisList(count % nemesisList.size)

        val nemesisEmbedText = s"The bot will poke <@&${nemesisRole.getId()}>\n\nIf anyone dies to a rare boss (so you can go steal it).\nAdd or remove yourself from the role using the buttons below."
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

        // restrict the channel so only roles with Permission.MANAGE_MESSAGES can write to the channels
        val botRole = guild.getSelfMember().getRoles()

        botRole.forEach(role => {
          alliesChannel.upsertPermissionOverride(role).grant(Permission.MESSAGE_SEND).complete()
          enemiesChannel.upsertPermissionOverride(role).grant(Permission.MESSAGE_SEND).complete()
          neutralsChannel.upsertPermissionOverride(role).grant(Permission.MESSAGE_SEND).complete()
          levelsChannel.upsertPermissionOverride(role).grant(Permission.MESSAGE_SEND).complete()
          deathsChannel.upsertPermissionOverride(role).grant(Permission.MESSAGE_SEND).complete()
          fullblessChannel.upsertPermissionOverride(role).grant(Permission.MESSAGE_SEND).complete()
          nemesisChannel.upsertPermissionOverride(role).grant(Permission.MESSAGE_SEND).complete()
        });

        alliesChannel.upsertPermissionOverride(guild.getPublicRole()).deny(Permission.MESSAGE_SEND).complete()
        enemiesChannel.upsertPermissionOverride(guild.getPublicRole()).deny(Permission.MESSAGE_SEND).complete()
        neutralsChannel.upsertPermissionOverride(guild.getPublicRole()).deny(Permission.MESSAGE_SEND).complete()
        levelsChannel.upsertPermissionOverride(guild.getPublicRole()).deny(Permission.MESSAGE_SEND).complete()
        deathsChannel.upsertPermissionOverride(guild.getPublicRole()).deny(Permission.MESSAGE_SEND).complete()
        fullblessChannel.upsertPermissionOverride(guild.getPublicRole()).deny(Permission.MESSAGE_SEND).complete()
        nemesisChannel.upsertPermissionOverride(guild.getPublicRole()).deny(Permission.MESSAGE_SEND).complete()

        val alliesId = alliesChannel.getId()
        val enemiesId = enemiesChannel.getId()
        val neutralsId = neutralsChannel.getId()
        val levelsId = levelsChannel.getId()
        val deathsId = deathsChannel.getId()
        val categoryId = newCategory.getId()
        val fullblessId = fullblessChannel.getId()
        val nemesisId = nemesisChannel.getId()

        // update the database
        worldCreateConfig(guild, world, alliesId, enemiesId, neutralsId, levelsId, deathsId, categoryId, fullblessRole.getId(), nemesisRole.getId(), fullblessId, nemesisId, 250, "true", "true")
        startBot(guild, Some(world))
        s":gear: The channels for **${world}** have been configured successfully."
      } else {
        // channels already exist
        logger.info(s"The channels have already been setup on '${guild.getName()} - ${guild.getId()}'.")
        s":x: The channels for **${world}** have already been setup."
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

  def getMessagesWithEmbedTitle(channel: TextChannel, title: String): List[Message] = {
    val messages = channel.getIterableHistory().complete().asScala
    messages.filter(message =>
      message.getEmbeds.asScala.exists(embed =>
        embed.getTitle.contains(title)
      )
    ).toList
  }

  def removeChannels(event: SlashCommandInteractionEvent): MessageEmbed = {
    // get guild & world information from the slash interaction
    val world: String = event.getInteraction.getOptions.asScala.find(_.getName == "world").map(_.getAsString).getOrElse("").trim().toLowerCase().capitalize
    val embedText = if (worlds.contains(world)) {
      val guild = event.getGuild()
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
        if (channelIds.contains(event.getChannel().getId())) {
          return new EmbedBuilder()
          .setColor(3092790)
          .setDescription(s":x: This command would delete this channel, run it somewhere else.")
          .build()
        }

        // cancel the stream
        val key = (guild, world.capitalize)
        deathTrackerStreams.get(key) match {
          case Some(stream) =>
            stream.cancel()
            deathTrackerStreams -= (key)
          case None =>
            logger.info(s"No stream found for guild '${guild.getName()} - ${guild.getId()}' and world '$world'.")
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

        // update the database
        worldRemoveConfig(guild, world)

        s":gear: The world **${world}** has been removed."
      } else {
        s":x: The world **${world}** is not configured here."
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
//
}
