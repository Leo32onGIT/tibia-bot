package com.tibiabot.customsort

import com.tibiabot.Config
import com.tibiabot.domain.CustomSort
import com.tibiabot.persistence.CustomSortRepository
import com.tibiabot.presentation.AdminLog
import com.tibiabot.presentation.Embeds.BrandColor
import com.tibiabot.state.StreamState
import com.tibiabot.tibiadata.TibiaApi
import com.tibiabot.tibiadata.response.GuildResponse
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContextExecutor, Future}
import com.tibiabot.presentation.Names

/**
 * Per-guild custom online-list tag categories (the online_list_categories
 * table) — the `/neutral tag add|remove|clear|list` command logic. Extracted
 * verbatim from BotApp (addOnlineListCategory/removeOnlineListCategory/
 * clearOnlineListCategory/listOnlineListCategory).
 *
 * `discordRetrieveConfig`/`checkConfigDatabase` stay owned by BotApp (passed
 * in as callbacks) since many other clusters depend on them too;
 * `fetchPlayerSummary` is [[com.tibiabot.hunted.HuntedAlliedService]]'s, shared
 * the same way.
 */
final class CustomSortService(
  customSortRepository: CustomSortRepository,
  streamState: StreamState,
  tibiaDataClient: TibiaApi,
  fetchPlayerSummary: String => Future[(String, String, String, Int)],
  discordRetrieveConfig: Guild => Map[String, String],
  checkConfigDatabase: Guild => Boolean
)(implicit ex: ExecutionContextExecutor) {

  def guildUrl(guild: String): String = com.tibiabot.presentation.Urls.guildUrl(guild)
  def charUrl(char: String): String = com.tibiabot.presentation.Urls.charUrl(char)

  private def addOnlineListCategoryToDatabase(guild: Guild, guildOrPlayer: String, name: String, label: String, emoji: String): Unit =
    customSortRepository.add(guild.getId, guildOrPlayer, name, label, emoji)

  private def removeOnlineListCategoryFromDatabase(guild: Guild, guildOrPlayer: String, name: String): Unit =
    customSortRepository.removeByNameEntity(guild.getId, guildOrPlayer, name)

  private def clearOnlineListCategoryFromDatabase(guild: Guild, label: String): Unit =
    customSortRepository.removeByLabel(guild.getId, label)

  def addOnlineListCategory(event: SlashCommandInteractionEvent, guildOrPlayer: String, name: String, label: String, emoji: String, callback: MessageEmbed => Unit): Unit = {
    val commandUser = event.getUser.getId
    val nameLower = name.toLowerCase
    val labelCapital = label.capitalize
    val guild = event.getGuild
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(BrandColor)
    var embedText = s"${Config.noEmoji} An error occurred while running the `/online` command"
    if (checkConfigDatabase(guild)) {
      val guildId = guild.getId
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (guildOrPlayer == "guild") {
        val guildCheck: Future[Either[String, GuildResponse]] = tibiaDataClient.getGuild(nameLower)
        guildCheck.map {
          case Right(guildResponse) =>
            val guildName = guildResponse.guild.name
            guildName
          case Left(_) =>
            ""
        }.map { guildName =>
          if (guildName != "") {
            if (!streamState.customSortData.getOrElse(guildId, List()).exists(g => g.entityType == "guild" && g.name.toLowerCase == nameLower)) {

              val emojiDupeOption = streamState.customSortData.getOrElse(guildId, List()).find(g => g.label == labelCapital)
              val emojiDupe = emojiDupeOption.map(_.emoji).getOrElse(emoji)

              streamState.modifyCustomSortData(m => m + (guildId -> (CustomSort(guildOrPlayer, guildName, labelCapital, emojiDupe) :: m.getOrElse(guildId, List()))))
              addOnlineListCategoryToDatabase(guild, guildOrPlayer, guildName, labelCapital, emojiDupe)
              embedText = s":gear: The guild **[$guildName](${guildUrl(guildName)})** has been tagged with: $emojiDupe **$labelCapital** $emojiDupe"

              AdminLog.post(adminChannel, s"${Names.user(event.getUser.getName)} tagged the guild **[$guildName](${guildUrl(guildName)})** with: $emojiDupe **$labelCapital** $emojiDupe", "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Library_Ticket.gif")

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
      } else if (guildOrPlayer == "player") {
        fetchPlayerSummary(nameLower).map { case (playerName, _, vocation, level) =>
          if (playerName != "") {
            if (!streamState.customSortData.getOrElse(guildId, List()).exists(g => g.entityType == "player" && g.name.toLowerCase == nameLower)) {

              val emojiDupeOption = streamState.customSortData.getOrElse(guildId, List()).find(g => g.label == labelCapital)
              val emojiDupe = emojiDupeOption.map(_.emoji).getOrElse(emoji)

              streamState.modifyCustomSortData(m => m + (guildId -> (CustomSort(guildOrPlayer, playerName, labelCapital, emojiDupe) :: m.getOrElse(guildId, List()))))
              addOnlineListCategoryToDatabase(guild, guildOrPlayer, playerName, labelCapital, emojiDupe)
              embedText = s":gear: The player **[$playerName](${charUrl(playerName)})** has been tagged with: $emojiDupe **$labelCapital** $emojiDupe"

              AdminLog.post(adminChannel, s"${Names.user(event.getUser.getName)} tagged the player\n$vocation **$level** — **[$playerName](${charUrl(playerName)})**\nwith: $emojiDupe **$labelCapital** $emojiDupe", "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Library_Ticket.gif")

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

  def removeOnlineListCategory(event: SlashCommandInteractionEvent, guildOrPlayer: String, name: String): MessageEmbed = {
    val commandUser = event.getUser.getId
    val nameLower = name.toLowerCase
    val guild = event.getGuild
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(BrandColor)
    var embedText = s"${Config.noEmoji} An error occurred while running the `/online` command"
    if (checkConfigDatabase(guild)) {
      val guildId = guild.getId
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (guildOrPlayer == "guild") {
        if (streamState.customSortData.getOrElse(guildId, List()).exists(g => g.entityType == "guild" && g.name.toLowerCase == nameLower)) {

          streamState.modifyCustomSortData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(entry => entry.entityType == "guild" && entry.name.equalsIgnoreCase(nameLower))))
          removeOnlineListCategoryFromDatabase(guild, guildOrPlayer, nameLower)

          embedText = s":gear: The guild **$nameLower** had its tag removed."

          AdminLog.post(adminChannel, s"${Names.user(event.getUser.getName)} removed the guild **$nameLower** from custom tagging.", "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Library_Ticket.gif")
        } else {
          embedText = s"${Config.noEmoji} The guild **$nameLower** does not have a tag assigned."

        }
      } else if (guildOrPlayer == "player") {
        if (streamState.customSortData.getOrElse(guildId, List()).exists(g => g.entityType == "player" && g.name.toLowerCase == nameLower)) {

          streamState.modifyCustomSortData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(entry => entry.entityType == "player" && entry.name.equalsIgnoreCase(nameLower))))
          removeOnlineListCategoryFromDatabase(guild, guildOrPlayer, nameLower)

          embedText = s":gear: The player **$nameLower** had its tag removed."

          AdminLog.post(adminChannel, s"${Names.user(event.getUser.getName)} removed the player **$nameLower** from custom tagging.", "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Library_Ticket.gif")
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

  def clearOnlineListCategory(event: SlashCommandInteractionEvent, label: String): MessageEmbed = {
    val commandUser = event.getUser.getId
    val labelLower = label.toLowerCase
    val guild = event.getGuild
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(BrandColor)
    var embedText = s"${Config.noEmoji} An error occurred while running the `/online` command"
    if (checkConfigDatabase(guild)) {
      val guildId = guild.getId
      val discordInfo = discordRetrieveConfig(guild)
      val adminChannel = guild.getTextChannelById(discordInfo("admin_channel"))
      if (streamState.customSortData.getOrElse(guildId, List()).exists(g => g.label.toLowerCase == labelLower)) {

        streamState.modifyCustomSortData(m => m + (guildId -> m.getOrElse(guildId, List()).filterNot(entry => entry.label.equalsIgnoreCase(labelLower))))
        clearOnlineListCategoryFromDatabase(guild, labelLower)

        embedText = s":gear: The tag **$labelLower** has been cleared."

        AdminLog.post(adminChannel, s"${Names.user(event.getUser.getName)} cleared everyone from the tag **$labelLower**.", "https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Library_Ticket.gif")
      } else {
        embedText = s"${Config.noEmoji} The tag **$labelLower** does not exist."

      }
    } else {
      embedText = s"${Config.noEmoji} You need to run `/setup` and add a world first."
    }
    embedBuild.setDescription(embedText)
    embedBuild.build()
  }

  def listOnlineListCategory(event: SlashCommandInteractionEvent): List[MessageEmbed] = {
    val guild = event.getGuild
    val embedBuffer = ListBuffer[MessageEmbed]()

    val guildId = guild.getId
    val guildTags: List[CustomSort] = streamState.customSortData.getOrElse(guildId, List())

    if (guildTags.isEmpty) {
      val interimEmbed = new EmbedBuilder()
      interimEmbed.setDescription(s"${Config.noEmoji} You do not have any custom tags.")
      interimEmbed.setColor(BrandColor)
      embedBuffer += interimEmbed.build()
    } else {
      val groupedTags: Map[(String, String), List[CustomSort]] = guildTags.groupBy(tag => (tag.label, tag.emoji))
      val groupList = ListBuffer[String]()

      val infoEmbed = new EmbedBuilder()
      infoEmbed.setDescription(s":speech_balloon: Tags are for *players* or *guilds* that aren't in your **allies** or **enemies** lists.\n\n- Their deaths will be highlighted **yellow**.\n- If you use the **`/online list combine`** version of the online list they will appear under their own category.")
      infoEmbed.setColor(14397256)
      embedBuffer += infoEmbed.build()

      groupedTags.foreach { case ((label, emoji), tags) =>
        groupList += s"\n$emoji **$label** $emoji"
        tags.foreach { customSort =>
          groupList += s"- ${customSort.name} *(${customSort.entityType})*"
        }
      }

      var field = ""
      groupList.foreach { v =>
        val currentField = field + "\n" + v
        if (currentField.length <= 4096) {
          field = currentField
        } else {
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
}
