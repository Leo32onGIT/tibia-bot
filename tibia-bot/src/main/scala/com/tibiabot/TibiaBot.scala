package com.tibiabot

import akka.actor.Cancellable
import akka.stream.ActorAttributes.supervisionStrategy
import akka.stream.scaladsl.{Flow, RunnableGraph, Sink, Source, Keep}
import akka.stream.{Attributes, Materializer, Supervision}
import com.tibiabot.tibiadata.TibiaDataClient
import com.tibiabot.tibiadata.response.{CharacterResponse, Deaths, WorldResponse, OnlinePlayers}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.TextChannel
import net.dv8tion.jda.api.entities.Channel
import net.dv8tion.jda.api.entities.ChannelType
import net.dv8tion.jda.api.entities.Webhook
import net.dv8tion.jda.api.entities.Guild
import scala.collection.immutable.ListMap
import club.minnced.discord.webhook.WebhookClient
import club.minnced.discord.webhook.send.WebhookMessageBuilder
import scala.util.{Success, Failure}
import java.time.ZonedDateTime
import scala.collection.immutable.HashMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters._
import java.util.Collections

class DeathTrackerStream(guild: Guild, alliesChannel: String, enemiesChannel: String, neutralsChannel: String, levelsChannel: String, deathsChannel: String, adminChannel: String, world: String, fullblessRole: String, nemesisRole: String)(implicit ex: ExecutionContextExecutor, mat: Materializer) extends StrictLogging {

  // A date-based "key" for a character, used to track recent deaths and recent online entries
  case class CharKey(char: String, time: ZonedDateTime)
  case class CurrentOnline(name: String, level: Int, vocation: String, guild: String, time: ZonedDateTime, duration: Long = 0L, flag: String)
  case class CharDeath(char: CharacterResponse, death: Deaths)
  case class CharLevel(name: String, level: Int, vocation: String, lastLogin: ZonedDateTime, time: ZonedDateTime)

  val guildId = guild.getId()

  private val recentDeaths = mutable.Set.empty[CharKey]
  private val recentLevels = mutable.Set.empty[CharLevel]
  private val recentOnline = mutable.Set.empty[CharKey]
  private val currentOnline = mutable.Set.empty[CurrentOnline]

  var onlineListTimer = ZonedDateTime.parse("2022-01-01T01:00:00Z")
  var alliesListPurgeTimer = ZonedDateTime.parse("2022-01-01T01:00:00Z")
  var enemiesListPurgeTimer = ZonedDateTime.parse("2022-01-01T01:00:00Z")
  var neutralsListPurgeTimer = ZonedDateTime.parse("2022-01-01T01:00:00Z")

  private val tibiaDataClient = new TibiaDataClient()

  private val deathRecentDuration = 30 * 60 // 30 minutes for a death to count as recent enough to be worth notifying
  private val onlineRecentDuration = 10 * 60 // 10 minutes for a character to still be checked for deaths after logging off
  private val recentLevelExpiry = 25 * 60 * 60 // 25 hours before deleting recentLevel entry

  private val logAndResumeDecider: Supervision.Decider = { e =>
    logger.error("An exception has occurred in the DeathTrackerStream:", e)
    Supervision.Resume
  }

  private val logAndResume: Attributes = supervisionStrategy(logAndResumeDecider)
  private lazy val sourceTick = Source.tick(2.seconds, 20.seconds, ()) // im kinda cow-boying it here
  private lazy val getWorld = Flow[Unit].mapAsync(1) { _ =>
    logger.info(s"Running stream for Guild: '${guild.getName()}' Id: '${guild.getId()}' World: '$world'")
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

    val charsToCheck: Set[String] = recentOnline.map(_.char).toSet
    Source(charsToCheck).mapAsyncUnordered(16)(tibiaDataClient.getCharacter).runWith(Sink.collection).map(_.toSet)
  }.withAttributes(logAndResume)

