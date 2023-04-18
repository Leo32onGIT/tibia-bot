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
  private val channelRateLimiters: mutable.Map[TextChannel, RateLimiter] = mutable.Map.empty
  private val channelRateLimits: mutable.Map[TextChannel, (Int, Long)] = mutable.Map.empty

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

          val (count, lastUpdated) = channelRateLimits.getOrElse(messageDetails.deathChannel, (0, System.currentTimeMillis()))
          if (System.currentTimeMillis() - lastUpdated < TimeUnit.MINUTES.toMillis(1)) {
            if (count >= 20) {
              // if more than 40 messages have been sent in the last minute, set the rate limiter to 1 per 3 seconds
              val currentRate = channelRateLimiters.getOrElseUpdate(messageDetails.deathChannel, RateLimiter.create(1.0/3))
              if (currentRate.getRate != 1.0/3){
                channelRateLimiters.put(messageDetails.deathChannel, RateLimiter.create(1.0/3))
                logger.warn(s"Rate limit for the death edits on Guild: '${messageDetails.guild.getId}' Channel: '${messageDetails.deathChannel.getId}' has been temporarily restricted to 1 per 3 seconds")
              }
            } else {
              channelRateLimits.put(messageDetails.deathChannel, (count + 1, lastUpdated)) // increment count, but keep lastUpdated the same
            }
          } else {
            val currentRate = channelRateLimiters.getOrElseUpdate(messageDetails.deathChannel, RateLimiter.create(1))
            if (currentRate.getRate != 1){
              channelRateLimiters.put(messageDetails.deathChannel, RateLimiter.create(1))
            }
            channelRateLimits.put(messageDetails.deathChannel, (1, System.currentTimeMillis())) // reset count and lastUpdated
          }

          // Acquire a permit from the rate limiter before sending the message
          val rateLimiter = channelRateLimiters.getOrElseUpdate(messageDetails.deathChannel, RateLimiter.create(1))
          rateLimiter.acquire()

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
