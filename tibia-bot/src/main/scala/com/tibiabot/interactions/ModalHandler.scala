package com.tibiabot.interactions

import com.tibiabot.{BotApp, Config, presentation}
import com.tibiabot.domain.SatchelStamp
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.text.{TextInput, TextInputStyle}
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.modals.Modal

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
       var inputName = element.getAsString.trim.toLowerCase
       val shortName = Map(
         "oberon" -> "grand master oberon",
         "scarlett" -> "scarlett etzel",
         "scarlet" -> "scarlett etzel",
         "timira" -> "timira the many-headed",
         "timira the many headed" -> "timira the many-headed",
         "timira many headed" -> "timira the many-headed",
         "timira many-headed" -> "timira the many-headed",
         "magma" -> "magma bubble",
         "rotten final" -> "bakragore",
         "yselda" -> "megasylvan yselda",
         "zelos" -> "king zelos",
         "despor" -> "dragon pack",
         "dragon hoard" -> "dragon pack",
         "vengar" -> "dragon pack",
         "maliz" -> "dragon pack",
         "bruton" -> "dragon pack",
         "greedok" -> "dragon pack",
         "vilear" -> "dragon pack",
         "crultor" -> "dragon pack",
         "dragon boss" -> "dragon pack",
         "dragon bosses" -> "dragon pack",
         "thorn knight" -> "the enraged thorn knight",
         "the thorn knight" -> "the enraged thorn knight",
         "shielded thorn knight" -> "the enraged thorn knight",
         "the shielded thorn knight" -> "the enraged thorn knight",
         "mounted thorn knight" -> "the enraged thorn knight",
         "the mounted thorn knight" -> "the enraged thorn knight",
         "paleworm" -> "the paleworm",
         "unwelcome" -> "the unwelcome",
         "yirkas" -> "yirkas blue scales",
         "vok" -> "vok the feakish",
         "irgix" -> "irgix the flimsy",
         "unaz" -> "unaz the mean",
         "utua" -> "utua stone sting",
         "katex" -> "katex blood tongue",
         "voidborn" -> "the unarmored voidborn",
         "the voidborn" -> "the unarmored voidborn",
         "unarmored voidborn" -> "the unarmored voidborn",
         "urmahlullu" -> "urmahlullu the weakened",
         "winter bloom" -> "the winter bloom",
         "time guardian" -> "the time guardian",
         "souldespoiler" -> "the souldespoiler",
         "scourge of oblivion" -> "the scourge of oblivion",
         "lib final" -> "the scourge of oblivion",
         "lb final" -> "the scourge of oblivion",
         "sandking" -> "the sandking",
         "nightmare beast" -> "the nightmare beast",
         "moonlight aster" -> "the moonlight aster",
         "monster" -> "the monster",
         "ingol boss" -> "the monster",
         "ingol final" -> "the monster",
         "mega magmaoid" -> "the mega magmaoid",
         "lily of night" -> "the lily of night",
         "flaming orchid" -> "the flaming orchid",
         "fear feaster" -> "the fear feaster",
         "false god" -> "the false god",
         "enraged thorn knight" -> "the enraged thorn knight",
         "dread maiden" -> "the dread maiden",
         "diamond blossom" -> "the diamond blossom",
         "brainstealer" -> "the brainstealer",
         "blazing rose" -> "the blazing rose",
         "srezz" -> "srezz yellow eyes",
         "werelion serpent spawn" -> "srezz yellow eyes",
         "werelions serpent spawn" -> "srezz yellow eyes",
         "werelion goanna" -> "yirkas blue scales",
         "werelions goanna" -> "yirkas blue scales",
         "werelion scorpion" -> "utua stone sting",
         "werelions scorpion" -> "utua stone sting",
         "werelion hyena" -> "katex blood tongue",
         "werelions hyena" -> "katex blood tongue",
         "werelion hyaena" -> "katex blood tongue",
         "werelions hyaena" -> "katex blood tongue",
         "werelion werehyena" -> "katex blood tongue",
         "werelions werehyena" -> "katex blood tongue",
         "werelion werehyaena" -> "katex blood tongue",
         "werelions werehyaena" -> "katex blood tongue",
         "dragon king" -> "soul of dragonking zyrtarch",
         "zyrtarch" -> "soul of dragonking zyrtarch",
         "dragonking zyrtarch" -> "soul of dragonking zyrtarch",
         "dragon king zyrtarch" -> "soul of dragonking zyrtarch",
         "dragonking zyrtarch" -> "soul of dragonking zyrtarch",
         "dragonking" -> "soul of dragonking zyrtarch",
         "tenebris" -> "lady tenebris",
         "ratmiral" -> "ratmiral blackwhiskers",
         "plague seal" -> "plagirath",
         "pumin seal" -> "tarbaz",
         "jugg seal" -> "razzagorn",
         "vexclaw seal" -> "shulgrax",
         "undead seal" -> "ragiaz"
       )
       if (shortName.contains(inputName)) {
         inputName = shortName(inputName)
       }
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
         val when = ZonedDateTime.now().plusDays(30).toEpochSecond.toString()
         val tagDisplay = element.getAsString.trim.toLowerCase
         newEmbed.setColor(3092790)
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
                 val when = satchel.when.plusDays(30).toEpochSecond.toString()
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
         val when = ZonedDateTime.now().plusDays(30).toEpochSecond.toString()
         val tagDisplay = element.getAsString.trim.toLowerCase
         newEmbed.setColor(3092790)
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
                 val when = satchel.when.plusDays(30).toEpochSecond.toString()
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
