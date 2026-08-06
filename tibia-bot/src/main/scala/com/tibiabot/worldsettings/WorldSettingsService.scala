package com.tibiabot.worldsettings

import com.tibiabot.Config
import com.tibiabot.domain.Worlds
import com.tibiabot.persistence.{DiscordConfigRepository, WorldConfigRepository}
import com.tibiabot.presentation.AdminLog
import com.tibiabot.presentation.Embeds.BrandColor
import com.tibiabot.state.StreamState
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.{Guild, MessageEmbed}
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

/**
 * Per-world setting slash commands: auto-hunt detection, deaths/levels
 * visibility, exiva-on-death, minimum level, fullbless level and online-list
 * layout. Extracted from BotApp (detectHunted/deathsLevelsHideShow/exivaList/
 * minLevel/fullblessLevel + the generic updateWorldSetting[T] helper from a
 * prior de-duplication pass).
 *
 * onlineListConfig does its own channel/category mutation through the
 * injected ChannelService rather than the updateWorldSetting[T] helper.
 */
final class WorldSettingsService(
  worldConfigRepository: WorldConfigRepository,
  discordConfigRepository: DiscordConfigRepository,
  streamState: StreamState,
  channelService: com.tibiabot.setup.ChannelService,
  botUser: String
) extends StrictLogging {

  private def discordRetrieveConfig(guild: Guild): Map[String, String] =
    discordConfigRepository.getConfig(guild.getId)

  private def worldRetrieveConfig(guild: Guild, world: String): Map[String, String] =
    worldConfigRepository.retrieveWorld(guild.getId, world)

  private def fullblessLevelToDatabase(guild: Guild, world: String, level: Int): Unit =
    worldConfigRepository.updateWorldInt(guild.getId, world, "fullbless_level", level)

  private def onlineListConfigToDatabase(guild: Guild, world: String, setting: String): Unit =
    worldConfigRepository.updateWorldString(guild.getId, com.tibiabot.domain.WorldName.formal(world), "online_combined", setting)

  private def detectHuntedsToDatabase(guild: Guild, world: String, detectSetting: String): Unit =
    worldConfigRepository.updateWorldString(guild.getId, com.tibiabot.domain.WorldName.formal(world), "detect_hunteds", detectSetting)

  private def exivaListToDatabase(guild: Guild, world: String, detectSetting: String): Unit =
    worldConfigRepository.updateWorldString(guild.getId, com.tibiabot.domain.WorldName.formal(world), "exiva_list", detectSetting)

  private def deathsLevelsHideShowToDatabase(guild: Guild, world: String, setting: String, playerType: String, channelType: String): Unit = {
    val worldFormal = com.tibiabot.domain.WorldName.formal(world)
    val tablePrefix = playerType match {
      case "allies" => "show_allies_"
      case "neutrals" => "show_neutral_"
      case "enemies" => "show_enemies_"
      case _ => ""
    }
    val tableName = s"$tablePrefix$channelType"
    worldConfigRepository.updateWorldString(guild.getId, worldFormal, tableName, setting)
  }

  private def minLevelToDatabase(guild: Guild, world: String, level: Int, levelOrDeath: String): Unit = {
    val columnName = if (levelOrDeath == "levels") "levels_min" else "deaths_min"
    worldConfigRepository.updateWorldInt(guild.getId, world, columnName, level)
  }

  /** Generic guarded update for a single per-world setting stored on `Worlds`.
   *  Returns notConfiguredMessage if the world isn't set up (currentValue
   *  yields None), alreadySetMessage if the value is unchanged, otherwise
   *  updates the in-memory cache, persists, posts an admin-log entry, and
   *  returns nowSetMessage. Used by the toggle-shaped world settings
   *  (auto-hunt detection, deaths/levels visibility, exiva list, minimum
   *  level). */
  private def updateWorldSetting[T](
    guild: Guild,
    world: String,
    newValue: T,
    currentValue: Worlds => Option[T],
    applyValue: (Worlds, T) => Worlds,
    persist: T => Unit,
    alreadySetMessage: String,
    nowSetMessage: String,
    notConfiguredMessage: String,
    adminLogMessage: String,
    adminLogThumbnail: String
  ): MessageEmbed = {
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(BrandColor)
    val cache = streamState.worldsData.getOrElse(guild.getId, List()).filter(w => w.name.toLowerCase() == world.toLowerCase())
    cache.headOption.flatMap(currentValue) match {
      case None =>
        embedBuild.setDescription(notConfiguredMessage)
      case Some(existing) if existing == newValue =>
        embedBuild.setDescription(alreadySetMessage)
      case Some(_) =>
        val modifiedWorlds = streamState.worldsData(guild.getId).map { w =>
          if (w.name.toLowerCase() == world.toLowerCase()) applyValue(w, newValue) else w
        }
        streamState.modifyWorldsData(_ + (guild.getId -> modifiedWorlds))
        persist(newValue)

        val discordConfig = discordRetrieveConfig(guild)
        val adminChannelId = if (discordConfig.nonEmpty) discordConfig("admin_channel") else ""
        val adminChannel: TextChannel = guild.getTextChannelById(adminChannelId)
        AdminLog.post(adminChannel, adminLogMessage, adminLogThumbnail)

        embedBuild.setDescription(nowSetMessage)
    }
    embedBuild.build()
  }

  def detectHunted(event: SlashCommandInteractionEvent): MessageEmbed = {
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val worldOption: String = options.getOrElse("world", "")
    val settingOption: String = options.getOrElse("option", "")
    val worldFormal = com.tibiabot.domain.WorldName.formal(worldOption).trim
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    updateWorldSetting[String](
      guild, worldOption, settingOption,
      currentValue = w => Some(w.detectHunteds),
      applyValue = (w, v) => w.copy(detectHunteds = v),
      persist = v => detectHuntedsToDatabase(guild, worldFormal, v),
      alreadySetMessage = s"${Config.noEmoji} **Automatic enemy detection** is already set to **$settingOption** for the world **$worldFormal**.",
      nowSetMessage = s":gear: **Automatic enemy detection** is now set to **$settingOption** for the world **$worldFormal**.",
      notConfiguredMessage = s"${Config.noEmoji} You need to run `/setup` and add **$worldFormal** before you can configure this setting.",
      adminLogMessage = s"<@$commandUser> set **automatic enemy detection** to **$settingOption** for the world **$worldFormal**.",
      adminLogThumbnail = "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Armillary_Sphere_(TibiaMaps).gif"
    )
  }

  def deathsLevelsHideShow(event: SlashCommandInteractionEvent, world: String, setting: String, playerType: String, channelType: String): MessageEmbed = {
    val worldFormal = com.tibiabot.domain.WorldName.formal(world)
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    val settingType = if (setting == "show") "true" else "false"
    val thumbnailIcon = playerType match {
      case "allies"   => "Angel_Statue"
      case "neutrals" => "Guardian_Statue"
      case "enemies"  => "Stone_Coffin"
      case _          => ""
    }
    updateWorldSetting[String](
      guild, world, settingType,
      currentValue = w => playerType match {
        case "allies" =>
          if (channelType == "deaths") Some(w.showAlliesDeaths)
          else if (channelType == "levels") Some(w.showAlliesLevels)
          else None
        case "neutrals" =>
          if (channelType == "deaths") Some(w.showNeutralDeaths)
          else if (channelType == "levels") Some(w.showNeutralLevels)
          // Only neutrals have an activity setting: the activity channel is
          // about tracked players by definition, so there is no allied or
          // enemy equivalent to turn off.
          else if (channelType == "activity") Some(w.showNeutralActivity)
          else None
        case "enemies" =>
          if (channelType == "deaths") Some(w.showEnemiesDeaths)
          else if (channelType == "levels") Some(w.showEnemiesLevels)
          else None
        case _ => None
      },
      applyValue = (w, v) => playerType match {
        case "allies" =>
          if (channelType == "deaths") w.copy(showAlliesDeaths = v)
          else if (channelType == "levels") w.copy(showAlliesLevels = v)
          else w
        case "neutrals" =>
          if (channelType == "deaths") w.copy(showNeutralDeaths = v)
          else if (channelType == "levels") w.copy(showNeutralLevels = v)
          else if (channelType == "activity") w.copy(showNeutralActivity = v)
          else w
        case "enemies" =>
          if (channelType == "deaths") w.copy(showEnemiesDeaths = v)
          else if (channelType == "levels") w.copy(showEnemiesLevels = v)
          else w
        case _ => w
      },
      persist = v => deathsLevelsHideShowToDatabase(guild, world, v, playerType, channelType),
      alreadySetMessage = s"${Config.noEmoji} The **$channelType** channel is already set to **$setting $playerType** for the world **$worldFormal**.",
      nowSetMessage = s":gear: The **$channelType** channel is now set to **$setting $playerType** for the world **$worldFormal**.",
      notConfiguredMessage = s"${Config.noEmoji} You need to run `/setup` and add **$worldFormal** before you can configure this setting.",
      adminLogMessage = s"<@$commandUser> set the **$channelType** channel to **$setting $playerType** for the world **$worldFormal**.",
      adminLogThumbnail = s"https://www.tibiawiki.com.br/wiki/Special:Redirect/file/$thumbnailIcon.gif"
    )
  }

  def exivaList(event: SlashCommandInteractionEvent): MessageEmbed = {
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option => option.getName.toLowerCase() -> option.getAsString.trim()).toMap
    val worldOption: String = options.getOrElse("world", "")
    val settingOption: String = options.getOrElse("option", "")
    val settingType = if (settingOption == "show") "true" else "false"
    val worldFormal = com.tibiabot.domain.WorldName.formal(worldOption).trim
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    updateWorldSetting[String](
      guild, worldOption, settingType,
      currentValue = w => Some(w.exivaList),
      applyValue = (w, v) => w.copy(exivaList = v),
      persist = v => exivaListToDatabase(guild, worldFormal, v),
      alreadySetMessage = s"${Config.noEmoji} The **exiva list on deaths** is already set to **$settingOption** for the world **$worldFormal**.",
      nowSetMessage = s":gear: **exiva list on deaths** is now set to **$settingOption** for the world **$worldFormal**.",
      notConfiguredMessage = s"${Config.noEmoji} You need to run `/setup` and add **$worldFormal** before you can configure this setting.",
      adminLogMessage = s"<@$commandUser> set **exiva list on deaths** to **$settingOption** for the world **$worldFormal**.",
      adminLogThumbnail = "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Find_Person.gif"
    )
  }

  def minLevel(event: SlashCommandInteractionEvent, world: String, level: Int, levelsOrDeaths: String): MessageEmbed = {
    val worldFormal = com.tibiabot.domain.WorldName.formal(world)
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    updateWorldSetting[Int](
      guild, world, level,
      currentValue = w => Some(if (levelsOrDeaths == "levels") w.levelsMin else w.deathsMin),
      applyValue = (w, v) => if (levelsOrDeaths == "levels") w.copy(levelsMin = v) else w.copy(deathsMin = v),
      persist = v => minLevelToDatabase(guild, worldFormal, v, levelsOrDeaths),
      alreadySetMessage = s"${Config.noEmoji} The minimum level for the **$levelsOrDeaths channel**\nis already set to `$level` for the world **$worldFormal**.",
      nowSetMessage = s":gear: The minimum level for the **$levelsOrDeaths channel**\nis now set to `$level` for the world **$worldFormal**.",
      notConfiguredMessage = s"${Config.noEmoji} You need to run `/setup` and add **$worldFormal** before you can configure this setting.",
      adminLogMessage = s"<@$commandUser> changed the minimum level for the **$levelsOrDeaths channel**\nto `$level` for the world **$worldFormal**.",
      adminLogThumbnail = "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Royal_Fanfare.gif"
    )
  }

  def fullblessLevel(event: SlashCommandInteractionEvent, world: String, level: Int): MessageEmbed = {
    val worldFormal = com.tibiabot.domain.WorldName.formal(world)
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(BrandColor)
    val cache = streamState.worldsData.getOrElse(guild.getId, List()).filter(w => w.name.toLowerCase() == world.toLowerCase())
    val levelSetting = cache.headOption.map(_.fullblessLevel).getOrElse(null)
    if (levelSetting != null) {
      if (levelSetting == level) {
        embedBuild.setDescription(s"${Config.noEmoji} The level to poke for **enemy fullblesses**\nis already set to **$level** for the world **$worldFormal**.")
        embedBuild.build()
      } else {
        val modifiedWorlds = streamState.worldsData(guild.getId).map { w =>
          if (w.name.toLowerCase() == world.toLowerCase()) {
            w.copy(fullblessLevel = level)
          } else {
            w
          }
        }
        streamState.modifyWorldsData(_ + (guild.getId -> modifiedWorlds))
        fullblessLevelToDatabase(guild, worldFormal, level)

        // find and update the existing notification embed to reflect the new level
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

              message.editMessageEmbeds(channelService.fullblessRoleEmbed(worldFormal, fullblessRole, nemesisRole, allyPkRole, masslogRole, level.toString))
                .setComponents(ActionRow.of(channelService.fullblessRoleButtons.asJava))
                .queue()
            }
          }
        }
        AdminLog.post(adminChannel, s"<@$commandUser> changed the level to poke for **enemy fullblesses**\nto **$level** for the world **$worldFormal**.", "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Amulet_of_Loss.gif")

        embedBuild.setDescription(s":gear: The level to poke for **enemy fullblesses**\nis now set to **$level** for the world **$worldFormal**.")
        embedBuild.build()
      }
    } else {
      embedBuild.setDescription(s"${Config.noEmoji} You need to run `/setup` and add **$worldFormal** before you can configure this setting.")
      embedBuild.build()
    }
  }

  def onlineListConfig(event: SlashCommandInteractionEvent, world: String, setting: String): MessageEmbed = {
    val worldFormal = com.tibiabot.domain.WorldName.formal(world)
    val guild = event.getGuild
    val commandUser = event.getUser.getId
    val settingType = if (setting == "combine") "true" else "false"
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(BrandColor)
    val thumbnailIcon = "Blackboard"
    val cache = streamState.worldsData.getOrElse(guild.getId, List()).filter(w => w.name.toLowerCase() == world.toLowerCase())
    val existingSetting = cache.headOption.map(_.onlineCombined)
    if (existingSetting.isDefined) {
      if (existingSetting.get == settingType) {
        embedBuild.setDescription(s"${Config.noEmoji} The online list is already set to **$setting** for the world **$worldFormal**.")
        embedBuild.build()
      } else {

        var disclaimer = ""

        val cache: Option[List[Worlds]] = streamState.worldsData.get(guild.getId) match {
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
              case ex: Throwable => logger.warn(s"Failed to delete Channel ID: '${alliesChannelInfo}' for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}' while combining the online list", ex)
            }
          }

          if (enemiesChannel != null) {
            try {
              enemiesChannel.delete().queue()
              disclaimer += s"\n- *The now unused `enemies` channel has been deleted.*"
            } catch {
              case ex: Throwable => logger.warn(s"Failed to delete Channel ID: '${enemiesChannelInfo}' for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}' while combining the online list", ex)
            }
          }

          if (neutralsChannel != null) {
            try {
              neutralsChannel.delete().queue()
              disclaimer += s"\n- *The now unused `neutrals` channel has been deleted.*"
            } catch {
              case ex: Throwable => logger.warn(s"Failed to delete Channel ID: '${neutralsChannelInfo}' for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}' while combining the online list", ex)
            }
          }

          // now that the separate channels are deleted, create the combined 'online' channel
          try {
            if (category == null) {
              val newCategory = guild.createCategory(worldFormal).complete()
              channelService.grantWorldPerms(newCategory, botRole, publicRole)
              category = newCategory
              channelService.worldRepairConfig(guild, worldFormal, "category", newCategory.getId)

              if (streamState.worldsData.contains(guild.getId)) {
                val worldsList = streamState.worldsData(guild.getId)
                val updatedWorldsList = worldsList.map { world =>
                  if (world.name.toLowerCase == worldFormal.toLowerCase) {
                    world.copy(category = newCategory.getId)
                  } else {
                    world
                  }
                }
                streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
              }
            }
            val recreateAlliesChannel = guild.createTextChannel("📈・ᴏɴʟɪɴᴇ", category).complete()
            channelService.worldRepairConfig(guild, worldFormal, "allies_channel", recreateAlliesChannel.getId)
            if (streamState.worldsData.contains(guild.getId)) {
              val worldsList = streamState.worldsData(guild.getId)
              val updatedWorldsList = worldsList.map { world =>
                if (world.name.toLowerCase == worldFormal.toLowerCase) {
                  world.copy(alliesChannel = recreateAlliesChannel.getId)
                } else {
                  world
                }
              }
              streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
            }
            channelService.grantWorldPerms(recreateAlliesChannel, botRole, publicRole)
            disclaimer += s"\n- *You may want to move the new <#${recreateAlliesChannel.getId}> channel.*"
          } catch {
            case ex: Throwable => logger.warn(s"Failed to create category or online channels for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}' while combining the online list", ex)
          }

        } else {
          // setting == "separate"

          if (event.getChannel.getId == alliesChannelInfo.getOrElse("0")) {
            embedBuild.setDescription(s"${Config.noEmoji} That command would delete this channel, run it somewhere else.")
            return embedBuild.build()
          }

          try {
            if (category == null) {
              val newCategory = guild.createCategory(worldFormal).complete()
              channelService.grantWorldPerms(newCategory, botRole, publicRole)
              category = newCategory
              channelService.worldRepairConfig(guild, worldFormal, "category", newCategory.getId)

              if (streamState.worldsData.contains(guild.getId)) {
                val worldsList = streamState.worldsData(guild.getId)
                val updatedWorldsList = worldsList.map { world =>
                  if (world.name.toLowerCase == worldFormal.toLowerCase) {
                    world.copy(category = newCategory.getId)
                  } else {
                    world
                  }
                }
                streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
              }
            } else {
              try {
                val categoryName = category.getName
                if (categoryName != s"${worldFormal}") {
                  val channelManager = category.getManager
                  channelManager.setName(s"${worldFormal}").queue()
                }
              } catch {
                case ex: Throwable => logger.warn(s"Failed to rename category for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}' while separating the online list", ex)
              }
            }
            val channelList = ListBuffer[(TextChannel, Boolean)]()

            // alliesChannel here is the old combined 'online' channel being replaced
            if (alliesChannel != null) {
              try {
                alliesChannel.delete().queue()
                disclaimer += s"\n- *The now unused `online` channel has been deleted.*"
              } catch {
                case ex: Throwable => logger.warn(s"Failed to delete Channel ID: '${alliesChannelInfo}' for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}' while separating the online list", ex)
              }
            }

            // create the channels underneath the new/existing category
            val recreateAlliesChannel = guild.createTextChannel("🤍・ᴀʟʟɪᴇs", category).complete()
            channelList += ((recreateAlliesChannel, false))
            channelService.worldRepairConfig(guild, worldFormal, "allies_channel", recreateAlliesChannel.getId)
            if (streamState.worldsData.contains(guild.getId)) {
              val worldsList = streamState.worldsData(guild.getId)
              val updatedWorldsList = worldsList.map { world =>
                if (world.name.toLowerCase == worldFormal.toLowerCase) {
                  world.copy(alliesChannel = recreateAlliesChannel.getId)
                } else {
                  world
                }
              }
              streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
            }
            disclaimer += s"\n- *The channel <#${recreateAlliesChannel.getId}> has been recreated (you may want to move it).*"

            if (enemiesChannel == null) {
              val recreateEnemiesChannel = guild.createTextChannel("☠️・ᴇɴᴇᴍɪᴇs", category).complete()
              channelList += ((recreateEnemiesChannel, false))
              channelService.worldRepairConfig(guild, worldFormal, "enemies_channel", recreateEnemiesChannel.getId)
              if (streamState.worldsData.contains(guild.getId)) {
                val worldsList = streamState.worldsData(guild.getId)
                val updatedWorldsList = worldsList.map { world =>
                  if (world.name.toLowerCase == worldFormal.toLowerCase) {
                    world.copy(enemiesChannel = recreateEnemiesChannel.getId)
                  } else {
                    world
                  }
                }
                streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
              }
              disclaimer += s"\n- *The channel <#${recreateEnemiesChannel.getId}> has been recreated (you may want to move it).*"
            }

            if (neutralsChannel == null) {
              val recreateNeutralsChannel = guild.createTextChannel("📈・ɴᴇᴜᴛʀᴀʟs", category).complete()
              channelList += ((recreateNeutralsChannel, false))
              channelService.worldRepairConfig(guild, worldFormal, "neutrals_channel", recreateNeutralsChannel.getId)
              if (streamState.worldsData.contains(guild.getId)) {
                val worldsList = streamState.worldsData(guild.getId)
                val updatedWorldsList = worldsList.map { world =>
                  if (world.name.toLowerCase == worldFormal.toLowerCase) {
                    world.copy(neutralsChannel = recreateNeutralsChannel.getId)
                  } else {
                    world
                  }
                }
                streamState.modifyWorldsData(_ + (guild.getId -> updatedWorldsList))
              }
              disclaimer += s"\n- *The channel <#${recreateNeutralsChannel.getId}> has been recreated (you may want to move it).*"
            }
            if (channelList.nonEmpty) {
              channelList.foreach { case (channel, _) =>
                channelService.grantWorldPerms(channel, botRole, publicRole)
              }
            }
          } catch {
            case ex: Throwable => logger.warn(s"Failed to create category, allies, enemies or neutrals channels for Guild ID: '${guild.getId}' Guild Name: '${guild.getName}' while separating the online list", ex)
          }
        }

        val modifiedWorlds = streamState.worldsData(guild.getId).map { w =>
          if (w.name.toLowerCase() == world.toLowerCase()) {
            w.copy(onlineCombined = settingType)
          } else {
            w
          }
        }

        streamState.modifyWorldsData(_ + (guild.getId -> modifiedWorlds))
        onlineListConfigToDatabase(guild, world, settingType)

        val discordConfig = discordRetrieveConfig(guild)
        val adminChannelId = if (discordConfig.nonEmpty) discordConfig("admin_channel") else ""
        val adminChannel: TextChannel = guild.getTextChannelById(adminChannelId)
        AdminLog.post(adminChannel, s"<@$commandUser> set the online list channel to **$setting** for the world **$worldFormal**.\n$disclaimer", s"https://www.tibiawiki.com.br/wiki/Special:Redirect/file/$thumbnailIcon.gif")

        embedBuild.setDescription(s":gear: The online list channel is now set to **$setting** for the world **$worldFormal**.\n$disclaimer")
        embedBuild.build()
      }
    } else {
      embedBuild.setDescription(s"${Config.noEmoji} You need to run `/setup` and add **$worldFormal** before you can configure this setting.")
      embedBuild.build()
    }
  }
}