  private lazy val scanForDeaths = Flow[Set[CharacterResponse]].mapAsync(1) { characterResponses =>
    val now = ZonedDateTime.now()

    // gather guild icons data for online player list
    val newDeaths = characterResponses.flatMap { char =>
      val charName = char.characters.character.name
      val guildName = char.characters.character.guild.headOption.map(_.name).getOrElse("")
      val allyGuildCheck = BotApp.alliedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == guildName.toLowerCase())
      val huntedGuildCheck = BotApp.huntedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == guildName.toLowerCase())
      val allyPlayerCheck = BotApp.alliedPlayersData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == charName.toLowerCase())
      val huntedPlayerCheck = BotApp.huntedPlayersData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == charName.toLowerCase())
      val guildIcon = (guildName, allyGuildCheck, huntedGuildCheck, allyPlayerCheck, huntedPlayerCheck) match {
        case (_, true, _, _, _) => Config.allyGuild // allied-guilds
        case (_, _, true, _, _) => Config.enemyGuild // hunted-guilds
        case (_, _, _, true, _) => Config.allyGuild // allied-players
        case (_, _, _, _, true) => Config.enemy // hunted-players
        case ("", _, _, _, _) => "" //Config.noGuild // no guild (not ally or hunted)
        case _ => Config.otherGuild // guild (not ally or hunted)
      }
      currentOnline.find(_.name == charName).foreach { onlinePlayer =>
        currentOnline -= onlinePlayer
        currentOnline += onlinePlayer.copy(guild = guildIcon)
      }
      // detecting new levels
      val deaths: List[Deaths] = char.characters.deaths.getOrElse(List.empty)
      val sheetLevel = char.characters.character.level
      val sheetVocation = char.characters.character.vocation
      val sheetLastLogin = ZonedDateTime.parse(char.characters.character.last_login.getOrElse("2022-01-01T01:00:00Z"))
      var recentlyDied = false
      if (deaths.nonEmpty){
        val mostRecentDeath = deaths.maxBy(death => ZonedDateTime.parse(death.time))
        val mostRecentDeathTime = ZonedDateTime.parse(mostRecentDeath.time)
        val mostRecentDeathAge = java.time.Duration.between(mostRecentDeathTime, now).getSeconds
        if (mostRecentDeathAge <= 600){
          recentlyDied = true
        }
      }
      if (!(recentlyDied)) {
        currentOnline.find(_.name == charName).foreach { onlinePlayer =>
          if (onlinePlayer.level > sheetLevel){
            val newCharLevel = CharLevel(charName, onlinePlayer.level, sheetVocation, sheetLastLogin, now)
            val webhookMessage = s"${vocEmoji(char)} **[$charName](${charUrl(charName)})** advanced to level **${onlinePlayer.level}** ${guildIcon}"
            val levelsTextChannel = guild.getTextChannelById(levelsChannel)
            if (levelsTextChannel != null){
              // check show_neutrals_levels setting
              val worldData = BotApp.worldsData.getOrElse(guildId, List()).filter(w => w.name.toLowerCase() == world.toLowerCase())
              val showNeutralLevels = worldData.headOption.map(_.showNeutralLevels).getOrElse("true")
              val showAlliesLevels = worldData.headOption.map(_.showAlliesLevels).getOrElse("true")
              val showEnemiesLevels = worldData.headOption.map(_.showEnemiesLevels).getOrElse("true")
              val enemyIcons = List(Config.enemy, Config.enemyGuild)
              val alliesIcons = List(Config.allyGuild)
              val neutralIcons = List(Config.otherGuild, "")
              // don't post level if showNeutrals is set to false and its a neutral level
              val levelsCheck =
                if (showNeutralLevels == "false" && neutralIcons.contains(guildIcon)) {
                  false
                } else if (showAlliesLevels == "false" && alliesIcons.contains(guildIcon)){
                  false
                } else if (showEnemiesLevels == "false" && enemyIcons.contains(guildIcon)){
                  false
                } else {
                  true
                }
              if (recentLevels.exists(x => x.name == charName && x.level == onlinePlayer.level)){
                val lastLoginInRecentLevels = recentLevels.filter(x => x.name == charName && x.level == onlinePlayer.level)
                if (lastLoginInRecentLevels.forall(x => x.lastLogin.isBefore(sheetLastLogin))){
                  recentLevels += newCharLevel
                  if (levelsCheck) {
                    createAndSendWebhookMessage(levelsTextChannel, webhookMessage, s"${world.capitalize}")
                  }
                  // add flag to onlineList if player has leveled
                  currentOnline.find(_.name == charName).foreach { onlinePlayer =>
                    currentOnline -= onlinePlayer
                    currentOnline += onlinePlayer.copy(flag = Config.levelUpEmoji)
                  }
                }
              } else {
                recentLevels += newCharLevel
                if (levelsCheck) {
                  createAndSendWebhookMessage(levelsTextChannel, webhookMessage, s"${world.capitalize}")
                }
                // add flag to onlineList if player has leveled
                currentOnline.find(_.name == charName).foreach { onlinePlayer =>
                  currentOnline -= onlinePlayer
                  currentOnline += onlinePlayer.copy(flag = Config.levelUpEmoji)
                }
              }
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
          Some(CharDeath(char, death))
        }
        else None
      }

    }
    // update online list every 5 minutes
    if (ZonedDateTime.now().isAfter(onlineListTimer.plusMinutes(5))) {
      val currentOnlineList: List[(String, Int, String, String, ZonedDateTime, Long, String)] = currentOnline.map { onlinePlayer =>
        (onlinePlayer.name, onlinePlayer.level, onlinePlayer.vocation, onlinePlayer.guild, onlinePlayer.time, onlinePlayer.duration, onlinePlayer.flag)
      }.toList
      // did the online list api call fail?
      if (currentOnlineList.size > 1){
        onlineListTimer = ZonedDateTime.now()
        onlineList(currentOnlineList)
      }
    }
    Future.successful(newDeaths)
  }.withAttributes(logAndResume)

  private lazy val postToDiscordAndCleanUp = Flow[Set[CharDeath]].mapAsync(1) { charDeaths =>
    val deathsTextChannel = guild.getTextChannelById(deathsChannel)
    if (deathsTextChannel != null){
      val worldData = BotApp.worldsData.getOrElse(guildId, List()).filter(w => w.name.toLowerCase() == world.toLowerCase())
      val embeds = charDeaths.toList.sortBy(_.death.time).map { charDeath =>
        var notablePoke = ""
        val charName = charDeath.char.characters.character.name
        val killer = charDeath.death.killers.last.name
        var context = "Died"
        var embedColor = 3092790 // background default
        var embedThumbnail = creatureImageUrl(killer)
        var vowelCheck = "" // this is for adding "an" or "a" in front of creature names
        var killerBuffer = ListBuffer[String]()
        var exivaBuffer = ListBuffer[String]()
        var exivaList = ""
        val killerList = charDeath.death.killers // get all killers

        // guild rank and name
        val guildName = charDeath.char.characters.character.guild.headOption.map(_.name).getOrElse("")
        val guildRank = charDeath.char.characters.character.guild.headOption.map(_.rank).getOrElse("")
        //var guildText = ":x: **No Guild**\n"
        var guildText = ""

        // guild
        // does player have guild?
        var guildIcon = Config.otherGuild
        if (guildName != "") {
          // if untracked neutral guild show grey
          if (embedColor == 3092790){
            embedColor = 4540237
          }
          // is player an ally
          val allyGuilds = BotApp.alliedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == guildName.toLowerCase())
          if (allyGuilds == true){
            embedColor = 13773097 // bright red
            guildIcon = Config.allyGuild
          }
          // is player in hunted guild
          val huntedGuilds = BotApp.huntedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == guildName.toLowerCase())
          if (huntedGuilds == true){
            embedColor = 36941 // bright green
            if (context == "Died") {
              notablePoke = "fullbless" // PVE fullbless opportuniy (only poke for level 400+)
            }
          }
          guildText = s"$guildIcon *$guildRank* of the [$guildName](https://www.tibia.com/community/?subtopic=guilds&page=view&GuildName=${guildName.replace(" ", "%20")})\n"
        }

        // player
        // ally player
        val allyPlayers = BotApp.alliedPlayersData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == charName.toLowerCase())
        if (allyPlayers == true){
          embedColor = 13773097 // bright red
        }
        // hunted player
        val huntedPlayers = BotApp.huntedPlayersData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == charName.toLowerCase())
        if (huntedPlayers == true){
          embedColor = 36941 // bright green
          if (context == "Died") {
            notablePoke = "fullbless" // PVE fullbless opportuniy
          }
        }

        // poke if killer is in notable-creatures config
        val poke = Config.notableCreatures.contains(killer.toLowerCase())
        if (poke == true) {
          notablePoke = "nemesis"
          embedColor = 11563775 // bright purple
        }

        if (killerList.nonEmpty) {
          killerList.foreach { k =>
            if (k.player == true) {
              if (k.name != charName){ // ignore 'self' entries on deathlist
                context = "Killed"
                notablePoke = "" // reset poke as its not a fullbless
                if (embedColor == 3092790 || embedColor == 4540237){
                  embedColor = 14869218 // bone white
                }
                embedThumbnail = creatureImageUrl("Phantasmal_Ooze")
                val isSummon = k.name.split(" of ", 2) // e.g: fire elemental of Violent Beams
                if (isSummon.length > 1){
                  if (isSummon(0).exists(_.isUpper) == false) { // summons will be lowercase, a player with " of " in their name will have a capital letter
                    val vowel = isSummon(0).take(1) match {
                    case "a" => "an"
                    case "e" => "an"
                    case "i" => "an"
                    case "o" => "an"
                    case "u" => "an"
                    case _ => "a"
                    }
                    killerBuffer += s"$vowel ${Config.summonEmoji} **${isSummon(0)} of [${isSummon(1)}](${charUrl(isSummon(1))})**"
                    if (guildIcon == Config.allyGuild) {
                      exivaBuffer += isSummon(1)
                    }
                  } else {
                    killerBuffer += s"**[${k.name}](${charUrl(k.name)})**" // player with " of " in the name e.g: Knight of Flame
                    if (guildIcon == Config.allyGuild) {
                      exivaBuffer += k.name
                    }
                  }
                } else {
                  killerBuffer += s"**[${k.name}](${charUrl(k.name)})**" // summon not detected
                  if (guildIcon == Config.allyGuild) {
                    exivaBuffer += k.name
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
                Config.kilmareshBosses -> Config.kilmareshEmoji
              )
              // assign the appropriate emoji
              val bossIcon = creatureEmojis.find {
                case (creatures, emoji) => creatures.contains(k.name.toLowerCase())
              }.map(_._2).getOrElse("")

              // add "an" or "a" depending on first letter of creatures name
              // ignore capitalized names (nouns) as they are bosses
              // if player dies to a neutral source show 'died by energy' instead of 'died by an energy'
              if (!(k.name.exists(_.isUpper))){
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
            if (i == 0){
              exivaList += s"""\n${Config.exivaEmoji} `exiva "$exiva"`""" // add exiva emoji
            } else {
              exivaList += s"""\n${Config.indentEmoji} `exiva "$exiva"`""" // just use indent emoji for further player names
            }
          }

          // see if detectHunted is toggled on or off
          val detectHunteds = worldData.headOption.map(_.detectHunteds).getOrElse("on")
          if (detectHunteds == "on"){
            // scan exiva list for enemies to be added to hunted
            val exivaBufferFlow = Source(exivaBuffer.toSet).mapAsyncUnordered(16)(tibiaDataClient.getCharacter).toMat(Sink.seq)(Keep.right)
            val futureResults: Future[Seq[CharacterResponse]] = exivaBufferFlow.run()
            futureResults.onComplete {
              case Success(output) => {
                var huntedBuffer = ListBuffer[(String, String, String, Int)]()
                output.foreach { charResponse =>
                  val killerName = charResponse.characters.character.name
                  val killerGuild = charResponse.characters.character.guild
                  val killerWorld = charResponse.characters.character.world
                  val killerVocation = vocEmoji(charResponse)
                  val killerLevel = charResponse.characters.character.level.toInt
                  val killerGuildName = if(!(killerGuild.isEmpty)) killerGuild.head.name else ""
                  var guildCheck = true
                  if (killerGuildName != ""){
                    if (BotApp.alliedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == killerGuildName.toLowerCase()) || BotApp.huntedGuildsData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == killerGuildName.toLowerCase())){
                      guildCheck = false // player guild is already ally/hunted
                    }
                  }
                  if (guildCheck == true){ // player is not in a guild or is in a guild that is not tracked
                    if (BotApp.alliedPlayersData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == killerName.toLowerCase()) || BotApp.huntedPlayersData.getOrElse(guildId, List()).exists(_.name.toLowerCase() == killerName.toLowerCase())){
                      // char is already on ally/hunted lis
                    } else {
                      // char is not on hunted list
                      if (!huntedBuffer.exists(_._1 == killerName)){
                        // add them to hunted list
                        huntedBuffer += ((killerName, killerWorld, killerVocation, killerLevel))
                      }
                    }
                  }
                }

                // process the new batch of players to add to hunted list
                if (huntedBuffer.nonEmpty){
                  val adminTextChannel = guild.getTextChannelById(adminChannel)
                  if (adminTextChannel != null){
                    huntedBuffer.foreach { case (player, world, vocation, level) =>
                      val playerString = player.toLowerCase()
                      // add them to cached huntedPlayersData list
                      BotApp.huntedPlayersData = BotApp.huntedPlayersData + (guildId -> (BotApp.Players(playerString, "false", "killed an allied player", BotApp.botUser) :: BotApp.huntedPlayersData.getOrElse(guildId, List())))
                      // add them to the database
                      BotApp.addHuntedToDatabase(guild, "player", playerString, "false", "killed an allied player", BotApp.botUser)
                      // send embed to admin channel
                      val commandUser = s"<@${BotApp.botUser}>"
                      val adminEmbed = new EmbedBuilder()
                      adminEmbed.setTitle(":robot: enemy automatically detected:")
                      adminEmbed.setDescription(s"$commandUser added the player\n$vocation $level — **[$player](${charUrl(player)})**\nto the hunted list for **$world**.")
                      adminEmbed.setThumbnail(creatureImageUrl("Stone_Coffin"))
                      adminEmbed.setColor(14397256) // orange for bot auto command
                      adminTextChannel.sendMessageEmbeds(adminEmbed.build()).queue()
                    }
                  }
                }
              }
              case Failure(e) => e.printStackTrace
            }
          }
        }

        // convert formatted killer list to one string
        val killerInit = if (killerBuffer.nonEmpty) killerBuffer.view.init else None
        var killerText =
          if (killerInit.nonEmpty) {
            killerInit.mkString(", ") + " and " + killerBuffer.last
          } else killerBuffer.headOption.getOrElse("")

        // this should only occur to pure suicides on bomb runes, or pure 'assists' deaths in yellow-skull friendy fire or retro/hardcore situations
        if (killerText == ""){
            embedThumbnail = creatureImageUrl("Red_Skull_(Item)")
            killerText = s"""`suicide`"""
        }

        val epochSecond = ZonedDateTime.parse(charDeath.death.time).toEpochSecond

        // this is the actual embed description
        var embedText = s"$guildText$context <t:$epochSecond:R> at level ${charDeath.death.level.toInt}\nby $killerText.$exivaList"

        // if the length is over 4096, start cutting exivas
        var embedLength = embedText.length
        while (embedLength > 4096) {
          val lastNewlineIndex = embedText.lastIndexOf("/n")
          if (lastNewlineIndex >= 0) {
            embedText = embedText.substring(0, lastNewlineIndex)
          }
          embedLength = embedText.length
        }

        val showNeutralDeaths = worldData.headOption.map(_.showNeutralDeaths).getOrElse("true")
        val showAlliesDeaths = worldData.headOption.map(_.showAlliesDeaths).getOrElse("true")
        val showEnemiesDeaths = worldData.headOption.map(_.showEnemiesDeaths).getOrElse("true")
        var embedCheck = true
        if (embedColor == 3092790 || embedColor == 14869218 || embedColor == 4540237){
          if(showNeutralDeaths == "false"){
            embedCheck = false
          }
        } else if (embedColor == 36941){
          if(showEnemiesDeaths == "false"){
            embedCheck = false
          }
        } else if (embedColor == 13773097){
          if(showAlliesDeaths == "false"){
            embedCheck = false
          }
        }
        val embed = new EmbedBuilder()
        embed.setTitle(s"${vocEmoji(charDeath.char)} $charName ${vocEmoji(charDeath.char)}", charUrl(charName))
        embed.setDescription(embedText)
        embed.setThumbnail(embedThumbnail)
        embed.setColor(embedColor)

        // return embed + poke
        (embed, notablePoke, charName, embedText, charDeath.death.level.toInt, embedCheck)
      }
      val fullblessLevel = worldData.headOption.map(_.fullblessLevel).getOrElse(250)
      // Send the embeds one at a time, otherwise some don't get sent if sending a lot at once
      embeds.foreach { embed =>
        if (embed._6){ // if showNeutralDeaths == "true"
          if (embed._2 == "nemesis"){
            deathsTextChannel.sendMessage(s"<@&$nemesisRole>").setEmbeds(embed._1.build()).queue()
          } else if (embed._2 == "fullbless"){
            // send adjusted embed for fullblesses
            val adjustedMessage = embed._4 + s"""\n${Config.exivaEmoji} `exiva "${embed._3}"`"""
            val adjustedEmbed = embed._1.setDescription(adjustedMessage)
            if (embed._5 >= fullblessLevel) { // only poke for 250+
              deathsTextChannel.sendMessage(s"<@&$fullblessRole>").setEmbeds(adjustedEmbed.build()).queue();
            } else {
              deathsTextChannel.sendMessageEmbeds(adjustedEmbed.build()).queue();
            }
          } else {
            deathsTextChannel.sendMessageEmbeds(embed._1.build()).queue()
          }
        }
      }
    }
    /***
    if (notablePoke != ""){
      deathsChannel.sendMessage(notablePoke).queue();
    }
    ***/

    cleanUp()

    Future.successful()
  }.withAttributes(logAndResume)

  private def onlineList(onlineData: List[(String, Int, String, String, ZonedDateTime, Long, String)]) {

    val vocationBuffers = ListMap(
      "druid" -> ListBuffer[(String, String)](),
      "knight" -> ListBuffer[(String, String)](),
      "paladin" -> ListBuffer[(String, String)](),
      "sorcerer" -> ListBuffer[(String, String)](),
      "none" -> ListBuffer[(String, String)]()
    )

    val sortedList = onlineData.sortWith(_._2 > _._2)
    sortedList.foreach { player =>
      val voc = player._3.toLowerCase.split(' ').last
      val vocEmoji = voc match {
        case "knight" => ":shield:"
        case "druid" => ":snowflake:"
        case "sorcerer" => ":fire:"
        case "paladin" => ":bow_and_arrow:"
        case "none" => ":hatching_chick:"
        case _ => ""
      }
      //vocationBuffers(voc) += ((s"${player._4}", s"${player._4} **[${player._1}](${charUrl(player._1)})** — Level ${player._2.toString} $vocEmoji"))
      val durationInSec = player._6
      val durationInMin = durationInSec / 60
      val durationStr = if (durationInMin >= 60) {
        val hours = durationInMin / 60
        val mins = durationInMin % 60
        s"${hours}hr ${mins}min"
      } else {
        s"${durationInMin}min"
      }
      //val durationString = if (player._4 == Config.allyGuild || player._4 == Config.enemyGuild || player._4 == Config.enemy) s"— `${durationStr}`" else ""
      val durationString = s"| `${durationStr}`"
      vocationBuffers(voc) += ((s"${player._4}", s"$vocEmoji ${player._2.toString} — **[${player._1}](${charUrl(player._1)})** ${player._4} ${durationString} ${player._7}"))
    }

    val alliesList: List[String] = vocationBuffers.values.flatMap(_.filter(_._1 == s"${Config.allyGuild}").map(_._2)).toList
    //val neutralsList: List[String] = vocationBuffers.values.flatMap(_.filter { case (first, _) => first == s"${Config.otherGuild}" || first == s"${Config.noGuild}" }.map(_._2)).toList
    val neutralsList: List[String] = vocationBuffers.values.flatMap(_.filter { case (first, _) => first == s"${Config.otherGuild}" || first == "" }.map(_._2)).toList
    val enemiesList: List[String] = vocationBuffers.values.flatMap(_.filter { case (first, _) => first == s"${Config.enemyGuild}" || first == s"${Config.enemy}" }.map(_._2)).toList

    val alliesCount = alliesList.size
    val neutralsCount = neutralsList.size
    val enemiesCount = enemiesList.size

    val pattern = "(.*)-(.*)$".r

    // run channel checks before updating the channels
    val alliesTextChannel = guild.getTextChannelById(alliesChannel)
    if (alliesTextChannel != null){
      // allow for custom channel names
      val channelName = alliesTextChannel.getName()
      val extractName = pattern.findFirstMatchIn(channelName)
      val customName = if (extractName.isDefined) {
        val m = extractName.get
        m.group(1)
      } else "allies"
      if (channelName != s"$customName-$alliesCount") {
        val channelManager = alliesTextChannel.getManager
        channelManager.setName(s"$customName-$alliesCount").queue()
      }
      if (alliesList.nonEmpty){
        updateMultiFields(alliesList, alliesTextChannel, "allies")
      } else {
        updateMultiFields(List("No allies are online right now."), alliesTextChannel, "allies")
      }
    }
    val neutralsTextChannel = guild.getTextChannelById(neutralsChannel)
    if (neutralsTextChannel != null){
      // allow for custom channel names
      val channelName = neutralsTextChannel.getName()
      val extractName = pattern.findFirstMatchIn(channelName)
      val customName = if (extractName.isDefined) {
        val m = extractName.get
        m.group(1)
      } else "neutrals"
      if (channelName != s"$customName-$neutralsCount") {
        val channelManager = neutralsTextChannel.getManager
        channelManager.setName(s"$customName-$neutralsCount").queue()
      }
      if (neutralsList.nonEmpty){
        updateMultiFields(neutralsList, neutralsTextChannel, "neutrals")
      } else {
        updateMultiFields(List("No neutrals are online right now."), neutralsTextChannel, "neutrals")
      }
    }
    val enemiesTextChannel = guild.getTextChannelById(enemiesChannel)
    if (enemiesTextChannel != null){
      // allow for custom channel names
      val channelName = enemiesTextChannel.getName()
      val extractName = pattern.findFirstMatchIn(channelName)
      val customName = if (extractName.isDefined) {
        val m = extractName.get
        m.group(1)
      } else "enemies"
      if (channelName != s"$customName-$enemiesCount") {
        val channelManager = enemiesTextChannel.getManager
        channelManager.setName(s"$customName-$enemiesCount").queue()
      }
      if (enemiesList.nonEmpty){
        updateMultiFields(enemiesList, enemiesTextChannel, "enemies")
      } else {
        updateMultiFields(List("No enemies are online right now."), enemiesTextChannel, "enemies")
      }
    }
  }

  def updateMultiFields(values: List[String], channel: TextChannel, purgeType: String): Unit = {
    var field = ""
    val embedColor = 3092790
    //get messages
    var messages = scala.collection.JavaConverters.seqAsJavaListConverter(channel.getHistory.retrievePast(100).complete().asScala.filter(m => m.getAuthor().getId().equals(BotApp.botUser)).toList.reverse).asJava

    // clear the channel every 6 hours
    if (purgeType == "allies"){
      if (ZonedDateTime.now().isAfter(alliesListPurgeTimer.plusHours(6))) {
        channel.purgeMessages(messages)
        alliesListPurgeTimer = ZonedDateTime.now()
        messages = List.empty.asJava
      }
    } else if (purgeType == "enemies"){
      if (ZonedDateTime.now().isAfter(enemiesListPurgeTimer.plusHours(6))) {
        channel.purgeMessages(messages)
        enemiesListPurgeTimer = ZonedDateTime.now()
        messages = List.empty.asJava
      }
    } else if (purgeType == "neutrals"){
      if (ZonedDateTime.now().isAfter(neutralsListPurgeTimer.plusHours(6))) {
        channel.purgeMessages(messages)
        neutralsListPurgeTimer = ZonedDateTime.now()
        messages = List.empty.asJava
      }
    }

    var currentMessage = 0
    values.foreach { v =>
      val currentField = field + "\n" + v
      if (currentField.length <= 4096) { // don't add field yet, there is still room
        field = currentField
      }
      else { // it's full, add the field
        val interimEmbed = new EmbedBuilder()
        interimEmbed.setDescription(field)
        interimEmbed.setColor(embedColor)
        if (currentMessage < messages.size) {
          // edit the existing message
          messages.get(currentMessage).editMessageEmbeds(interimEmbed.build()).queue()
        }
        else {
          // there isn't an existing message to edit, so post a new one
          channel.sendMessageEmbeds(interimEmbed.build()).queue()
        }
        field = v
        currentMessage += 1
      }
    }
    val finalEmbed = new EmbedBuilder()
    finalEmbed.setDescription(field)
    finalEmbed.setColor(embedColor)
    if (currentMessage < messages.size) {
      // edit the existing message
      messages.get(currentMessage).editMessageEmbeds(finalEmbed.build()).queue()
    }
    else {
      // there isn't an existing message to edit, so post a new one
      channel.sendMessageEmbeds(finalEmbed.build()).queue()
    }
    if (currentMessage < messages.size - 1) {
      // delete extra messages
      val messagesToDelete = messages.subList(currentMessage + 1, messages.size)
      channel.purgeMessages(messagesToDelete)
    }
  }

  // send a webhook to discord (this is used as we can have hyperlinks in Text Messages)
  def createAndSendWebhookMessage(webhookChannel: TextChannel, messageContent: String, messageAuthor: String): Unit = {
    val getWebHook = webhookChannel.retrieveWebhooks().submit().get()
    var webhook: Webhook = null
    if (getWebHook.isEmpty) {
        val createWebhook = webhookChannel.createWebhook(messageAuthor).submit()
        webhook = createWebhook.get()
    } else {
        webhook = getWebHook.get(0)
    }
    val webhookUrl = webhook.getUrl()
    val client = WebhookClient.withUrl(webhookUrl)
    val message = new WebhookMessageBuilder()
      .setUsername(messageAuthor)
      .setContent(messageContent)
      .setAvatarUrl(Config.webHookAvatar)
      .build()
    client.send(message)
    client.close()
  }

  // Remove players from the list who haven't logged in for a while. Remove old saved deaths.
  private def cleanUp(): Unit = {
    val now = ZonedDateTime.now()
    recentOnline.filterInPlace { i =>
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

  private def vocEmoji(char: CharacterResponse): String = {
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
