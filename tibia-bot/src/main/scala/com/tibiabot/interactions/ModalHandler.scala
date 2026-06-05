package com.tibiabot.interactions

import com.tibiabot.{BotApp, Config, domain, presentation}
import com.tibiabot.domain.SatchelStamp
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.interactions.components.buttons.Button

import scala.jdk.CollectionConverters._
import java.time.ZonedDateTime

/** Handles modal submissions (boosted boss-name and galthen tag inputs).
 *  Moved verbatim from BotListener.onModalInteraction. */
object ModalHandler {
  def handle(event: ModalInteractionEvent): Unit = {
    event.deferEdit().queue()
     val user = event.getUser
     val modalValues = event.getValues.asScala.toList
     modalValues.map { element =>
       val id = element.getId
       val inputName = domain.BossAliases.canonical(element.getAsString.trim.toLowerCase)
       if (id == "boosted add") {
         val newEmbed = BotApp.boostedService.boosted(user.getId, "add", inputName)
         event.getHook().editOriginalEmbeds(newEmbed).setActionRow(
           Button.success("boosted add", "Add"),
           Button.danger("boosted remove", "Remove"),
           Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOffEmoji))
         ).queue()
       } else if (id == "boosted remove") {
         val newEmbed = BotApp.boostedService.boosted(user.getId, "remove", inputName)
         event.getHook().editOriginalEmbeds(newEmbed).setActionRow(
           Button.success("boosted add", "Add"),
           Button.danger("boosted remove", "Remove"),
           Button.secondary("boosted toggle", " ").withEmoji(Emoji.fromFormatted(Config.torchOffEmoji))
         ).queue()
       } else if (id == "galthen add") {

         val newEmbed = new EmbedBuilder()
         val tagDisplay = element.getAsString.trim.toLowerCase
         newEmbed.setColor(presentation.Embeds.BrandColor)
         if (tagDisplay.toLowerCase == user.getName.toLowerCase) {
           BotApp.galthenService.add(user.getId, ZonedDateTime.now(), "")
         } else {
           BotApp.galthenService.add(user.getId, ZonedDateTime.now(), tagDisplay)
         }
         var editedMessage = ""
         var oneRecord = false
         val satchelTimeOption: Option[List[SatchelStamp]] = BotApp.galthenService.getStamps(event.getUser.getId)
         satchelTimeOption match {
           case Some(satchelTimeList) =>
             val fullList = satchelTimeList.collect {
               case satchel =>
                 val when = domain.time.SatchelCooldown.expiresAtEpoch(satchel.when)
                 val displayTag = if (satchel.tag == "") s"<@${event.getUser.getId}>" else s"**`${satchel.tag}`**"
                 s"${Config.satchelEmoji} can be collected by $displayTag <t:$when:R>"
             }
             if (fullList.nonEmpty) {
               newEmbed.setTitle("Existing Cooldowns:")
               if (fullList.size == 1) {
                 oneRecord = true
                 editedMessage = fullList.mkString
               } else {
                 editedMessage = presentation.GalthenEmbeds.truncate(fullList)
               }
             }
           case None => //
         }
         val replyMessage = s"\n\n${Config.yesEmoji} cooldown tracker for **`$tagDisplay`** has been **added**."
         newEmbed.setDescription(editedMessage + replyMessage)
         if (oneRecord) {
           event.getHook().editOriginalEmbeds(newEmbed.build).setActionRow(
               Button.success("galthenAdd", "Add Cooldown").withEmoji(Emoji.fromFormatted(Config.satchelEmoji)),
               Button.danger("galthenRemoveAll", "Remove")
             ).queue()
         } else {
           event.getHook().editOriginalEmbeds(newEmbed.build).setActionRow(
               Button.success("galthenAdd", "Add Cooldown").withEmoji(Emoji.fromFormatted(Config.satchelEmoji)),
               Button.danger("galthenButtonRem", "Remove"),
               Button.secondary("galthenRemoveAll", "Clear All")
             ).queue()
         }
       } else if (id == "galthen rem") {
         val newEmbed = new EmbedBuilder()
         val tagDisplay = element.getAsString.trim.toLowerCase
         newEmbed.setColor(presentation.Embeds.BrandColor)
         if (tagDisplay.toLowerCase == user.getName.toLowerCase) {
           BotApp.galthenService.del(user.getId, "")
         } else {
           BotApp.galthenService.del(user.getId, tagDisplay)
         }
         var editedMessage = ""
         var oneRecord = false
         val satchelTimeOption: Option[List[SatchelStamp]] = BotApp.galthenService.getStamps(event.getUser.getId)
         satchelTimeOption match {
           case Some(satchelTimeList) =>
             val fullList = satchelTimeList.collect {
               case satchel =>
                 val when = domain.time.SatchelCooldown.expiresAtEpoch(satchel.when)
                 val displayTag = if (satchel.tag == "") s"<@${event.getUser.getId}>" else s"**`${satchel.tag}`**"
                 s"${Config.satchelEmoji} can be collected by $displayTag <t:$when:R>"
             }
             if (fullList.nonEmpty) {
               newEmbed.setTitle("Existing Cooldowns:")
               if (fullList.size == 1) {
                 oneRecord = true
                 editedMessage = fullList.mkString
               } else {
                 editedMessage = presentation.GalthenEmbeds.truncate(fullList)
               }
             }
           case None => // WIP
         }
         val replyMessage = s"\n\n${Config.yesEmoji} cooldown tracker for **`$tagDisplay`** has been **Disabled**."
         newEmbed.setDescription(editedMessage + replyMessage)
         if (oneRecord) {
           event.getHook().editOriginalEmbeds(newEmbed.build).setActionRow(
               Button.success("galthenAdd", "Add Cooldown").withEmoji(Emoji.fromFormatted(Config.satchelEmoji)),
               Button.danger("galthenRemoveAll", "Remove")
             ).queue()
         } else {
           event.getHook().editOriginalEmbeds(newEmbed.build).setActionRow(
               Button.success("galthenAdd", "Add Cooldown").withEmoji(Emoji.fromFormatted(Config.satchelEmoji)),
               Button.danger("galthenButtonRem", "Remove"),
               Button.secondary("galthenRemoveAll", "Clear All")
             ).queue()
         }
       }
     }
  }
}
