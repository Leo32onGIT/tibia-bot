package com.tibiabot.galthen

import com.tibiabot.discord.DiscordGateway
import com.tibiabot.domain.SatchelStamp
import com.tibiabot.domain.time.SatchelCooldown
import com.tibiabot.persistence.{ConnectionProvider, GalthenRepository}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.components.buttons.Button

import java.sql.Timestamp
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Galthen's Satchel cooldown tracking: CRUD over the satchel table plus the
 * daily expiry DM. Extracted from BotApp verbatim; CRUD delegates to the
 * repository, [[cleanExpired]] runs the notify-then-delete job.
 */
final class GalthenService(
  repository: GalthenRepository,
  connectionProvider: ConnectionProvider,
  discordGateway: DiscordGateway
) extends StrictLogging {

  def getStamps(userId: String): Option[List[SatchelStamp]] = repository.getStamps(userId)
  def add(user: String, when: ZonedDateTime, tag: String): Unit = repository.add(user, when, tag)
  def del(user: String, tag: String): Unit = repository.del(user, tag)
  def delAll(user: String): Unit = repository.delAll(user)

  /** DM each user whose 30-day satchel cooldown has expired, then delete those rows. */
  def cleanExpired(): Unit = {
    val conn = connectionProvider.cache()
    try {

    // Retrieve the data before deletion
    val selectStatement = conn.prepareStatement("SELECT userid,time,tag FROM satchel WHERE time < ?;")
    selectStatement.setTimestamp(1, Timestamp.from(ZonedDateTime.now().minus(SatchelCooldown.durationDays, ChronoUnit.DAYS).toInstant))
    val resultSet = selectStatement.executeQuery()

    // Retrieve the data from the result set
    while (resultSet.next()) {
      val userId = resultSet.getString("userid")
      val tagId = Option(resultSet.getString("tag")).getOrElse("")
      val user: User = discordGateway.retrieveUser(userId)
      val userTimeStamp = resultSet.getTimestamp("time").toInstant()
      val cooldown = userTimeStamp.plus(SatchelCooldown.durationDays, ChronoUnit.DAYS).getEpochSecond.toString()

      if (user != null) {
        try {
          user.openPrivateChannel().queue { privateChannel =>
            val embed = new EmbedBuilder()
            if (tagId.nonEmpty) embed.setFooter(s"Tag: ${tagId.toLowerCase}")
            val displayTag = if (tagId.nonEmpty) s"**`$tagId`**" else s"<@$userId>"
            embed.setColor(178877)
            embed.setThumbnail("https://www.tibiawiki.com.br/wiki/Special:Redirect/file/Galthen's_Satchel.gif")
            embed.setDescription(s"<:satchel:1030348072577945651> cooldown for $displayTag expired <t:$cooldown:R>\n\nMark it as **Collected** and I will message you when the 30 day cooldown expires.")
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
    deleteStatement.setTimestamp(1, Timestamp.from(ZonedDateTime.now().minus(SatchelCooldown.durationDays, ChronoUnit.DAYS).toInstant))
    deleteStatement.executeUpdate()
    deleteStatement.close()
    } finally {
      conn.close() // always release the connection, even if a query above threw
    }
  }
}
