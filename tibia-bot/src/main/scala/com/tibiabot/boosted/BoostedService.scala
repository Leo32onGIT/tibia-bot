package com.tibiabot.boosted

import com.tibiabot.Config
import com.tibiabot.domain.{BoostedStamp, BoostedName}
import com.tibiabot.persistence.{BoostedRepository, ConnectionProvider}
import com.tibiabot.presentation.{Urls, EmbedText}
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

import scala.collection.mutable.ListBuffer

/**
 * Per-user boosted boss/creature notification subscriptions
 * (the boosted_notifications table) and the /boosted command logic.
 * Moved verbatim from BotApp; name capitalisation is shared via
 * presentation.Names and the private creatureWikiUrl mirrors BotApp's.
 */
final class BoostedService(
  connectionProvider: ConnectionProvider,
  boostedRepository: BoostedRepository,
  boostedBosses: () => List[String]
) {

  def boostedAll(): List[BoostedStamp] = boostedRepository.all()

  def boostedList(userId: String): Boolean =
    boostedRepository.forUser(userId).exists(bs => bs.user == userId && bs.boostedName.toLowerCase == "all")

  private def creatureWikiUrl(creature: String): String =
    Urls.creatureWikiUrl(creature, Config.creatureUrlMappings)

  // User-facing /boosted notification-list status messages, shared so the wording
  // stays consistent. They were duplicated inline ~6x and had already drifted: a
  // misspelling of "bosses" had crept into several copies but not others.
  private def filterListMessage(list: String): String =
    s"${Config.letterEmoji} You will be messaged if any of the following **bosses** or **creatures** are boosted:\n\n$list"
  private val allBoostedMessage: String =
    s"${Config.letterEmoji} You will be notified for **all** boosted **bosses** and **creatures** at *server save*."
  private val emptyListMessage: String =
    s"${Config.letterEmoji} Your notification list is *empty*."

  /** Render a boosted entry's name: a wiki-linked bold name for a boss/creature,
   *  plain bold for the "all" group. */
  private def boostedNameMarkdown(boostedName: String, group: String): String = {
    val name = com.tibiabot.presentation.Names.capitalizeWords(boostedName)
    if (group == "boss" || group == "creature") s"**[$name](${creatureWikiUrl(name)})**"
    else s"**$name**"
  }

  /** Group a user's boosted subscriptions by type, sort each group by name, and
   *  render each as "<emoji> <name-markdown>", newline-joined. */
  private def renderBoostedEntries(entries: List[BoostedStamp]): String =
    entries
      .groupBy(_.boostedType)
      .view.mapValues(_.sortBy(_.boostedName.toLowerCase))
      .toSeq
      .sortBy(_._1)
      .flatMap { case (group, names) =>
        names.map { boosted =>
          val emoji =
            if (group == "boss") Config.bossEmoji
            else if (group == "creature") Config.creatureEmoji
            else Config.indentEmoji
          s"$emoji ${boostedNameMarkdown(boosted.boostedName, group)}"
        }
      }.mkString("\n")

  def boosted(userId: String, boostedOption: String, boostedName: String): MessageEmbed = {
    val conn = connectionProvider.cache()
    try {
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

    val sanitizedName = BoostedName.sanitize(boostedName)
    val existingNames = boostedStampList.toList

    val replyEmbed = new EmbedBuilder()
    replyEmbed.setColor(com.tibiabot.presentation.Embeds.BrandColor)
    if (boostedOption == "list") { // UNFINISHED
      if (existingNames.size > 0) {
        val listSetting = existingNames.exists(bs => bs.user == userId && bs.boostedName.toLowerCase == "all")
        val groupedAndSorted = renderBoostedEntries(existingNames)
        embedMessage = if (listSetting) allBoostedMessage else filterListMessage(groupedAndSorted)
        embedMessage = EmbedText.fit(embedMessage)
      } else {
        embedMessage = emptyListMessage
      }
    } else if (boostedOption == "add"){
      if (sanitizedName != "") {
        if (existingNames.exists(bs => BoostedName.sanitize(bs.boostedName) == sanitizedName)) {
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
            val isBoostedBoss = boostedBosses().exists(_.equalsIgnoreCase(sanitizedName))

            // Check if sanitizedName is a valid creature
            val dreamcourtCheck: Boolean = com.tibiabot.domain.time.DreamScarCycle.isDreamCourtBoss(sanitizedName)
            val creatureCheck: Boolean = if (Config.creaturesList.contains(sanitizedName.toLowerCase)) true else false
            val monsterType = if (isBoostedBoss) "boss" else if (creatureCheck) "creature" else "all"
            if (dreamcourtCheck){
              embedMessage = s"${Config.noEmoji} Dream Court bosses aren't supported yet."
            } else {
              if (monsterType == "all") {
                val groupedAndSorted = renderBoostedEntries(existingNames)
                val listMessage = if (groupedAndSorted.trim != "") filterListMessage(groupedAndSorted) else emptyListMessage
                val commandMessage = s"${Config.noEmoji} **$sanitizedName** is not a valid `boss` or `creature`."
                embedMessage = EmbedText.fit(listMessage, commandMessage)
              } else {
                val query = "INSERT INTO boosted_notifications (userid, name, type) VALUES (?, ?, ?) ON CONFLICT (userid, name) DO NOTHING"
                val preparedStatement = conn.prepareStatement(query)
                preparedStatement.setString(1, userId)
                preparedStatement.setString(2, sanitizedName)
                preparedStatement.setString(3, monsterType)
                preparedStatement.executeUpdate()
                preparedStatement.close()

                val newNames = existingNames :+ BoostedStamp(userId, monsterType, sanitizedName)
                val groupedAndSorted = renderBoostedEntries(newNames)
                val listMessage = if (groupedAndSorted.trim != "") filterListMessage(groupedAndSorted) else allBoostedMessage
                val commandMessage = s"${Config.yesEmoji} **$sanitizedName** was added."
                //WIP
                embedMessage = EmbedText.fit(listMessage, commandMessage)
              }
            }
          }
        }
      } else {
        // Check if sanitizedName exists in boostedBossesList
        val isBoostedBoss = boostedBosses().exists(_.equalsIgnoreCase(sanitizedName))

        // Check if sanitizedName is a valid creature
        val creatureCheck: Boolean = if (Config.creaturesList.contains(sanitizedName.toLowerCase)) true else false
        val monsterType = if (isBoostedBoss) "boss" else if (creatureCheck) "creature" else "all"
        val listSetting = existingNames.exists(bs => bs.user == userId && bs.boostedName.toLowerCase == "all")
        val newNames = existingNames :+ BoostedStamp(userId, monsterType, boostedName)
        val groupedAndSorted = renderBoostedEntries(newNames)
        val listMessage = if (listSetting) allBoostedMessage else filterListMessage(groupedAndSorted)
        val commandMessage = s"${Config.noEmoji} **$sanitizedName** is not a valid `boss` or `creature`."
        embedMessage = EmbedText.fit(listMessage, commandMessage)
      }
    } else if (boostedOption == "remove"){
      val filteredGroupedAndSorted = renderBoostedEntries(existingNames.filterNot(_.boostedName.toLowerCase == sanitizedName))
      if (sanitizedName == "all") {
        val query = "DELETE FROM boosted_notifications WHERE userid = ?"
        val preparedStatement = conn.prepareStatement(query)
        preparedStatement.setString(1, userId)
        preparedStatement.executeUpdate()
        preparedStatement.close()

        embedMessage = s"${Config.yesEmoji} you have disabled notifications for **all** bosses and creatures."
      } else if (existingNames.exists(bs => BoostedName.sanitize(bs.boostedName) == sanitizedName)) {
        val query = "DELETE FROM boosted_notifications WHERE userid = ? AND LOWER(name) = LOWER(?)"
        val preparedStatement = conn.prepareStatement(query)
        preparedStatement.setString(1, userId)
        preparedStatement.setString(2, sanitizedName)
        preparedStatement.executeUpdate()
        preparedStatement.close()

        val listMessage = if (filteredGroupedAndSorted.trim != "") filterListMessage(filteredGroupedAndSorted) else emptyListMessage
        val commandMessage = s"${Config.yesEmoji} you removed **$sanitizedName** from the list."
        embedMessage = EmbedText.fit(listMessage, commandMessage)

      } else {

        val listMessage = if (filteredGroupedAndSorted.trim != "") filterListMessage(filteredGroupedAndSorted) else emptyListMessage
        val commandMessage = s"${Config.noEmoji} **$sanitizedName** is not on your list."
        embedMessage = EmbedText.fit(listMessage, commandMessage)
      }
      //
    } else if (boostedOption == "toggle"){
      val existingSetting = existingNames.exists(bs => bs.user == userId && bs.boostedName.toLowerCase == "all")
      if (existingSetting) {
        val query = "DELETE FROM boosted_notifications WHERE userid = ?"
        val preparedStatement = conn.prepareStatement(query)
        preparedStatement.setString(1, userId)
        preparedStatement.executeUpdate()
        preparedStatement.close()
        // WIP Message
        embedMessage = emptyListMessage
      } else {
        val query = "INSERT INTO boosted_notifications (userid, name, type) VALUES (?, ?, ?) ON CONFLICT (userid, name) DO NOTHING"
        val preparedStatement = conn.prepareStatement(query)
        preparedStatement.setString(1, userId)
        preparedStatement.setString(2, "all")
        preparedStatement.setString(3, "all")
        preparedStatement.executeUpdate()
        preparedStatement.close()
        embedMessage = allBoostedMessage
      }
      //
    } else if (boostedOption == "disable") {
      val query = "DELETE FROM boosted_notifications WHERE userid = ?"
      val preparedStatement = conn.prepareStatement(query)
      preparedStatement.setString(1, userId)
      preparedStatement.executeUpdate()
      preparedStatement.close()

      embedMessage = s"${Config.yesEmoji} you have **disabled** notifications for **all** bosses and creatures."
    }

    replyEmbed.setDescription(embedMessage).build()
    } finally {
      conn.close() // always release the connection, even if a query above threw
    }
  }
}
