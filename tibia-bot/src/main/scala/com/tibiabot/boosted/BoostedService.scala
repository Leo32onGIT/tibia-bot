package com.tibiabot.boosted

import com.tibiabot.Config
import com.tibiabot.domain.BoostedStamp
import com.tibiabot.persistence.{BoostedRepository, ConnectionProvider}
import com.tibiabot.presentation.Urls
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

import scala.collection.mutable.ListBuffer

/**
 * Per-user boosted boss/creature notification subscriptions
 * (the boosted_notifications table) and the /boosted command logic.
 * Moved verbatim from BotApp; the private helpers mirror the former
 * BotApp.capitalizeAllWords / creatureWikiUrl.
 */
final class BoostedService(
  connectionProvider: ConnectionProvider,
  boostedRepository: BoostedRepository,
  boostedBosses: () => List[String]
) {

  def boostedAll(): List[BoostedStamp] = boostedRepository.all()

  def boostedList(userId: String): Boolean =
    boostedRepository.forUser(userId).exists(bs => bs.user == userId && bs.boostedName.toLowerCase == "all")

  private def capitalizeAllWords(s: String): String = s.split(" ").map(_.capitalize).mkString(" ")

  private def creatureWikiUrl(creature: String): String =
    Urls.creatureWikiUrl(creature, Config.creatureUrlMappings)

  def boosted(userId: String, boostedOption: String, boostedName: String): MessageEmbed = {
    val conn = connectionProvider.cache()
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
            val isBoostedBoss = boostedBosses().exists(_.equalsIgnoreCase(sanitizedName))

            // Check if sanitizedName is a valid creature
            //val boostedCreature: Future[Either[String, RaceResponse]] = tibiaDataClient.getCreature(sanitizedName)

            val dreamcourtCheck: Boolean =  if (List("plagueroot","malofur mangrinder","maxxenius","alptramun","izcandar the banished").contains(sanitizedName.toLowerCase)) true else false
            val creatureCheck: Boolean = if (Config.creaturesList.contains(sanitizedName.toLowerCase)) true else false
            val monsterType = if (isBoostedBoss) "boss" else if (creatureCheck) "creature" else "all"
            if (dreamcourtCheck){
              embedMessage = s"${Config.noEmoji} dreamcourt bosses arn't supported yet."
            } else {
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
        }
      } else {
        // Check if sanitizedName exists in boostedBossesList
        val isBoostedBoss = boostedBosses().exists(_.equalsIgnoreCase(sanitizedName))

        // Check if sanitizedName is a valid creature
        /**
        val boostedCreature: Future[Either[String, RaceResponse]] = tibiaDataClient.getCreature(sanitizedName)
        val creatureCheck: Future[Boolean] = boostedCreature.map {
          case Right(raceResponse) =>
          raceResponse.creature.isDefined
          case Left(errorMessage) => false
        }
        **/
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
}
