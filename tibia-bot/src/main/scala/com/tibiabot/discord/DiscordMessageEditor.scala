package com.tibiabot.discord

import com.google.common.util.concurrent.RateLimiter
import com.tibiabot.Config
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.{Guild, Webhook}

import java.util.concurrent._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._
import java.time.ZonedDateTime

//noinspection UnstableApiUsage
class DiscordMessageEditor() extends StrictLogging {

  private case class EmbedDetails(guild: Guild, deathChannel: TextChannel, messageId: String, timeStamp: ZonedDateTime)
  private val messages: ListBuffer[EmbedDetails] = ListBuffer.empty[EmbedDetails]

  val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
  scheduler.scheduleAtFixedRate(() => editMessages(), 0, 30, TimeUnit.SECONDS)

  def editDeathMessage(guild: Guild, deathChannel: TextChannel, messageId: String, timeStamp: ZonedDateTime): Unit = {
    val messageDetails = EmbedDetails(guild, deathChannel, messageId, timeStamp)
    try {
        messages += messageDetails
    } catch {
      case ex: Exception => logger.error(s"Failed to add death embed to queue for Guild: '${guild.getId}' Channel: '${deathChannel.getId}' Message: $messageId", ex)
    }
  }

  private def editMessages(): Unit = {
    if (!messages.isEmpty) {
      for (messageDetails <- messages) {
        if (messageDetails.timeStamp.plusMinutes(15).isBefore(ZonedDateTime.now())) {

          // Do the things
          try {
            val channel = messageDetails.deathChannel
            val death = messageDetails.messageId
            val message = channel.retrieveMessageById(death).complete()
            val embeds = message.getEmbeds
            if (embeds.size > 0) {
              val theEmbed = embeds.get(0)
              val description = theEmbed.getDescription
              val exivaIndex = description.indexOf(Config.exivaEmoji)
              if (exivaIndex != -1){
                val substring = description.substring(0, exivaIndex)
                // Create a new EmbedBuilder and copy all properties from the first embed
                val newEmbedBuilder = new EmbedBuilder()
                  .setColor(theEmbed.getColor)
                  .setTitle(theEmbed.getTitle, theEmbed.getUrl)
                  .setThumbnail(theEmbed.getThumbnail.getUrl)
                  .setDescription(substring)
                // Create a new Message with the updated embed
                message.editMessageEmbeds(newEmbedBuilder.build()).queue()
              }
            }
          } catch {
            case ex: Exception => logger.error(s"Failed to EDIT a death embed for Guild: '${messageDetails.guild.getId}' Channel: '${messageDetails.deathChannel.getId}'  Message: '${messageDetails.messageId}'", ex)
          }
          messages -= messageDetails
        }
      }
    }
  }
}
