package com.tibiabot.hunted

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.tibiabot.Config
import com.tibiabot.domain.{Guilds, ListCache, PlayerCache, Players, Worlds}
import com.tibiabot.persistence.{ActivityRepository, CacheRepository, HuntedAlliedRepository}
import com.tibiabot.presentation.{AdminLog, Embeds}
import com.tibiabot.presentation.Embeds.BrandColor
import com.tibiabot.state.StreamState
import com.tibiabot.tibiadata.TibiaApi
import com.tibiabot.tibiadata.response.{CharacterResponse, GuildResponse, Members}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import scala.collection.immutable.ListMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

/**
 * Per-guild hunted/allied player and guild list CRUD, plus the shared
 * activity-cache bookkeeping those commands trigger. Extracted verbatim from
 * BotApp (infoHunted/infoAllies/listAlliesAndHuntedGuilds/listAlliesAndHuntedPlayers/
 * clearAllies/clearHunted/addHunted/addAlly/removeHunted/removeAlly), which
 * previously held ~950 lines of this logic directly.
 *
 * `discordRetrieveConfig`/`worldConfig`/`checkConfigDatabase` stay owned by
 * BotApp (passed in as callbacks) since many other, not-yet-extracted clusters
 * depend on them too.
 */
final class HuntedAlliedService(
  huntedAlliedRepository: HuntedAlliedRepository,
  activityRepository: ActivityRepository,
  cacheRepository: CacheRepository,
  streamState: StreamState,
  tibiaDataClient: TibiaApi,
  discordRetrieveConfig: Guild => Map[String, String],
  worldConfig: Guild => List[Worlds],
  checkConfigDatabase: Guild => Boolean
)(implicit system: ActorSystem, ex: ExecutionContextExecutor) extends StrictLogging {

  def charUrl(char: String): String = com.tibiabot.presentation.Urls.charUrl(char)
  def guildUrl(guild: String): String = com.tibiabot.presentation.Urls.guildUrl(guild)

  def vocEmoji(char: CharacterResponse): String =
    com.tibiabot.presentation.Emojis.vocEmoji(char.character.character.vocation)

  /** Fetch a character and reduce it to the (name, world, vocation-emoji, level)
   *  summary the add/remove player commands render. On lookup failure yields the
   *  empty/"does not exist" summary (name == ""). Also used by
   *  [[com.tibiabot.customsort.CustomSortService]]'s player-tagging command. */
  def fetchPlayerSummary(name: String): Future[(String, String, String, Int)] =
    tibiaDataClient.getCharacter(name).map {
      case Right(charResponse) =>
        val character = charResponse.character.character
        (character.name, character.world, vocEmoji(charResponse), character.level.toInt)
      case Left(_) =>
        ("", "", s"${Config.noEmoji}", 0)
    }

  private def getListTable(world: String): List[ListCache] =
    cacheRepository.getList(world)

  def addListToCache(name: String, formerNames: List[String], world: String, formerWorlds: List[String], guild: String, level: String, vocation: String, lastLogin: String, updatedTime: ZonedDateTime): Unit =
    cacheRepository.addToList(name, formerNames, world, formerWorlds, guild, level, vocation, lastLogin, updatedTime)

  private def dateStringToEpochSeconds(dateString: String): String =
    com.tibiabot.presentation.RecentLogin.stamp(dateString, java.time.Instant.now())

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

  /** Exposed for TibiaBot's auto-hunted-detection paths (join/leave/exiva
   *  scans), which mutate the hunted/allied lists directly rather than going
   *  through a command. */
  def modifyHuntedPlayersData(f: Map[String, List[Players]] => Map[String, List[Players]]): Unit =
    streamState.modifyHuntedPlayersData(f)

  def modifyAlliedPlayersData(f: Map[String, List[Players]] => Map[String, List[Players]]): Unit =
    streamState.modifyAlliedPlayersData(f)

  def infoHunted(event: SlashCommandInteractionEvent, subCommand: String, subOptionValue: String): MessageEmbed = {
    val subOptionValueLower = subOptionValue.toLowerCase()
    val guild = event.getGuild
    var embedText = s"${Config.noEmoji} An error occurred while running the `info` command"
    if (checkConfigDatabase(guild)) {
      val guildId = guild.getId
      if (subCommand == "guild") {
        val huntedGuilds = streamState.huntedGuildsData.getOrElse(guildId, List.empty[Guilds])
        huntedGuilds.find(_.name == subOptionValueLower).map {
          case gData =>
            val gText = gData.reasonText
            val gUser = gData.addedBy
            val gNameFormal = com.tibiabot.presentation.Names.capitalizeWords(subOptionValueLower)
            val gLink = guildUrl(gNameFormal)
            embedText = s"**Guild:** [$gNameFormal]($gLink)\n **added by:** <@$gUser>\n **reason:** $gText"

            val embed = new EmbedBuilder()
            embed.setTitle(s":gear: hunted guild details:")
            embed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Tibiapedia.gif")
            embed.setColor(BrandColor)
            embed.setDescription(embedText)
            val returnEmbed = embed.build()
            return returnEmbed

        }.getOrElse {
          embedText = s":gear: The guild **$subOptionValueLower** is not on the hunted list."
        }
      } else if (subCommand == "player") {
        val huntedPlayers = streamState.huntedPlayersData.getOrElse(guildId, List.empty[Players])
        huntedPlayers.find(_.name == subOptionValueLower).map {
          case pData =>
            val pText = pData.reasonText
            val pUser = pData.addedBy
            val pNameFormal = com.tibiabot.presentation.Names.capitalizeWords(subOptionValueLower)
            val pLink = charUrl(pNameFormal)
            embedText = s"**Player:** [$pNameFormal]($pLink)\n **added by:** <@$pUser>\n **reason:** $pText"

            val embed = new EmbedBuilder()
            embed.setTitle(s":gear: hunted player details:")
            embed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Tibiapedia.gif")
            embed.setColor(BrandColor)
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
    Embeds.response(embedText)
  }

  def infoAllies(event: SlashCommandInteractionEvent, subCommand: String, subOptionValue: String): MessageEmbed = {
    val subOptionValueLower = subOptionValue.toLowerCase()
    val guild = event.getGuild
    var embedText = s"${Config.noEmoji} An error occurred while running the `info` command"
    if (checkConfigDatabase(guild)) {
      val guildId = guild.getId
      if (subCommand == "guild") {
        val alliedGuilds = streamState.alliedGuildsData.getOrElse(guildId, List.empty[Guilds])
        alliedGuilds.find(_.name == subOptionValueLower).map {
          case gData =>
            val gText = gData.reasonText
            val gUser = gData.addedBy
            val gNameFormal = com.tibiabot.presentation.Names.capitalizeWords(subOptionValueLower)
            val gLink = guildUrl(gNameFormal)
            embedText = s"**Guild:** [$gNameFormal]($gLink)\n **added by:** <@$gUser>\n **reason:** $gText"

            val embed = new EmbedBuilder()
            embed.setTitle(s":gear: allied guild details:")
            embed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Tibiapedia.gif")
            embed.setColor(BrandColor)
            embed.setDescription(embedText)
            val returnEmbed = embed.build()
            return returnEmbed

        }.getOrElse {
          embedText = s":gear: The guild **$subOptionValueLower** is not on the allied list."
        }
      } else if (subCommand == "player") {
        val alliedPlayers = streamState.alliedPlayersData.getOrElse(guildId, List.empty[Players])
        alliedPlayers.find(_.name == subOptionValueLower).map {
          case pData =>
            val pText = pData.reasonText
            val pUser = pData.addedBy
            val pNameFormal = com.tibiabot.presentation.Names.capitalizeWords(subOptionValueLower)
            val pLink = charUrl(pNameFormal)
            embedText = s"**Player: [$pNameFormal]($pLink)**\n **added by:** <@$pUser>\n **reason:** $pText"

            val embed = new EmbedBuilder()
            embed.setTitle(s":gear: allied player details:")
            embed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Tibiapedia.gif")
            embed.setColor(BrandColor)
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
    Embeds.response(embedText)
  }

  def listAlliesAndHuntedGuilds(event: SlashCommandInteractionEvent, arg: String, callback: List[MessageEmbed] => Unit): Unit = {
    val guild = event.getGuild
    val embedColor = 3092790

    val guildHeader = s"__**Guilds:**__"
    val listGuilds: List[Guilds] = if (arg == "allies") streamState.alliedGuildsData.getOrElse(guild.getId, List.empty[Guilds]).map(g => g)
      else if (arg == "hunted") streamState.huntedGuildsData.getOrElse(guild.getId, List.empty[Guilds]).map(g => g)
      else List.empty
    val guildThumbnail = if (arg == "allies") "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Angel_Statue.gif" else if (arg == "hunted") "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Stone_Coffin.gif" else ""
    val guildBuffer = ListBuffer[MessageEmbed]()
    if (listGuilds.nonEmpty) {
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
          guildBuffer ++= com.tibiabot.presentation.ListEmbeds.paginate(guildsAsList, guildThumbnail, embedColor)
          callback(guildBuffer.toList)
        case Failure(exception) =>
          logger.error(s"Failed to build the $arg guilds list for Guild '${guild.getName}': ${exception.getMessage}", exception)
          val errorEmbed = new EmbedBuilder()
          errorEmbed.setColor(embedColor)
          errorEmbed.setDescription(s"${Config.noEmoji} Failed to load the guilds list, try again.")
          callback(List(errorEmbed.build()))
      }
    } else {
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
    val guild = event.getGuild
    val guildId = guild.getId
    val embedColor = 3092790

    val playerHeader = s"__**Players:**__"
    val listPlayers: List[Players] = if (arg == "allies") streamState.alliedPlayersData.getOrElse(guild.getId, List.empty[Players]).map(g => g)
      else if (arg == "hunted") streamState.huntedPlayersData.getOrElse(guild.getId, List.empty[Players]).map(g => g)
      else List.empty
    val embedThumbnail = if (arg == "allies") "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Angel_Statue.gif" else if (arg == "hunted") "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Stone_Coffin.gif" else ""
    val playerBuffer = ListBuffer[MessageEmbed]()
    if (listPlayers.nonEmpty) {

      val allWorlds: List[Worlds] = worldConfig(guild)
      var concatenatedListCache: List[ListCache] = List.empty[ListCache]
      for (world <- allWorlds) {
        val listCacheForWorld: List[ListCache] = getListTable(world.name)
        concatenatedListCache = concatenatedListCache ++ listCacheForWorld
      }

      val playersToUpdate: List[Players] = listPlayers.filterNot { player =>
        concatenatedListCache.find(_.name.toLowerCase == player.name.toLowerCase).exists { cache =>
          cache.updatedTime.isAfter(ZonedDateTime.now().minus(24, ChronoUnit.HOURS))
        }
      }
      val playerNamesSet: Set[String] = listPlayers.map(_.name.toLowerCase).toSet
      val filteredConcatenatedListCache: List[ListCache] = concatenatedListCache.filter { player =>
        playerNamesSet.contains(player.name.toLowerCase) && player.updatedTime.isAfter(ZonedDateTime.now().minus(24, ChronoUnit.HOURS))
      }
      val listPlayersFlow = Source(playersToUpdate.map(p => (p.name, p.reason, p.reasonText)).toSet).mapAsyncUnordered(4)(tibiaDataClient.getCharacterWithInput).toMat(Sink.seq)(Keep.right)
      val futureResults: Future[Seq[(Either[String, CharacterResponse], String, String, String)]] = listPlayersFlow.run()
      futureResults.onComplete {
        case Success(output) =>
          val vocationBuffers = ListMap(
            com.tibiabot.domain.Vocations.displayOrder.map(_ -> ListBuffer[(Int, String, String)]()): _*
          )
          for (player <- filteredConcatenatedListCache) {
            val pName = player.name
            val pWorld = player.world
            val pLvl = player.level
            val pVoc = player.vocation.toLowerCase.split(' ').last
            val pEmoji = com.tibiabot.presentation.Emojis.vocEmoji(pVoc)
            val pGuild = player.guild
            val allyGuildCheck = if (pGuild != "") streamState.alliedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == pGuild.toLowerCase()) else false
            val huntedGuildCheck = if (pGuild != "") streamState.huntedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == pGuild.toLowerCase()) else false
            val pIcon = com.tibiabot.presentation.GuildIcons.listGuildIcon(pGuild, allyGuildCheck, huntedGuildCheck, arg)
            val pLoginRelative = dateStringToEpochSeconds(player.last_login)
            if (pVoc != "") {
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
                val allyGuildCheck = if (charGuildName != "") streamState.alliedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == charGuildName.toLowerCase()) else false
                val huntedGuildCheck = if (charGuildName != "") streamState.huntedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == charGuildName.toLowerCase()) else false
                val guildIcon = com.tibiabot.presentation.GuildIcons.listGuildIcon(charGuildName, allyGuildCheck, huntedGuildCheck, arg)
                val charVocation = charResponse.character.character.vocation
                val charWorld = charResponse.character.character.world
                val charLink = charUrl(charName)
                val charEmoji = vocEmoji(charResponse)
                val pNameFormal = com.tibiabot.presentation.Names.capitalizeWords(name)
                val voc = charVocation.toLowerCase.split(' ').last
                val lastLoginTime = charResponse.character.character.last_login.getOrElse("")
                if (allWorlds.exists(_.name.toLowerCase == charWorld.toLowerCase)) {
                  vocationBuffers(voc) += ((charLevel, charWorld, s"$charEmoji **${charLevel.toString}** — **[$pNameFormal]($charLink)** $guildIcon ${dateStringToEpochSeconds(lastLoginTime)}"))
                }
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
          val allPlayers = com.tibiabot.presentation.WorldList.byWorld(
            vocationBuffers.map { case (voc, buffer) => voc -> buffer.toSeq })

          val playersList = List(playerHeader) ++ com.tibiabot.presentation.WorldList.format(allPlayers)

          playerBuffer ++= com.tibiabot.presentation.ListEmbeds.paginate(playersList, embedThumbnail, embedColor)
          callback(playerBuffer.toList)
        case Failure(exception) =>
          logger.error(s"Failed to build the $arg players list for Guild '${guild.getName}': ${exception.getMessage}", exception)
          val errorEmbed = new EmbedBuilder()
          errorEmbed.setColor(embedColor)
          errorEmbed.setDescription(s"${Config.noEmoji} Failed to load the players list, try again.")
          callback(List(errorEmbed.build()))
      }
    } else {
      val listIsEmpty = new EmbedBuilder()
      val listisEmptyMessage = playerHeader ++ s"\n*The players list is empty.*"
      listIsEmpty.setDescription(listisEmptyMessage)
      listIsEmpty.setThumbnail(embedThumbnail)
      listIsEmpty.setColor(embedColor)
      playerBuffer += listIsEmpty.build()
      callback(playerBuffer.toList)

    }
  }

  def clearAllies(event: SlashCommandInteractionEvent): MessageEmbed = {
    val guild = event.getGuild
    val guildId = guild.getId

    val listGuilds: List[Guilds] = streamState.alliedGuildsData.getOrElse(guildId, List.empty[Guilds])
    val listPlayers: List[Players] = streamState.alliedPlayersData.getOrElse(guildId, List.empty[Players])

    val guildNamesToRemove = listGuilds.map(_.name.toLowerCase).toSet
    val playerNamesToRemove = listPlayers.map(_.name.toLowerCase).toSet

    if (listGuilds.nonEmpty) {
      streamState.modifyActivityData { m =>
        m.view.mapValues {
          _.filterNot(pc => guildNamesToRemove.contains(pc.guild.toLowerCase))
        }.toMap
      }

      listGuilds.foreach { guildEntry =>
        removeAllyFromDatabase(guild, "guild", guildEntry.name.toLowerCase)
        removeGuildActivityfromDatabase(guild, guildEntry.name.toLowerCase)
      }
    }

    if (listPlayers.nonEmpty) {
      streamState.modifyActivityData { m =>
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
    Embeds.response(embedText)
  }

  def clearHunted(event: SlashCommandInteractionEvent): MessageEmbed = {
    val guild = event.getGuild
    val guildId = guild.getId
    val listGuilds: List[Guilds] = streamState.huntedGuildsData.getOrElse(guild.getId, List.empty[Guilds])
    val listPlayers: List[Players] = streamState.huntedPlayersData.getOrElse(guild.getId, List.empty[Players])
    val guildNamesToRemove = listGuilds.map(_.name.toLowerCase).toSet
    val playerNamesToRemove = listPlayers.map(_.name.toLowerCase).toSet
    if (listGuilds.nonEmpty) {
      streamState.modifyActivityData { m =>
        m.view.mapValues {
          _.filterNot(pc => guildNamesToRemove.contains(pc.guild.toLowerCase))
        }.toMap
      }
      listGuilds.foreach { guildEntry =>
        removeHuntedFromDatabase(guild, "guild", guildEntry.name.toLowerCase)
        removeGuildActivityfromDatabase(guild, guildEntry.name.toLowerCase)
      }
    }
    if (listPlayers.nonEmpty) {
      streamState.modifyActivityData { m =>
        val updatedList = m.getOrElse(guildId, List.empty)
          .filterNot(player => playerNamesToRemove.contains(player.name.toLowerCase))

        m.updated(guildId, updatedList)
      }
      listPlayers.foreach { filterPlayer =>
        removeHuntedFromDatabase(guild, "player", filterPlayer.name.toLowerCase)
        removePlayerActivityfromDatabase(guild, filterPlayer.name.toLowerCase)
      }
    }
    val embedText = s"${Config.yesEmoji} The hunted list has been reset."
    Embeds.response(embedText)
  }

  def addHunted(event: SlashCommandInteractionEvent, subCommand: String, subOptionValue: String, subOptionReason: String, callback: MessageEmbed => Unit): Unit = {
    val subOptionValueLower = subOptionValue.toLowerCase()
    val reason = if (subOptionReason == "none") "false" else "true"
    val commandUser = event.getUser.getId
    val guild = event.getGuild
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(BrandColor)
    var embedText = s"${Config.noEmoji} An error occurred while running the /hunted command"
    if (checkConfigDatabase(guild)) {
      val guildId = guild.getId
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (subCommand == "guild") {
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
            if (!streamState.huntedGuildsData.getOrElse(guildId, List()).exists(g => g.name == subOptionValueLower)) {
              streamState.modifyHuntedGuildsData(m => m + (guildId -> (Guilds(subOptionValueLower, reason, subOptionReason, commandUser) :: m.getOrElse(guildId, List()))))
              addHuntedToDatabase(guild, "guild", subOptionValueLower, reason, subOptionReason, commandUser)
              embedText = s":gear: The guild **[$guildName](${guildUrl(guildName)})** has been added to the hunted list."

              AdminLog.post(adminChannel, s"<@$commandUser> added the guild **[$guildName](${guildUrl(guildName)})** to the hunted list.", "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Stone_Coffin.gif")

              guildMembers.foreach { member =>
                val guildPlayers = streamState.activityData.getOrElse(guildId, List())
                if (!guildPlayers.exists(_.name == member.name)) {
                  val updatedTime = ZonedDateTime.now()
                  streamState.modifyActivityData(m => m + (guildId -> (PlayerCache(member.name, List(""), guildName, updatedTime) :: guildPlayers)))
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
      } else if (subCommand == "player") {
        fetchPlayerSummary(subOptionValueLower).map { case (playerName, world, vocation, level) =>
          if (playerName != "") {
            if (!streamState.huntedPlayersData.getOrElse(guildId, List()).exists(g => g.name == subOptionValueLower)) {
              streamState.modifyHuntedPlayersData(m => m + (guildId -> (Players(subOptionValueLower, reason, subOptionReason, commandUser) :: m.getOrElse(guildId, List()))))
              addHuntedToDatabase(guild, "player", subOptionValueLower, reason, subOptionReason, commandUser)
              embedText = s":gear: The player **[$playerName](${charUrl(playerName)})** has been added to the hunted list."

              AdminLog.post(adminChannel, s"<@$commandUser> added the player\n$vocation **$level** — **[$playerName](${charUrl(playerName)})**\nto the hunted list for **$world**.", "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Stone_Coffin.gif")

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
    // mirrors addHunted's structure, targeting the allied lists instead
    val subOptionValueLower = subOptionValue.toLowerCase()
    val reason = if (subOptionReason == "none") "false" else "true"
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(BrandColor)
    var embedText = s"${Config.noEmoji} An error occurred while running the /allies command"
    if (checkConfigDatabase(guild)) {
      val guildId = guild.getId
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (subCommand == "guild") {
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
            if (!streamState.alliedGuildsData.getOrElse(guildId, List()).exists(g => g.name == subOptionValueLower)) {
              streamState.modifyAlliedGuildsData(m => m + (guildId -> (Guilds(subOptionValueLower, reason, subOptionReason, commandUser) :: m.getOrElse(guildId, List()))))
              addAllyToDatabase(guild, "guild", subOptionValueLower, reason, subOptionReason, commandUser)
              embedText = s":gear: The guild **[$guildName](${guildUrl(guildName)})** has been added to the allies list."

              AdminLog.post(adminChannel, s"<@$commandUser> added the guild **[$guildName](${guildUrl(guildName)})** to the allies list.", "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Angel_Statue.gif")

              guildMembers.foreach { member =>
                val guildPlayers = streamState.activityData.getOrElse(guildId, List())
                if (!guildPlayers.exists(_.name == member.name)) {
                  val updatedTime = ZonedDateTime.now()
                  streamState.modifyActivityData(m => m + (guildId -> (PlayerCache(member.name, List(""), guildName, updatedTime) :: guildPlayers)))
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
        fetchPlayerSummary(subOptionValueLower).map { case (playerName, world, vocation, level) =>
          if (playerName != "") {
            if (!streamState.alliedPlayersData.getOrElse(guildId, List()).exists(g => g.name == subOptionValueLower)) {
              streamState.modifyAlliedPlayersData(m => m + (guildId -> (Players(subOptionValueLower, reason, subOptionReason, commandUser) :: m.getOrElse(guildId, List()))))
              addAllyToDatabase(guild, "player", subOptionValueLower, reason, subOptionReason, commandUser)
              embedText = s":gear: The player **[$playerName](${charUrl(playerName)})** has been added to the allies list."

              AdminLog.post(adminChannel, s"<@$commandUser> added the player\n$vocation **$level** — **[$playerName](${charUrl(playerName)})**\nto the allies list for **$world**.", "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Angel_Statue.gif")

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
    val subOptionValueLower = subOptionValue.toLowerCase()
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(BrandColor)
    var embedText = s"${Config.noEmoji} An error occurred while running the /removehunted command"
    if (checkConfigDatabase(guild)) {
      val guildId = guild.getId
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (subCommand == "guild") {
        var guildString = subOptionValueLower
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
          val huntedGuildsList = streamState.huntedGuildsData.getOrElse(guildId, List())
          huntedGuildsList.find(_.name.toLowerCase == subOptionValueLower) match {
            case Some(_) =>
              val updatedList = huntedGuildsList.filterNot(_.name.toLowerCase == subOptionValueLower)
              streamState.modifyHuntedGuildsData(_.updated(guildId, updatedList))
              removeHuntedFromDatabase(guild, "guild", subOptionValueLower)

              streamState.modifyActivityData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(_.guild.equalsIgnoreCase(subOptionValueLower))))
              removeGuildActivityfromDatabase(guild, subOptionValueLower)

              val filteredPlayers: List[Players] = {
                streamState.huntedPlayersData.getOrElse(guildId, List()).filter(_.reasonText.toLowerCase == s"was originally in hunted guild ${subOptionValueLower}".toLowerCase)
              }
              val huntedPlayersList = streamState.huntedPlayersData.getOrElse(guildId, List())
              val updatedHuntedPlayersList = huntedPlayersList.filterNot(player => filteredPlayers.exists(_.name == player.name))
              streamState.modifyHuntedPlayersData(m => m.updated(guildId, updatedHuntedPlayersList))

              streamState.modifyActivityData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(player => filteredPlayers.map(_.name.toLowerCase).contains(player.name.toLowerCase))))
              filteredPlayers.foreach { filterPlayer =>
                removeHuntedFromDatabase(guild, "player", filterPlayer.name)
                removePlayerActivityfromDatabase(guild, filterPlayer.name)
              }

              AdminLog.post(adminChannel, s"<@$commandUser> removed guild **$guildString** from the hunted list.", "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Stone_Coffin.gif")

              embedText = s":gear: The guild **$guildString** was removed from the hunted list."
              embedBuild.setDescription(embedText)
              callback(embedBuild.build())
            case None =>
              embedText = s"${Config.noEmoji} The guild **$guildString** is not on the hunted list."

              val filteredPlayers: List[Players] = {
                streamState.huntedPlayersData.getOrElse(guildId, List()).filter(_.reasonText.toLowerCase == s"was originally in hunted guild ${subOptionValueLower}".toLowerCase)
              }
              if (filteredPlayers.nonEmpty){
                val huntedPlayersList = streamState.huntedPlayersData.getOrElse(guildId, List())
                val updatedHuntedPlayersList = huntedPlayersList.filterNot(player => filteredPlayers.exists(_.name == player.name))
                streamState.modifyHuntedPlayersData(m => m.updated(guildId, updatedHuntedPlayersList))

                streamState.modifyActivityData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(player => filteredPlayers.map(_.name.toLowerCase).contains(player.name.toLowerCase))))
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
        fetchPlayerSummary(subOptionValueLower).map { case (playerName, world, vocation, level) =>
          if (playerName != "") {
            playerString = s"[$playerName](${charUrl(playerName)})"
          }
          val huntedPlayersList = streamState.huntedPlayersData.getOrElse(guildId, List())
          huntedPlayersList.find(_.name.toLowerCase == subOptionValueLower) match {
            case Some(_) =>
              val updatedList = huntedPlayersList.filterNot(_.name.toLowerCase == subOptionValueLower)

              streamState.modifyHuntedPlayersData(m => m.updated(guildId, updatedList))
              removeHuntedFromDatabase(guild, "player", subOptionValueLower)

              streamState.modifyActivityData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(_.name.equalsIgnoreCase(subOptionValueLower))))
              removePlayerActivityfromDatabase(guild, subOptionValueLower)

              AdminLog.post(adminChannel, s"<@$commandUser> removed the player\n$vocation **$level** — **$playerString**\nfrom the hunted list for **$world**.", "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Stone_Coffin.gif")

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
    val subOptionValueLower = subOptionValue.toLowerCase()
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(BrandColor)
    var embedText = s"${Config.noEmoji} An error occurred while running the /removehunted command"
    if (checkConfigDatabase(guild)) {
      val guildId = guild.getId
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (subCommand == "guild") {
        var guildString = subOptionValueLower
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
          val alliedGuildsList = streamState.alliedGuildsData.getOrElse(guildId, List())
          alliedGuildsList.find(_.name.toLowerCase == subOptionValueLower) match {
            case Some(_) =>
              val updatedList = alliedGuildsList.filterNot(_.name.toLowerCase == subOptionValueLower)
              streamState.modifyAlliedGuildsData(_.updated(guildId, updatedList))
              removeAllyFromDatabase(guild, "guild", subOptionValueLower)

              streamState.modifyActivityData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(_.guild.equalsIgnoreCase(subOptionValueLower))))
              removeGuildActivityfromDatabase(guild, subOptionValueLower)

              AdminLog.post(adminChannel, s"<@$commandUser> removed **$guildString** from the allies list.", "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Angel_Statue.gif")

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
        fetchPlayerSummary(subOptionValueLower).map { case (playerName, world, vocation, level) =>
          if (playerName != "") {
            playerString = s"[$playerName](${charUrl(playerName)})"
          }
          val alliedPlayersList = streamState.alliedPlayersData.getOrElse(guildId, List())
          alliedPlayersList.find(_.name.toLowerCase == subOptionValueLower) match {
            case Some(_) =>
              val updatedList = alliedPlayersList.filterNot(_.name.toLowerCase == subOptionValueLower)
              streamState.modifyAlliedPlayersData(m => m.updated(guildId, updatedList))
              removeAllyFromDatabase(guild, "player", subOptionValueLower)

              streamState.modifyActivityData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(_.name.equalsIgnoreCase(subOptionValueLower))))
              removePlayerActivityfromDatabase(guild, subOptionValueLower)

              AdminLog.post(adminChannel, s"<@$commandUser> removed the player\n$vocation **$level** — **$playerString**\nfrom the allies list for **$world**.", "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Angel_Statue.gif")

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
}
