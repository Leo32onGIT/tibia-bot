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
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Try, Success, Failure}

class DiscordMessageSender() extends StrictLogging {

  private case class MessageDetails(webhookChannel: TextChannel, messageContent: String, messageAuthor: String)

  private val guildQueues: mutable.Map[Guild, mutable.Queue[MessageDetails]] = mutable.Map.empty
  private val channelRateLimiters: mutable.Map[TextChannel, RateLimiter] = mutable.Map.empty
  private val webhookRateLimits: mutable.Map[TextChannel, (Int, Long)] = mutable.Map.empty

  private implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(6))
  private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

  def sendWebhookMessage(guild: Guild, webhookChannel: TextChannel, messageContent: String, messageAuthor: String): Unit = {
    val messageDetails = MessageDetails(webhookChannel, messageContent, messageAuthor)
    guildQueues.synchronized {
      guildQueues.getOrElseUpdate(guild, mutable.Queue.empty[MessageDetails]).enqueue(messageDetails)
    }
  }

  private def sendMessagesForGuild(guild: Guild, messages: ListBuffer[MessageDetails]): Unit = {
    for (messageDetails <- messages) {
      val (count, lastUpdated) = webhookRateLimits.getOrElse(messageDetails.webhookChannel, (0, System.currentTimeMillis()))
      if (System.currentTimeMillis() - lastUpdated < TimeUnit.MINUTES.toMillis(1)) {
        if (count >= 20) {
          // if more than 20 messages have been sent in the last minute, set the rate limiter to 1 per 3 seconds
          val currentRate = channelRateLimiters.getOrElseUpdate(messageDetails.webhookChannel, RateLimiter.create(1.0 / 3))
          if (currentRate.getRate != 1.0 / 3) {
            channelRateLimiters.put(messageDetails.webhookChannel, RateLimiter.create(1.0 / 3))
            logger.warn(
              s"Webhook rate limit for the levels channel on Guild: '${guild.getId}' Channel: '${messageDetails.webhookChannel.getId}'  World: '${messageDetails.messageAuthor}' has been temporarily restricted to 1 per 3 seconds"
            )
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

      var webhook: Webhook = null
      var webhookCheck = true
      Try(messageDetails.webhookChannel.retrieveWebhooks().complete()) match {
        case Success(webhooks) if !webhooks.isEmpty =>
          webhook = webhooks.get(0)
        case _ =>
          webhookCheck = false
      }

      if (webhookCheck) {
        if (webhook == null) {
          try {
            val createWebhook = messageDetails.webhookChannel.createWebhook(messageDetails.messageAuthor).complete()
            webhook = createWebhook
          } catch {
            case ex: Exception =>
              logger.warn(
                s"Failed to CREATE webhook for Guild: '${guild.getId}' Channel: '${messageDetails.webhookChannel.getId}'  World: '${messageDetails.messageAuthor}'",
                ex
              )
          }
        }
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
          case ex: Exception =>
            logger.error(
              s"Failed to SEND webhook for Guild: '${guild.getId}' Channel: '${messageDetails.webhookChannel.getId}'  World: '${messageDetails.messageAuthor}'",
              ex
            )
        } finally {
          client.close()
        }
      }
    }
  }

  private def sendMessages(): Unit = {
    val guildMessagePairs: List[(Guild, ListBuffer[MessageDetails])] = guildQueues.synchronized {
      guildQueues.map { case (guild, queue) =>
        val messages = ListBuffer.empty[MessageDetails]
        queue.synchronized {
          while (queue.nonEmpty) {
            messages += queue.dequeue()
          }
        }
        (guild, messages)
      }.toList
    }

    Future.traverse(guildMessagePairs) { case (guild, messages) =>
      Future {
        sendMessagesForGuild(guild, messages)
      }
    }

    executor.schedule(new Runnable {
      def run(): Unit = sendMessages()
    }, 30, TimeUnit.SECONDS)
  }

  executor.schedule(new Runnable {
    def run(): Unit = sendMessages()
  }, 30, TimeUnit.SECONDS)

}
