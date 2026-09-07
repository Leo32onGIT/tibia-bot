package com.tibiabot.hunted

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import com.tibiabot.Config
import com.tibiabot.domain.{BulkListOutcome, Guilds, ListCache, PlayerCache, PlayerLookup, Players, Worlds}
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
import net.dv8tion.jda.api.events.interaction.GenericInteractionCreateEvent

import java.time.{Duration, ZonedDateTime}
import java.time.temporal.ChronoUnit
import scala.collection.immutable.ListMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal
import com.tibiabot.presentation.Names

/**
 * Per-guild hunted/allied player and guild list CRUD, plus the shared
 * activity-cache bookkeeping those commands trigger. Extracted verbatim from
 * BotApp (infoHunted/infoAllies/playersEmbeds/guildsEmbeds/
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

  /** Who added an entry, by name.
   *
   *  The row keeps only their Discord id, because these lines used to be
   *  mentions and an id was all a mention needed. The name comes from the
   *  guild's own member cache, so it costs no request; somebody who has since
   *  left the server, or who simply is not cached, reads as "someone" rather
   *  than as a bare id, which tells a reader nothing at all.
   */
  private def addedByName(guild: Guild, userId: String): String =
    scala.util.Try(Option(userId).filter(_.nonEmpty).flatMap(id => Option(guild.getMemberById(id))))
      .toOption.flatten
      .map(member => Names.user(member.getUser.getName))
      .getOrElse("**`someone`**")

  /** Look a character up before adding or removing it, and say which of the three
   *  things actually happened - see [[com.tibiabot.domain.PlayerLookup]].
   *
   *  Uses `getCharacterOnDemand` rather than `getCharacter`: this is the command
   *  path, where there is no next poll to fix a 503. A failed Future (the retries
   *  gave up on a connection failure) is Unavailable too - it is the same answer
   *  as a failed response, and must not read as "no such character".
   *
   *  A sheet that comes back is filed in the shared list cache on the way past.
   *  That is what lets the hunted and allies lists render without asking the API
   *  anything: a name is looked up once, when somebody adds it, and the list is
   *  drawn from what that lookup already learned.
   */
  def fetchPlayerSummary(name: String): Future[PlayerLookup] =
    tibiaDataClient.getCharacterOnDemand(name).map {
      case Right(charResponse) =>
        val character = charResponse.character.character
        // TibiaData answers a name nobody owns with 200 and an empty sheet, so an
        // empty name here is a real "no such character" rather than a failure.
        if (character.name.isEmpty) PlayerLookup.NotFound
        else {
          cacheSheet(charResponse)
          PlayerLookup.Found(character.name, character.world, vocEmoji(charResponse),
            character.level.toInt, character.traded.getOrElse(false))
        }
      case Left(_) => PlayerLookup.Unavailable
    }.recover { case NonFatal(_) => PlayerLookup.Unavailable }

  /** File a character sheet in the shared list cache. Never allowed to break the
   *  lookup that produced it - a cache write failing is not a reason to refuse an
   *  add. */
  private def cacheSheet(response: CharacterResponse): Unit =
    try {
      val character = response.character.character
      addListToCache(
        character.name,
        character.former_names.map(_.toList).getOrElse(Nil),
        character.world,
        character.former_worlds.map(_.toList).getOrElse(Nil),
        character.guild.map(_.name).getOrElse(""),
        character.level.toInt.toString,
        character.vocation,
        character.last_login.getOrElse(""),
        ZonedDateTime.now())
    } catch {
      case NonFatal(ex) => logger.warn(s"Failed to cache the sheet for a list lookup: ${ex.getMessage}")
    }

  private def getListTable(world: String): List[ListCache] =
    cacheRepository.getList(world)

  def addListToCache(name: String, formerNames: List[String], world: String, formerWorlds: List[String], guild: String, level: String, vocation: String, lastLogin: String, updatedTime: ZonedDateTime): Unit =
    cacheRepository.addToList(name, formerNames, world, formerWorlds, guild, level, vocation, lastLogin, updatedTime)

  private def dateStringToEpochSeconds(dateString: String): String =
    com.tibiabot.presentation.RecentLogin.stamp(dateString, java.time.Instant.now())

  def addHuntedToDatabase(guild: Guild, option: String, name: String, reason: String, reasonText: String,
                          addedBy: String, tradedWhenAdded: Boolean = false): Unit =
    huntedAlliedRepository.addHunted(guild.getId, option, name, reason, reasonText, addedBy, tradedWhenAdded)

  def addActivityToDatabase(guild: Guild, name: String, formerNames: List[String], guildName: String, updatedTime: ZonedDateTime): Unit =
    activityRepository.add(guild.getId, name, formerNames, guildName, updatedTime)

  def updateActivityToDatabase(guild: Guild, name: String, formerNames: List[String], guildName: String, updatedTime: ZonedDateTime, newName: String): Unit =
    activityRepository.update(guild.getId, name, formerNames, guildName, updatedTime, newName)

  def updateHuntedOrAllyNameToDatabase(guild: Guild, option: String, oldName: String, newName: String): Unit =
    huntedAlliedRepository.rename(guild.getId, option, oldName, newName)

  private def addAllyToDatabase(guild: Guild, option: String, name: String, reason: String, reasonText: String,
                                addedBy: String, tradedWhenAdded: Boolean = false): Unit =
    huntedAlliedRepository.addAllied(guild.getId, option, name, reason, reasonText, addedBy, tradedWhenAdded)

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

  def infoHunted(event: GenericInteractionCreateEvent, subCommand: String, subOptionValue: String): MessageEmbed = {
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
            embedText = s"**Guild:** [$gNameFormal]($gLink)\n **added by:** ${addedByName(guild, gUser)}\n **reason:** $gText"

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
            embedText = s"**Player:** [$pNameFormal]($pLink)\n **added by:** ${addedByName(guild, pUser)}\n **reason:** $pText"

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

  def infoAllies(event: GenericInteractionCreateEvent, subCommand: String, subOptionValue: String): MessageEmbed = {
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
            embedText = s"**Guild:** [$gNameFormal]($gLink)\n **added by:** ${addedByName(guild, gUser)}\n **reason:** $gText"

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
            embedText = s"**Player: [$pNameFormal]($pLink)**\n **added by:** ${addedByName(guild, pUser)}\n **reason:** $pText"

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
  def clearAllies(event: GenericInteractionCreateEvent): MessageEmbed = {
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

  def clearHunted(event: GenericInteractionCreateEvent): MessageEmbed = {
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


  // --- drawing the lists ---------------------------------------------------
  //
  // Built from what is already known and nothing else. The older list command
  // fetched every player whose cached sheet was over a day old, which on a list
  // of seventy-five was seventy-five requests against an API that 503s about
  // half the time - slow, and worse than slow: a request that failed was drawn
  // as "Character does not exist", so a real character read as a deleted one.
  //
  // Nothing needs fetching now. A name is looked up once, when somebody adds it,
  // and that sheet is filed in the list cache on the way past (see
  // fetchPlayerSummary), so the list has something to draw the moment a name is
  // on it. The world poll refreshes those sheets as it goes.

  /** The players on a guild's hunted or allied list, drawn from cache.
   *
   *  Synchronous, because there is nothing to wait for. Players the cache has
   *  never seen are still listed - by name, without a level - rather than left
   *  out: the list is the record of who is on it, and a missing sheet is a gap in
   *  what is known about them, not evidence they are not there.
   */
  def playersEmbeds(guild: Guild, arg: String): List[MessageEmbed] = {
    val guildId = guild.getId
    val embedColor = 3092790
    val thumbnail =
      if (arg == "allies") "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Angel_Statue.gif"
      else "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Stone_Coffin.gif"

    val listed: List[Players] =
      if (arg == "allies") streamState.alliedPlayersData.getOrElse(guildId, List.empty[Players])
      else streamState.huntedPlayersData.getOrElse(guildId, List.empty[Players])

    if (listed.isEmpty) {
      val empty = new EmbedBuilder()
      empty.setTitle("Players")
      empty.setDescription("*Nobody on the list yet.*")
      empty.setColor(embedColor)
      empty.setThumbnail(thumbnail)
      List(empty.build())
    } else {
      val allWorlds: List[Worlds] = worldConfig(guild)
      val cached: Map[String, ListCache] =
        allWorlds.flatMap(w => getListTable(w.name)).map(entry => entry.name.toLowerCase -> entry).toMap

      val vocationBuffers = ListMap(
        com.tibiabot.domain.Vocations.displayOrder.map(_ -> ListBuffer[(Int, String, String)]()): _*
      )
      listed.foreach { player =>
        cached.get(player.name.toLowerCase) match {
          case Some(sheet) if sheet.vocation.nonEmpty &&
            allWorlds.exists(_.name.equalsIgnoreCase(sheet.world)) =>
            val voc = sheet.vocation.toLowerCase.split(' ').last
            val emoji = com.tibiabot.presentation.Emojis.vocEmoji(voc)
            val icon = guildIconFor(guildId, sheet.guild, arg)
            val login = dateStringToEpochSeconds(sheet.last_login)
            val level = scala.util.Try(sheet.level.toInt).getOrElse(0)
            if (vocationBuffers.contains(voc))
              vocationBuffers(voc) += ((level, sheet.world,
                s"$emoji **${sheet.level}** - **[${sheet.name}](${charUrl(sheet.name)})** $icon $login"))
          case _ =>
            // On the list, but nothing cached about them yet - the next poll or
            // the next time somebody adds them fills this in.
            val shown = com.tibiabot.presentation.Names.capitalizeWords(player.name)
            vocationBuffers("none") += ((0, "Not checked yet",
              s":grey_question: **?** - **[$shown](${charUrl(player.name)})**"))
        }
      }

      val byWorld = com.tibiabot.presentation.WorldList.byWorld(
        vocationBuffers.map { case (voc, buffer) => voc -> buffer.toSeq })
      val lines = com.tibiabot.presentation.WorldList.format(byWorld)
      // Packed by the online list's packer rather than a flat character count, so
      // a world's heading opens a fresh embed instead of landing halfway down one
      // - and so a heading is never left stranded above the players it
      // introduces. Its message grouping is flattened away here: what a message
      // may carry is settled later, once the guild embeds are alongside these.
      //
      // "Players" is the embed's title rather than a first line, because a line
      // above the first "## " heading would be split off into an embed of its
      // own by that very rule - a heading stranded the other way up.
      com.tibiabot.presentation.OnlineListEmbeds.packMessages(lines).flatten
        .zipWithIndex.map { case (description, index) =>
          val embed = new EmbedBuilder()
          embed.setDescription(description)
          embed.setColor(embedColor)
          if (index == 0) {
            embed.setTitle("Players")
            embed.setThumbnail(thumbnail)
          }
          embed.build()
        }
    }
  }

  /** The guilds on a list, drawn the same way.
   *
   *  The member count comes from the activity records this bot already keeps for
   *  a tracked guild's roster - the same records that make a member leaving it
   *  visible - so it costs nothing. A guild whose roster has not been recorded
   *  yet is listed without a count rather than being fetched for one.
   */
  def guildsEmbeds(guild: Guild, arg: String): List[MessageEmbed] = {
    val guildId = guild.getId
    val embedColor = 3092790
    val thumbnail =
      if (arg == "allies") "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Angel_Statue.gif"
      else "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Stone_Coffin.gif"

    val listed: List[Guilds] =
      if (arg == "allies") streamState.alliedGuildsData.getOrElse(guildId, List.empty[Guilds])
      else streamState.huntedGuildsData.getOrElse(guildId, List.empty[Guilds])

    val builder = new EmbedBuilder()
    builder.setColor(embedColor)
    builder.setThumbnail(thumbnail)
    if (listed.isEmpty) {
      builder.setTitle("Guilds")
      builder.setDescription("*No guilds on the list yet.*")
      List(builder.build())
    } else {
      val roster: Map[String, Int] =
        streamState.activityData.getOrElse(guildId, List())
          .filter(_.guild.nonEmpty)
          .groupBy(_.guild.toLowerCase)
          .map { case (name, members) => name -> members.size }

      val lines = listed.sortBy(_.name).map { entry =>
        val shown = com.tibiabot.presentation.Names.capitalizeWords(entry.name)
        val members = roster.get(entry.name.toLowerCase).map(n => s" — **$n** members").getOrElse("")
        val reason = if (entry.reason == "true") " :pencil:" else ""
        s"**[$shown](${guildUrl(entry.name)})**$members$reason"
      }
      // "Guilds" as the title rather than a first line, matching the players
      // half - and here it also keeps the label out of the paginated body, so a
      // guilds list long enough to span embeds is not headed only on page one.
      com.tibiabot.presentation.ListEmbeds.paginate(lines, thumbnail, embedColor).toList
        .zipWithIndex.map { case (embed, index) =>
          if (index == 0) new EmbedBuilder(embed).setTitle("Guilds").build() else embed
        }
    }
  }

  /** Which icon a player's guild earns on a list — allied, hunted, or neither. */
  private def guildIconFor(guildId: String, guildName: String, arg: String): String =
    if (guildName.isEmpty) com.tibiabot.presentation.GuildIcons.listGuildIcon("", false, false, arg)
    else {
      val allied = streamState.alliedGuildsData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(guildName))
      val hunted = streamState.huntedGuildsData.getOrElse(guildId, List()).exists(_.name.equalsIgnoreCase(guildName))
      com.tibiabot.presentation.GuildIcons.listGuildIcon(guildName, allied, hunted, arg)
    }

  // --- retiring an entry ---------------------------------------------------

  /** Flag one listed player and say so in the admin channel, once.
   *
   *  Marking and announcing are one step on purpose: writing the reason is what
   *  stops the notice repeating, so a notice that went out without the write
   *  would go out again on the next sweep, forever. Returns whether anything was
   *  said, which is what the caller counts.
   *
   *  Nothing is removed. The notice names what to do and a person does it — see
   *  ListReview for why neither finding is safe to act on automatically.
   */
  def flagForRemoval(guild: Guild, hunted: Boolean, entry: Players,
                     finding: ListReview.Finding): Boolean =
    try {
      val table = if (hunted) "hunted_players" else "allied_players"
      huntedAlliedRepository.flagPlayer(guild.getId, table, entry.name, finding.reason)

      val flagged = entry.copy(flaggedReason = finding.reason)
      val replace = (players: List[Players]) =>
        players.map(player => if (player.name.equalsIgnoreCase(entry.name)) flagged else player)
      if (hunted) streamState.modifyHuntedPlayersData(m => m + (guild.getId -> replace(m.getOrElse(guild.getId, List()))))
      else streamState.modifyAlliedPlayersData(m => m + (guild.getId -> replace(m.getOrElse(guild.getId, List()))))

      val listName = if (hunted) "hunted" else "allies"
      val shown = com.tibiabot.presentation.Names.capitalizeWords(entry.name)
      val because = finding match {
        case ListReview.Finding.Traded =>
          s"has been **traded** since being added"
        case ListReview.Finding.MovedWorld(world) =>
          s"has moved to **$world**, which isn't set up here"
      }
      val thumbnail =
        if (hunted) "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Stone_Coffin.gif"
        else "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Angel_Statue.gif"
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      AdminLog.post(adminChannel,
        s"**[$shown](${charUrl(entry.name)})** $because, so the $listName list entry is " +
          "probably worth removing.\nNothing has been changed — remove them with `/" + listName + "` if you agree.",
        thumbnail)
      true
    } catch {
      // A guild whose admin channel has gone, or whose database is unreachable.
      // Losing one notice is not a reason to break the sweep for every other guild.
      case NonFatal(ex) =>
        logger.warn(s"Failed to flag '${entry.name}' in guild '${guild.getId}': ${ex.getMessage}")
        false
    }

  /** Re-check listed players the world poll has not seen lately.
   *
   *  The poll only ever scans characters that turn up in a *tracked* world's
   *  online list, which leaves two blind spots this covers:
   *
   *   - a player who has stopped logging in, so nothing refreshes what is known
   *     about them and a trade goes unnoticed;
   *   - a player who moved to a world this guild does not track, who from that
   *     moment never appears in any list the poll reads. Every world finding
   *     comes from here — the poll cannot make one, because its per-guild loop
   *     only runs for discords tracking the world the character was seen on.
   *
   *  Bounded by only asking about the quiet ones: anybody the poll has refreshed
   *  recently is already known to be fine, so a busy server costs nothing. The
   *  lookups go through the command-path fetch, which retries — a 503 must not
   *  read as "no such character" here any more than it may on an add.
   */
  def reviewQuietPlayers(guild: Guild, quietFor: Duration = Duration.ofHours(24),
                         maxPerSweep: Int = 20): Future[Int] = {
    if (!checkConfigDatabase(guild)) return Future.successful(0)
    val guildId = guild.getId
    val trackedWorlds = worldConfig(guild).map(_.name).toSet
    // Nothing to compare a world against: a guild mid-setup, or one whose worlds
    // have all been removed. Flagging everybody for having left a set of no
    // worlds would be the worst possible reading of that.
    if (trackedWorlds.isEmpty) return Future.successful(0)

    val cached: Map[String, ListCache] =
      trackedWorlds.toList.flatMap(getListTable).map(entry => entry.name.toLowerCase -> entry).toMap
    val cutoff = ZonedDateTime.now().minus(quietFor)

    def quiet(entry: Players): Boolean =
      entry.flaggedReason.isEmpty &&
        cached.get(entry.name.toLowerCase).forall(_.updatedTime.isBefore(cutoff))

    val hunted = streamState.huntedPlayersData.getOrElse(guildId, List()).filter(quiet).map(_ -> true)
    val allied = streamState.alliedPlayersData.getOrElse(guildId, List()).filter(quiet).map(_ -> false)
    // Capped so one sweep cannot turn a long-neglected list into hundreds of
    // lookups at once; the rest are picked up by the sweeps after it.
    val toCheck = (hunted ++ allied).take(maxPerSweep)

    if (toCheck.isEmpty) Future.successful(0)
    else Source(toCheck)
      .mapAsyncUnordered(BulkParallelism) { case (entry, isHunted) =>
        fetchPlayerSummary(entry.name).map {
          case PlayerLookup.Found(_, world, _, _, traded) =>
            ListReview.review(entry, traded, world, trackedWorlds)
              .exists(finding => flagForRemoval(guild, isHunted, entry, finding))
          // Says nothing about the character either way, so it decides nothing.
          // A deleted character is left alone deliberately: that is a third thing
          // entirely, and not one this was asked to act on.
          case _ => false
        }
      }
      .runWith(Sink.seq)
      .map(_.count(identity))
  }

  // --- bulk list changes ---------------------------------------------------
  //
  // What the paste boxes on the /hunted and /allies panels run. The single-name
  // add and remove above still exist and still do their own reply; these do the
  // same work for a list, and differ in three ways that matter:
  //
  //  - names already on the list are settled before anything is looked up, so a
  //    paste of a roster somebody already added costs no API calls at all;
  //  - a failed lookup is its own outcome rather than "does not exist", which is
  //    the whole point of PlayerLookup - see BulkListOutcome;
  //  - the admin channel gets one post for the batch, not one per name. A
  //    hundred posts would be a hundred REST calls into a rate limit, and nobody
  //    reads a hundred lines to learn one thing happened.

  /** How many lookups are in flight at once. Four, matching the other streamed
   *  fetches in this file - the API's own pacing is the real ceiling, so going
   *  wider only queues behind it. */
  private val BulkParallelism = 4

  /** Add a pasted list of players or guilds. `hunted` picks which list. */
  def addMany(guild: Guild, hunted: Boolean, kind: String, names: List[String],
              reason: String, commandUser: String): Future[BulkListOutcome] = {
    if (!checkConfigDatabase(guild)) return Future.successful(BulkListOutcome.empty)
    val guildId = guild.getId
    val reasonFlag = if (reason.isEmpty) "false" else "true"
    val reasonText = if (reason.isEmpty) "none" else reason

    // One snapshot, taken before anything is fetched: it settles what is already
    // on the list for the whole batch, so a name cannot pass the check twice.
    val existing: Set[String] =
      if (kind == "guild") currentGuildNames(guildId, hunted) else currentPlayerNames(guildId, hunted)
    val (duplicates, fresh) = names.partition(name => existing.contains(name.toLowerCase))
    val startingPoint = BulkListOutcome(already = duplicates)

    if (fresh.isEmpty) Future.successful(startingPoint)
    else Source(fresh)
      .mapAsyncUnordered(BulkParallelism)(name =>
        if (kind == "guild") addOneGuild(guild, hunted, name, reasonFlag, reasonText, commandUser)
        else addOnePlayer(guild, hunted, name, reasonFlag, reasonText, commandUser))
      .runWith(Sink.seq)
      .map(_.foldLeft(startingPoint)(_ merge _))
  }

  private def currentPlayerNames(guildId: String, hunted: Boolean): Set[String] =
    (if (hunted) streamState.huntedPlayersData else streamState.alliedPlayersData)
      .getOrElse(guildId, List()).map(_.name.toLowerCase).toSet

  private def currentGuildNames(guildId: String, hunted: Boolean): Set[String] =
    (if (hunted) streamState.huntedGuildsData else streamState.alliedGuildsData)
      .getOrElse(guildId, List()).map(_.name.toLowerCase).toSet

  private def addOnePlayer(guild: Guild, hunted: Boolean, name: String, reasonFlag: String,
                           reasonText: String, commandUser: String): Future[BulkListOutcome] = {
    val lower = name.toLowerCase
    fetchPlayerSummary(lower).map {
      case PlayerLookup.Found(realName, _, _, _, traded) =>
        // The traded flag is snapshotted here and never recomputed. A player who
        // was already traded when somebody listed them is deliberate, and must
        // never be proposed for removal on that basis later.
        val entry = Players(lower, reasonFlag, reasonText, commandUser, tradedWhenAdded = traded)
        if (hunted) {
          streamState.modifyHuntedPlayersData(m => m + (guild.getId -> (entry :: m.getOrElse(guild.getId, List()))))
          addHuntedToDatabase(guild, "player", lower, reasonFlag, reasonText, commandUser, traded)
        } else {
          streamState.modifyAlliedPlayersData(m => m + (guild.getId -> (entry :: m.getOrElse(guild.getId, List()))))
          addAllyToDatabase(guild, "player", lower, reasonFlag, reasonText, commandUser, traded)
        }
        BulkListOutcome(added = List(realName))
      case PlayerLookup.NotFound    => BulkListOutcome(notFound = List(name))
      case PlayerLookup.Unavailable => BulkListOutcome(unavailable = List(name))
    }
  }

  private def addOneGuild(guild: Guild, hunted: Boolean, name: String, reasonFlag: String,
                          reasonText: String, commandUser: String): Future[BulkListOutcome] = {
    val lower = name.toLowerCase
    tibiaDataClient.getGuild(lower).map {
      case Right(response) if response.guild.name.nonEmpty =>
        val realName = response.guild.name
        val entry = Guilds(lower, reasonFlag, reasonText, commandUser)
        if (hunted) {
          streamState.modifyHuntedGuildsData(m => m + (guild.getId -> (entry :: m.getOrElse(guild.getId, List()))))
          addHuntedToDatabase(guild, "guild", lower, reasonFlag, reasonText, commandUser)
        } else {
          streamState.modifyAlliedGuildsData(m => m + (guild.getId -> (entry :: m.getOrElse(guild.getId, List()))))
          addAllyToDatabase(guild, "guild", lower, reasonFlag, reasonText, commandUser)
        }
        cacheGuildMembers(guild, realName, response.guild.members.getOrElse(List.empty[Members]))
        BulkListOutcome(added = List(realName))
      case Right(_) => BulkListOutcome(notFound = List(name))
      case Left(_)  => BulkListOutcome(unavailable = List(name))
    }.recover { case NonFatal(_) => BulkListOutcome(unavailable = List(name)) }
  }

  /** A newly hunted guild's roster becomes activity records, the same as the
   *  single-guild add does - that is what makes a member leaving it visible. */
  private def cacheGuildMembers(guild: Guild, guildName: String, members: List[Members]): Unit =
    members.foreach { member =>
      val known = streamState.activityData.getOrElse(guild.getId, List())
      if (!known.exists(_.name == member.name)) {
        val now = ZonedDateTime.now()
        streamState.modifyActivityData(m => m + (guild.getId -> (PlayerCache(member.name, List(""), guildName, now) :: known)))
        addActivityToDatabase(guild, member.name, List(""), guildName, now)
      }
    }

  /** Remove a pasted list. No API call anywhere: what comes off the list is
   *  decided by what is on it, so a name that never existed and a name that was
   *  never added are the same answer - it was not on the list. */
  def removeMany(guild: Guild, hunted: Boolean, kind: String, names: List[String]): BulkListOutcome = {
    if (!checkConfigDatabase(guild)) return BulkListOutcome.empty
    val guildId = guild.getId
    val present = if (kind == "guild") currentGuildNames(guildId, hunted) else currentPlayerNames(guildId, hunted)
    val (found, missing) = names.partition(name => present.contains(name.toLowerCase))

    found.foreach { name =>
      val lower = name.toLowerCase
      if (kind == "guild") {
        if (hunted) {
          streamState.modifyHuntedGuildsData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(_.name.equalsIgnoreCase(lower))))
          removeHuntedFromDatabase(guild, "guild", lower)
        } else {
          streamState.modifyAlliedGuildsData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(_.name.equalsIgnoreCase(lower))))
          removeAllyFromDatabase(guild, "guild", lower)
        }
      } else {
        if (hunted) {
          streamState.modifyHuntedPlayersData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(_.name.equalsIgnoreCase(lower))))
          removeHuntedFromDatabase(guild, "player", lower)
        } else {
          streamState.modifyAlliedPlayersData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(_.name.equalsIgnoreCase(lower))))
          removeAllyFromDatabase(guild, "player", lower)
        }
        streamState.modifyActivityData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(_.name.equalsIgnoreCase(lower))))
        removePlayerActivityfromDatabase(guild, lower)
      }
    }
    // `notFound` rather than a bucket of its own: from the caller's side, "it was
    // not on the list" is the only thing that did not happen.
    BulkListOutcome(added = found, notFound = missing)
  }

  /** One line in the admin channel for a whole batch. */
  def logBulk(guild: Guild, hunted: Boolean, adding: Boolean, actor: String, outcome: BulkListOutcome): Unit =
    if (outcome.changedAnything) {
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      val listName = if (hunted) "hunted" else "allies"
      val verb = if (adding) "added" else "removed"
      val preposition = if (adding) "to" else "from"
      val names = outcome.added
      val shown = names.take(20).map(n => s"**$n**").mkString(", ")
      val more = if (names.sizeIs > 20) s" and ${names.size - 20} more" else ""
      val thumbnail =
        if (hunted) "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Stone_Coffin.gif"
        else "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Angel_Statue.gif"
      AdminLog.post(adminChannel,
        s"${Names.user(actor)} $verb ${names.size} $preposition the $listName list:\n$shown$more.",
        thumbnail)
    }
}
