package com.tibiabot.discord

import scala.collection.mutable.ListBuffer
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Webhook
import net.dv8tion.jda.api.{JDA, JDABuilder}
import net.dv8tion.jda.api.entities.MessageEmbed
import club.minnced.discord.webhook.WebhookClient
import club.minnced.discord.webhook.send.WebhookMessageBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import com.google.common.util.concurrent.RateLimiter
import scala.collection.JavaConverters._
import scala.collection.mutable
import com.typesafe.scalalogging.StrictLogging
import com.tibiabot.Config
import scala.util.{Try, Success, Failure}
import java.util.concurrent.{ScheduledExecutorService, TimeUnit}
import java.util.concurrent.Executors

class DiscordMessageSender() extends StrictLogging {

  case class MessageDetails(guild: Guild, webhookChannel: TextChannel, messageContent: String, messageAuthor: String)

  private val queue: BlockingQueue[MessageDetails] = new LinkedBlockingQueue[MessageDetails]()
  private val channelRateLimiters: mutable.Map[TextChannel, RateLimiter] = mutable.Map.empty

  val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
  scheduler.scheduleAtFixedRate(() => sendMessages, 0, 5, TimeUnit.SECONDS)

  def sendWebhookMessage(guild: Guild, webhookChannel: TextChannel, messageContent: String, messageAuthor: String): Unit = {
    val messageDetails = MessageDetails(guild, webhookChannel, messageContent, messageAuthor)
    try {
        queue.put(messageDetails)
    } catch {
      case ex: Exception => logger.error(s"Failed to add level message to queue for Guild:'${guild.getId()}' Channel:'${webhookChannel.getId()}' World:'${messageAuthor}':\nMessage: $messageContent", ex)
    }
  }

  private def sendMessages(): Unit = {
    val messages: ListBuffer[MessageDetails] = ListBuffer.empty[MessageDetails]
    queue.drainTo(messages.asJava)
    if (!messages.isEmpty) {
      for (messageDetails <- messages) {
        // Acquire a permit from the rate limiter before sending the message
        val rateLimiter = channelRateLimiters.getOrElseUpdate(messageDetails.webhookChannel, RateLimiter.create(1))
        rateLimiter.acquire()

        var webhookCheck = true
        val getWebHook = Try(messageDetails.webhookChannel.retrieveWebhooks().submit().get()) match {
          case Success(webhooks) => webhooks
          case Failure(e) =>
            webhookCheck = false
            List.empty[Webhook].asJava
        }
        var webhook: Webhook = null
        if (getWebHook.isEmpty && webhookCheck) {
          try {
            val createWebhook = messageDetails.webhookChannel.createWebhook(messageDetails.messageAuthor).submit()
            webhook = createWebhook.get()
          } catch {
            case ex: Exception => logger.warn(s"Failed to CREATE webhook for Guild:'${messageDetails.guild.getId()}' Channel:'${messageDetails.webhookChannel.getId()}'  World:'${messageDetails.messageAuthor}'", ex)
          }
        } else if (!getWebHook.isEmpty && webhookCheck){
          try {
            webhook = getWebHook.get(0)
          } catch {
            case ex: Exception => logger.warn(s"Failed to GET webhook for Guild:'${messageDetails.guild.getId()}' Channel:'${messageDetails.webhookChannel.getId()}'  World:'${messageDetails.messageAuthor}'", ex)
          }
        } else {
          logger.warn(s"Failed to RETRIEVE webhooks for Guild:'${messageDetails.guild.getId()}' Channel:'${messageDetails.webhookChannel.getId()}'  World:'${messageDetails.messageAuthor}'")
        }
        if (webhook != null){
          val webhookUrl = webhook.getUrl()
          val messageBuilder = new WebhookMessageBuilder()
            .setUsername(messageDetails.messageAuthor)
            .setContent(messageDetails.messageContent)
            .setAvatarUrl(Config.webHookAvatar)
          val message = messageBuilder.build()
          try {
            WebhookClient.withUrl(webhookUrl).send(message)
          } catch {
            case ex: Exception => logger.error(s"Failed to SEND webhook for Guild:'${messageDetails.guild.getId()}' Channel:'${messageDetails.webhookChannel.getId()}'  World:'${messageDetails.messageAuthor}'", ex)
          }
        }
      }
    }
  }

  // start the background thread to send messages
  private val sendThread = new Thread(new Runnable() {
    override def run(): Unit = {
      sendMessages()
    }
  })
  sendThread.setDaemon(true)
  sendThread.start()
}
