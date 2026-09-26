package com.tibiabot.interactions

import com.tibiabot.{BotApp, Config}
import com.tibiabot.domain.PendingScreenshot
import com.tibiabot.presentation.DeathCard
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button

import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Handles DM/guild messages for the death-screenshot upload flow.
 *  Moved verbatim from BotListener.onMessageReceived/handlePrivateMessage;
 *  the shared pendingScreenshots map is passed in by BotListener. */
object ScreenshotMessageHandler extends StrictLogging {

  def onMessage(event: MessageReceivedEvent, pendingScreenshots: mutable.Map[String, PendingScreenshot]): Unit = {
    if (!event.getAuthor.isBot) {
      if (!event.isFromGuild) {
        handlePrivate(event, pendingScreenshots)
        return
      }

      if (event.isFromGuild) {
        val guild = event.getGuild
        val user = event.getAuthor
        val pendingKey = s"${user.getId}_${guild.getId}"

        pendingScreenshots.get(pendingKey) match {
        case Some(pending) =>
          val attachments = event.getMessage.getAttachments.asScala
          val imageAttachments = attachments.filter { attachment =>
            val fileName = attachment.getFileName.toLowerCase
            fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
            fileName.endsWith(".gif") || fileName.endsWith(".webp")
          }

          if (imageAttachments.nonEmpty) {
            val attachment = imageAttachments.head
            val imageUrl = attachment.getUrl

            pendingScreenshots.remove(pendingKey)

            try {
              BotApp.storeDeathScreenshot(pending.guildId, pending.world, pending.charName, pending.deathTime, imageUrl, pending.userId, user.getName, pending.messageId)

              val channel = guild.getTextChannelById(pending.channelId)
              if (channel != null) {
                channel.retrieveMessageById(pending.messageId).queue(message => {
                  if (showLatest(message, pending, user.getId, user.getName, imageUrl)) {

                    // Confirm with a reaction, then remove the user's upload message
                    event.getMessage.addReaction(Emoji.fromUnicode("✅")).queue(_ => {
                      event.getMessage.delete().queue()
                    })

                    logger.info(s"Screenshot uploaded successfully for ${pending.charName} death at ${pending.deathTime}")
                  }
                })
              }
            } catch {
              case e: Exception =>
                logger.error(s"Failed to store screenshot: ${e.getMessage}", e)
                event.getMessage.addReaction(Emoji.fromUnicode("❌")).queue()
            }
          }
        case None =>
        }
      }
    }
  }

  private def handlePrivate(event: MessageReceivedEvent, pendingScreenshots: mutable.Map[String, PendingScreenshot]): Unit = {
    val user = event.getAuthor

    val userPendingScreenshots = pendingScreenshots.filter(_._1.startsWith(user.getId + "_")).toMap

    if (userPendingScreenshots.nonEmpty) {
      val attachments = event.getMessage.getAttachments.asScala
      val imageAttachments = attachments.filter { attachment =>
        val fileName = attachment.getFileName.toLowerCase
        fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
        fileName.endsWith(".gif") || fileName.endsWith(".webp")
      }

      if (imageAttachments.nonEmpty) {
        val attachment = imageAttachments.head
        val imageUrl = attachment.getUrl

        // A DM upload may need to fill pending requests from more than one guild
        userPendingScreenshots.foreach { case (pendingKey, pending) =>
          pendingScreenshots.remove(pendingKey)

          try {
            BotApp.storeDeathScreenshot(pending.guildId, pending.world, pending.charName, pending.deathTime, imageUrl, pending.userId, user.getName, pending.messageId)

            val guild = event.getJDA.getGuildById(pending.guildId)
            if (guild != null) {
              val channel = guild.getTextChannelById(pending.channelId)
              if (channel != null) {
                channel.retrieveMessageById(pending.messageId).queue(message => {
                  if (showLatest(message, pending, user.getId, user.getName, imageUrl)) {

                    logger.info(s"Screenshot uploaded successfully via DM for ${pending.charName} death at ${pending.deathTime} in guild ${guild.getName}")
                  }
                })
              }
            }

            event.getChannel.sendMessage(s"${Config.yesEmoji} Screenshot uploaded successfully for **[${pending.charName}](${BotApp.charUrl(pending.charName)})**.").queue()

          } catch {
            case e: Exception =>
              logger.error(s"Failed to store screenshot from DM: ${e.getMessage}", e)
              event.getChannel.sendMessage(s"${Config.noEmoji} Failed to upload screenshot. Please try again.").queue()
          }
        }
      } else {
        val messageContent = event.getMessage.getContentRaw.toLowerCase.trim
        if (messageContent.contains("cancel")) {
          val cancelledCount = userPendingScreenshots.size
          userPendingScreenshots.keys.foreach(pendingScreenshots.remove)

          if (cancelledCount == 1) {
            event.getChannel.sendMessage(s"Your pending upload has been cancelled.").queue()
          } else if (cancelledCount > 1) {
            event.getChannel.sendMessage(s"${cancelledCount} pending uploads have been cancelled.").queue()
          }

          logger.info(s"User ${user.getName} (${user.getId}) cancelled ${cancelledCount} pending uploads via DM")
        } else {
          event.getChannel.sendMessage("Please upload an image file (PNG, JPG, GIF, WebP) or paste an image from your clipboard.\nType `cancel` to cancel any pending upload requests.").queue()
        }
      }
    } else {
      val messageContent = event.getMessage.getContentRaw.toLowerCase.trim
      if (messageContent.contains("cancel")) {
        event.getChannel.sendMessage("You don't have any pending uploads to cancel.").queue()
      }
    }
  }

  /** Show the newest screenshot on the death post, with its paging, and delete
   *  for whoever added it. False when the message is not a death post. */
  private def showLatest(message: Message, pending: PendingScreenshot, userId: String, userName: String,
                         imageUrl: String): Boolean =
    DeathCard.read(message, pending.charName) match {
      case None => false
      case Some(post) =>
        val screenshots = BotApp.getDeathScreenshots(pending.guildId, pending.world, pending.charName, pending.deathTime)
        val count = screenshots.length
        val latestIndex = math.max(0, count - 1) // screenshots are stored oldest-first, so the last one is newest
        val latest = screenshots.lastOption
        val shot = latest.fold(DeathCard.Screenshot(imageUrl, s"Screenshot added by $userName"))(newest =>
          DeathCard.Screenshot(newest.screenshotUrl, s"Screenshot added by ${newest.addedName} • $count/$count"))
        val row = DeathCard.screenshotRow(pending.charName, pending.deathTime, pending.messageId, latestIndex, count,
          deletable = latest.exists(_.addedBy == userId))
        message.editMessage(DeathCard.edit(post.copy(screenshot = Some(shot)),
          Some(DeathCard.cameraButton(pending.charName, pending.deathTime, pending.messageId)), row)).queue()
        true
    }
}
