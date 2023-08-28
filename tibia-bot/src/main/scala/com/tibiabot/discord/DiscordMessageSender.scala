package com.tibiabot.discord

import club.minnced.discord.webhook.WebhookClientBuilder
import club.minnced.discord.webhook.send.WebhookMessageBuilder
import com.google.common.util.concurrent.RateLimiter
import com.tibiabot.Config
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.{Guild, Webhook}

import java.util.concurrent._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}
import java.util.concurrent.Executors

//noinspection UnstableApiUsage
class DiscordMessageSender() extends StrictLogging {

  private case class MessageDetails(guild: Guild, webhookChannel: TextChannel, messageContent: String, messageAuthor: String)

  private case class GuildMessageQueue(guild: Guild, queue: BlockingQueue[MessageDetails])

  private val guildQueues: mutable.Map[Guild, GuildMessageQueue] = mutable.Map.empty
  private val channelRateLimiters: mutable.Map[TextChannel, RateLimiter] = mutable.Map.empty
  private val webhookRateLimits: mutable.Map[TextChannel, (Int, Long)] = mutable.Map.empty
  private val guildProcessingThreads: mutable.Map[Guild, Thread] = mutable.Map.empty

  // Create a thread pool with a fixed number of threads
  private val threadPool = Executors.newFixedThreadPool(10) // Choose an appropriate number of threads

  def sendWebhookMessage(guild: Guild, webhookChannel: TextChannel, messageContent: String, messageAuthor: String): Unit = {
    val messageDetails = MessageDetails(guild, webhookChannel, messageContent, messageAuthor)
    try {
      val guildQueue = guildQueues.getOrElseUpdate(guild, {
        val newQueue = new LinkedBlockingQueue[MessageDetails]()
        val guildMessageQueue = GuildMessageQueue(guild, newQueue)
        // Start processing the new queue in the thread pool if not already started
        if (!guildProcessingThreads.contains(guild)) {
          threadPool.submit(() => processGuildQueue(guildMessageQueue))
          guildProcessingThreads(guild) = true
        }
        guildMessageQueue
      })
      guildQueue.queue.put(messageDetails)
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to add level message to queue for Guild: '${guild.getId}' Channel: '${webhookChannel.getId}' World: '$messageAuthor':\nMessage: $messageContent", ex)
    }
  }

  private def processGuildQueue(guildQueue: GuildMessageQueue): Unit = {
    while (true) { // This loop keeps the thread alive for processing messages continuously
      sendGuildMessages(guildQueue)
      Thread.sleep(30000) // Wait for 30 seconds before processing the next batch
    }
  }

  private def sendGuildMessages(guildQueue: GuildMessageQueue): Unit = {
    val messages: ListBuffer[MessageDetails] = ListBuffer.empty[MessageDetails]
    guildQueue.queue.drainTo(messages.asJava)
    if (!messages.isEmpty) {
      for (messageDetails <- messages) {

        val (count, lastUpdated) = webhookRateLimits.getOrElse(messageDetails.webhookChannel, (0, System.currentTimeMillis()))
        if (System.currentTimeMillis() - lastUpdated < TimeUnit.MINUTES.toMillis(1)) {
          if (count >= 20) {
            // if more than 40 messages have been sent in the last minute, set the rate limiter to 1 per 3 seconds
            val currentRate = channelRateLimiters.getOrElseUpdate(messageDetails.webhookChannel, RateLimiter.create(1.0/3))
            if (currentRate.getRate != 1.0/3) {
              channelRateLimiters.put(messageDetails.webhookChannel, RateLimiter.create(1.0/3))
              logger.warn(s"Webhook rate limit for the levels channel on Guild: '${messageDetails.guild.getId}' Channel: '${messageDetails.webhookChannel.getId}'  World: '${messageDetails.messageAuthor}' has been temporarily restricted to 1 per 3 seconds")
            }
          } else {
            webhookRateLimits.put(messageDetails.webhookChannel, (count + 1, lastUpdated)) // increment count, but keep lastUpdated the same
          }
        } else {
          val currentRate = channelRateLimiters.getOrElseUpdate(messageDetails.webhookChannel, RateLimiter.create(1))
          if (currentRate.getRate != 1) {
            channelRateLimiters.put(messageDetails.webhookChannel, RateLimiter.create(1))
          }
          webhookRateLimits.put(messageDetails.webhookChannel, (1, System.currentTimeMillis())) // reset count and lastUpdated
        }

        // Acquire a permit from the rate limiter before sending the message
        val rateLimiter = channelRateLimiters.getOrElseUpdate(messageDetails.webhookChannel, RateLimiter.create(1))
        rateLimiter.acquire()

        var webhookCheck = true
        val getWebHook = Try(messageDetails.webhookChannel.retrieveWebhooks().submit().get()) match {
          case Success(webhooks) => webhooks
          case Failure(_) =>
            webhookCheck = false
            List.empty[Webhook].asJava
        }
        var webhook: Webhook = null
        if (getWebHook.isEmpty && webhookCheck) {
          try {
            val createWebhook = messageDetails.webhookChannel.createWebhook(messageDetails.messageAuthor).submit()
            webhook = createWebhook.get()
          } catch {
            case ex: Exception => logger.warn(s"Failed to CREATE webhook for Guild: '${messageDetails.guild.getId}' Channel: '${messageDetails.webhookChannel.getId}'  World: '${messageDetails.messageAuthor}'", ex)
          }
        } else if (!getWebHook.isEmpty && webhookCheck) {
          try {
            webhook = getWebHook.get(0)
          } catch {
            case ex: Exception => logger.warn(s"Failed to GET webhook for Guild: '${messageDetails.guild.getId}' Channel: '${messageDetails.webhookChannel.getId}'  World: '${messageDetails.messageAuthor}'", ex)
          }
        } else {
          logger.warn(s"Failed to RETRIEVE webhooks for Guild: '${messageDetails.guild.getId}' Channel: '${messageDetails.webhookChannel.getId}'  World: '${messageDetails.messageAuthor}'")
        }
        if (webhook != null) {
          val webhookUrl = webhook.getUrl
          val messageBuilder = new WebhookMessageBuilder()
            .setUsername(messageDetails.messageAuthor)
            .setContent(messageDetails.messageContent)
            .setAvatarUrl(Config.webHookAvatar)
          val client = new WebhookClientBuilder(webhookUrl).build()
          try {
            client.send(messageBuilder.build())
          } catch {
            case ex: Exception => logger.error(s"Failed to SEND webhook for Guild: '${messageDetails.guild.getId}' Channel: '${messageDetails.webhookChannel.getId}'  World: '${messageDetails.messageAuthor}'", ex)
          }
          client.close()
        }
      }
    }
  }
}
